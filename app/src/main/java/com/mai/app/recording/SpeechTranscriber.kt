package com.mai.app.recording

import android.content.Context
import android.util.Base64
import com.mai.app.BuildConfig
import com.mai.app.data.MaiDb
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Realtime transcript is a live preview only. The saved AAC recording is always the
 * authoritative source for the final transcript and MOM.
 *
 * Android records at 16 kHz for durable AAC storage. OpenAI Realtime transcription uses
 * 24 kHz PCM, so only the mirrored live stream is resampled to 24 kHz here.
 */
class SpeechTranscriber(
    context: Context,
    private val sampleRate: Int = 16_000,
    participantNames: List<String> = emptyList(),
    private val onUpdate: (transcript: String, partial: String) -> Unit,
    private val onWarning: (String) -> Unit
) : Closeable {
    companion object {
        private const val LIVE_RATE = 24_000
        private const val MAX_BUFFER_BYTES = LIVE_RATE * 2 * 60 // 60 seconds PCM16
        private val RECONNECT_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000)
    }

    private val names: List<String> = participantNames.ifEmpty {
        runCatching {
            MaiDb(context.applicationContext).listMeetings()
                .firstOrNull { it.status == "recording" }
                ?.participants
                ?.map { it.name }
                .orEmpty()
        }.getOrDefault(emptyList())
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val lock = Any()
    private val finalText = StringBuilder()
    private var partialText = ""
    private val pcmQueue = ArrayDeque<ByteArray>()
    private var queuedBytes = 0

    @Volatile private var socket: WebSocket? = null
    private val ready = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val reconnecting = AtomicBoolean(false)
    private val reconnectAttempt = AtomicInteger(0)
    private val awaitingFinalCommit = AtomicBoolean(false)
    @Volatile private var finalCommitLatch = CountDownLatch(1)

    init { requestConnection(immediate = true) }

    private fun requestConnection(immediate: Boolean = false) {
        if (closed.get() || !reconnecting.compareAndSet(false, true)) return
        Thread {
            var retryAfter = false
            try {
                if (!immediate) {
                    val attempt = reconnectAttempt.getAndIncrement()
                    val delay = RECONNECT_DELAYS_MS[minOf(attempt, RECONNECT_DELAYS_MS.lastIndex)]
                    Thread.sleep(delay)
                }
                if (!closed.get()) retryAfter = !connectOnce()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                reconnecting.set(false)
            }
            if (retryAfter && !closed.get()) requestConnection()
        }.apply {
            isDaemon = true
            name = "mai-live-stt-connect"
            start()
        }
    }

    private fun connectOnce(): Boolean {
        val base = BuildConfig.MAI_BACKEND_URL.trim().trimEnd('/')
        if (base.isBlank()) {
            onWarning("Live transcription is unavailable until the MAI backend is configured.")
            return true
        }
        return try {
            val payload = JSONObject()
                .put("participants", JSONArray().apply { names.forEach { put(it) } })
                .toString()
            val request = Request.Builder()
                .url("$base/v1/realtime/client-secret")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .header("Accept", "application/json")
                .apply {
                    BuildConfig.MAI_GATEWAY_TOKEN.trim().takeIf(String::isNotBlank)?.let {
                        header("Authorization", "Bearer $it")
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("token service returned ${response.code}")
                val json = JSONObject(response.body?.string().orEmpty())
                val token = json.optString("value").trim()
                val url = json.optString("websocket_url").trim()
                if (token.isBlank() || url.isBlank()) throw IllegalStateException("token response was incomplete")
                if (!closed.get()) openSocket(url, token)
            }
            true
        } catch (t: Throwable) {
            if (!closed.get()) onWarning("Live transcript reconnecting: ${t.message ?: "network unavailable"}")
            false
        }
    }

    private fun openSocket(url: String, token: String) {
        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", "realtime, openai-insecure-api-key.$token")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (closed.get()) {
                    webSocket.close(1000, "meeting ended")
                    return
                }
                reconnectAttempt.set(0)
                val session = JSONObject()
                    .put("type", "session.update")
                    .put(
                        "session",
                        JSONObject()
                            .put("type", "transcription")
                            .put(
                                "audio",
                                JSONObject().put(
                                    "input",
                                    JSONObject()
                                        .put("format", JSONObject().put("type", "audio/pcm").put("rate", LIVE_RATE))
                                        .put("noise_reduction", JSONObject().put("type", "far_field"))
                                        .put(
                                            "transcription",
                                            JSONObject()
                                                .put("model", "gpt-live-transcribe")
                                                .put("languages", JSONArray().put("ta").put("en"))
                                                .put("keywords", JSONArray().apply {
                                                    names.take(20).forEach { name ->
                                                        val clean = name.replace("\n", " ").replace("\r", " ")
                                                            .replace("<", "").replace(">", "").trim()
                                                        if (clean.isNotBlank()) put(clean)
                                                    }
                                                    listOf("MAI", "VGP", "Marine Kingdom", "Universal Kingdom", "Waghoba", "Rednote", "WhatsApp").forEach { put(it) }
                                                })
                                                .put("delay", "high")
                                        )
                                        .put(
                                            "turn_detection",
                                            JSONObject()
                                                .put("type", "server_vad")
                                                .put("threshold", 0.45)
                                                .put("prefix_padding_ms", 350)
                                                .put("silence_duration_ms", 650)
                                        )
                                )
                            )
                    )
                if (!webSocket.send(session.toString())) {
                    ready.set(false)
                    onWarning("Live transcription session could not start. Reconnecting…")
                    requestConnection()
                    return
                }
                ready.set(true)
                flushBuffered(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "conversation.item.input_audio_transcription.delta" -> synchronized(lock) {
                            partialText += json.optString("delta")
                            onUpdate(finalText.toString().trim(), partialText.trim())
                        }

                        "conversation.item.input_audio_transcription.completed" -> synchronized(lock) {
                            val piece = json.optString("transcript").trim()
                            if (piece.isNotBlank()) {
                                if (finalText.isNotEmpty()) finalText.append('\n')
                                finalText.append(piece)
                            }
                            partialText = ""
                            onUpdate(finalText.toString().trim(), "")
                            if (awaitingFinalCommit.compareAndSet(true, false)) finalCommitLatch.countDown()
                        }

                        "error" -> {
                            val message = json.optJSONObject("error")?.optString("message")
                                ?: json.optString("message", "Realtime transcription error")
                            onWarning(message)
                            if (awaitingFinalCommit.compareAndSet(true, false)) finalCommitLatch.countDown()
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore forward-compatible/unknown server events.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready.set(false)
                if (!closed.get()) {
                    onWarning("Live transcript connection dropped. Recording is still safe locally; reconnecting…")
                    requestConnection()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready.set(false)
                if (!closed.get()) requestConnection()
            }
        })
    }

    fun offer(data: ByteArray, length: Int) {
        if (closed.get() || length <= 1) return
        val livePcm = resample16kTo24k(data, length)
        if (livePcm.isEmpty()) return
        val active = socket
        if (ready.get() && active != null && sendPcm(active, livePcm)) return
        enqueuePcm(livePcm)
    }

    fun transcript(): String = synchronized(lock) { finalText.toString().trim() }

    fun partial(): String = synchronized(lock) { partialText.trim() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val active = socket
        if (ready.get() && active != null) {
            flushBuffered(active)
            finalCommitLatch = CountDownLatch(1)
            awaitingFinalCommit.set(true)
            active.send(JSONObject().put("type", "input_audio_buffer.commit").toString())
            try {
                finalCommitLatch.await(8, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            active.close(1000, "meeting ended")
        } else {
            active?.cancel()
        }
        ready.set(false)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun sendPcm(webSocket: WebSocket, pcm: ByteArray): Boolean {
        val payload = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(pcm, Base64.NO_WRAP))
            .toString()
        return webSocket.send(payload)
    }

    private fun enqueuePcm(pcm: ByteArray) = synchronized(lock) {
        pcmQueue.addLast(pcm)
        queuedBytes += pcm.size
        while (queuedBytes > MAX_BUFFER_BYTES && pcmQueue.isNotEmpty()) {
            queuedBytes -= pcmQueue.removeFirst().size
        }
    }

    private fun flushBuffered(webSocket: WebSocket) {
        val pending = synchronized(lock) {
            val list = pcmQueue.toList()
            pcmQueue.clear()
            queuedBytes = 0
            list
        }
        for (index in pending.indices) {
            if (!sendPcm(webSocket, pending[index])) {
                ready.set(false)
                for (remaining in index until pending.size) enqueuePcm(pending[remaining])
                requestConnection()
                break
            }
        }
    }

    private fun resample16kTo24k(input: ByteArray, length: Int): ByteArray {
        if (sampleRate == LIVE_RATE) return input.copyOf(length)
        val sampleCount = length / 2
        if (sampleCount < 2) return ByteArray(0)
        val outputCount = ((sampleCount.toLong() * LIVE_RATE) / sampleRate).toInt()
        val output = ByteArray(outputCount * 2)

        fun sampleAt(index: Int): Int {
            val i = index.coerceIn(0, sampleCount - 1) * 2
            return ((input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xFF)).toShort().toInt()
        }

        for (outIndex in 0 until outputCount) {
            val sourceNumerator = outIndex.toLong() * sampleRate
            val left = (sourceNumerator / LIVE_RATE).toInt().coerceAtMost(sampleCount - 1)
            val remainder = (sourceNumerator % LIVE_RATE).toInt()
            val right = (left + 1).coerceAtMost(sampleCount - 1)
            val a = sampleAt(left)
            val b = sampleAt(right)
            val value = a + ((b - a) * remainder / LIVE_RATE)
            output[outIndex * 2] = (value and 0xFF).toByte()
            output[outIndex * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return output
    }
}
