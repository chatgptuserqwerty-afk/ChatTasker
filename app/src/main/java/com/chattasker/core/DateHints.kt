package com.chattasker.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Lightweight, offline date/time spotting for English and Roman Urdu.
 * Used for the "Quick tasks (no AI)" path and to sanity-fill missing dates.
 */
object DateHints {

    data class Hint(val date: LocalDate?, val time: LocalTime?) {
        val found get() = date != null || time != null
    }

    private val weekdays: Map<String, DayOfWeek> = buildMap {
        listOf("monday", "mon", "peer", "pir", "somwar").forEach { put(it, DayOfWeek.MONDAY) }
        listOf("tuesday", "tue", "tues", "mangal", "mangalwar").forEach { put(it, DayOfWeek.TUESDAY) }
        listOf("wednesday", "wed", "budh", "budhwar").forEach { put(it, DayOfWeek.WEDNESDAY) }
        listOf("thursday", "thu", "thur", "thurs", "jumerat", "jumeraat", "jumeraat").forEach { put(it, DayOfWeek.THURSDAY) }
        listOf("friday", "fri", "jumma", "juma", "jummah", "jumah").forEach { put(it, DayOfWeek.FRIDAY) }
        listOf("saturday", "sat", "hafta", "hafte", "sanichar").forEach { put(it, DayOfWeek.SATURDAY) }
        listOf("sunday", "sun", "itwar", "itwaar", "itvar", "itwaar").forEach { put(it, DayOfWeek.SUNDAY) }
    }

    private val months = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    fun find(textIn: String, ref: LocalDateTime): Hint {
        val text = " " + textIn.lowercase()
            .replace("a.m.", "am").replace("p.m.", "pm")
            .replace(Regex("[^a-z0-9:/. ]"), " ")
            // keep . : / only between digits (5:30, 20/10, 5.30)
            .replace(Regex("(?<!\\d)[.:/]|[.:/](?!\\d)"), " ")
            .replace(Regex("\\s+"), " ") + " "
        val words = text.trim().split(' ')
        var date: LocalDate? = null
        var time: LocalTime? = null
        var period: String? = null // morning / afternoon / evening / night

        fun has(vararg w: String) = w.any { text.contains(" $it ") }

        // ---- relative offsets: "in 2 hours", "2 ghante mein", "3 din baad"
        Regex("""(?:in |within )?(\d{1,3}) ?(min|mins|minute|minutes|hour|hours|hr|hrs|ghanta|ghante|ghantay|day|days|din|week|weeks|hafte|haftay)\b""")
            .find(text)?.let { m ->
                val n = m.groupValues[1].toLong()
                val unit = m.groupValues[2]
                val t = when {
                    unit.startsWith("min") -> ref.plusMinutes(n)
                    unit.startsWith("h") && !unit.startsWith("haft") || unit.startsWith("gh") -> ref.plusHours(n)
                    unit.startsWith("d") -> ref.plusDays(n)
                    else -> ref.plusWeeks(n)
                }
                val isTimeUnit = unit.startsWith("min") || unit.startsWith("gh") ||
                    (unit.startsWith("h") && !unit.startsWith("haft"))
                return if (isTimeUnit) Hint(t.toLocalDate(), t.toLocalTime().withSecond(0).withNano(0))
                else Hint(t.toLocalDate(), null)
            }

        // ---- days
        when {
            has("day after tomorrow", "parson", "parsoon", "parso", "parsun") -> date = ref.toLocalDate().plusDays(2)
            has("tomorrow", "tmrw", "tmr", "tomorow", "kal", "kl", "kall") -> date = ref.toLocalDate().plusDays(1)
            has("today", "tonight", "aaj", "aj", "abhi", "eod") -> date = ref.toLocalDate()
            has("next week", "agle hafte", "agly hafte", "agle haftay", "aglay hafte") -> date = ref.toLocalDate().plusWeeks(1)
        }
        if (has("tonight")) period = "night"
        if (has("eod")) time = LocalTime.of(18, 0)

        if (date == null) {
            for (i in words.indices) {
                val d = weekdays[words[i]] ?: continue
                // "hafta" alone usually means "week"; only accept it with a cue word nearby
                if (words[i].startsWith("haft") && i > 0 && words[i - 1] !in setOf("is", "by", "on", "next", "agle", "aglay")) continue
                val next = words.getOrNull(i - 1) in setOf("next", "agle", "aglay", "agly")
                var day = ref.toLocalDate().with(TemporalAdjusters.next(d))
                if (next && day.isBefore(ref.toLocalDate().plusDays(7))) day = day.plusWeeks(1)
                date = day
                break
            }
        }

        // ---- explicit dates: "20 oct", "oct 20", "20/10", "20-10-2026"
        if (date == null) {
            Regex("""\b(\d{1,2}) ?(?:st|nd|rd|th)? ?(jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec)[a-z]*\b""").find(text)?.let {
                date = safeDate(ref, months.getValue(it.groupValues[2]), it.groupValues[1].toInt())
            } ?: Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sept|sep|oct|nov|dec)[a-z]* ?(\d{1,2})\b""").find(text)?.let {
                date = safeDate(ref, months.getValue(it.groupValues[1]), it.groupValues[2].toInt())
            } ?: Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""").find(text)?.let {
                // Inside message bodies people in Pakistan usually write day/month
                val dd = it.groupValues[1].toInt(); val mm = it.groupValues[2].toInt()
                date = if (mm in 1..12) safeDate(ref, mm, dd) else safeDate(ref, dd, mm)
            }
        }

        // ---- periods
        when {
            has("morning", "subah", "subha", "subh", "sobah") -> period = "morning"
            has("afternoon", "dopahar", "dopehar", "dophr", "dopher") -> period = "afternoon"
            has("evening", "shaam", "sham", "shame") -> period = "evening"
            has("night", "raat", "rat", "rath") -> if (period == null) period = "night"
        }

        // ---- clock times: 5pm, 5:30 pm, 17:00, "5 baje", "at 5"
        if (time == null) {
            val m1 = Regex("""\b(\d{1,2})(?::|\.)?(\d{2})? ?(am|pm|a\.m\.|p\.m\.)""").find(text)
            val m2 = Regex("""\b(\d{1,2})(?::(\d{2}))? ?(?:baje|bje|bajay|bajy|bajey|o ?clock)\b""").find(text)
            val m3 = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""").find(text)
            val m4 = Regex("""\b(?:at|by|till|until|tak) (\d{1,2})(?::(\d{2}))?\b""").find(text)
            when {
                m1 != null -> {
                    var h = m1.groupValues[1].toInt()
                    val mm = m1.groupValues[2].toIntOrNull() ?: 0
                    val pm = m1.groupValues[3].startsWith("p")
                    if (h in 1..12) {
                        if (pm && h < 12) h += 12
                        if (!pm && h == 12) h = 0
                        if (mm in 0..59) time = LocalTime.of(h, mm)
                    }
                }
                m2 != null -> time = periodHour(m2.groupValues[1].toInt(), m2.groupValues[2].toIntOrNull() ?: 0, period)
                m3 != null -> time = LocalTime.of(m3.groupValues[1].toInt(), m3.groupValues[2].toInt())
                m4 != null -> time = periodHour(m4.groupValues[1].toInt(), m4.groupValues[2].toIntOrNull() ?: 0, period)
            }
        }

        if (time == null && period != null) {
            time = when (period) {
                "morning" -> LocalTime.of(9, 0)
                "afternoon" -> LocalTime.of(14, 0)
                "evening" -> LocalTime.of(18, 0)
                else -> LocalTime.of(21, 0)
            }
        }

        // Only a time, already passed → next day
        if (date == null && time != null && !ref.toLocalTime().isBefore(time)) {
            date = ref.toLocalDate().plusDays(1)
        }
        return Hint(date, time)
    }

    /** Combine a hint into a due date. Date-only hints get the default time. */
    fun toDue(h: Hint, ref: LocalDateTime, defaultTime: LocalTime): LocalDateTime? = when {
        h.date != null && h.time != null -> LocalDateTime.of(h.date, h.time)
        h.date != null -> LocalDateTime.of(h.date, defaultTime)
        h.time != null -> LocalDateTime.of(ref.toLocalDate(), h.time)
        else -> null
    }

    private fun periodHour(hIn: Int, m: Int, period: String?): LocalTime? {
        if (hIn !in 0..23 || m !in 0..59) return null
        var h = hIn
        if (h in 1..11) {
            h = when (period) {
                "morning" -> h
                "afternoon", "evening", "night" -> h + 12
                // No cue: 1-7 is almost always afternoon/evening in conversation
                else -> if (h <= 7) h + 12 else h
            }
        }
        return LocalTime.of(h % 24, m)
    }

    private fun safeDate(ref: LocalDateTime, month: Int, day: Int): LocalDate? = try {
        val d = LocalDate.of(ref.year, month, day)
        if (d.isBefore(ref.toLocalDate().minusDays(1))) d.plusYears(1) else d
    } catch (e: Exception) {
        null
    }
}
