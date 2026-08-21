package com.mai.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mai.app.pipeline.PipelineRecovery
import com.mai.app.retention.ActionReminderWorker
import com.mai.app.retention.AudioRetentionWorker
import java.util.concurrent.TimeUnit

class MaiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Process startup stays lightweight: speech/native AI models are never loaded here.
        runCatching { PipelineRecovery.schedule(this) }
        runCatching {
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "mai-audio-retention",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<AudioRetentionWorker>(24, TimeUnit.HOURS).build()
            )
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "mai-action-reminders",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ActionReminderWorker>(24, TimeUnit.HOURS).build()
            )
        }
    }
}
