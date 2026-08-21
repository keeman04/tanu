package com.tanu.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: TanuDatabase
    private lateinit var dao: TanuDao
    private val encoder = SpeechChunkEncoder()
    private val encodeMutex = Mutex()
    private val encodingJobs = mutableListOf<Job>()
    private val pendingEncodes = AtomicInteger(0)

    @Volatile private var stopRequested = false
    @Volatile private var recording = false
    private var activeMeetingId: String? = null
    private var activeTitle = "Meeting"
    private var meetingStartElapsed = 0L

    override fun onCreate() {
        super.onCreate()
        db = TanuDatabase.get(this)
        dao = db.dao()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val meetingId = intent.getStringExtra(EXTRA_MEETING_ID) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Meeting" }
                if (!recording) {
                    startForeground(NOTIFICATION_ID, notification("Starting microphone"))
                    scope.launch { runRecording(meetingId, title) }
                }
            }
            ACTION_STOP -> {
                stopRequested = true
                broadcast("finishing", "Closing the final audio chunk…")
            }
            ACTION_RETRY -> {
                PipelineRecovery.schedule(this)
                activeMeetingId?.let { SyncMeetingWorker.enqueue(this, it) }
                broadcast("retrying", "Retrying saved audio and transcript sync")
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private suspend fun runRecording(meetingId: String, title: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            broadcastFailure(meetingId, "Microphone permission is required")
            finishService(); return
        }

        activeMeetingId = meetingId
        activeTitle = title
        stopRequested = false
        recording = true
        meetingStartElapsed = android.os.SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val existing = dao.meeting(meetingId)
        dao.upsertMeeting(existing ?: MeetingEntity(meetingId, title, now, startedAtMs = now))
        scope.launch { runServerSyncLoop(meetingId) }

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_BYTES * 4)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            broadcastFailure(meetingId, "TANU could not initialize the microphone")
            recorder.release(); finishService(); return
        }

        var sequence = dao.maxSequence(meetingId) + 1
        var chunkStartMs = elapsedMeetingMs()
        var pcmFile = PipelineFiles.tempPcm(this, meetingId, sequence)
        var output = FileOutputStream(pcmFile)
        var bytesInChunk = 0L
        var silenceMs = 0L
        val frame = ByteArray(FRAME_BYTES)

        try {
            recorder.startRecording()
            broadcast("recording", "Audio safe • Opus chunks upload continuously")
            while (!stopRequested && scope.isActive) {
                var collected = 0
                while (collected < frame.size && !stopRequested) {
                    val read = recorder.read(frame, collected, frame.size - collected)
                    if (read > 0) collected += read
                }
                if (collected <= 0) continue
                output.write(frame, 0, collected)
                bytesInChunk += collected

                val frameDurationMs = (collected / 2L) * 1000L / SAMPLE_RATE
                silenceMs = if (isSilent(frame, collected)) silenceMs + frameDurationMs else 0L
                val chunkDurationMs = bytesInChunk * 1000L / (SAMPLE_RATE * 2L)
                val cutOnSilence = chunkDurationMs >= TARGET_CHUNK_MS && silenceMs >= SILENCE_TO_CUT_MS
                val hardCut = chunkDurationMs >= MAX_CHUNK_MS
                if (cutOnSilence || hardCut) {
                    output.flush(); output.close()
                    val endMs = chunkStartMs + chunkDurationMs
                    queueEncoding(meetingId, sequence, pcmFile, chunkStartMs, endMs)
                    sequence++
                    chunkStartMs = endMs
                    pcmFile = PipelineFiles.tempPcm(this, meetingId, sequence)
                    output = FileOutputStream(pcmFile)
                    bytesInChunk = 0L
                    silenceMs = 0L
                }
            }
        } catch (t: Throwable) {
            broadcast("partial", "Recording error; completed chunks are safe: ${t.message ?: "unknown"}")
        } finally {
            runCatching { output.flush() }
            runCatching { output.close() }
            runCatching { recorder.stop() }
            recorder.release()
            recording = false
        }

        if (pcmFile.exists() && pcmFile.length() > 0) {
            val durationMs = pcmFile.length() * 1000L / (SAMPLE_RATE * 2L)
            queueEncoding(meetingId, sequence, pcmFile, chunkStartMs, chunkStartMs + durationMs)
        } else {
            pcmFile.delete()
        }

        broadcast("encoding", "Securing final compressed chunks…")
        waitForEncodes()
        dao.updateMeetingState(meetingId, "finalizing", System.currentTimeMillis())
        runCatching { ApiClient().ensureMeeting(dao.meeting(meetingId)!!) }
        runCatching { ApiClient().finalizeMeeting(meetingId) }
        SyncMeetingWorker.enqueue(this, meetingId, 1)
        broadcast("finalizing", "Audio secured • transcript and MOM are finishing")
        waitForFinalMom(meetingId)
        finishService()
    }

    private fun queueEncoding(meetingId: String, sequence: Int, pcmFile: File, startMs: Long, endMs: Long) {
        pendingEncodes.incrementAndGet()
        val job = scope.launch {
            try {
                val encoded = encodeMutex.withLock {
                    encoder.encode(pcmFile, PipelineFiles.encodedBase(this@RecordingService, meetingId, sequence))
                }
                val chunk = AudioChunkEntity(
                    meetingId = meetingId,
                    sequence = sequence,
                    localPath = encoded.file.absolutePath,
                    startMs = startMs,
                    endMs = endMs,
                    durationMs = endMs - startMs,
                    sizeBytes = encoded.file.length(),
                    sha256 = PipelineFiles.sha256(encoded.file),
                    codec = encoded.codec,
                    mimeType = encoded.mimeType,
                    state = ChunkState.QUEUED
                )
                dao.upsertChunk(chunk)
                pcmFile.delete()
                ChunkUploadWorker.enqueue(this@RecordingService, meetingId, sequence)
                broadcast("recording", "Audio safe • ${pendingEncodes.get() - 1} chunk(s) encoding")
            } catch (t: Throwable) {
                broadcast("partial", "A chunk stayed as PCM for recovery: ${t.message ?: "encode failed"}")
            } finally {
                pendingEncodes.decrementAndGet()
            }
        }
        synchronized(encodingJobs) { encodingJobs += job }
    }

    private suspend fun waitForEncodes() {
        val deadline = android.os.SystemClock.elapsedRealtime() + 45_000L
        while (pendingEncodes.get() > 0 && android.os.SystemClock.elapsedRealtime() < deadline) delay(200)
    }

    private suspend fun runServerSyncLoop(meetingId: String) {
        while (recording && scope.isActive) {
            SyncMeetingWorker.enqueue(this@RecordingService, meetingId)
            delay(10_000)
        }
    }

    private suspend fun waitForFinalMom(meetingId: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + FINALIZE_DEADLINE_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val meeting = dao.meeting(meetingId)
            if (!meeting?.finalMomJson.isNullOrBlank()) {
                broadcast("ready", "MOM ready")
                return
            }
            runCatching {
                val update = ApiClient().fetchUpdates(meetingId)
                persistUpdate(meetingId, update)
                val mom = ApiClient().fetchMom(meetingId)
                if (mom != null) {
                    dao.saveFinalMom(meetingId, mom.toJson(), "cloud")
                    broadcast("ready", "MOM ready")
                    return
                }
            }
            delay(3_000)
        }
        broadcast("partial", "Audio is safe. Processing continues with retry/recovery.")
    }

    private suspend fun persistUpdate(meetingId: String, update: MeetingUpdate) {
        update.chunks.forEach { server ->
            if (!server.text.isNullOrBlank()) {
                dao.upsertTranscript(TranscriptSegmentEntity(meetingId, server.sequence, server.startMs, server.endMs, server.text))
                dao.chunk(meetingId, server.sequence)?.let {
                    dao.updateChunk(it.copy(state = ChunkState.TRANSCRIBED, transcribedAtMs = System.currentTimeMillis()))
                }
            }
        }
        dao.clearRollingSummaries(meetingId)
        update.rollingSummaries.forEach { dao.upsertRollingSummary(it) }
    }

    private fun isSilent(bytes: ByteArray, count: Int): Boolean {
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < count) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            sumSquares += sample.toDouble() * sample.toDouble()
            samples++
            i += 2
        }
        if (samples == 0) return true
        val rms = sqrt(sumSquares / samples) / 32768.0
        return rms < SILENCE_RMS_THRESHOLD
    }

    private fun elapsedMeetingMs(): Long = android.os.SystemClock.elapsedRealtime() - meetingStartElapsed

    private fun broadcast(state: String, message: String) {
        val id = activeMeetingId ?: return
        scope.launch {
            val pending = runCatching { dao.pendingUploadCount(id) }.getOrDefault(0)
            val storage = runCatching { dao.localAudioBytes(id) }.getOrDefault(0L)
            sendBroadcast(Intent(ACTION_UPDATE).setPackage(packageName)
                .putExtra(EXTRA_MEETING_ID, id)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_PENDING, pending)
                .putExtra(EXTRA_STORAGE_BYTES, storage))
            updateNotification(message)
        }
    }

    private fun broadcastFailure(meetingId: String, message: String) {
        activeMeetingId = meetingId
        broadcast("failed", message)
    }

    private fun finishService() {
        recording = false
        stopRequested = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "TANU recording", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TANU meeting")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(recording)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    companion object {
        const val ACTION_START = "com.tanu.app.START"
        const val ACTION_STOP = "com.tanu.app.STOP"
        const val ACTION_RETRY = "com.tanu.app.RETRY"
        const val ACTION_UPDATE = "com.tanu.app.PIPELINE_UPDATE"
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PENDING = "pending"
        const val EXTRA_STORAGE_BYTES = "storage_bytes"

        private const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20
        private const val FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000
        private const val TARGET_CHUNK_MS = 15_000L
        private const val MAX_CHUNK_MS = 20_000L
        private const val SILENCE_TO_CUT_MS = 300L
        private const val SILENCE_RMS_THRESHOLD = 0.012
        private const val FINALIZE_DEADLINE_MS = 90_000L
        private const val CHANNEL_ID = "tanu_recording"
        private const val NOTIFICATION_ID = 1042
    }
}
