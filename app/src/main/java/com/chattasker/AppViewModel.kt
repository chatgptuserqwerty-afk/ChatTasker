package com.chattasker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import com.chattasker.core.AiReplyParser
import com.chattasker.core.ChatMessage
import com.chattasker.core.Direction
import com.chattasker.core.Draft
import com.chattasker.core.Ids
import com.chattasker.core.NoteEntry
import com.chattasker.core.PromptBuilder
import com.chattasker.core.QuickExtractor
import com.chattasker.core.TaskItem
import com.chattasker.core.WhatsAppParser
import com.chattasker.data.AiApp
import com.chattasker.data.Store
import com.chattasker.reminders.Reminders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime

enum class Stage { IDLE, MESSAGES, WAITING_FOR_AI, REVIEW }

/**
 * Capture state lives only in memory. Messages are cleared as soon as
 * the reviewed tasks and notes are saved, or when you tap Clear.
 */
data class CaptureState(
    val stage: Stage = Stage.IDLE,
    val messages: List<ChatMessage> = emptyList(),
    val drafts: List<Draft> = emptyList(),
    val info: String? = null,
)

class AppViewModel : ViewModel() {

    private val _capture = MutableStateFlow(CaptureState())
    val capture: StateFlow<CaptureState> = _capture.asStateFlow()

    /** Bottom-bar tab requested by the activity (e.g. after a share). */
    val tab = MutableStateFlow(0)

    /** Handles any incoming text: WhatsApp messages or an AI reply. */
    fun ingest(text: String?) {
        if (text.isNullOrBlank()) {
            _capture.value = _capture.value.copy(info = "Clipboard is empty. Copy messages in WhatsApp first.")
            return
        }
        val s = Store.settings.value
        if (AiReplyParser.looksLikeReply(text)) {
            val drafts = AiReplyParser.parse(text, s.defaultTime)
            _capture.value = _capture.value.copy(
                stage = Stage.REVIEW,
                drafts = drafts,
                info = "${drafts.count { !it.isNote }} tasks and ${drafts.count { it.isNote }} notes found. Review and save.",
            )
            return
        }
        val parsed = WhatsAppParser.parse(text, s.myName, s.dateOrder)
        if (parsed.isEmpty()) return
        // Appending lets you collect messages from several chats before analysing
        val merged = (_capture.value.messages + parsed)
            .distinctBy { Triple(it.time, it.sender, it.text) }
        _capture.value = CaptureState(
            stage = Stage.MESSAGES,
            messages = merged,
            info = "${parsed.size} message(s) added. Held in memory only.",
        )
    }

    fun buildPrompt(): String {
        val s = Store.settings.value
        return PromptBuilder.build(_capture.value.messages, s.myName, s.extraInstructions)
    }

    /** Copies the prompt and hands it to ChatGPT / Gemini. */
    fun sendToAi(ctx: Context) {
        val prompt = buildPrompt()
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("ChatTasker prompt", prompt))

        val app = Store.settings.value.aiApp
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, prompt)
        }
        val direct = app.pkg?.let { Intent(send).setPackage(it) }
        val launched = try {
            if (direct != null && direct.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
            } else {
                ctx.startActivity(
                    Intent.createChooser(send, "Send to AI app").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ); true
            }
        } catch (e: Exception) {
            false
        }
        _capture.value = _capture.value.copy(
            stage = Stage.WAITING_FOR_AI,
            info = if (launched)
                "Prompt sent to ${if (app == AiApp.ASK) "the AI app" else app.label} (also copied). When it replies, copy the reply and tap Paste AI reply, or share it to ChatTasker."
            else
                "Couldn't open the AI app. The prompt is copied: paste it into ChatGPT or Gemini yourself.",
        )
    }

    fun quickTasks() {
        val drafts = QuickExtractor.extract(_capture.value.messages, Store.settings.value.defaultTime)
        _capture.value = _capture.value.copy(
            stage = Stage.REVIEW,
            drafts = drafts,
            info = "Quick tasks (no AI): ticked items look actionable. Edit titles before saving.",
        )
    }

    fun updateDraft(d: Draft) {
        _capture.value = _capture.value.copy(
            drafts = _capture.value.drafts.map { if (it.key == d.key) d else it }
        )
    }

    fun removeMessage(index: Int) {
        val list = _capture.value.messages.toMutableList()
        if (index in list.indices) list.removeAt(index)
        _capture.value = if (list.isEmpty()) CaptureState()
        else _capture.value.copy(messages = list)
    }

    fun saveDrafts(ctx: Context) {
        val s = Store.settings.value
        val now = LocalDateTime.now()
        val chosen = _capture.value.drafts.filter { it.selected }
        val tasks = chosen.filter { !it.isNote && it.title.isNotBlank() }.map { d ->
            val due = d.due
            val remind = when {
                due == null -> null
                d.direction == Direction.WAITING -> due.plusMinutes(s.waitingFollowUpMinutes.toLong())
                else -> due.minusMinutes(s.leadMinutes.toLong())
            }
            TaskItem(
                id = Ids.next(),
                title = d.title.trim(),
                person = d.person.trim(),
                direction = d.direction,
                due = d.due,
                remindAt = remind,
                priority = d.priority,
                createdAt = now,
            )
        }
        val notes = chosen.filter { it.isNote && it.title.isNotBlank() }.map { d ->
            NoteEntry(id = Ids.next(), person = d.person.trim().ifEmpty { "General" }, text = d.title.trim(), createdAt = now)
        }
        Store.addTasks(tasks)
        Store.addNotes(notes)
        tasks.forEach { Reminders.schedule(ctx, it) }
        _capture.value = CaptureState(info = "Saved ${tasks.size} task(s) and ${notes.size} note(s). Messages cleared.")
    }

    fun clear() {
        _capture.value = CaptureState(info = "Cleared.")
    }

    fun backToMessages() {
        _capture.value = _capture.value.copy(
            stage = if (_capture.value.messages.isEmpty()) Stage.IDLE else Stage.MESSAGES,
            drafts = emptyList(),
            info = null,
        )
    }

    fun dismissInfo() {
        _capture.value = _capture.value.copy(info = null)
    }

    override fun onCleared() {
        _capture.value = CaptureState()
    }
}
