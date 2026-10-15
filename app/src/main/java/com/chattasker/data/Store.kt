package com.chattasker.data

import android.content.Context
import com.chattasker.core.DateOrder
import com.chattasker.core.Direction
import com.chattasker.core.NoteEntry
import com.chattasker.core.Priority
import com.chattasker.core.Repeat
import com.chattasker.core.TaskItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.LocalTime

enum class AiApp(val label: String, val pkg: String?) {
    CHATGPT("ChatGPT", "com.openai.chatgpt"),
    GEMINI("Gemini", "com.google.android.apps.bard"),
    ASK("Ask each time", null),
}

data class Settings(
    val myName: String = "Narch",
    val dateOrder: DateOrder = DateOrder.AUTO,
    val defaultTime: LocalTime = LocalTime.of(10, 0),
    /** Reminder fires this many minutes before the due time. */
    val leadMinutes: Int = 0,
    val snoozeMinutes: Int = 30,
    val waitingFollowUpMinutes: Int = 60,
    val aiApp: AiApp = AiApp.CHATGPT,
    val digestEnabled: Boolean = true,
    val digestTime: LocalTime = LocalTime.of(8, 0),
    val extraInstructions: String = "",
    val tags: List<String> = listOf("Client", "Work", "Personal", "Urgent"),
)

/**
 * Stores ONLY tasks, notes and settings. Chat messages are never written here.
 * Shared by the UI and the reminder receivers, so it is a process-wide singleton.
 */
object Store {
    private lateinit var dir: File
    private lateinit var appContext: Context
    private var loaded = false

    private val _tasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val tasks: StateFlow<List<TaskItem>> = _tasks.asStateFlow()

    private val _notes = MutableStateFlow<List<NoteEntry>>(emptyList())
    val notes: StateFlow<List<NoteEntry>> = _notes.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        appContext = context.applicationContext
        dir = appContext.filesDir
        _tasks.value = readArray("tasks.json").mapNotNull { taskFromJson(it) }
        _notes.value = readArray("notes.json").mapNotNull { noteFromJson(it) }
        _settings.value = loadSettings()
        loaded = true
    }

    // ---------------- tasks ----------------

    @Synchronized
    fun upsertTask(t: TaskItem) {
        val list = _tasks.value.toMutableList()
        val i = list.indexOfFirst { it.id == t.id }
        if (i >= 0) list[i] = t else list += t
        _tasks.value = list
        saveTasks()
    }

    @Synchronized
    fun addTasks(items: List<TaskItem>) {
        _tasks.value = _tasks.value + items
        saveTasks()
    }

    @Synchronized
    fun deleteTask(id: Long) {
        _tasks.value = _tasks.value.filterNot { it.id == id }
        saveTasks()
    }

    fun task(id: Long): TaskItem? = _tasks.value.firstOrNull { it.id == id }

    // ---------------- notes ----------------

    @Synchronized
    fun addNotes(items: List<NoteEntry>) {
        _notes.value = _notes.value + items
        saveNotes()
    }

    @Synchronized
    fun upsertNote(n: NoteEntry) {
        val list = _notes.value.toMutableList()
        val i = list.indexOfFirst { it.id == n.id }
        if (i >= 0) list[i] = n else list += n
        _notes.value = list
        saveNotes()
    }

    @Synchronized
    fun deleteNote(id: Long) {
        _notes.value = _notes.value.filterNot { it.id == id }
        saveNotes()
    }

    // ---------------- settings ----------------

    @Synchronized
    fun updateSettings(s: Settings) {
        _settings.value = s
        val p = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        p.edit()
            .putString("myName", s.myName)
            .putString("dateOrder", s.dateOrder.name)
            .putString("defaultTime", s.defaultTime.toString())
            .putInt("leadMinutes", s.leadMinutes)
            .putInt("snoozeMinutes", s.snoozeMinutes)
            .putInt("waitingFollowUpMinutes", s.waitingFollowUpMinutes)
            .putString("aiApp", s.aiApp.name)
            .putBoolean("digestEnabled", s.digestEnabled)
            .putString("digestTime", s.digestTime.toString())
            .putString("extraInstructions", s.extraInstructions)
            .putString("tags", JSONArray(s.tags).toString())
            .apply()
    }

    private fun loadSettings(): Settings {
        val p = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val d = Settings()
        if (!p.contains("myName")) return d
        return Settings(
            myName = p.getString("myName", d.myName) ?: d.myName,
            dateOrder = enumOr(p.getString("dateOrder", null), d.dateOrder),
            defaultTime = timeOr(p.getString("defaultTime", null), d.defaultTime),
            leadMinutes = p.getInt("leadMinutes", d.leadMinutes),
            snoozeMinutes = p.getInt("snoozeMinutes", d.snoozeMinutes),
            waitingFollowUpMinutes = p.getInt("waitingFollowUpMinutes", d.waitingFollowUpMinutes),
            aiApp = enumOr(p.getString("aiApp", null), d.aiApp),
            digestEnabled = p.getBoolean("digestEnabled", d.digestEnabled),
            digestTime = timeOr(p.getString("digestTime", null), d.digestTime),
            extraInstructions = p.getString("extraInstructions", "") ?: "",
            tags = try {
                val a = JSONArray(p.getString("tags", "[]"))
                List(a.length()) { a.getString(it) }
            } catch (e: Exception) { d.tags },
        )
    }

    // ---------------- backup ----------------

    fun exportJson(): String = JSONObject()
        .put("version", 1)
        .put("tasks", JSONArray(_tasks.value.map { taskToJson(it) }))
        .put("notes", JSONArray(_notes.value.map { noteToJson(it) }))
        .toString(2)

    /** Replaces tasks and notes with the backup. Returns (tasks, notes) counts. */
    @Synchronized
    fun importJson(text: String): Pair<Int, Int> {
        val o = JSONObject(text)
        val t = o.optJSONArray("tasks") ?: JSONArray()
        val n = o.optJSONArray("notes") ?: JSONArray()
        _tasks.value = List(t.length()) { t.getJSONObject(it) }.mapNotNull { taskFromJson(it) }
        _notes.value = List(n.length()) { n.getJSONObject(it) }.mapNotNull { noteFromJson(it) }
        saveTasks(); saveNotes()
        return _tasks.value.size to _notes.value.size
    }

    // ---------------- JSON ----------------

    private fun saveTasks() = writeAtomic("tasks.json", JSONArray(_tasks.value.map { taskToJson(it) }).toString())
    private fun saveNotes() = writeAtomic("notes.json", JSONArray(_notes.value.map { noteToJson(it) }).toString())

    private fun writeAtomic(name: String, content: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(content)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    private fun readArray(name: String): List<JSONObject> {
        val f = File(dir, name)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            List(a.length()) { a.getJSONObject(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun taskToJson(t: TaskItem) = JSONObject()
        .put("id", t.id)
        .put("title", t.title)
        .put("person", t.person)
        .put("direction", t.direction.name)
        .put("due", t.due?.toString() ?: JSONObject.NULL)
        .put("remindAt", t.remindAt?.toString() ?: JSONObject.NULL)
        .put("priority", t.priority.name)
        .put("tags", JSONArray(t.tags))
        .put("details", t.details)
        .put("repeat", t.repeat.name)
        .put("done", t.done)
        .put("createdAt", t.createdAt.toString())

    private fun taskFromJson(o: JSONObject): TaskItem? = try {
        val tags = o.optJSONArray("tags") ?: JSONArray()
        TaskItem(
            id = o.getLong("id"),
            title = o.getString("title"),
            person = o.optString("person", ""),
            direction = enumOr(o.optString("direction"), Direction.MINE),
            due = dt(o, "due"),
            remindAt = dt(o, "remindAt"),
            priority = enumOr(o.optString("priority"), Priority.MEDIUM),
            tags = List(tags.length()) { tags.getString(it) },
            details = o.optString("details", ""),
            repeat = enumOr(o.optString("repeat"), Repeat.NONE),
            done = o.optBoolean("done", false),
            createdAt = dt(o, "createdAt") ?: LocalDateTime.now(),
        )
    } catch (e: Exception) { null }

    private fun noteToJson(n: NoteEntry) = JSONObject()
        .put("id", n.id).put("person", n.person).put("text", n.text)
        .put("createdAt", n.createdAt.toString())

    private fun noteFromJson(o: JSONObject): NoteEntry? = try {
        NoteEntry(
            id = o.getLong("id"),
            person = o.optString("person", ""),
            text = o.getString("text"),
            createdAt = dt(o, "createdAt") ?: LocalDateTime.now(),
        )
    } catch (e: Exception) { null }

    private fun dt(o: JSONObject, key: String): LocalDateTime? =
        if (o.isNull(key)) null else try { LocalDateTime.parse(o.getString(key)) } catch (e: Exception) { null }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        try { if (name.isNullOrEmpty()) default else enumValueOf<E>(name) } catch (e: Exception) { default }

    private fun timeOr(s: String?, d: LocalTime): LocalTime =
        try { if (s == null) d else LocalTime.parse(s) } catch (e: Exception) { d }
}
