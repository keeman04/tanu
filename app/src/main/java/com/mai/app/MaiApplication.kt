package com.mai.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mai.app.data.MaiDb
import com.mai.app.retention.AudioRetentionWorker
import java.util.concurrent.TimeUnit

class MaiApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // If Android killed MAI during a prior recording, preserve the latest checkpoint
        // instead of leaving a meeting permanently marked as recording.
        runCatching { MaiDb(this).recoverInterruptedMeetings() }

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
