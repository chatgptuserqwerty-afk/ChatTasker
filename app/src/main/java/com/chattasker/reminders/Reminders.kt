package com.chattasker.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.chattasker.MainActivity
import com.chattasker.R
import com.chattasker.core.Direction
import com.chattasker.core.Repeat
import com.chattasker.core.TaskItem
import com.chattasker.data.Store
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * In-app reminders: the app schedules its own alarms with AlarmManager and shows
 * its own notifications. Nothing is added to the calendar or the Clock app.
 */
object Reminders {
    const val CH_REMINDERS = "reminders"
    const val CH_DIGEST = "digest"

    const val ACTION_REMIND = "com.chattasker.REMIND"
    const val ACTION_DONE = "com.chattasker.DONE"
    const val ACTION_SNOOZE = "com.chattasker.SNOOZE"
    const val ACTION_DIGEST = "com.chattasker.DIGEST"
    const val EXTRA_ID = "task_id"

    private const val DIGEST_REQ = 0x5EED

    private val timeFmt = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDERS, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Reminders for your tasks" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DIGEST, "Daily digest", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Morning summary of due and overdue tasks" }
        )
    }

    // ---------------- scheduling ----------------

    fun schedule(ctx: Context, t: TaskItem) {
        cancel(ctx, t.id)
        val at = t.remindAt ?: return
        if (t.done) return
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (millis < System.currentTimeMillis() - 60_000) return // past, skip
        setAlarm(ctx, millis, pending(ctx, ACTION_REMIND, t.id))
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx, ACTION_REMIND, id))
    }

    fun rescheduleAll(ctx: Context) {
        Store.init(ctx)
        Store.tasks.value.forEach { schedule(ctx, it) }
        scheduleDigest(ctx)
    }

    fun scheduleDigest(ctx: Context) {
        val s = Store.settings.value
        val pi = PendingIntent.getBroadcast(
            ctx, DIGEST_REQ,
            Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_DIGEST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pi)
        if (!s.digestEnabled) return
        var next = LocalDateTime.now().with(s.digestTime).withSecond(0).withNano(0)
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1)
        setAlarm(ctx, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    private fun setAlarm(ctx: Context, millis: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactAllowed) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    private fun pending(ctx: Context, action: String, id: Long): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx, requestCode(action, id), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requestCode(action: String, id: Long): Int =
        (id xor (id ushr 32)).toInt() * 31 + action.hashCode()

    // ---------------- task actions ----------------

    /** Marks a task done, or rolls a repeating task to its next occurrence. */
    fun complete(ctx: Context, t: TaskItem) {
        if (t.repeat == Repeat.NONE) {
            val done = t.copy(done = true)
            Store.upsertTask(done)
            cancel(ctx, t.id)
        } else {
            val next = t.copy(due = advance(t.due, t.repeat), remindAt = advance(t.remindAt, t.repeat))
            Store.upsertTask(next)
            schedule(ctx, next)
        }
        NotificationManagerCompat.from(ctx).cancel(notifId(t.id))
    }

    fun snooze(ctx: Context, t: TaskItem) {
        val mins = Store.settings.value.snoozeMinutes.toLong().coerceAtLeast(1)
        val next = t.copy(remindAt = LocalDateTime.now().plusMinutes(mins).withSecond(0).withNano(0))
        Store.upsertTask(next)
        schedule(ctx, next)
        NotificationManagerCompat.from(ctx).cancel(notifId(t.id))
    }

    private fun advance(d: LocalDateTime?, r: Repeat): LocalDateTime? {
        if (d == null) return null
        var x: LocalDateTime = d
        val now = LocalDateTime.now()
        do {
            x = when (r) {
                Repeat.DAILY -> x.plusDays(1)
                Repeat.WEEKLY -> x.plusWeeks(1)
                Repeat.MONTHLY -> x.plusMonths(1)
                Repeat.NONE -> return x
            }
        } while (x.isBefore(now))
        return x
    }

    // ---------------- notifications ----------------

    fun notifId(id: Long): Int = (id xor (id ushr 32)).toInt()

    private fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, req: Int): PendingIntent = PendingIntent.getActivity(
        ctx, req,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun showReminder(ctx: Context, t: TaskItem) {
        if (!canNotify(ctx)) return
        val who = if (t.person.isNotBlank()) t.person else null
        val heading = when (t.direction) {
            Direction.WAITING -> "Follow up" + (who?.let { " with $it" } ?: "")
            Direction.MINE -> "To do" + (who?.let { " for $it" } ?: "")
        }
        val body = buildString {
            append(t.title)
            t.due?.let { append("\nDue ").append(timeFmt.format(it)) }
            if (t.details.isNotBlank()) append("\n").append(t.details)
        }
        val n = NotificationCompat.Builder(ctx, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(t.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, notifId(t.id)))
            .addAction(0, "Done", pending(ctx, ACTION_DONE, t.id))
            .addAction(0, "Snooze ${Store.settings.value.snoozeMinutes} min", pending(ctx, ACTION_SNOOZE, t.id))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(notifId(t.id), n)
        } catch (e: SecurityException) { /* permission revoked */ }
    }

    fun showDigest(ctx: Context) {
        if (!canNotify(ctx)) return
        val now = LocalDateTime.now()
        val endOfDay = now.toLocalDate().plusDays(1).atStartOfDay()
        val open = Store.tasks.value.filter { !it.done }
        val overdue = open.filter { it.due != null && it.due.isBefore(now) }
        val today = open.filter { it.due != null && !it.due.isBefore(now) && it.due.isBefore(endOfDay) }
        val waiting = open.count { it.direction == Direction.WAITING }
        if (overdue.isEmpty() && today.isEmpty()) return
        val lines = buildString {
            if (today.isNotEmpty()) {
                append("Due today:\n")
                today.sortedBy { it.due }.forEach { append("• ").append(it.title).append("\n") }
            }
            if (overdue.isNotEmpty()) {
                append("Overdue:\n")
                overdue.sortedBy { it.due }.forEach { append("• ").append(it.title).append("\n") }
            }
            if (waiting > 0) append("Waiting on others: $waiting")
        }.trim()
        val n = NotificationCompat.Builder(ctx, CH_DIGEST)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${today.size} due today, ${overdue.size} overdue")
            .setContentText(lines.lineSequence().drop(1).firstOrNull() ?: lines)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, DIGEST_REQ))
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(DIGEST_REQ, n)
        } catch (e: SecurityException) { }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        Reminders.createChannels(ctx)
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
        when (intent.action) {
            Reminders.ACTION_REMIND -> Store.task(id)?.takeIf { !it.done }?.let { Reminders.showReminder(ctx, it) }
            Reminders.ACTION_DONE -> Store.task(id)?.let { Reminders.complete(ctx, it) }
            Reminders.ACTION_SNOOZE -> Store.task(id)?.let { Reminders.snooze(ctx, it) }
            Reminders.ACTION_DIGEST -> {
                Reminders.showDigest(ctx)
                Reminders.scheduleDigest(ctx) // next day
            }
        }
    }
}

/** Alarms are cleared on reboot or time change, so put them back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.createChannels(ctx)
        Reminders.rescheduleAll(ctx)
    }
}
