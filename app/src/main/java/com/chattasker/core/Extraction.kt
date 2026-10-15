package com.chattasker.core

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ISO_MIN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val DAY_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)

/** Builds the prompt handed to ChatGPT / Gemini. */
object PromptBuilder {

    fun build(
        messages: List<ChatMessage>,
        myName: String,
        extraInstructions: String,
        now: LocalDateTime = LocalDateTime.now(),
    ): String {
        val me = myName.split(',').first().trim().ifEmpty { "me" }
        val sb = StringBuilder()
        sb.appendLine("Extract tasks from this WhatsApp conversation for me. My WhatsApp name is \"$me\"; messages from \"$me\" (or \"You\") are written by me, all others are from my contacts. Messages may be in English, Urdu or Roman Urdu.")
        sb.appendLine("Now: ${DAY_NAME.format(now)} ${ISO_MIN.format(now)}. Resolve relative times (aaj, kal, parson, shaam, tomorrow, Friday, 9 baje) relative to the time of the message they appear in.")
        sb.appendLine()
        sb.appendLine("Reply ONLY with lines in exactly this format. No other text, no markdown, no table, no code block:")
        sb.appendLine("TASK | MINE or WAITING | short task title in English | person | YYYY-MM-DD HH:mm or NONE | HIGH or MEDIUM or LOW")
        sb.appendLine("NOTE | person | one or two sentence summary in English")
        sb.appendLine("END")
        sb.appendLine()
        sb.appendLine("MINE = something I need to do. WAITING = something someone else promised or owes me.")
        sb.appendLine("Only include real action items, requests and commitments. Ignore greetings and chit-chat. If there are no tasks, give NOTE lines only. Give one NOTE per person. The last line must be END.")
        if (extraInstructions.isNotBlank()) {
            sb.appendLine("Additional rules: ${extraInstructions.trim()}")
        }
        sb.appendLine()
        sb.appendLine("Conversation:")
        for (m in messages) {
            val t = m.time?.let { ISO_MIN.format(it) } ?: "time unknown"
            val who = when {
                m.fromMe -> me
                m.sender.isBlank() -> "Unknown"
                else -> m.sender
            }
            sb.appendLine("$t | $who: ${m.text.replace("\n", " / ")}")
        }
        return sb.toString().trimEnd()
    }
}

/** Parses the AI's reply (the TASK / NOTE lines) into drafts. */
object AiReplyParser {

    private val dateFormats = listOf(
        "yyyy-MM-dd HH:mm", "yyyy-MM-dd H:mm", "yyyy-MM-dd'T'HH:mm", "yyyy/MM/dd HH:mm",
    ).map { DateTimeFormatter.ofPattern(it) }

    fun looksLikeReply(text: String): Boolean =
        text.lines().any { classify(clean(it)) != null }

    fun parse(text: String, defaultTime: LocalTime): List<Draft> {
        val out = mutableListOf<Draft>()
        var key = 0
        for (rawLine in text.lines()) {
            val line = clean(rawLine)
            val kind = classify(line) ?: continue
            val parts = line.split('|').map { it.trim().trim('*', '`', '"') }
            if (kind == "NOTE") {
                if (parts.size < 3) continue
                val person = parts[1]
                val note = parts.drop(2).joinToString(" | ").trim()
                if (note.isNotEmpty()) out += Draft(key++, isNote = true, title = note, person = person)
            } else {
                if (parts.size < 3) continue
                val dir = if (parts[1].uppercase().contains("WAIT")) Direction.WAITING else Direction.MINE
                val title = parts[2]
                if (title.isBlank()) continue
                val person = parts.getOrElse(3) { "" }
                val due = parts.getOrNull(4)?.let { parseDue(it, defaultTime) }
                val pr = when (parts.getOrNull(5)?.uppercase()?.firstOrNull()) {
                    'H' -> Priority.HIGH
                    'L' -> Priority.LOW
                    else -> Priority.MEDIUM
                }
                out += Draft(key++, isNote = false, title = title, person = person, direction = dir, due = due, priority = pr)
            }
        }
        return out
    }

    /** Strips bullets, numbering, markdown and table pipes. */
    fun clean(line: String): String {
        var s = line.trim().trim('`').trim()
        s = s.replace(Regex("""^(?:[-*•>]+|\d+[.)])\s*"""), "")
        s = s.replace("**", "")
        s = s.trim().removePrefix("|").removeSuffix("|").trim()
        return s
    }

    private fun classify(line: String): String? {
        val head = line.substringBefore('|').trim().uppercase()
        if (!line.contains('|')) return null
        return when (head) {
            "TASK" -> "TASK"
            "NOTE" -> "NOTE"
            else -> null
        }
    }

    private fun parseDue(s: String, defaultTime: LocalTime): LocalDateTime? {
        val v = s.trim()
        if (v.isEmpty() || v.uppercase().startsWith("NONE") || v == "-" || v.uppercase() == "N/A") return null
        for (f in dateFormats) {
            try { return LocalDateTime.parse(v, f) } catch (_: Exception) {}
        }
        return try {
            java.time.LocalDate.parse(v.take(10)).atTime(defaultTime)
        } catch (_: Exception) {
            null
        }
    }
}

/** Offline extraction: one draft per message that looks actionable. */
object QuickExtractor {

    private val requestCues = listOf(
        "please", "plz", "pls", "can you", "could you", "send", "share", "need", "check", "call me",
        "remind", "kr do", "kar do", "krdo", "kardo", "kr dena", "kar dena", "bhej do", "bhejo",
        "bhej dena", "bata do", "batao", "dekh lo", "dekhna", "chahiye", "zaroor", "jaldi", "asap", "urgent",
    )
    private val promiseCues = listOf(
        "i'll", "i will", "will send", "will share", "will do", "i'm sending", "sending",
        "bhaijta", "bhejta", "bhej raha", "bhej rha", "bhej dunga", "bhej deta", "bhaij deta",
        "kar dunga", "kr dunga", "karta hun", "krta hun", "karta hoon", "deta hun", "deta hoon",
        "kar deta", "kr deta", "dekhta hun", "dekhta hoon", "batata hun", "bataunga", "send krta",
    )

    fun extract(
        messages: List<ChatMessage>,
        defaultTime: LocalTime,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<Draft> {
        val out = mutableListOf<Draft>()
        var key = 0
        for (m in messages) {
            val lower = " " + m.text.lowercase() + " "
            val ref = m.time ?: now
            val hint = DateHints.find(m.text, ref)
            val promise = promiseCues.any { lower.contains(it) }
            val request = requestCues.any { lower.contains(it) }
            val direction = when {
                m.fromMe -> Direction.MINE
                promise -> Direction.WAITING
                else -> Direction.MINE
            }
            val actionable = promise || request || hint.found
            val person = if (m.fromMe) "" else m.sender
            out += Draft(
                key = key++,
                isNote = false,
                title = m.text.replace("\n", " ").take(120),
                person = person,
                direction = direction,
                due = DateHints.toDue(hint, ref, defaultTime),
                priority = if (lower.contains("urgent") || lower.contains("asap") || lower.contains("jaldi")) Priority.HIGH else Priority.MEDIUM,
                selected = actionable,
            )
        }
        return out
    }
}
