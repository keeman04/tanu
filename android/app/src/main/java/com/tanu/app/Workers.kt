package com.tanu.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class ChunkUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val meetingId = inputData.getString(KEY_MEETING_ID) ?: return Result.failure()
        val sequence = inputData.getInt(KEY_SEQUENCE, -1)
        if (sequence < 0) return Result.failure()
        val db = TanuDatabase.get(applicationContext)
        val dao = db.dao()
        val chunk = dao.chunk(meetingId, sequence) ?: return Result.success()
        val file = File(chunk.localPath)
        if (!file.exists()) {
            dao.updateChunkFailure(meetingId, sequence, ChunkState.FAILED, chunk.retryCount + 1, "Local audio missing")
            return Result.failure()
        }
        val meeting = dao.meeting(meetingId) ?: return Result.failure()
        return try {
            dao.updateChunk(chunk.copy(state = ChunkState.UPLOADING, lastError = null))
            val api = ApiClient()
            api.ensureMeeting(meeting)
            check(api.uploadChunk(chunk)) { "Server did not acknowledge chunk" }
            dao.markUploaded(meetingId, sequence, ChunkState.UPLOADED, System.currentTimeMillis())
            SyncMeetingWorker.enqueue(applicationContext, meetingId, delaySeconds = 3)
            Result.success()
        } catch (t: Throwable) {
            val retries = chunk.retryCount + 1
            dao.updateChunkFailure(meetingId, sequence, ChunkState.FAILED, retries, t.message?.take(500))
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_MEETING_ID = "meeting_id"
        private const val KEY_SEQUENCE = "sequence"

        fun enqueue(context: Context, meetingId: String, sequence: Int) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ChunkUploadWorker>()
                .setInputData(workDataOf(KEY_MEETING_ID to meetingId, KEY_SEQUENCE to sequence))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "tanu-upload-$meetingId-$sequence",
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}

class SyncMeetingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val meetingId = inputData.getString(KEY_MEETING_ID) ?: return Result.failure()
        val dao = TanuDatabase.get(applicationContext).dao()
        return try {
            val update = ApiClient().fetchUpdates(meetingId)
            update.chunks.forEach { server ->
                val local = dao.chunk(meetingId, server.sequence)
                if (!server.text.isNullOrBlank()) {
                    dao.upsertTranscript(TranscriptSegmentEntity(
                        meetingId, server.sequence, server.startMs, server.endMs, server.text
                    ))
                    if (local != null) dao.updateChunk(local.copy(state = ChunkState.TRANSCRIBED, transcribedAtMs = System.currentTimeMillis()))
                } else if (local != null && server.state == "transcribing" && local.state == ChunkState.UPLOADED) {
                    dao.updateChunk(local.copy(state = ChunkState.TRANSCRIBING))
                }
            }
            dao.clearRollingSummaries(meetingId)
            update.rollingSummaries.forEach { dao.upsertRollingSummary(it) }
            dao.markServerSync(meetingId, System.currentTimeMillis())
            val meeting = dao.meeting(meetingId)
            if (meeting?.state == "finalizing" || update.pendingChunks > 0) {
                enqueue(applicationContext, meetingId, delaySeconds = if (update.pendingChunks > 0) 5 else 2)
            }
            val mom = if (meeting?.state == "finalizing") ApiClient().fetchMom(meetingId) else null
            if (mom != null) dao.saveFinalMom(meetingId, mom.toJson(), "cloud")
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount < 8) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_MEETING_ID = "meeting_id"
        fun enqueue(context: Context, meetingId: String, delaySeconds: Long = 0) {
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SyncMeetingWorker>()
                .setInputData(workDataOf(KEY_MEETING_ID to meetingId))
                .setConstraints(constraints)
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "tanu-sync-$meetingId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}

class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = TanuDatabase.get(applicationContext).dao()
        dao.recoverableChunks().forEach { chunk ->
            if (File(chunk.localPath).exists()) ChunkUploadWorker.enqueue(applicationContext, chunk.meetingId, chunk.sequence)
        }
        Result.success()
    }
}

object PipelineRecovery {
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<RecoveryWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork("tanu-recovery", ExistingWorkPolicy.REPLACE, request)
    }
}
