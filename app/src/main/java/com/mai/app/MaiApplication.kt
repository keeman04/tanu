package com.mai.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mai.app.data.MaiDb
import com.mai.app.intelligence.AiEnhanceWorker
import com.mai.app.recording.RecoverableAudioWriter
import com.mai.app.retention.AudioRetentionWorker
import java.io.File
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
                val backendConfigured = BuildConfig.MAI_BACKEND_URL.isNotBlank()

                db.listMeetings().filter { it.status == "interrupted" }.forEach { meeting ->
                    val recoveredAudio = runCatching { RecoverableAudioWriter.recover(this, meeting.id) }.getOrNull()
                        ?: meeting.audioPath?.let(::File)?.takeIf { it.isFile && it.length() > 512L }
                    val ended = meeting.endedAt ?: System.currentTimeMillis()
                    val status = when {
                        recoveredAudio == null -> "processing_failed"
                        backendConfigured -> "processing"
                        else -> "recorded"
                    }
                    val summary = when {
                        recoveredAudio == null -> "Meeting was interrupted and MAI could not recover usable audio."
                        backendConfigured -> "Meeting was interrupted, saved audio was recovered, and MAI is processing the complete recording."
                        else -> "Meeting was interrupted, but the saved audio was recovered safely. Final AI processing is waiting for a configured backend."
                    }
                    db.finishMeeting(
                        id = meeting.id,
                        endedAt = ended,
                        transcript = meeting.transcript,
                        summary = summary,
                        decisions = emptyList(),
                        actions = emptyList(),
                        audioPath = recoveredAudio?.absolutePath,
                        audioExpiresAt = if (retentionDays <= 0) null else ended + retentionDays * 86_400_000L,
                        status = status
                    )
                    if (recoveredAudio != null && backendConfigured) enqueueAi(meeting.id)
                }

                // WorkManager is persistent, but app upgrades/device cleanup can remove a queued
                // request. Re-enqueue any safely recorded meeting that still needs final processing.
                if (backendConfigured) {
                    db.listMeetings()
                        .filter { it.status in setOf("processing", "recorded") }
                        .filter { it.audioPath?.let(::File)?.isFile == true }
                        .forEach { enqueueAi(it.id) }
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

    private fun enqueueAi(meetingId: String) {
        val request = OneTimeWorkRequestBuilder<AiEnhanceWorker>()
            .setInputData(AiEnhanceWorker.input(meetingId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("mai-ai-$meetingId", ExistingWorkPolicy.KEEP, request)
    }
}
