package com.mai.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mai.app.data.MaiDb
import com.mai.app.intelligence.MomEngine
import com.mai.app.recording.RecoverableAudioWriter
import com.mai.app.retention.AudioRetentionWorker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MaiApplication : Application() {
    private val recoveryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mai-recovery").apply { isDaemon = true }
    }

    override fun onCreate() {
        super.onCreate()

        val settings = getSharedPreferences("mai_settings", MODE_PRIVATE)
        if (!settings.contains("audio_retention_days")) {
            settings.edit().putInt("audio_retention_days", 7).apply()
        }

        // A multi-hour interrupted meeting can contain hundreds of 15-second chunks.
        // Never rebuild those on Android's main/startup thread; MAI must open immediately.
        recoveryExecutor.execute {
            runCatching {
                val db = MaiDb(this)
                db.recoverInterruptedMeetings()
                val retentionDays = settings.getInt("audio_retention_days", 7)
                db.listMeetings().filter { it.status == "interrupted" }.forEach { meeting ->
                    val recoveredAudio = runCatching { RecoverableAudioWriter.recover(this, meeting.id) }.getOrNull()
                    val mom = MomEngine.generate(meeting.transcript, meeting.participants, meeting.startedAt)
                    val ended = meeting.endedAt ?: System.currentTimeMillis()
                    db.finishMeeting(
                        id = meeting.id,
                        endedAt = ended,
                        transcript = meeting.transcript,
                        summary = if (meeting.transcript.isBlank()) {
                            "Meeting was interrupted. Saved audio was recovered where possible."
                        } else mom.summary,
                        decisions = mom.decisions,
                        actions = mom.actions,
                        audioPath = recoveredAudio?.absolutePath ?: meeting.audioPath?.takeIf { java.io.File(it).isFile },
                        audioExpiresAt = if (retentionDays <= 0) null else ended + retentionDays * 86_400_000L
                    )
                }
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
