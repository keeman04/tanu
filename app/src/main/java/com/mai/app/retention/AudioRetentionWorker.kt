package com.mai.app.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mai.app.data.MaiDb
import java.io.File

class AudioRetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = MaiDb(applicationContext)
        var shouldRetry = false
        db.expiredAudio(System.currentTimeMillis()).forEach { meeting ->
            val path = meeting.audioPath ?: return@forEach
            val file = File(path)
            val deleted = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
            if (deleted) {
                db.markAudioDeleted(meeting.id)
            } else {
                // Keep the database reference so the user does not lose access to a file
                // that still exists. WorkManager can try the cleanup again later.
                shouldRetry = true
            }
        }
        return if (shouldRetry && runAttemptCount < 3) Result.retry() else Result.success()
    }
}
