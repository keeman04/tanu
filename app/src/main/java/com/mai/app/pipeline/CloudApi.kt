package com.mai.app.pipeline

import android.content.Context
import com.mai.app.BuildConfig
import com.mai.app.data.MeetingRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class CloudApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("mai_settings", Context.MODE_PRIVATE)
    private val baseUrl: String = prefs.getString("backend_url", "").orEmpty().trim()
        .ifBlank { BuildConfig.MAI_API_BASE_URL.trim() }.trimEnd('/')
    private val token: String = prefs.getString("backend_token", "").orEmpty().trim()
        .ifBlank { BuildConfig.MAI_API_TOKEN.trim() }
    val configured: Boolean get() = baseUrl.startsWith("https://") || baseUrl.startsWith("http://10.0.2.2") || baseUrl.startsWith("http://127.0.0.1")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        if (!configured) return@withContext false
        runCatching {
            val json = JSONObject(execute(Request.Builder().url("$baseUrl/health").get()))
            json.optString("status") == "ok"
        }.getOrDefault(false)
    }

    suspend fun ensureMeeting(meeting: MeetingRecord) = withContext(Dispatchers.IO) {
        requireConfigured()
        val people = JSONArray().apply {
            meeting.participants.forEach { put(JSONObject().put("name", it.name).put("phone", it.phone)) }
        }
        val body = JSONObject()
            .put("id", meeting.id)
            .put("title", meeting.title)
            .put("started_at_ms", meeting.startedAt)
            .put("participants", people)
            .toString().toRequestBody(JSON)
        execute(Request.Builder().url("$baseUrl/v1/meetings").post(body))
    }

    suspend fun uploadChunk(chunk: AudioChunkEntity): Boolean = withContext(Dispatchers.IO) {
        requireConfigured()
        val file = File(chunk.localPath)
        check(file.exists()) { "Audio chunk missing locally" }
        val request = Request.Builder()
            .url("$baseUrl/v1/meetings/${chunk.meetingId}/chunks/${chunk.sequence}")
            .header("X-MAI-SHA256", chunk.sha256)
            .header("X-MAI-START-MS", chunk.startMs.toString())
            .header("X-MAI-END-MS", chunk.endMs.toString())
            .header("X-MAI-CODEC", chunk.codec)
            .put(file.asRequestBody(chunk.mimeType.toMediaType()))
        JSONObject(execute(request)).optBoolean("accepted", false)
    }

    suspend fun fetchUpdates(meetingId: String): MeetingUpdate = withContext(Dispatchers.IO) {
        requireConfigured()
        val json = JSONObject(execute(Request.Builder().url("$baseUrl/v1/meetings/$meetingId/updates").get()))
        val arr = json.optJSONArray("chunks") ?: JSONArray()
        val chunks = buildList {
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                add(ServerChunk(
                    sequence = c.getInt("sequence"),
                    startMs = c.optLong("start_ms"),
                    endMs = c.optLong("end_ms"),
                    state = c.optString("state"),
                    text = c.optString("text").trim().takeIf(String::isNotBlank)
                ))
            }
        }
        MeetingUpdate(
            state = json.optString("state", "processing"),
            totalChunks = json.optInt("total_chunks"),
            pendingChunks = json.optInt("pending_chunks"),
            transcribedChunks = json.optInt("transcribed_chunks"),
            chunks = chunks
        )
    }

    suspend fun finalizeMeeting(meetingId: String, expectedChunks: Int) = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().put("expected_chunks", expectedChunks).toString().toRequestBody(JSON)
        execute(Request.Builder().url("$baseUrl/v1/meetings/$meetingId/finalize").post(body))
    }

    suspend fun fetchMom(meetingId: String): CloudMom? = withContext(Dispatchers.IO) {
        requireConfigured()
        val request = authorized(Request.Builder().url("$baseUrl/v1/meetings/$meetingId/mom").get()).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 202 || response.code == 404) return@withContext null
            if (!response.isSuccessful) error("MAI MOM fetch failed (${response.code}): ${safeMessage(text)}")
            CloudMom.fromJson(text)
        }
    }

    suspend fun ask(question: String): AskAnswer = withContext(Dispatchers.IO) {
        requireConfigured()
        val body = JSONObject().put("question", question).toString().toRequestBody(JSON)
        val json = JSONObject(execute(Request.Builder().url("$baseUrl/v1/ask").post(body)))
        val sources = json.optJSONArray("sources") ?: JSONArray()
        AskAnswer(
            answer = json.optString("answer").trim(),
            sources = buildList { for (i in 0 until sources.length()) sources.optString(i).trim().takeIf(String::isNotBlank)?.let(::add) },
            cloud = true
        )
    }

    private fun requireConfigured() { check(configured) { "MAI Cloud is not configured" } }

    private fun execute(builder: Request.Builder): String {
        val request = authorized(builder).build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("MAI API failed (${response.code}): ${safeMessage(payload)}")
            return payload
        }
    }

    private fun authorized(builder: Request.Builder): Request.Builder {
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder
    }

    private fun safeMessage(raw: String): String = runCatching { JSONObject(raw).optString("detail").take(300) }
        .getOrDefault(raw.take(300))

    private companion object { val JSON = "application/json".toMediaType() }
}
