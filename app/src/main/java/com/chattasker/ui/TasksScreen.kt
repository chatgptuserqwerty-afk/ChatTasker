package com.chattasker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.chattasker.core.Direction
import com.chattasker.core.Ids
import com.chattasker.core.Priority
import com.chattasker.core.Repeat
import com.chattasker.core.TaskItem
import com.chattasker.data.Store
import com.chattasker.reminders.Reminders
import java.time.LocalDateTime

private enum class Filter(val label: String) { OPEN("Open"), MINE("My tasks"), WAITING("Waiting on"), TODAY("Today"), DONE("Done"), ALL("All") }
private enum class Sort(val label: String) { DUE("Due date"), PRIORITY("Priority"), PERSON("Person"), NEWEST("Newest") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TasksScreen() {
    val tasks by Store.tasks.collectAsState()
    val settings by Store.settings.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Filter.OPEN) }
    var sort by rememberSaveable { mutableStateOf(Sort.DUE) }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<TaskItem?>(null) }
    val ctx = LocalContext.current
    val now = LocalDateTime.now()
    val endOfDay = now.toLocalDate().plusDays(1).atStartOfDay()

    val shown = tasks.filter { t ->
        when (filter) {
            Filter.OPEN -> !t.done
            Filter.MINE -> !t.done && t.direction == Direction.MINE
            Filter.WAITING -> !t.done && t.direction == Direction.WAITING
            Filter.TODAY -> !t.done && t.due != null && t.due.isBefore(endOfDay)
            Filter.DONE -> t.done
            Filter.ALL -> true
        } && (tag == null || tag in t.tags) &&
            (query.isBlank() || t.title.contains(query, true) || t.person.contains(query, true) || t.details.contains(query, true))
    }.let { list ->
        when (sort) {
            Sort.DUE -> list.sortedWith(compareBy<TaskItem> { it.due == null }.thenBy { it.due }.thenBy { it.priority })
            Sort.PRIORITY -> list.sortedWith(compareBy<TaskItem> { it.priority }.thenBy { it.due == null }.thenBy { it.due })
            Sort.PERSON -> list.sortedWith(compareBy<TaskItem> { it.person.ifBlank { "~" }.lowercase() }.thenBy { it.due == null }.thenBy { it.due })
            Sort.NEWEST -> list.sortedByDescending { it.createdAt }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Tasks", style = MaterialTheme.typography.headlineSmall) }
            item {
                OutlinedTextField(
                    query, { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search tasks or people") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Filter.values().forEach { f ->
                        FilterChip(filter == f, { filter = f }, label = { Text(f.label) })
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                    Text("Sort:", Modifier.align(Alignment.CenterVertically))
                    Sort.values().forEach { s ->
                        FilterChip(sort == s, { sort = s }, label = { Text(s.label) })
                    }
                }
            }
            if (settings.tags.isNotEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tag:", Modifier.align(Alignment.CenterVertically))
                    settings.tags.forEach { tg ->
                        FilterChip(tag == tg, { tag = if (tag == tg) null else tg }, label = { Text(tg) })
                    }
                }
            }
            if (shown.isEmpty()) item {
                Text("No tasks here.", Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shown, key = { it.id }) { t ->
                TaskRow(t, now,
                    onToggle = {
                        if (t.done) {
                            val reopened = t.copy(done = false)
                            Store.upsertTask(reopened); Reminders.schedule(ctx, reopened)
                        } else Reminders.complete(ctx, t)
                    },
                    onClick = { editing = t })
            }
        }
        FloatingActionButton(
            onClick = { editing = TaskItem(id = 0, title = "") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add task") }
    }

    editing?.let { t ->
        TaskEditor(
            initial = t,
            onDismiss = { editing = null },
            onDelete = {
                Reminders.cancel(ctx, t.id); Store.deleteTask(t.id); editing = null
            },
            onSave = { saved ->
                val final = if (saved.id == 0L) saved.copy(id = Ids.next(), createdAt = LocalDateTime.now()) else saved
                Store.upsertTask(final)
                Reminders.schedule(ctx, final)
                editing = null
            },
        )
    }
}

@Composable
private fun TaskRow(t: TaskItem, now: LocalDateTime, onToggle: () -> Unit, onClick: () -> Unit) {
    val overdue = !t.done && t.due != null && t.due.isBefore(now)
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(5.dp).height(64.dp).background(priorityColor(t.priority)))
            Checkbox(checked = t.done, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 4.dp)) {
                Text(
                    t.title, style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                )
                val meta = buildList {
                    add(if (t.direction == Direction.MINE) "My task" else "Waiting on")
                    if (t.person.isNotBlank()) add(t.person)
                    if (t.repeat != Repeat.NONE) add(t.repeat.name.lowercase())
                    if (t.remindAt != null && !t.done) add("🔔")
                }.joinToString("  ·  ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (t.due != null) Text(
                    (if (overdue) "Overdue: " else "") + fmtDue(t.due),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (t.tags.isNotEmpty()) Text(t.tags.joinToString("  ") { "#$it" },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private enum class RemindMode(val label: String) { AT_DUE("At due time"), BEFORE("Minutes before"), CUSTOM("Custom time"), NONE("No reminder") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskEditor(initial: TaskItem, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (TaskItem) -> Unit) {
    val ctx = LocalContext.current
    val settings by Store.settings.collectAsState()
    var title by remember { mutableStateOf(initial.title) }
    var person by remember { mutableStateOf(initial.person) }
    var details by remember { mutableStateOf(initial.details) }
    var direction by remember { mutableStateOf(initial.direction) }
    var priority by remember { mutableStateOf(initial.priority) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var due by remember { mutableStateOf(initial.due) }
    var tags by remember { mutableStateOf(initial.tags) }
    var newTag by remember { mutableStateOf("") }
    var mode by remember {
        mutableStateOf(
            when {
                initial.remindAt == null -> if (initial.id == 0L) RemindMode.AT_DUE else RemindMode.NONE
                initial.remindAt == initial.due -> RemindMode.AT_DUE
                initial.due != null && initial.remindAt != null && initial.remindAt.isBefore(initial.due) -> RemindMode.BEFORE
                else -> RemindMode.CUSTOM
            }
        )
    }
    var beforeMin by remember {
        mutableStateOf(
            if (initial.due != null && initial.remindAt != null && initial.remindAt.isBefore(initial.due))
                java.time.Duration.between(initial.remindAt, initial.due).toMinutes().toString()
            else "30"
        )
    }
    var custom by remember { mutableStateOf(initial.remindAt) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "New task" else "Edit task") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(title, { title = it }, label = { Text("Task") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(person, { person = it }, label = { Text("Person") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(details, { details = it }, label = { Text("Details") }, modifier = Modifier.fillMaxWidth())

                SectionTitle("Type")
                ChoiceChips(listOf(Direction.MINE, Direction.WAITING), direction,
                    { if (it == Direction.MINE) "My task" else "Waiting on" }) { direction = it }

                SectionTitle("Priority")
                ChoiceChips(Priority.values().toList(), priority,
                    { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { priority = it }

                SectionTitle("Due")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { pickDateTime(ctx, due) { due = it } }) { Text(fmtDue(due)) }
                    if (due != null) TextButton(onClick = { due = null }) { Text("Clear") }
                }

                SectionTitle("Reminder")
                ChoiceChips(RemindMode.values().toList(), mode, { it.label }) { mode = it }
                when (mode) {
                    RemindMode.BEFORE -> OutlinedTextField(
                        beforeMin, { v -> beforeMin = v.filter { it.isDigit() }.take(5) },
                        label = { Text("Minutes before due") }, singleLine = true,
                    )
                    RemindMode.CUSTOM -> TextButton(onClick = { pickDateTime(ctx, custom ?: due) { custom = it } }) {
                        Text(custom?.let { fmtDue(it) } ?: "Pick reminder time")
                    }
                    else -> {}
                }
                if ((mode == RemindMode.AT_DUE || mode == RemindMode.BEFORE) && due == null) {
                    Text("Set a due date for this reminder to work.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }

                SectionTitle("Repeat")
                ChoiceChips(Repeat.values().toList(), repeat,
                    { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { repeat = it }

                SectionTitle("Tags")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (settings.tags + tags).distinct().forEach { tg ->
                        FilterChip(tg in tags, { tags = if (tg in tags) tags - tg else tags + tg }, label = { Text(tg) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newTag, { newTag = it }, label = { Text("New tag") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        val tg = newTag.trim()
                        if (tg.isNotEmpty() && tg !in tags) tags = tags + tg
                        newTag = ""
                    }) { Text("Add") }
                }
                if (initial.id != 0L) {
                    TextButton(onClick = onDelete) { Text("Delete task", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                val d = due
                val remindAt = when (mode) {
                    RemindMode.AT_DUE -> d
                    RemindMode.BEFORE -> d?.minusMinutes(beforeMin.toLongOrNull() ?: 0L)
                    RemindMode.CUSTOM -> custom
                    RemindMode.NONE -> null
                }
                onSave(initial.copy(
                    title = title.trim(), person = person.trim(), details = details.trim(),
                    direction = direction, priority = priority, repeat = repeat,
                    due = d, remindAt = remindAt, tags = tags,
                ))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
