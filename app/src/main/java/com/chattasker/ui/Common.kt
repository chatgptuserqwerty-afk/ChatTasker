package com.chattasker.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chattasker.core.Priority
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val dayTime = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
private val dayTimeYear = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
private val timeOnly = DateTimeFormatter.ofPattern("h:mm a")
private val dayOnly = DateTimeFormatter.ofPattern("d MMM yyyy")

fun fmtDue(d: LocalDateTime?): String {
    if (d == null) return "No date"
    val today = LocalDate.now()
    val days = ChronoUnit.DAYS.between(today, d.toLocalDate())
    return when (days) {
        0L -> "Today, " + timeOnly.format(d)
        1L -> "Tomorrow, " + timeOnly.format(d)
        -1L -> "Yesterday, " + timeOnly.format(d)
        else -> if (d.year == today.year) dayTime.format(d) else dayTimeYear.format(d)
    }
}

fun fmtDay(d: LocalDateTime): String = dayOnly.format(d)

fun fmtTime(t: LocalTime): String = timeOnly.format(t)

fun priorityColor(p: Priority): Color = when (p) {
    Priority.HIGH -> Color(0xFFD32F2F)
    Priority.MEDIUM -> Color(0xFFF9A825)
    Priority.LOW -> Color(0xFF43A047)
}

/** Opens the system date picker, then the time picker. */
fun pickDateTime(ctx: Context, initial: LocalDateTime?, onPicked: (LocalDateTime) -> Unit) {
    val start = initial ?: LocalDateTime.now().plusHours(1).withMinute(0)
    DatePickerDialog(ctx, { _, y, m, d ->
        TimePickerDialog(ctx, { _, h, min ->
            onPicked(LocalDateTime.of(y, m + 1, d, h, min))
        }, start.hour, start.minute, DateFormat.is24HourFormat(ctx)).show()
    }, start.year, start.monthValue - 1, start.dayOfMonth).show()
}

fun pickTime(ctx: Context, initial: LocalTime, onPicked: (LocalTime) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPicked(LocalTime.of(h, m)) },
        initial.hour, initial.minute, DateFormat.is24HourFormat(ctx)).show()
}

/** A row of single-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
