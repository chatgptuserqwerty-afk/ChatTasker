package com.chattasker.core

import java.time.LocalDateTime

/** MINE = something I need to do. WAITING = something someone else owes me. */
enum class Direction { MINE, WAITING }

enum class Priority { HIGH, MEDIUM, LOW }

enum class Repeat { NONE, DAILY, WEEKLY, MONTHLY }

enum class DateOrder { AUTO, MDY, DMY }

/** One WhatsApp message. Held in memory only, never written to disk. */
data class ChatMessage(
    val time: LocalDateTime?,
    val sender: String,
    val text: String,
    val fromMe: Boolean,
)

data class TaskItem(
    val id: Long,
    val title: String,
    val person: String = "",
    val direction: Direction = Direction.MINE,
    val due: LocalDateTime? = null,
    /** When the reminder fires. Null = no reminder. */
    val remindAt: LocalDateTime? = null,
    val priority: Priority = Priority.MEDIUM,
    val tags: List<String> = emptyList(),
    val details: String = "",
    val repeat: Repeat = Repeat.NONE,
    val done: Boolean = false,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

data class NoteEntry(
    val id: Long,
    val person: String,
    val text: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

/** A task or note proposed by the AI or the quick parser, waiting for review. */
data class Draft(
    val key: Int,
    val isNote: Boolean,
    val title: String,
    val person: String,
    val direction: Direction = Direction.MINE,
    val due: LocalDateTime? = null,
    val priority: Priority = Priority.MEDIUM,
    val selected: Boolean = true,
)

object Ids {
    private var last = 0L
    @Synchronized
    fun next(): Long {
        val now = System.currentTimeMillis()
        last = if (now > last) now else last + 1
        return last
    }
}
