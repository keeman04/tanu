package com.mai.app.pipeline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mai.app.data.MaiDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class ChunkUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = slots.withPermit {
        val meetingId = inputData.getString(KEY_MEETING_ID) ?: return@withPermit Result.failure()
        val sequence = inputData.getInt(KEY_SEQUENCE, -1)
        if (sequence < 0) return@withPermit Result.failure()
        val dao = MaiPipelineDatabase.get(applicationContext).dao()
        val chunk = dao.chunk(meetingId, sequence) ?: return@withPermit Result.success()
        val file = File(chunk.localPath)
        if (!file.exists()) {
            dao.markFailure(meetingId, sequence, ChunkState.FAILED, chunk.retryCount + 1, "Local audio missing")
            return@withPermit Result.failure()
        }
        val api = CloudApi(applicationContext)
        if (!api.configured) return@withPermit Result.success()
        val meeting = MaiDb(applicationContext).getMeeting(meetingId) ?: return@withPermit Result.failure()
        try {
            dao.updateChunk(chunk.copy(state = ChunkState.UPLOADING, lastError = null))
            api.ensureMeeting(meeting)
            check(api.uploadChunk(chunk)) { "Server did not acknowledge audio chunk" }
            dao.markUploaded(meetingId, sequence, ChunkState.UPLOADED, System.currentTimeMillis())
            SyncMeetingWorker.enqueue(applicationContext, meetingId, 2)
            Result.success()
        } catch (t: Throwable) {
            val retries = chunk.retryCount + 1
            dao.markFailure(meetingId, sequence, ChunkState.FAILED, retries, t.message?.take(500))
            if (runAttemptCount < 10) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_MEETING_ID = "meeting_id"
        private const val KEY_SEQUENCE = "sequence"
        private val slots = Semaphore(3)
        fun enqueue(context: Context, meetingId: String, sequence: Int) {
            val request = OneTimeWorkRequestBuilder<ChunkUploadWorker>()
                .setInputData(workDataOf(KEY_MEETING_ID to meetingId, KEY_SEQUENCE to sequence))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("mai-upload-$meetingId-$sequence", ExistingWorkPolicy.KEEP, request)
        }
    }
}

class SyncMeetingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val meetingId = inputData.getString(KEY_MEETING_ID) ?: return Result.failure()
        val api = CloudApi(applicationContext)
        if (!api.configured) return Result.success()
        val dao = MaiPipelineDatabase.get(applicationContext).dao()
        return try {
            val update = api.fetchUpdates(meetingId)
            update.chunks.forEach { server ->
                val local = dao.chunk(meetingId, server.sequence)
                if (!server.text.isNullOrBlank()) {
                    dao.upsertTranscript(TranscriptSegmentEntity(meetingId, server.sequence, server.startMs, server.endMs, server.text))
                    if (local != null) dao.updateChunk(local.copy(state = ChunkState.TRANSCRIBED, transcribedAtMs = System.currentTimeMillis(), lastError = null))
                } else if (local != null && server.state == "transcribing" && local.state == ChunkState.UPLOADED) {
                    dao.updateChunk(local.copy(state = ChunkState.TRANSCRIBING))
                }
            }
            dao.markServerSync(meetingId, System.currentTimeMillis())
            val transcript = dao.transcript(meetingId).joinToString("\n") { it.text }.trim()
            if (transcript.isNotBlank()) MaiDb(applicationContext).updateTranscript(meetingId, transcript)
            val pipelineMeeting = dao.meeting(meetingId)
            if (pipelineMeeting?.state in setOf("finalizing", "processing")) {
                val mom = api.fetchMom(meetingId)
                if (mom != null) {
                    MaiDb(applicationContext).applyCloudResult(meetingId, transcript, mom.summary, mom.decisions, mom.actions, mom.followUps)
                    dao.updateMeetingState(meetingId, "ready", pipelineMeeting.expectedChunks)
                    return Result.success()
                }
            }
            if (update.pendingChunks > 0 || pipelineMeeting?.state in setOf("finalizing", "processing")) enqueue(applicationContext, meetingId, 4)
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount < 10) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_MEETING_ID = "meeting_id"
        fun enqueue(context: Context, meetingId: String, delaySeconds: Long = 0) {
            val request = OneTimeWorkRequestBuilder<SyncMeetingWorker>()
                .setInputData(workDataOf(KEY_MEETING_ID to meetingId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("mai-sync-$meetingId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

class FinalizeMeetingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val meetingId = inputData.getString(KEY_MEETING_ID) ?: return Result.failure()
        val dao = MaiPipelineDatabase.get(applicationContext).dao()
        val api = CloudApi(applicationContext)
        val pipelineMeeting = dao.meeting(meetingId) ?: return Result.failure()
        if (!api.configured) {
            dao.updateMeetingState(meetingId, "local_only", pipelineMeeting.expectedChunks)
            return Result.success()
        }

        val chunks = dao.chunks(meetingId)
        val expected = pipelineMeeting.expectedChunks ?: chunks.size
        if (chunks.size < expected) {
            // A PCM file may still be waiting for encoding after a crash or a slow final encode.
            // Never lower the expected count merely because that recovered chunk is late.
            PipelineRecovery.schedule(applicationContext)
            return if (runAttemptCount < 12) Result.retry() else Result.failure()
        }

        chunks.filter { it.state in setOf(ChunkState.RECORDED, ChunkState.QUEUED, ChunkState.UPLOADING, ChunkState.FAILED) }
            .forEach { ChunkUploadWorker.enqueue(applicationContext, meetingId, it.sequence) }
        if (chunks.any { it.state !in setOf(ChunkState.UPLOADED, ChunkState.TRANSCRIBING, ChunkState.TRANSCRIBED) }) {
            return if (runAttemptCount < 12) Result.retry() else Result.failure()
        }

        val meeting = MaiDb(applicationContext).getMeeting(meetingId) ?: return Result.failure()
        return try {
            api.ensureMeeting(meeting)
            api.finalizeMeeting(meetingId, expected)
            dao.updateMeetingState(meetingId, "processing", expected)
            val update = api.fetchUpdates(meetingId)
            update.chunks.filter { !it.text.isNullOrBlank() }.forEach { server ->
                dao.upsertTranscript(TranscriptSegmentEntity(meetingId, server.sequence, server.startMs, server.endMs, server.text!!))
            }
            val transcript = dao.transcript(meetingId).joinToString("\n") { it.text }.trim()
            if (transcript.isNotBlank()) MaiDb(applicationContext).updateTranscript(meetingId, transcript)
            val mom = api.fetchMom(meetingId)
            if (mom != null) {
                MaiDb(applicationContext).applyCloudResult(meetingId, transcript, mom.summary, mom.decisions, mom.actions, mom.followUps)
                dao.updateMeetingState(meetingId, "ready", expected)
                Result.success()
            } else {
                SyncMeetingWorker.enqueue(applicationContext, meetingId, 3)
                if (runAttemptCount < 12) Result.retry() else Result.success()
            }
        } catch (_: Throwable) {
            if (runAttemptCount < 12) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_MEETING_ID = "meeting_id"
        fun enqueue(context: Context, meetingId: String, delaySeconds: Long = 0) {
            val request = OneTimeWorkRequestBuilder<FinalizeMeetingWorker>()
                .setInputData(workDataOf(KEY_MEETING_ID to meetingId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("mai-finalize-$meetingId", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dao = MaiPipelineDatabase.get(applicationContext).dao()
        val encoder = SpeechChunkEncoder()
        val prefs = applicationContext.getSharedPreferences("mai_recording", Context.MODE_PRIVATE)
        val activeId = prefs.getString("active_meeting_id", null)
        val heartbeat = prefs.getLong("active_heartbeat", 0L)
        val activeIsFresh = activeId != null && System.currentTimeMillis() - heartbeat < 30_000L

        PipelineFiles.meetingsRoot(applicationContext).listFiles { file -> file.isDirectory }.orEmpty().forEach { meetingDir ->
            val meetingId = meetingDir.name
            if (activeIsFresh && meetingId == activeId) return@forEach
            PipelineFiles.tempPcmFiles(applicationContext, meetingId).forEach { pcm ->
                val sequence = PipelineFiles.sequenceFromName(pcm.name)
                if (sequence < 0 || pcm.length() <= 0L) return@forEach
                if (dao.chunk(meetingId, sequence) != null) { pcm.delete(); return@forEach }
                runCatching {
                    val previousEnd = dao.chunks(meetingId).filter { it.sequence < sequence }.maxOfOrNull { it.endMs } ?: 0L
                    val duration = pcm.length() * 1000L / (SpeechChunkEncoder.SAMPLE_RATE * 2L)
                    val encoded = encoder.encode(pcm, PipelineFiles.encodedBase(applicationContext, meetingId, sequence))
                    dao.upsertChunk(AudioChunkEntity(
                        meetingId, sequence, encoded.file.absolutePath, previousEnd, previousEnd + duration, duration,
                        encoded.file.length(), PipelineFiles.sha256(encoded.file), encoded.codec, encoded.mimeType, ChunkState.QUEUED
                    ))
                    pcm.delete()
                    ChunkUploadWorker.enqueue(applicationContext, meetingId, sequence)
                }
            }
        }

        dao.recoverableChunks().forEach { chunk ->
            if (File(chunk.localPath).exists()) ChunkUploadWorker.enqueue(applicationContext, chunk.meetingId, chunk.sequence)
        }

        dao.activeMeetings().forEach { meeting ->
            if (!(activeIsFresh && meeting.id == activeId) && meeting.state == "recording") {
                val count = dao.chunkCount(meeting.id)
                if (count > 0) {
                    dao.updateMeetingState(meeting.id, "finalizing", count)
                    MaiDb(applicationContext).markProcessing(meeting.id)
                    FinalizeMeetingWorker.enqueue(applicationContext, meeting.id)
                }
            } else if (meeting.state in setOf("finalizing", "processing")) {
                FinalizeMeetingWorker.enqueue(applicationContext, meeting.id)
            }
        }
        Result.success()
    }
}

object PipelineRecovery {
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "mai-pipeline-recovery",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RecoveryWorker>().build()
        )
    }
}
