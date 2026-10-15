package com.chattasker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.chattasker.core.Ids
import com.chattasker.core.NoteEntry
import com.chattasker.data.Store
import java.time.LocalDateTime

@Composable
fun NotesScreen() {
    val notes by Store.notes.collectAsState()
    val tasks by Store.tasks.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf<NoteEntry?>(null) }

    val filtered = notes.filter {
        query.isBlank() || it.text.contains(query, true) || it.person.contains(query, true)
    }
    val groups = filtered.groupBy { it.person.ifBlank { "General" } }
        .toList()
        .sortedByDescending { (_, list) -> list.maxOf { it.createdAt } }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Notes", style = MaterialTheme.typography.headlineSmall) }
            item {
                OutlinedTextField(
                    query, { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text("Search notes or people") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (groups.isEmpty()) item {
                Text("No notes yet. They're created from AI summaries, or add one with +.",
                    Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            groups.forEach { (person, list) ->
                item(key = "p:$person") {
                    val open = person in expanded || query.isNotBlank()
                    val openTasks = tasks.count { !it.done && it.person.equals(person, true) }
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { expanded = if (person in expanded) expanded - person else expanded + person }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(person, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${list.size} note(s)" + if (openTasks > 0) "  ·  $openTasks open task(s)" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                            }
                            if (open) {
                                list.sortedByDescending { it.createdAt }.forEach { n ->
                                    HorizontalDivider()
                                    Column(Modifier.fillMaxWidth().clickable { editing = n }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                        Text(fmtDay(n.createdAt), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary)
                                        Text(n.text)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { editing = NoteEntry(id = 0, person = "", text = "") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, "Add note") }
    }

    editing?.let { n ->
        var person by remember(n.id) { mutableStateOf(n.person) }
        var text by remember(n.id) { mutableStateOf(n.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (n.id == 0L) "New note" else "Edit note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(person, { person = it }, label = { Text("Person") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(text, { text = it }, label = { Text("Note") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    if (n.id != 0L) TextButton(onClick = { Store.deleteNote(n.id); editing = null }) {
                        Text("Delete note", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val p = person.trim().ifEmpty { "General" }
                    Store.upsertNote(
                        if (n.id == 0L) NoteEntry(Ids.next(), p, text.trim(), LocalDateTime.now())
                        else n.copy(person = p, text = text.trim())
                    )
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
