package com.mai.app.recording

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mai.app.BuildConfig
import com.mai.app.MainActivity
import com.mai.app.R
import com.mai.app.data.MaiDb
import com.mai.app.intelligence.AiEnhanceWorker
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class RecordingService : Service() {
    companion object {
        const val ACTION_START = "com.mai.app.START"
        const val ACTION_STOP = "com.mai.app.STOP"
        const val EXTRA_MEETING_ID = "meeting_id"
        private const val CHANNEL = "mai_recording"
        private const val NOTIFICATION_ID = 1001
        private const val SAMPLE_RATE = 16_000
        private const val CHECKPOINT_MS = 5_000L
        private const val STORAGE_CHECK_MS = 10_000L
        private const val UI_UPDATE_MS = 100L
        private const val NOTIFICATION_UPDATE_MS = 1_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    @Volatile private var activeAudio: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var meetingId: String? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> stopRecording()
                ACTION_START -> if (!running.get()) startRecording(intent.getStringExtra(EXTRA_MEETING_ID))
            }
        } catch (t: Throwable) {
            RecordingBus.update {
                it.copy(active = false, status = "error", error = t.message ?: "Unable to start recording")
            }
            releaseWakeLock()
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(id: String?) {
        if (id.isNullOrBlank()) {
            RecordingBus.update { it.copy(active = false, status = "error", error = "Meeting id is missing") }
            stopSelf()
            return
        }
        RecordingPreflight.blockingIssue(this)?.let { issue ->
            RecordingBus.update { it.copy(active = false, meetingId = id, status = "error", error = issue) }
            stopSelf()
            return
        }

        meetingId = id
        startedAt = System.currentTimeMillis()

        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting microphone", 0L),
            serviceType
        )

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAI:Recording")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        running.set(true)
        RecordingBus.update {
            RecordingSnapshot(active = true, meetingId = id, startedAt = startedAt, status = "starting")
        }

        thread = Thread({
            try {
                captureLoop(id)
            } catch (t: Throwable) {
                val recovered = runCatching { RecoverableAudioWriter.recover(this, id) }.getOrNull()
                val transcript = runCatching { MaiDb(this).getMeeting(id)?.transcript.orEmpty() }.getOrDefault("")
                finalizeMeeting(id, recovered, transcript, t)
            }
        }, "mai-audio").apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                RecordingBus.update {
                    it.copy(active = false, status = "error", error = error.message ?: "Recording thread failed")
                }
            }
            start()
        }
    }

    private fun captureLoop(id: String) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }

        val db = MaiDb(this)
        val baseTranscript = db.getMeeting(id)?.transcript.orEmpty().trim()
        var audio: AudioRecord? = null
        var buffer = ByteArray(8192)
        var health: MicHealthMonitor? = null
        var writer: RecoverableAudioWriter? = null
        var transcriber: SpeechTranscriber? = null
        var recordingFailure: Throwable? = null
        var lastCheckpoint = 0L
        var lastStorageCheck = 0L
        var lastUi = 0L
        var lastNotification = 0L
        var lastGoodRead = 0L
        var consecutiveReadErrors = 0
        var storageWarning: String? = null

        fun combinedTranscript(): String {
            val live = transcriber?.transcript().orEmpty().trim()
            return listOf(baseTranscript, live).filter { it.isNotBlank() }.joinToString("\n")
        }

        fun installHealthMonitor(recorder: AudioRecord): MicHealthMonitor = MicHealthMonitor(this, recorder) { message, silenced ->
            RecordingBus.update { old ->
                old.copy(
                    interruption = message,
                    audioSafe = if (silenced) false else old.audioSafe,
                    status = if (silenced) "interrupted" else if (old.active) "recording" else old.status
                )
            }
        }

        try {
            writer = RecoverableAudioWriter(this, id, SAMPLE_RATE)
            transcriber = SpeechTranscriber(
                context = this,
                sampleRate = SAMPLE_RATE,
                onUpdate = { transcript, partial ->
                    val text = listOf(baseTranscript, transcript.trim()).filter { it.isNotBlank() }.joinToString("\n")
                    RecordingBus.update { old -> old.copy(transcript = text, partial = partial) }
                },
                onWarning = { warning ->
                    RecordingBus.update { old -> old.copy(interruption = warning) }
                }
            )

            val opened = openMicrophone()
            audio = opened.first
            buffer = ByteArray(opened.second)
            activeAudio = audio
            health = installHealthMonitor(audio)
            RecordingBus.update { it.copy(active = true, status = "recording", error = null) }

            while (running.get()) {
                val currentAudio = audio ?: throw IOException("Microphone unavailable")
                val read = currentAudio.read(buffer, 0, buffer.size)
                if (read > 0) {
                    consecutiveReadErrors = 0
                    val now = System.currentTimeMillis()
                    lastGoodRead = now
                    writer.writePcm(buffer, read)
                    transcriber.offer(buffer, read)

                    health?.pollRouteChange()?.let { routeEvent ->
                        RecordingBus.update { old -> old.copy(interruption = routeEvent) }
                    }

                    if (now - lastCheckpoint >= CHECKPOINT_MS) {
                        lastCheckpoint = now
                        writer.checkpoint()
                        db.checkpointMeeting(id, combinedTranscript(), writer.recoveryPath)
                    }

                    if (now - lastStorageCheck >= STORAGE_CHECK_MS) {
                        lastStorageCheck = now
                        val storage = RecordingPreflight.storageState(this)
                        storageWarning = storage.warning
                        if (storage.critical) {
                            throw IOException("Storage critically low. Recording stopped safely before the audio file could be damaged.")
                        }
                    }

                    val silenced = health?.isSilenced() == true
                    val level = if (silenced) 0f else normalize(rms(buffer, read))
                    val audioSafe = writer.hasDurableAudio && now - lastGoodRead < 3_000L && !silenced

                    if (now - lastUi >= UI_UPDATE_MS) {
                        lastUi = now
                        RecordingBus.update { old ->
                            old.copy(
                                active = true,
                                meetingId = id,
                                elapsedMs = now - startedAt,
                                level = level,
                                levels = (old.levels + level).takeLast(48),
                                audioSafe = audioSafe,
                                storageWarning = storageWarning,
                                status = when {
                                    silenced -> "interrupted"
                                    !audioSafe -> "securing"
                                    level < .035f -> "listening"
                                    else -> "voice"
                                },
                                error = null
                            )
                        }
                    }
                    if (now - lastNotification >= NOTIFICATION_UPDATE_MS) {
                        lastNotification = now
                        updateNotification(now - startedAt, audioSafe, silenced)
                    }
                } else {
                    if (!running.get()) break
                    consecutiveReadErrors++
                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        RecordingBus.update { old ->
                            old.copy(
                                audioSafe = false,
                                status = "reconnecting",
                                interruption = "Microphone route disconnected. MAI is reconnecting…"
                            )
                        }
                        health?.close()
                        health = null
                        runCatching { currentAudio.stop() }
                        runCatching { currentAudio.release() }
                        activeAudio = null

                        var replacement: Pair<AudioRecord, Int>? = null
                        repeat(MAX_RECONNECT_ATTEMPTS) { attempt ->
                            if (replacement == null && running.get()) {
                                if (attempt > 0) Thread.sleep(300L)
                                replacement = runCatching { openMicrophone() }.getOrNull()
                            }
                        }
                        val reopened = replacement ?: throw IOException("Microphone disconnected and could not be restored")
                        audio = reopened.first
                        buffer = ByteArray(reopened.second)
                        activeAudio = audio
                        health = installHealthMonitor(audio!!)
                        consecutiveReadErrors = 0
                        RecordingBus.update { old ->
                            old.copy(status = "recording", interruption = "Microphone reconnected. Recording continued.")
                        }
                        continue
                    }
                    if (consecutiveReadErrors >= 8) {
                        throw IOException("Microphone stopped delivering audio ($read)")
                    }
                    Thread.sleep(20L)
                }
            }
        } catch (t: Throwable) {
            recordingFailure = t
            RecordingBus.update {
                it.copy(active = false, audioSafe = false, status = "error", error = t.message ?: "Recording error")
            }
        } finally {
            activeAudio = null
            health?.close()
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            runCatching { writer?.checkpoint() }
            runCatching { db.checkpointMeeting(id, combinedTranscript(), writer?.recoveryPath) }

            // Graceful user Stop reaches this path without interrupting the audio thread, so
            // realtime STT gets a chance to flush its last buffered words. This live text is
            // only a preview; the full AAC pass remains authoritative.
            runCatching { transcriber?.close() }
            val transcript = combinedTranscript()
            val finalAudio = runCatching { writer?.finalizeFile(this) }
                .getOrNull()
                ?: runCatching { RecoverableAudioWriter.recover(this, id) }.getOrNull()
            finalizeMeeting(id, finalAudio, transcript, recordingFailure)
        }
    }

    private fun openMicrophone(): Pair<AudioRecord, Int> {
        val created = createAudioRecord()
        val audio = created.first
        try {
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Microphone did not enter recording state")
            }
            return created
        } catch (t: Throwable) {
            runCatching { audio.release() }
            throw t
        }
    }

    private fun createAudioRecord(): Pair<AudioRecord, Int> {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission was revoked")
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuffer > 0) maxOf(8192, minBuffer * 2) else 8192
        val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        var lastError: Throwable? = null

        for (source in sources) {
            val recorder = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (t: Throwable) {
                lastError = t
                null
            }
            if (recorder != null) {
                if (recorder.state == AudioRecord.STATE_INITIALIZED) return recorder to bufferSize
                runCatching { recorder.release() }
                lastError = IllegalStateException("Audio source $source failed to initialize")
            }
        }
        throw IllegalStateException("No usable microphone input", lastError)
    }

    private fun finalizeMeeting(id: String, output: File?, transcript: String, failure: Throwable?) {
        val db = runCatching { MaiDb(this) }.getOrNull()
        val meeting = runCatching { db?.getMeeting(id) }.getOrNull()
        val finalPath = output?.takeIf { it.exists() && it.length() > 0L }?.absolutePath
        val backendConfigured = BuildConfig.MAI_BACKEND_URL.isNotBlank()
        val nextStatus = when {
            finalPath == null -> "processing_failed"
            backendConfigured -> "processing"
            else -> "recorded"
        }

        if (db != null && meeting != null) {
            val retentionDays = getSharedPreferences("mai_settings", MODE_PRIVATE).getInt("audio_retention_days", 7)
            val ended = System.currentTimeMillis()
            val expiry = if (retentionDays <= 0) null else ended + retentionDays * 86_400_000L
            val message = when {
                finalPath == null -> "Recording ended and MAI could not recover a usable audio file."
                !backendConfigured -> "Audio saved safely. Final multilingual transcription is waiting for a configured MAI backend."
                failure != null -> "Recording was interrupted, but saved audio was recovered. MAI is processing the complete recording."
                else -> "Audio saved safely. MAI is processing the complete recording for the final transcript and MOM."
            }
            runCatching {
                db.finishMeeting(
                    id = id,
                    endedAt = ended,
                    transcript = transcript,
                    summary = message,
                    decisions = emptyList(),
                    actions = emptyList(),
                    audioPath = finalPath,
                    audioExpiresAt = expiry,
                    status = nextStatus
                )
            }
            if (finalPath != null && backendConfigured) runCatching { enqueueAiEnhancement(id) }
        }

        val endedAt = System.currentTimeMillis()
        RecordingBus.update {
            it.copy(
                active = false,
                partial = "",
                status = nextStatus,
                elapsedMs = endedAt - startedAt,
                audioSafe = finalPath != null,
                error = if (finalPath == null) failure?.message ?: "No usable audio was recovered" else null,
                interruption = if (failure != null && finalPath != null) "Recording interruption recovered; final audio is safe." else it.interruption
            )
        }
        releaseWakeLock()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun enqueueAiEnhancement(id: String) {
        val request = OneTimeWorkRequestBuilder<AiEnhanceWorker>()
            .setInputData(AiEnhanceWorker.input(id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork("mai-ai-$id", ExistingWorkPolicy.REPLACE, request)
    }

    private fun stopRecording() {
        if (running.compareAndSet(true, false)) {
            // Do not interrupt the recording thread during a normal user Stop. Interrupting
            // it also interrupts the realtime final-flush wait and can drop the last words.
            runCatching { activeAudio?.stop() }
            RecordingBus.update { it.copy(status = "finalizing") }
        } else {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // onDestroy is an emergency/lifecycle teardown path, not the normal Stop path.
        if (running.compareAndSet(true, false)) {
            runCatching { activeAudio?.stop() }
            thread?.interrupt()
        }
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun rms(bytes: ByteArray, length: Int): Double {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    private fun normalize(rms: Double): Float {
        val raw = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
        return if (raw < .035f) 0f else raw
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(
                    NotificationChannel(CHANNEL, "MAI recording", NotificationManager.IMPORTANCE_LOW)
                )
        }
    }

    private fun notification(status: String, elapsed: Long): android.app.Notification {
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.mai_notification)
            .setContentTitle("MAI is recording")
            .setContentText("$status · ${formatElapsed(elapsed)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(elapsed: Long, audioSafe: Boolean, silenced: Boolean) {
        val status = when {
            silenced -> "Microphone interrupted"
            audioSafe -> "Audio checkpointed"
            else -> "Securing audio"
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(status, elapsed))
    }

    private fun formatElapsed(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
