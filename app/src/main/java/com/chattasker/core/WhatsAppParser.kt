package com.chattasker.core

import java.time.LocalDateTime

/**
 * Parses text copied (or exported) from WhatsApp.
 *
 * Copied messages:  [10/15, 8:29 PM] N Zahid Pdw Technician: Ss bhaijta ap ko
 * Copied with year: [15/10/2026, 20:29] Name: text
 * Exported chat:    10/15/26, 8:29 PM - Name: text
 *
 * Lines without a header are continuations of the previous message.
 * Text with no header at all (a single copied message) becomes one message
 * with an unknown sender.
 */
object WhatsAppParser {

    private const val TIME =
        """(\d{1,2})[:.](\d{2})(?:[:.]\d{2})?(?:\s*([AaPp])\.?\s*[Mm]\.?)?"""

    private val bracketHeader = Regex(
        """^\[(\d{1,4})[/.\-](\d{1,2})(?:[/.\-](\d{2,4}))?,?\s*$TIME\]\s*([^:]{1,80}?):\s?(.*)$"""
    )
    private val dashHeader = Regex(
        """^(\d{1,4})[/.\-](\d{1,2})[/.\-](\d{2,4}),?\s*$TIME\s+-\s+([^:]{1,80}?):\s?(.*)$"""
    )

    private data class Raw(
        val a: Int, val b: Int, val year: Int?,
        val hour: Int, val minute: Int, val ampm: Char?,
        val sender: String, val text: StringBuilder,
    )

    fun normalize(s: String): String =
        s.replace(' ', ' ').replace(' ', ' ')
            .replace("‎", "").replace("‏", "")
            .replace("\r\n", "\n").replace('\r', '\n')

    fun looksLikeWhatsApp(text: String): Boolean =
        normalize(text).lineSequence().any { matchHeader(it.trim()) != null }

    fun parse(
        input: String,
        myName: String,
        order: DateOrder,
        now: LocalDateTime = LocalDateTime.now(),
    ): List<ChatMessage> {
        val text = normalize(input).trim()
        if (text.isEmpty()) return emptyList()

        val raws = mutableListOf<Raw>()
        val preamble = StringBuilder()
        for (line in text.lines()) {
            val r = matchHeader(line.trim())
            if (r != null) raws += r
            else if (raws.isNotEmpty()) raws.last().text.append('\n').append(line)
            else preamble.append(if (preamble.isEmpty()) "" else "\n").append(line)
        }

        if (raws.isEmpty()) {
            return listOf(ChatMessage(null, "", text, fromMe = false))
        }

        val resolvedOrder = resolveOrder(order, raws)
        val out = mutableListOf<ChatMessage>()
        if (preamble.isNotBlank()) out += ChatMessage(null, "", preamble.toString().trim(), false)
        for (r in raws) {
            val sender = r.sender.trim()
            out += ChatMessage(
                time = buildTime(r, resolvedOrder, now),
                sender = sender,
                text = r.text.toString().trim(),
                fromMe = isMe(sender, myName),
            )
        }
        return out
    }

    fun isMe(sender: String, myName: String): Boolean {
        val s = sender.trim()
        if (s.equals("You", true) || s.equals("Aap", true)) return true
        val names = myName.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return names.any { s.equals(it, ignoreCase = true) }
    }

    private fun matchHeader(line: String): Raw? {
        bracketHeader.matchEntire(line)?.let { return toRaw(it) }
        dashHeader.matchEntire(line)?.let { return toRaw(it) }
        return null
    }

    private fun toRaw(m: MatchResult): Raw? {
        val g = m.groupValues
        val a = g[1].toIntOrNull() ?: return null
        val b = g[2].toIntOrNull() ?: return null
        val y = g[3].toIntOrNull()
        val h = g[4].toIntOrNull() ?: return null
        val min = g[5].toIntOrNull() ?: return null
        val ap = g[6].firstOrNull()?.uppercaseChar()
        // Guard against lines like "[note] Something: else" being taken as headers
        if (min > 59 || h > 23) return null
        return Raw(a, b, y, h, min, ap, g[7], StringBuilder(g[8]))
    }

    private fun resolveOrder(order: DateOrder, raws: List<Raw>): DateOrder {
        if (order != DateOrder.AUTO) return order
        if (raws.any { it.a > 31 }) return DateOrder.DMY // year-first, handled in buildTime
        if (raws.any { it.a > 12 }) return DateOrder.DMY
        if (raws.any { it.b > 12 }) return DateOrder.MDY
        return DateOrder.MDY
    }

    private fun buildTime(r: Raw, order: DateOrder, now: LocalDateTime): LocalDateTime? {
        var month: Int
        var day: Int
        var year: Int? = r.year
        if (r.a > 31) { // yyyy/MM/dd style
            year = r.a; month = r.b; day = r.year ?: return null
        } else if (order == DateOrder.DMY) {
            day = r.a; month = r.b
        } else {
            month = r.a; day = r.b
        }
        var hour = r.hour
        if (r.ampm == 'P' && hour < 12) hour += 12
        if (r.ampm == 'A' && hour == 12) hour = 0
        if (year != null && year < 100) year += 2000

        return try {
            if (year != null) {
                LocalDateTime.of(year, month, day, hour, r.minute)
            } else {
                val guess = LocalDateTime.of(now.year, month, day, hour, r.minute)
                // A copied message can't be from the future: it must be from last year
                if (guess.isAfter(now.plusDays(1))) guess.minusYears(1) else guess
            }
        } catch (e: Exception) {
            null
        }
    }
}
