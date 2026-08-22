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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mai.app.MainActivity
import com.mai.app.R
import com.mai.app.data.MaiDb
import com.mai.app.intelligence.AiEnhanceWorker
import com.mai.app.intelligence.MomEngine
import org.json.JSONObject
import org.vosk.Recognizer
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
        private const val SAMPLE_RATE = 16000
        private const val CHECKPOINT_MS = 10_000L
        private const val UI_UPDATE_MS = 100L
        private const val NOTIFICATION_UPDATE_MS = 1_000L
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingBus.update { it.copy(active = false, status = "error", error = "Microphone permission missing") }
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
                // Last-resort guard: an exception on the audio thread must never be allowed
                // to become an uncaught process-level crash.
                RecordingBus.update { it.copy(error = t.message ?: "Recording error", status = "error") }
                finalizeMeeting(id, File(File(filesDir, "audio"), "$id.aac"), "", t)
            }
        }, "mai-audio").apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
                RecordingBus.update { it.copy(active = false, status = "error", error = error.message ?: "Recording thread failed") }
            }
            start()
        }
    }

    private fun captureLoop(id: String) {
        val output = File(File(filesDir, "audio").apply { mkdirs() }, "$id.aac")
        var db: MaiDb? = null
        var audio: AudioRecord? = null
        var sink: AacAdtsSink? = null
        var recognizer: Recognizer? = null
        var transcript = StringBuilder()
        var recordingFailure: Throwable? = null
        var lastCheckpoint = 0L
        var lastUi = 0L
        var lastNotification = 0L
        var lastGoodRead = 0L
        var speechLoadRequested = false
        var consecutiveReadErrors = 0

        try {
            db = MaiDb(this)
            transcript = StringBuilder(db.getMeeting(id)?.transcript.orEmpty())

            val created = createAudioRecord()
            audio = created.first
            val bufferSize = created.second
            activeAudio = audio

            sink = AacAdtsSink(output)
            audio.startRecording()
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Microphone did not enter recording state")
            }

            val buffer = ByteArray(bufferSize)
            RecordingBus.update { it.copy(active = true, status = "recording", error = null) }

            while (running.get()) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read > 0) {
                    consecutiveReadErrors = 0
                    val now = System.currentTimeMillis()
                    lastGoodRead = now
                    sink.writePcm(buffer, read)

                    // Start optional offline STT only after the microphone is confirmed live.
                    // It is never allowed to gate or precede audio capture.
                    if (!speechLoadRequested) {
                        speechLoadRequested = true
                        runCatching { SpeechModelHolder.ensureForRecording(this) }
                    }

                    if (recognizer == null) {
                        SpeechModelHolder.model?.let { loadedModel ->
                            recognizer = runCatching {
                                Recognizer(loadedModel, SAMPLE_RATE.toFloat()).apply { setWords(true) }
                            }.getOrNull()
                        }
                    }

                    val level = normalize(rms(buffer, read))
                    var partial = ""
                    recognizer?.let { r ->
                        val accepted = runCatching { r.acceptWaveForm(buffer, read) }.getOrDefault(false)
                        if (accepted) {
                            val text = runCatching { JSONObject(r.result).optString("text").trim() }.getOrDefault("")
                            if (text.isNotBlank()) {
                                if (transcript.isNotEmpty()) transcript.append('\n')
                                transcript.append(text)
                            }
                        } else {
                            partial = runCatching { JSONObject(r.partialResult).optString("partial").trim() }.getOrDefault("")
                        }
                    }

                    if (now - lastCheckpoint >= CHECKPOINT_MS) {
                        lastCheckpoint = now
                        runCatching { sink.checkpoint() }
                        runCatching { db.checkpointMeeting(id, transcript.toString(), output.absolutePath) }
                    }

                    val audioSafe = output.exists() && output.length() > 512L && now - lastGoodRead < 3_000L
                    if (now - lastUi >= UI_UPDATE_MS) {
                        lastUi = now
                        RecordingBus.update { old ->
                            old.copy(
                                active = true,
                                meetingId = id,
                                elapsedMs = now - startedAt,
                                level = level,
                                levels = (old.levels + level).takeLast(48),
                                transcript = transcript.toString(),
                                partial = partial,
                                audioSafe = audioSafe,
                                status = when {
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
                        updateNotification(now - startedAt, audioSafe)
                    }
                } else {
                    consecutiveReadErrors++
                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw IOException("Microphone disconnected")
                    }
                    if (consecutiveReadErrors >= 8) {
                        throw IOException("Microphone stopped delivering audio ($read)")
                    }
                    Thread.sleep(20)
                }
            }

            recognizer?.let { r ->
                val text = runCatching { JSONObject(r.finalResult).optString("text").trim() }.getOrDefault("")
                if (text.isNotBlank()) {
                    if (transcript.isNotEmpty()) transcript.append('\n')
                    transcript.append(text)
                }
            }
        } catch (t: Throwable) {
            recordingFailure = t
            RecordingBus.update { it.copy(error = t.message ?: "Recording error", status = "error", active = false) }
        } finally {
            activeAudio = null
            runCatching { audio?.stop() }
            runCatching { audio?.release() }
            runCatching { recognizer?.close() }
            runCatching { sink?.close() }
            runCatching { db?.checkpointMeeting(id, transcript.toString(), output.takeIf { it.exists() }?.absolutePath) }
            finalizeMeeting(id, output, transcript.toString(), recordingFailure)
        }
    }

    private fun createAudioRecord(): Pair<AudioRecord, Int> {
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

    private fun finalizeMeeting(id: String, output: File, transcript: String, failure: Throwable?) {
        val db = runCatching { MaiDb(this) }.getOrNull()
        val meeting = runCatching { db?.getMeeting(id) }.getOrNull()
        if (db != null && meeting != null) {
            val mom = runCatching { MomEngine.generate(transcript, meeting.participants, meeting.startedAt) }
                .getOrElse { MomEngine.generate("", meeting.participants, meeting.startedAt) }
            val retentionDays = getSharedPreferences("mai_settings", MODE_PRIVATE).getInt("audio_retention_days", 0)
            val ended = System.currentTimeMillis()
            val path = output.takeIf { it.exists() && it.length() > 0 }?.absolutePath
            val expiry = if (retentionDays <= 0) null else ended + retentionDays * 86_400_000L
            runCatching {
                db.finishMeeting(
                    id = id,
                    endedAt = ended,
                    transcript = transcript,
                    summary = if (failure == null) mom.summary else if (transcript.isBlank()) {
                        "Recording ended unexpectedly. Saved audio was preserved where possible."
                    } else mom.summary,
                    decisions = mom.decisions,
                    actions = mom.actions,
                    audioPath = path,
                    audioExpiresAt = expiry
                )
            }
            if (path != null) runCatching { enqueueAiEnhancement(id) }
        }

        val endedAt = System.currentTimeMillis()
        RecordingBus.update {
            it.copy(
                active = false,
                partial = "",
                status = "ready",
                elapsedMs = endedAt - startedAt,
                audioSafe = output.exists() && output.length() > 0,
                error = failure?.message
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
            runCatching { activeAudio?.stop() }
            thread?.interrupt()
        } else {
            stopSelf()
        }
    }

    override fun onDestroy() {
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

    private fun updateNotification(elapsed: Long, audioSafe: Boolean) {
        val status = if (audioSafe) "Audio stream active" else "Checking microphone"
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
