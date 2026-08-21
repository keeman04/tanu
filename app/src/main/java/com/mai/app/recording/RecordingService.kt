package com.mai.app.recording

import android.Manifest
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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.mai.app.MainActivity
import com.mai.app.R
import com.mai.app.data.MaiDb
import com.mai.app.intelligence.MomEngine
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.File
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
    }

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var meetingId: String? = null
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> if (!running.get()) startRecording(intent.getStringExtra(EXTRA_MEETING_ID))
        }
        return START_NOT_STICKY
    }

    private fun startRecording(id: String?) {
        if (id.isNullOrBlank()) { stopSelf(); return }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingBus.update { it.copy(error = "Microphone permission missing") }
            stopSelf(); return
        }

        meetingId = id
        startedAt = System.currentTimeMillis()
        startForeground(NOTIFICATION_ID, notification("Recording", 0L))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAI:Recording")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        running.set(true)
        RecordingBus.update { RecordingSnapshot(active = true, meetingId = id, startedAt = startedAt, status = "recording") }

        // Audio starts first. Speech recognition warms up independently and can fail without
        // ever blocking or invalidating the source recording.
        SpeechModelHolder.ensureForRecording(this)
        thread = Thread({ captureLoop(id) }, "mai-audio").apply { start() }
    }

    private fun captureLoop(id: String) {
        val db = MaiDb(this)
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(4096, min * 2)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        val output = File(File(filesDir, "audio").apply { mkdirs() }, "$id.aac")
        var sink: AacAdtsSink? = null
        var recognizer: Recognizer? = null
        val transcript = StringBuilder(db.getMeeting(id)?.transcript.orEmpty())
        var audioSafe = output.exists() && output.length() > 512
        var lastCheckpoint = 0L
        var recordingFailure: Throwable? = null

        try {
            sink = AacAdtsSink(output)
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone failed to initialize")
            audio.startRecording()
            val buffer = ByteArray(bufferSize)
            var lastUi = 0L

            while (running.get()) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // Source audio is persisted before any recognition or UI work.
                sink.writePcm(buffer, read)
                audioSafe = audioSafe || output.length() > 512

                if (recognizer == null) {
                    SpeechModelHolder.model?.let { loadedModel ->
                        recognizer = runCatching { Recognizer(loadedModel, SAMPLE_RATE.toFloat()).apply { setWords(true) } }.getOrNull()
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

                val now = System.currentTimeMillis()
                if (now - lastCheckpoint >= CHECKPOINT_MS) {
                    lastCheckpoint = now
                    runCatching { sink.checkpoint() }
                    runCatching { db.checkpointMeeting(id, transcript.toString(), output.absolutePath) }
                    audioSafe = output.exists() && output.length() > 512
                }

                if (now - lastUi >= 100) {
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
                            status = when { recognizer == null -> "recording"; level < .035f -> "waiting"; else -> "voice" }
                        )
                    }
                    updateNotification(now - startedAt)
                }
            }

            recognizer?.let { r ->
                val text = runCatching { JSONObject(r.finalResult).optString("text").trim() }.getOrDefault("")
                if (text.isNotBlank()) { if (transcript.isNotEmpty()) transcript.append('\n'); transcript.append(text) }
            }
        } catch (t: Throwable) {
            recordingFailure = t
            RecordingBus.update { it.copy(error = t.message ?: "Recording error", status = "error") }
        } finally {
            runCatching { audio.stop() }
            runCatching { audio.release() }
            runCatching { recognizer?.close() }
            runCatching { sink?.close() }
            runCatching { db.checkpointMeeting(id, transcript.toString(), output.takeIf { it.exists() }?.absolutePath) }
            finalizeMeeting(id, output, transcript.toString(), recordingFailure)
        }
    }

    private fun finalizeMeeting(id: String, output: File, transcript: String, failure: Throwable?) {
        val db = MaiDb(this)
        val meeting = db.getMeeting(id)
        if (meeting != null) {
            val mom = MomEngine.generate(transcript, meeting.participants, meeting.startedAt)
            val retentionDays = getSharedPreferences("mai_settings", MODE_PRIVATE).getInt("audio_retention_days", 0)
            val ended = System.currentTimeMillis()
            val path = output.takeIf { it.exists() && it.length() > 0 }?.absolutePath
            val expiry = if (retentionDays <= 0) null else ended + retentionDays * 86_400_000L
            db.finishMeeting(
                id = id,
                endedAt = ended,
                transcript = transcript,
                summary = if (failure == null) mom.summary else if (transcript.isBlank()) "Recording ended unexpectedly. Saved audio was preserved where possible." else mom.summary,
                decisions = mom.decisions,
                actions = mom.actions,
                audioPath = path,
                audioExpiresAt = expiry
            )
        }
        RecordingBus.update {
            it.copy(
                active = false,
                partial = "",
                status = "ready",
                elapsedMs = System.currentTimeMillis() - startedAt,
                audioSafe = output.exists() && output.length() > 0
            )
        }
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopRecording() {
        if (running.compareAndSet(true, false)) thread?.interrupt() else stopSelf()
    }

    override fun onDestroy() {
        if (running.compareAndSet(true, false)) thread?.interrupt()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun rms(bytes: ByteArray, length: Int): Double {
        var sum = 0.0; var count = 0; var i = 0
        while (i + 1 < length) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xff)).toShort().toInt()
            sum += sample.toDouble() * sample; count++; i += 2
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
                .createNotificationChannel(NotificationChannel(CHANNEL, "MAI recording", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(status: String, elapsed: Long): android.app.Notification {
        val stop = PendingIntent.getService(this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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

    private fun updateNotification(elapsed: Long) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification("Audio safe", elapsed))
    }

    private fun formatElapsed(ms: Long): String {
        val total = ms / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
