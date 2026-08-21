package com.mai.app.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mai.app.data.MaiDb
import java.io.File

class AudioRetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val db = MaiDb(applicationContext)
        db.expiredAudio(System.currentTimeMillis()).forEach { meeting ->
            val path = meeting.audioPath ?: return@forEach
            runCatching {
                val file = File(path)
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }
            db.markAudioDeleted(meeting.id)
        }
        return Result.success()
    }
}
