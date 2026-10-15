package com.chattasker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chattasker.AppViewModel
import com.chattasker.Stage
import com.chattasker.core.ChatMessage
import com.chattasker.core.Direction
import com.chattasker.core.Draft
import com.chattasker.core.Priority
import com.chattasker.data.AiApp
import com.chattasker.data.Store
import java.time.format.DateTimeFormatter

private val msgTime = DateTimeFormatter.ofPattern("d MMM, h:mm a")

@Composable
fun CaptureScreen(vm: AppViewModel) {
    val state by vm.capture.collectAsState()
    val settings by Store.settings.collectAsState()
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<Draft?>(null) }
    val aiName = if (settings.aiApp == AiApp.ASK) "AI app" else settings.aiApp.label

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("Capture", style = MaterialTheme.typography.headlineSmall)
        }
        state.info?.let { info ->
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(info, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = vm::dismissInfo) { Icon(Icons.Default.Close, "Dismiss") }
                    }
                }
            }
        }

        // ---------- paste buttons (always available) ----------
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.ingest(clipboard.getText()?.text) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (state.messages.isEmpty()) "Paste messages" else "Paste more") }
                OutlinedButton(
                    onClick = { vm.ingest(clipboard.getText()?.text) },
                    modifier = Modifier.weight(1f),
                ) { Text("Paste AI reply") }
            }
        }

        when (state.stage) {
            Stage.IDLE -> item { HowTo(aiName) }

            Stage.MESSAGES, Stage.WAITING_FOR_AI -> {
                item {
                    Text(
                        "${state.messages.size} message(s) in memory",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                itemsIndexed(state.messages) { i, m -> MessageRow(m) { vm.removeMessage(i) } }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.sendToAi(ctx) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Analyse with $aiName")
                        }
                        OutlinedButton(onClick = vm::quickTasks, modifier = Modifier.fillMaxWidth()) {
                            Text("Quick tasks (no AI)")
                        }
                        TextButton(onClick = vm::clear, modifier = Modifier.fillMaxWidth()) {
                            Text("Clear messages")
                        }
                    }
                }
            }

            Stage.REVIEW -> {
                val tasks = state.drafts.filter { !it.isNote }
                val notes = state.drafts.filter { it.isNote }
                if (tasks.isNotEmpty()) item { SectionTitle("Tasks") }
                tasks.forEach { d ->
                    item(key = "t${d.key}") {
                        DraftRow(d, onToggle = { vm.updateDraft(d.copy(selected = it)) }, onEdit = { editing = d })
                    }
                }
                if (notes.isNotEmpty()) item { SectionTitle("Notes") }
                notes.forEach { d ->
                    item(key = "n${d.key}") {
                        DraftRow(d, onToggle = { vm.updateDraft(d.copy(selected = it)) }, onEdit = { editing = d })
                    }
                }
                if (state.drafts.isEmpty()) item { Text("Nothing found in that reply.") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::backToMessages, modifier = Modifier.weight(1f)) { Text("Back") }
                        Button(
                            onClick = { vm.saveDrafts(ctx) },
                            enabled = state.drafts.any { it.selected },
                            modifier = Modifier.weight(1f),
                        ) { Text("Save selected") }
                    }
                }
            }
        }
    }

    editing?.let { d ->
        DraftEditor(d, onDismiss = { editing = null }) { vm.updateDraft(it); editing = null }
    }
}

@Composable
private fun HowTo(aiName: String) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How it works", style = MaterialTheme.typography.titleMedium)
            Text("1. In WhatsApp, long-press a message, select the ones you want, then tap Copy (or Share to ChatTasker).")
            Text("2. Come back here and tap Paste messages. You can paste from several chats.")
            Text("3. Tap Analyse with $aiName, then press send in that app.")
            Text("4. Copy its reply, come back and tap Paste AI reply (or share the reply to ChatTasker).")
            Text("5. Review and save. Messages are then discarded; only tasks and notes are kept.")
        }
    }
}

@Composable
private fun MessageRow(m: ChatMessage, onRemove: () -> Unit) {
    Card(colors = CardDefaults.cardColors(
        containerColor = if (m.fromMe) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    )) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    (if (m.fromMe) "Me" else m.sender.ifBlank { "Unknown sender" }) +
                        (m.time?.let { "  ·  " + msgTime.format(it) } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(m.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Remove") }
        }
    }
}

@Composable
private fun DraftRow(d: Draft, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onEdit() }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
            Checkbox(checked = d.selected, onCheckedChange = onToggle)
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(d.title, style = MaterialTheme.typography.bodyLarge)
                val meta = buildList {
                    if (!d.isNote) add(if (d.direction == Direction.MINE) "My task" else "Waiting on")
                    if (d.person.isNotBlank()) add(d.person)
                    if (!d.isNote) add(fmtDue(d.due))
                    if (!d.isNote && d.priority != Priority.MEDIUM) add(d.priority.name.lowercase().replaceFirstChar { it.uppercase() })
                }.joinToString("  ·  ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
        }
    }
}

@Composable
private fun DraftEditor(d: Draft, onDismiss: () -> Unit, onSave: (Draft) -> Unit) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(d.title) }
    var person by remember { mutableStateOf(d.person) }
    var direction by remember { mutableStateOf(d.direction) }
    var priority by remember { mutableStateOf(d.priority) }
    var due by remember { mutableStateOf(d.due) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (d.isNote) "Edit note" else "Edit task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(if (d.isNote) "Note" else "Task") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(person, { person = it }, label = { Text("Person") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (!d.isNote) {
                    ChoiceChips(listOf(Direction.MINE, Direction.WAITING), direction,
                        { if (it == Direction.MINE) "My task" else "Waiting on" }) { direction = it }
                    ChoiceChips(Priority.values().toList(), priority,
                        { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { priority = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { pickDateTime(ctx, due) { due = it } }) { Text(fmtDue(due)) }
                        Spacer(Modifier.width(4.dp))
                        if (due != null) TextButton(onClick = { due = null }) { Text("Clear date") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(d.copy(title = title, person = person, direction = direction, priority = priority, due = due, selected = true))
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
