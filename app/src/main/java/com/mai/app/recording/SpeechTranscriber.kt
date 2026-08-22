package com.mai.app.recording

import android.content.Context
import android.os.Process
import org.json.JSONObject
import org.vosk.Recognizer
import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Best-effort live English STT. Audio capture never waits for this worker: PCM frames are
 * offered to a bounded queue and may be dropped if speech recognition falls behind.
 * The source AAC recording therefore remains the priority under CPU pressure.
 */
class SpeechTranscriber(
    context: Context,
    private val sampleRate: Int = 16_000,
    private val onUpdate: (transcript: String, partial: String) -> Unit,
    private val onWarning: (String) -> Unit
) : Closeable {
    private val appContext = context.applicationContext
    private val queue = ArrayBlockingQueue<ByteArray>(80)
    private val running = AtomicBoolean(true)
    private val transcriptRef = AtomicReference("")
    private val partialRef = AtomicReference("")
    private val worker = Thread(::runLoop, "mai-stt").apply {
        isDaemon = true
        start()
    }

    fun offer(data: ByteArray, length: Int) {
        if (!running.get() || length <= 0) return
        val copy = data.copyOf(length)
        if (!queue.offer(copy)) {
            onWarning("Live transcript is falling behind. Audio recording remains safe.")
        }
    }

    fun transcript(): String = transcriptRef.get()
    fun partial(): String = partialRef.get()

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        worker.join(1_500L)
        if (worker.isAlive) worker.interrupt()
    }

    private fun runLoop() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        runCatching { SpeechModelHolder.ensureForRecording(appContext) }
        var recognizer: Recognizer? = null
        var modelWaitStarted = System.currentTimeMillis()

        try {
            while (running.get() || queue.isNotEmpty()) {
                if (recognizer == null) {
                    SpeechModelHolder.model?.let { model ->
                        recognizer = runCatching {
                            Recognizer(model, sampleRate.toFloat()).apply { setWords(true) }
                        }.getOrNull()
                    }
                    if (recognizer == null) {
                        // Keep only a bounded amount of early speech while the model warms up.
                        if (System.currentTimeMillis() - modelWaitStarted > 20_000L) {
                            queue.poll()
                            modelWaitStarted = System.currentTimeMillis()
                        }
                        Thread.sleep(100L)
                        continue
                    }
                }

                val frame = queue.poll(250L, TimeUnit.MILLISECONDS) ?: continue
                val r = recognizer ?: continue
                val accepted = runCatching { r.acceptWaveForm(frame, frame.size) }.getOrDefault(false)
                if (accepted) {
                    val text = runCatching { JSONObject(r.result).optString("text").trim() }.getOrDefault("")
                    if (text.isNotBlank()) appendFinal(text)
                    partialRef.set("")
                } else {
                    val partial = runCatching { JSONObject(r.partialResult).optString("partial").trim() }.getOrDefault("")
                    partialRef.set(partial)
                }
                onUpdate(transcriptRef.get(), partialRef.get())
            }

            recognizer?.let { r ->
                val text = runCatching { JSONObject(r.finalResult).optString("text").trim() }.getOrDefault("")
                if (text.isNotBlank()) appendFinal(text)
            }
            partialRef.set("")
            onUpdate(transcriptRef.get(), "")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (t: Throwable) {
            onWarning(t.message ?: "Live transcript unavailable. Audio recording continues.")
        } finally {
            runCatching { recognizer?.close() }
        }
    }

    private fun appendFinal(text: String) {
        while (true) {
            val old = transcriptRef.get()
            val next = if (old.isBlank()) text else "$old\n$text"
            if (transcriptRef.compareAndSet(old, next)) return
        }
    }
}
