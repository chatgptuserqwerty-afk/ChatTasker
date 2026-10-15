package com.chattasker.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chattasker.core.DateOrder
import com.chattasker.data.AiApp
import com.chattasker.data.Settings
import com.chattasker.data.Store
import com.chattasker.reminders.Reminders

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val s by Store.settings.collectAsState()
    var newTag by remember { mutableStateOf("") }
    var confirmImport by remember { mutableStateOf<Uri?>(null) }

    fun update(block: (Settings) -> Settings) {
        val next = block(Store.settings.value)
        Store.updateSettings(next)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            ctx.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(Store.exportJson()) }
            Toast.makeText(ctx, "Backup saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't save backup", Toast.LENGTH_LONG).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport = uri
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)

        SectionTitle("WhatsApp")
        OutlinedTextField(
            s.myName, { v -> update { it.copy(myName = v) } },
            label = { Text("My WhatsApp name(s)") },
            supportingText = { Text("As it appears in copied messages. Separate several with commas.") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text("Date format in copied messages")
        ChoiceChips(DateOrder.values().toList(), s.dateOrder, {
            when (it) { DateOrder.AUTO -> "Auto"; DateOrder.MDY -> "Month/Day"; DateOrder.DMY -> "Day/Month" }
        }) { v -> update { it.copy(dateOrder = v) } }

        HorizontalDivider()
        SectionTitle("AI app")
        ChoiceChips(AiApp.values().toList(), s.aiApp, { it.label }) { v -> update { it.copy(aiApp = v) } }
        OutlinedTextField(
            s.extraInstructions, { v -> update { it.copy(extraInstructions = v) } },
            label = { Text("Extra instructions for the AI") },
            supportingText = { Text("e.g. \"Ignore family groups\", \"Only tasks addressed to me\", \"Mark anything from clients as HIGH\"") },
            minLines = 2, modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider()
        SectionTitle("Reminders")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Default time for date-only tasks", Modifier.weight(1f))
            TextButton(onClick = { pickTime(ctx, s.defaultTime) { t -> update { it.copy(defaultTime = t) } } }) {
                Text(fmtTime(s.defaultTime))
            }
        }
        NumberField("Remind me this many minutes before due (my tasks)", s.leadMinutes) { v -> update { it.copy(leadMinutes = v) } }
        NumberField("Follow up this many minutes after due (waiting on)", s.waitingFollowUpMinutes) { v -> update { it.copy(waitingFollowUpMinutes = v) } }
        NumberField("Snooze length (minutes)", s.snoozeMinutes) { v -> update { it.copy(snoozeMinutes = v.coerceAtLeast(1)) } }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daily digest", Modifier.weight(1f))
            Switch(s.digestEnabled, { v -> update { it.copy(digestEnabled = v) }; Reminders.scheduleDigest(ctx) })
        }
        if (s.digestEnabled) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Digest time", Modifier.weight(1f))
            TextButton(onClick = {
                pickTime(ctx, s.digestTime) { t -> update { it.copy(digestTime = t) }; Reminders.scheduleDigest(ctx) }
            }) { Text(fmtTime(s.digestTime)) }
        }
        OutlinedButton(onClick = {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                Toast.makeText(ctx, "Already unrestricted. Reminders will fire on time.", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) { }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Allow reminders in battery saver") }
        if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = {
            try {
                ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName)))
            } catch (e: Exception) {
                Toast.makeText(ctx, "Exact alarms are already allowed.", Toast.LENGTH_SHORT).show()
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Check exact-time reminders permission") }

        HorizontalDivider()
        SectionTitle("Tags")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.tags.forEach { tg ->
                InputChip(selected = false, onClick = { update { it.copy(tags = it.tags - tg) } },
                    label = { Text("$tg  ✕") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newTag, { newTag = it }, label = { Text("Add tag") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                val tg = newTag.trim()
                if (tg.isNotEmpty() && tg !in s.tags) update { it.copy(tags = it.tags + tg) }
                newTag = ""
            }) { Text("Add") }
        }

        HorizontalDivider()
        SectionTitle("Backup")
        Text("Exports tasks and notes only (no messages).", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                exportLauncher.launch("chattasker-backup-${java.time.LocalDate.now()}.json")
            }, modifier = Modifier.weight(1f)) { Text("Export") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.weight(1f)) { Text("Import") }
        }
        Text("Version 1.0 · tasks and notes stay on this phone",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text("Replace all tasks and notes?") },
            text = { Text("Importing a backup replaces everything currently in the app.") },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                        val (t, n) = Store.importJson(text)
                        Reminders.rescheduleAll(ctx)
                        Toast.makeText(ctx, "Imported $t tasks and $n notes", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Not a valid backup file", Toast.LENGTH_LONG).show()
                    }
                    confirmImport = null
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { v ->
            text = v.filter { it.isDigit() }.take(5)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}
