package com.mai.app.recording

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mai.app.MainActivity
import com.mai.app.R
import com.mai.app.data.MaiDb
import com.mai.app.intelligence.MomEngine
import com.mai.app.pipeline.AudioChunkEntity
import com.mai.app.pipeline.ChunkState
import com.mai.app.pipeline.ChunkUploadWorker
import com.mai.app.pipeline.CloudApi
import com.mai.app.pipeline.FinalizeMeetingWorker
import com.mai.app.pipeline.MaiPipelineDatabase
import com.mai.app.pipeline.PipelineFiles
import com.mai.app.pipeline.PipelineMeetingEntity
import com.mai.app.pipeline.PipelineRecovery
import com.mai.app.pipeline.SpeechChunkEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.mai.app.START"
        const val ACTION_STOP = "com.mai.app.STOP"
        const val EXTRA_MEETING_ID = "meeting_id"
        private const val CHANNEL = "mai_recording"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20
        private const val FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000
        private const val TARGET_CHUNK_MS = 15_000L
        private const val MAX_CHUNK_MS = 20_000L
        private const val SILENCE_TO_CUT_MS = 320L
        private const val SILENCE_RMS_THRESHOLD = 0.012
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val encoder = SpeechChunkEncoder()
    private val encodeMutex = Mutex()
    private val pendingEncodes = AtomicInteger(0)
    @Volatile private var stopRequested = false
    @Volatile private var recording = false
    private var meetingId: String? = null
    private var startedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id = intent.getStringExtra(EXTRA_MEETING_ID)
                if (!id.isNullOrBlank() && !recording) {
                    startForeground(NOTIFICATION_ID, notification("Starting microphone", 0L))
                    scope.launch { runRecording(id) }
                }
            }
            ACTION_STOP -> {
                stopRequested = true
                RecordingBus.update { it.copy(status = "finishing") }
                updateNotification("Finishing meeting", elapsed())
            }
        }
        return START_REDELIVER_INTENT
    }

    @SuppressLint("MissingPermission")
    private suspend fun runRecording(id: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingBus.update { it.copy(error = "Microphone permission missing", status = "error") }
            finishService(); return
        }
        val mainDb = MaiDb(this)
        val meeting = mainDb.getMeeting(id) ?: run { finishService(); return }
        val dao = MaiPipelineDatabase.get(this).dao()
        meetingId = id
        startedAt = meeting.startedAt
        stopRequested = false
        recording = true
        persistActive(id)
        dao.upsertMeeting(PipelineMeetingEntity(id, meeting.title, meeting.startedAt, "recording"))
        PipelineRecovery.schedule(this)
        SpeechModelHolder.ensureForRecording(this)
        acquireWakeLock()

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, FRAME_BYTES * 8)
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            RecordingBus.update { it.copy(error = "Microphone failed to initialize", status = "error") }
            recorder.release(); finishService(); return
        }

        val dbMax = dao.maxSequence(id)
        val tempMax = PipelineFiles.tempPcmFiles(this, id).maxOfOrNull { PipelineFiles.sequenceFromName(it.name) } ?: -1
        var sequence = maxOf(dbMax, tempMax) + 1
        var chunkStartMs = elapsed()
        var pcmFile = PipelineFiles.tempPcm(this, id, sequence)
        var output = FileOutputStream(pcmFile)
        var bytesInChunk = 0L
        var silenceMs = 0L
        val frame = ByteArray(FRAME_BYTES)
        val localTranscript = StringBuilder()
        var recognizer: Recognizer? = null
        var lastUi = 0L
        var lastHeartbeat = 0L

        RecordingBus.update { RecordingSnapshot(active = true, meetingId = id, startedAt = startedAt, status = "recording") }
        try {
            recorder.startRecording()
            while (!stopRequested && scope.isActive) {
                var collected = 0
                while (collected < frame.size && !stopRequested) {
                    val read = recorder.read(frame, collected, frame.size - collected)
                    if (read > 0) collected += read
                    else if (read < 0) throw IllegalStateException("Microphone read failed: $read")
                }
                if (collected <= 0) continue

                // Source audio is persisted before any speech/AI work.
                output.write(frame, 0, collected)
                bytesInChunk += collected
                val frameDurationMs = (collected / 2L) * 1000L / SAMPLE_RATE
                silenceMs = if (isSilent(frame, collected)) silenceMs + frameDurationMs else 0L

                if (recognizer == null) {
                    SpeechModelHolder.model?.let { model ->
                        recognizer = runCatching { Recognizer(model, SAMPLE_RATE.toFloat()).apply { setWords(true) } }.getOrNull()
                    }
                }
                var partial = ""
                recognizer?.let { r ->
                    val accepted = runCatching { r.acceptWaveForm(frame, collected) }.getOrDefault(false)
                    if (accepted) {
                        val text = runCatching { JSONObject(r.result).optString("text").trim() }.getOrDefault("")
                        if (text.isNotBlank()) {
                            if (localTranscript.isNotEmpty()) localTranscript.append('\n')
                            localTranscript.append(text)
                        }
                    } else {
                        partial = runCatching { JSONObject(r.partialResult).optString("partial").trim() }.getOrDefault("")
                    }
                }

                val now = System.currentTimeMillis()
                val level = normalizeRms(frame, collected)
                if (now - lastUi >= 100L) {
                    lastUi = now
                    RecordingBus.update { old ->
                        old.copy(
                            active = true,
                            meetingId = id,
                            startedAt = startedAt,
                            elapsedMs = now - startedAt,
                            level = level,
                            levels = (old.levels + level).takeLast(48),
                            transcript = localTranscript.toString(),
                            partial = partial,
                            audioSafe = bytesInChunk > 512 || sequence > 0,
                            status = if (level <= 0f) "waiting" else "voice"
                        )
                    }
                    updateNotification("Recording · audio safe", now - startedAt)
                }
                if (now - lastHeartbeat >= 2_000L) {
                    lastHeartbeat = now
                    heartbeat(id)
                }

                val chunkDuration = bytesInChunk * 1000L / (SAMPLE_RATE * 2L)
                if ((chunkDuration >= TARGET_CHUNK_MS && silenceMs >= SILENCE_TO_CUT_MS) || chunkDuration >= MAX_CHUNK_MS) {
                    runCatching { output.fd.sync() }
                    output.close()
                    val endMs = chunkStartMs + chunkDuration
                    queueEncoding(id, sequence, pcmFile, chunkStartMs, endMs)
                    sequence++
                    chunkStartMs = endMs
                    pcmFile = PipelineFiles.tempPcm(this, id, sequence)
                    output = FileOutputStream(pcmFile)
                    bytesInChunk = 0L
                    silenceMs = 0L
                }
            }
        } catch (t: Throwable) {
            RecordingBus.update { it.copy(error = t.message ?: "Recording error", status = "partial") }
        } finally {
            runCatching { output.flush() }
            runCatching { output.fd.sync() }
            runCatching { output.close() }
            runCatching { recorder.stop() }
            recorder.release()
            recognizer?.let { r ->
                val finalText = runCatching { JSONObject(r.finalResult).optString("text").trim() }.getOrDefault("")
                if (finalText.isNotBlank()) {
                    if (localTranscript.isNotEmpty()) localTranscript.append('\n')
                    localTranscript.append(finalText)
                }
                runCatching { r.close() }
            }
            recording = false
        }

        val finalHasAudio = pcmFile.exists() && pcmFile.length() > 0L
        if (finalHasAudio) {
            val duration = pcmFile.length() * 1000L / (SAMPLE_RATE * 2L)
            queueEncoding(id, sequence, pcmFile, chunkStartMs, chunkStartMs + duration)
        } else pcmFile.delete()
        val expectedChunks = if (finalHasAudio) sequence + 1 else sequence

        RecordingBus.update { it.copy(status = "securing", partial = "") }
        waitForEncodes()
        val ended = System.currentTimeMillis()
        val localText = localTranscript.toString().trim()
        val localMom = MomEngine.generate(localText, meeting.participants, meeting.startedAt)
        val retentionDays = getSharedPreferences("mai_settings", MODE_PRIVATE).getInt("audio_retention_days", 0)
        val expires = if (retentionDays <= 0) null else ended + retentionDays * 86_400_000L
        val cloud = CloudApi(this).configured
        mainDb.finishMeeting(
            id = id,
            endedAt = ended,
            transcript = localText,
            summary = localMom.summary,
            decisions = localMom.decisions,
            actions = localMom.actions,
            followUps = localMom.followUps,
            audioPath = PipelineFiles.audioDir(this, id).absolutePath,
            audioExpiresAt = expires,
            status = if (cloud) "processing" else "ready"
        )
        dao.updateMeetingState(id, if (cloud) "finalizing" else "local_only", expectedChunks)
        if (cloud) FinalizeMeetingWorker.enqueue(this, id) else PipelineRecovery.schedule(this)

        RecordingBus.update {
            it.copy(active = false, meetingId = id, elapsedMs = ended - startedAt, partial = "", transcript = localText,
                audioSafe = expectedChunks > 0, status = "ready")
        }
        clearActive()
        finishService()
    }

    private fun queueEncoding(meetingId: String, sequence: Int, pcmFile: File, startMs: Long, endMs: Long) {
        pendingEncodes.incrementAndGet()
        scope.launch {
            try {
                val encoded = encodeMutex.withLock { encoder.encode(pcmFile, PipelineFiles.encodedBase(this@RecordingService, meetingId, sequence)) }
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
                MaiPipelineDatabase.get(this@RecordingService).dao().upsertChunk(chunk)
                pcmFile.delete()
                ChunkUploadWorker.enqueue(this@RecordingService, meetingId, sequence)
            } catch (t: Throwable) {
                RecordingBus.update { it.copy(error = "A saved chunk is waiting for recovery: ${t.message.orEmpty()}", status = "partial") }
            } finally {
                pendingEncodes.decrementAndGet()
            }
        }
    }

    private suspend fun waitForEncodes() {
        val deadline = android.os.SystemClock.elapsedRealtime() + 60_000L
        while (pendingEncodes.get() > 0 && android.os.SystemClock.elapsedRealtime() < deadline) delay(150L)
    }

    private fun isSilent(bytes: ByteArray, count: Int): Boolean {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < count) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample
            samples++
            i += 2
        }
        if (samples == 0) return true
        return sqrt(sum / samples) / 32768.0 < SILENCE_RMS_THRESHOLD
    }

    private fun normalizeRms(bytes: ByteArray, count: Int): Float {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < count) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample
            samples++
            i += 2
        }
        val rms = if (samples == 0) 0.0 else sqrt(sum / samples)
        val normalized = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
        return if (normalized < 0.035f) 0f else normalized
    }

    private fun acquireWakeLock() {
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAI:LongMeeting")
            .apply { acquire(10 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun persistActive(id: String) {
        getSharedPreferences("mai_recording", MODE_PRIVATE).edit()
            .putString("active_meeting_id", id).putLong("active_heartbeat", System.currentTimeMillis()).apply()
    }

    private fun heartbeat(id: String) {
        getSharedPreferences("mai_recording", MODE_PRIVATE).edit()
            .putString("active_meeting_id", id).putLong("active_heartbeat", System.currentTimeMillis()).apply()
    }

    private fun clearActive() {
        getSharedPreferences("mai_recording", MODE_PRIVATE).edit().clear().apply()
    }

    private fun elapsed(): Long = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)

    private fun finishService() {
        stopRequested = false
        recording = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!recording) clearActive()
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "MAI recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(status: String, elapsed: Long): android.app.Notification {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.mai_notification)
            .setContentTitle("MAI")
            .setContentText("$status · ${formatElapsed(elapsed)}")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(status: String, elapsed: Long) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status, elapsed))
    }

    private fun formatElapsed(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
