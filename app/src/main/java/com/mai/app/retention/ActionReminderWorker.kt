package com.mai.app.retention

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mai.app.MainActivity
import com.mai.app.R
import com.mai.app.data.MaiDb
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ActionReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }
        val today = LocalDate.now()
        val due = MaiDb(applicationContext).listMeetings().flatMap { meeting ->
            meeting.actions.mapNotNull { action ->
                val date = parse(action.due) ?: return@mapNotNull null
                if (date.isAfter(today)) null else Triple(meeting, action, date)
            }
        }
        if (due.isEmpty()) return Result.success()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "MAI action reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            applicationContext, 201, Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val overdue = due.count { it.third.isBefore(today) }
        val text = when {
            overdue > 0 -> "$overdue overdue · ${due.size} due action${if (due.size == 1) "" else "s"}"
            else -> "${due.size} action${if (due.size == 1) "" else "s"} due today"
        }
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.mai_notification)
            .setContentTitle("MAI · Actions need attention")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(due.take(4).joinToString("\n") { "• ${it.second.text}" }))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build())
        return Result.success()
    }

    private fun parse(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return listOf(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd MMM yyyy")).firstNotNullOfOrNull { fmt ->
            runCatching { LocalDate.parse(raw.trim(), fmt) }.getOrNull()
        }
    }

    companion object {
        private const val CHANNEL = "mai_actions"
        private const val NOTIFICATION_ID = 2101
    }
}
