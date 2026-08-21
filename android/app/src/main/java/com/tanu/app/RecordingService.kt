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
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var store: MeetingStore
    private val api = ApiClient()
    private val semaphore = Semaphore(3)
    private val transcriptionJobs = Collections.synchronizedList(mutableListOf<Job>())
    private val queued = AtomicInteger(0)

    @Volatile private var stopRequested = false
    @Volatile private var isRecording = false
    private var activeMeetingId: String? = null
    private var activeTitle: String = "Meeting"

    override fun onCreate() {
        super.onCreate()
        store = MeetingStore(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val meetingId = intent.getStringExtra(EXTRA_MEETING_ID) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Meeting" }
                if (!isRecording) {
                    startForeground(NOTIFICATION_ID, notification("Starting recording"))
                    scope.launch { runRecording(meetingId, title) }
                }
            }
            ACTION_STOP -> {
                stopRequested = true
                updateNotification("Finishing meeting")
                broadcast("finishing", "Finishing the last audio chunk…")
            }
            ACTION_RETRY -> {
                val meetingId = intent.getStringExtra(EXTRA_MEETING_ID) ?: return START_NOT_STICKY
                val title = store.title(meetingId)
                startForeground(NOTIFICATION_ID, notification("Retrying saved audio"))
                scope.launch { retrySavedAudio(meetingId, title) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private suspend fun runRecording(meetingId: String, title: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            broadcast("failed", "Microphone permission is required.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        activeMeetingId = meetingId
        activeTitle = title
        stopRequested = false
        isRecording = true
        transcriptionJobs.clear()
        queued.set(0)
        store.initializeMeeting(meetingId, title)

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufferSize = max(minBuffer, 4096)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            ENCODING,
            bufferSize
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            broadcast("failed", "TANU could not initialize the microphone recorder.")
            recorder.release()
            finishService()
            return
        }

        val readBuffer = ByteArray(bufferSize)
        var current = ByteArrayOutputStream(BYTES_PER_CHUNK)
        var chunkIndex = 0

        try {
            recorder.startRecording()
            broadcast("recording", "Recording • transcript is processed continuously")
            while (!stopRequested && scope.isActive) {
                val count = recorder.read(readBuffer, 0, readBuffer.size)
                if (count <= 0) continue
                var offset = 0
                while (offset < count) {
                    val room = BYTES_PER_CHUNK - current.size()
                    val amount = min(room, count - offset)
                    current.write(readBuffer, offset, amount)
                    offset += amount
                    if (current.size() >= BYTES_PER_CHUNK) {
                        queueChunk(meetingId, chunkIndex++, current.toByteArray())
                        current = ByteArrayOutputStream(BYTES_PER_CHUNK)
                    }
                }
            }
            if (current.size() > 0) queueChunk(meetingId, chunkIndex, current.toByteArray())
        } catch (t: Throwable) {
            broadcast("failed", "Recording error: ${t.message ?: "unknown error"}")
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            isRecording = false
        }

        completeMeeting(meetingId, title)
        finishService()
    }

    private fun queueChunk(meetingId: String, index: Int, pcm: ByteArray) {
        val file = store.chunkFile(meetingId, index)
        file.writeBytes(wavBytes(pcm))
        queued.incrementAndGet()
        broadcast(
            if (isRecording) "recording" else "transcribing",
            "Saved chunk ${index + 1} • ${queued.get()} waiting/processing"
        )
        val job = scope.launch {
            semaphore.withPermit {
                try {
                    val text = api.transcribeChunk(meetingId, index, file)
                    if (text.isNotBlank()) store.saveTranscriptSegment(meetingId, index, text)
                    broadcast(
                        if (isRecording) "recording" else "transcribing",
                        "Transcript updated • ${max(0, queued.get() - 1)} waiting/processing"
                    )
                } catch (t: Throwable) {
                    broadcast(
                        if (isRecording) "recording" else "partial",
                        "Chunk ${index + 1} kept for retry: ${t.message ?: "transcription failed"}"
                    )
                } finally {
                    queued.decrementAndGet()
                }
            }
        }
        transcriptionJobs.add(job)
    }

    private suspend fun completeMeeting(meetingId: String, title: String) {
        broadcast("transcribing", "Finishing remaining transcript chunks…")
        val allDone = waitForJobsHardDeadline(FINALIZE_DEADLINE_MS)
        if (!allDone) {
            transcriptionJobs.filter { it.isActive }.forEach { it.cancel() }
            broadcast("partial", "Finalization deadline reached. Saved audio can be retried.")
        }
        val transcript = store.orderedTranscript(meetingId)
        if (transcript.isBlank()) {
            broadcast("failed", "No transcript yet. Audio is safely stored; tap Retry Processing.")
            return
        }

        broadcast("mom", "Generating structured MOM…")
        val mom = try {
            api.generateMom(title, transcript)
        } catch (_: Throwable) {
            LocalMomFallback.generate(transcript)
        }
        store.saveMom(meetingId, mom)
        val detail = if (mom.source == "cloud") {
            "MOM ready"
        } else {
            "MOM ready with local fallback • retry later for structured cloud MOM"
        }
        broadcast("ready", detail)
    }

    private suspend fun retrySavedAudio(meetingId: String, title: String) {
        if (isRecording) return
        transcriptionJobs.clear()
        queued.set(0)
        activeMeetingId = meetingId
        activeTitle = title
        val pending = store.missingChunkFiles(meetingId)
        if (pending.isEmpty()) {
            completeMeeting(meetingId, title)
            finishService()
            return
        }
        broadcast("transcribing", "Retrying ${pending.size} saved chunks…")
        pending.forEach { file ->
            val index = store.chunkIndex(file)
            queued.incrementAndGet()
            val job = scope.launch {
                semaphore.withPermit {
                    try {
                        val text = api.transcribeChunk(meetingId, index, file)
                        if (text.isNotBlank()) store.saveTranscriptSegment(meetingId, index, text)
                    } catch (t: Throwable) {
                        broadcast("partial", "Retry failed for chunk ${index + 1}: ${t.message ?: "error"}")
                    } finally {
                        queued.decrementAndGet()
                    }
                }
            }
            transcriptionJobs.add(job)
        }
        completeMeeting(meetingId, title)
        finishService()
    }

    private suspend fun waitForJobsHardDeadline(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = synchronized(transcriptionJobs) { transcriptionJobs.toList() }
            if (snapshot.all { it.isCompleted }) return true
            delay(250)
        }
        return synchronized(transcriptionJobs) { transcriptionJobs.all { it.isCompleted } }
    }

    private fun broadcast(state: String, message: String) {
        val meetingId = activeMeetingId ?: return
        sendBroadcast(
            Intent(ACTION_UPDATE)
                .setPackage(packageName)
                .putExtra(EXTRA_MEETING_ID, meetingId)
                .putExtra(EXTRA_STATE, state)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_QUEUED, queued.get())
        )
        updateNotification(message)
    }

    private fun finishService() {
        activeMeetingId = null
        stopRequested = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TANU recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("TANU meeting")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(isRecording)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification(text))
    }

    private fun wavBytes(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        out.write("RIFF".toByteArray())
        out.write(leInt(36 + pcm.size))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(leInt(16))
        out.write(leShort(1))
        out.write(leShort(1))
        out.write(leInt(SAMPLE_RATE))
        out.write(leInt(SAMPLE_RATE * 2))
        out.write(leShort(2))
        out.write(leShort(16))
        out.write("data".toByteArray())
        out.write(leInt(pcm.size))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun leInt(value: Int): ByteArray = ByteBuffer.allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(value)
        .array()

    private fun leShort(value: Int): ByteArray = ByteBuffer.allocate(2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(value.toShort())
        .array()

    companion object {
        const val ACTION_START = "com.tanu.app.START"
        const val ACTION_STOP = "com.tanu.app.STOP"
        const val ACTION_RETRY = "com.tanu.app.RETRY"
        const val ACTION_UPDATE = "com.tanu.app.PIPELINE_UPDATE"
        const val EXTRA_MEETING_ID = "meeting_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_QUEUED = "queued"

        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SECONDS = 20
        private const val BYTES_PER_CHUNK = SAMPLE_RATE * 2 * CHUNK_SECONDS
        private const val FINALIZE_DEADLINE_MS = 90_000L
        private const val CHANNEL_ID = "tanu_recording"
        private const val NOTIFICATION_ID = 1042
    }
}
