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
        if (id.isNullOrBlank()) {
            stopSelf()
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingBus.update { it.copy(error = "Microphone permission missing") }
            stopSelf()
            return
        }

        // Never block a meeting because STT is still warming up. Audio capture starts first.
        SpeechModelHolder.ensure(this)
        meetingId = id
        startedAt = System.currentTimeMillis()
        startForeground(NOTIFICATION_ID, notification("Recording", 0L))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MAI:Recording")
            .apply { acquire() }
        running.set(true)
        RecordingBus.update {
            RecordingSnapshot(active = true, meetingId = id, startedAt = startedAt, status = "recording")
        }

        thread = Thread({ captureLoop(id) }, "mai-audio").apply { start() }
    }

    private fun captureLoop(id: String) {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(4096, min * 2)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        val audioDir = File(filesDir, "audio").apply { mkdirs() }
        val output = File(audioDir, "$id.aac")
        var sink: AacAdtsSink? = null
        var recognizer: Recognizer? = null
        val transcript = StringBuilder()
        var audioSafe = false

        try {
            sink = AacAdtsSink(output)
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Microphone failed to initialize")
            }
            audio.startRecording()
            val buffer = ByteArray(bufferSize)
            var lastUi = 0L

            while (running.get()) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // Audio is always written before any optional AI/STT work.
                sink.writePcm(buffer, read)
                audioSafe = audioSafe || output.length() > 512

                if (recognizer == null) {
                    SpeechModelHolder.model?.let { loadedModel ->
                        recognizer = runCatching {
                            Recognizer(loadedModel, SAMPLE_RATE.toFloat()).apply { setWords(true) }
                        }.getOrNull()
                    }
                }

                val rms = rms(buffer, read)
                val level = normalize(rms)
                var partial = ""
                val activeRecognizer = recognizer
                if (activeRecognizer != null) {
                    val accepted = runCatching { activeRecognizer.acceptWaveForm(buffer, read) }.getOrDefault(false)
                    if (accepted) {
                        val text = runCatching { JSONObject(activeRecognizer.result).optString("text").trim() }
                            .getOrDefault("")
                        if (text.isNotBlank()) {
                            if (transcript.isNotEmpty()) transcript.append('\n')
                            transcript.append(text)
                        }
                    } else {
                        partial = runCatching {
                            JSONObject(activeRecognizer.partialResult).optString("partial").trim()
                        }.getOrDefault("")
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastUi >= 80) {
                    lastUi = now
                    RecordingBus.update { old ->
                        val nextLevels = (old.levels + level).takeLast(48)
                        old.copy(
                            active = true,
                            meetingId = id,
                            elapsedMs = now - startedAt,
                            level = level,
                            levels = nextLevels,
                            transcript = transcript.toString(),
                            partial = partial,
                            audioSafe = audioSafe,
                            status = when {
                                recognizer == null -> "recording"
                                level < 0.035f -> "waiting"
                                else -> "voice"
                            }
                        )
                    }
                    updateNotification(now - startedAt)
                }
            }

            recognizer?.let { r ->
                val finalText = runCatching { JSONObject(r.finalResult).optString("text").trim() }
                    .getOrDefault("")
                if (finalText.isNotBlank()) {
                    if (transcript.isNotEmpty()) transcript.append('\n')
                    transcript.append(finalText)
                }
            }
        } catch (t: Throwable) {
            RecordingBus.update { it.copy(error = t.message ?: "Recording error", status = "error") }
        } finally {
            runCatching { audio.stop() }
            runCatching { audio.release() }
            runCatching { recognizer?.close() }
            runCatching { sink?.close() }
            finalizeMeeting(id, output, transcript.toString())
        }
    }

    private fun finalizeMeeting(id: String, output: File, transcript: String) {
        val db = MaiDb(this)
        val meeting = db.getMeeting(id)
        if (meeting != null) {
            val mom = MomEngine.generate(transcript, meeting.participants, meeting.startedAt)
            val retentionDays = getSharedPreferences("mai_settings", MODE_PRIVATE)
                .getInt("audio_retention_days", 7)
            val ended = System.currentTimeMillis()
            db.finishMeeting(
                id = id,
                endedAt = ended,
                transcript = transcript,
                summary = mom.summary,
                decisions = mom.decisions,
                actions = mom.actions,
                audioPath = output.absolutePath,
                audioExpiresAt = ended + retentionDays * 86_400_000L
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
        return if (raw < 0.035f) 0f else raw
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
            .setContentTitle("MAI")
            .setContentText("$status · ${formatElapsed(elapsed)}")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun updateNotification(elapsed: Long) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification("Recording", elapsed))
    }

    private fun formatElapsed(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }
}
