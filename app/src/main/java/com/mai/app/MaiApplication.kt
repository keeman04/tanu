package com.mai.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mai.app.data.MaiDb
import com.mai.app.intelligence.MomEngine
import com.mai.app.retention.AudioRetentionWorker
import java.util.concurrent.TimeUnit

class MaiApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Product default: retain source audio for 7 days unless the user explicitly
        // chooses another policy. MOM/transcript/actions are not tied to this expiry.
        val settings = getSharedPreferences("mai_settings", MODE_PRIVATE)
        if (!settings.contains("audio_retention_days")) {
            settings.edit().putInt("audio_retention_days", 7).apply()
        }

        // Recover any session Android killed while MAI was recording. The last durable
        // transcript/audio checkpoint is converted into a normal usable meeting record.
        runCatching {
            val db = MaiDb(this)
            db.recoverInterruptedMeetings()
            val retentionDays = settings.getInt("audio_retention_days", 7)
            db.listMeetings().filter { it.status == "interrupted" }.forEach { meeting ->
                val mom = MomEngine.generate(meeting.transcript, meeting.participants, meeting.startedAt)
                val ended = meeting.endedAt ?: System.currentTimeMillis()
                db.finishMeeting(
                    id = meeting.id,
                    endedAt = ended,
                    transcript = meeting.transcript,
                    summary = if (meeting.transcript.isBlank()) "Meeting was interrupted. Saved audio was preserved where possible." else mom.summary,
                    decisions = mom.decisions,
                    actions = mom.actions,
                    audioPath = meeting.audioPath,
                    audioExpiresAt = if (retentionDays <= 0) null else ended + retentionDays * 86_400_000L
                )
            }
        }

        runCatching {
            val retention = PeriodicWorkRequestBuilder<AudioRetentionWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "mai-audio-retention",
                ExistingPeriodicWorkPolicy.UPDATE,
                retention
            )
        }
    }
}
