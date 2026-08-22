package com.mai.app.recording

import android.content.Context
import com.mai.app.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MAI 1.2 realtime multilingual transcription.
 * Audio recording remains independent in RecordingService; this class mirrors PCM to
 * Vercel AI Gateway using a short-lived token minted by MAI's serverless backend.
 */
class SpeechTranscriber(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val sampleRate: Int = 16_000,
    private val onUpdate: (transcript: String, partial: String) -> Unit,
    private val onWarning: (String) -> Unit
) : Closeable {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val lock = Any()
    private val finalText = StringBuilder()
    private var partialText = ""
    @Volatile private var socket: WebSocket? = null
    private val ready = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val finished = CountDownLatch(1)

    init { connect() }

    private fun connect() {
        val base = BuildConfig.MAI_BACKEND_URL.trim().trimEnd('/')
        if (base.isBlank()) {
            onWarning("Accurate transcription service is not configured.")
            return
        }
        Thread {
            try {
                val req = Request.Builder()
                    .url("$base/api/transcription-token")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("AI token service returned ${response.code}")
                    val json = JSONObject(response.body?.string().orEmpty())
                    val token = json.optString("token")
                    val url = json.optString("url")
                    if (token.isBlank() || url.isBlank()) throw IllegalStateException("AI token response was incomplete")
                    openSocket(url, token)
                }
            } catch (t: Throwable) {
                onWarning("Live multilingual transcription unavailable: ${t.message ?: "connection failed"}")
            }
        }.start()
    }

    private fun openSocket(url: String, token: String) {
        val request = Request.Builder()
            .url(url)
            .header(
                "Sec-WebSocket-Protocol",
                "ai-gateway-transcription.v1, ai-gateway-auth.$token"
            )
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ready.set(true)
                val start = JSONObject()
                    .put("type", "transcription-stream.start")
                    .put(
                        "inputAudioFormat",
                        JSONObject().put("type", "audio/pcm").put("rate", sampleRate)
                    )
                    .put(
                        "providerOptions",
                        JSONObject().put(
                            "openai",
                            JSONObject().put("streaming", JSONObject().put("delay", "xhigh"))
                        )
                    )
                webSocket.send(start.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    when (json.optString("type")) {
                        "transcript-delta" -> {
                            synchronized(lock) {
                                partialText += json.optString("delta")
                                onUpdate(finalText.toString().trim(), partialText.trim())
                            }
                        }
                        "transcript-partial" -> {
                            synchronized(lock) {
                                partialText = json.optString("text")
                                onUpdate(finalText.toString().trim(), partialText.trim())
                            }
                        }
                        "transcript-final" -> {
                            synchronized(lock) {
                                val piece = json.optString("text").trim()
                                if (piece.isNotBlank()) {
                                    if (finalText.isNotEmpty()) finalText.append('\n')
                                    finalText.append(piece)
                                }
                                partialText = ""
                                onUpdate(finalText.toString().trim(), "")
                            }
                        }
                        "finish" -> {
                            synchronized(lock) {
                                val whole = json.optString("text").trim()
                                if (whole.isNotBlank() && whole.length > finalText.length) {
                                    finalText.setLength(0)
                                    finalText.append(whole)
                                }
                                partialText = ""
                                onUpdate(finalText.toString().trim(), "")
                            }
                            finished.countDown()
                        }
                        "error" -> {
                            val err = json.opt("error")
                            onWarning("AI transcription error: ${err ?: "unknown error"}")
                            finished.countDown()
                        }
                    }
                } catch (_: Throwable) {
                    // Ignore unknown/forward-compatible frames.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ready.set(false)
                if (!closed.get()) onWarning("Live transcription connection dropped: ${t.message ?: "network error"}")
                finished.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ready.set(false)
                finished.countDown()
            }
        })
    }

    fun offer(data: ByteArray, length: Int) {
        if (!ready.get() || closed.get() || length <= 0) return
        socket?.send(ByteString.of(data, 0, length))
    }

    fun transcript(): String = synchronized(lock) { finalText.toString().trim() }

    fun partial(): String = synchronized(lock) { partialText.trim() }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.send(JSONObject().put("type", "transcription-stream.audio-done").toString())
        finished.await(12, TimeUnit.SECONDS)
        socket?.close(1000, "meeting ended")
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
