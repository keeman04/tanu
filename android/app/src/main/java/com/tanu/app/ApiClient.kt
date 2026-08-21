package com.tanu.app

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

class ApiClient(
    private val baseUrl: String = BuildConfig.TANU_API_BASE_URL.trimEnd('/'),
    private val token: String = BuildConfig.TANU_API_TOKEN
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    suspend fun ensureMeeting(meeting: MeetingEntity) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("id", meeting.id)
            .put("title", meeting.title)
            .put("started_at_ms", meeting.startedAtMs)
            .toString()
            .toRequestBody("application/json".toMediaType())
        execute(Request.Builder().url("$baseUrl/v1/meetings").post(body))
    }

    suspend fun uploadChunk(chunk: AudioChunkEntity): Boolean = withContext(Dispatchers.IO) {
        val file = File(chunk.localPath)
        check(file.exists()) { "Audio chunk is missing locally" }
        val request = Request.Builder()
            .url("$baseUrl/v1/meetings/${chunk.meetingId}/chunks/${chunk.sequence}")
            .header("X-TANU-SHA256", chunk.sha256)
            .header("X-TANU-START-MS", chunk.startMs.toString())
            .header("X-TANU-END-MS", chunk.endMs.toString())
            .header("X-TANU-CODEC", chunk.codec)
            .put(file.asRequestBody(chunk.mimeType.toMediaType()))
        val json = JSONObject(execute(request))
        json.optBoolean("accepted", false)
    }

    suspend fun fetchUpdates(meetingId: String): MeetingUpdate = withContext(Dispatchers.IO) {
        val json = JSONObject(execute(Request.Builder().url("$baseUrl/v1/meetings/$meetingId/updates").get()))
        val chunkArray = json.optJSONArray("chunks") ?: JSONArray()
        val chunks = buildList {
            for (i in 0 until chunkArray.length()) {
                val c = chunkArray.getJSONObject(i)
                add(ServerChunk(
                    sequence = c.getInt("sequence"),
                    startMs = c.optLong("start_ms"),
                    endMs = c.optLong("end_ms"),
                    state = c.optString("state"),
                    text = c.optString("text").ifBlank { null }
                ))
            }
        }
        val rolling = json.optJSONArray("rolling_summaries") ?: JSONArray()
        val summaries = buildList {
            for (i in 0 until rolling.length()) {
                val r = rolling.getJSONObject(i)
                add(RollingSummaryEntity(
                    meetingId = meetingId,
                    windowStartMs = r.getLong("window_start_ms"),
                    windowEndMs = r.getLong("window_end_ms"),
                    json = r.getJSONObject("data").toString()
                ))
            }
        }
        MeetingUpdate(
            state = json.optString("state", "processing"),
            totalChunks = json.optInt("total_chunks"),
            pendingChunks = json.optInt("pending_chunks"),
            transcribedChunks = json.optInt("transcribed_chunks"),
            chunks = chunks,
            rollingSummaries = summaries
        )
    }

    suspend fun finalizeMeeting(meetingId: String) = withContext(Dispatchers.IO) {
        execute(Request.Builder().url("$baseUrl/v1/meetings/$meetingId/finalize").post(ByteArray(0).toRequestBody(null)))
    }

    suspend fun fetchMom(meetingId: String): Mom? = withContext(Dispatchers.IO) {
        val request = authorized(Request.Builder().url("$baseUrl/v1/meetings/$meetingId/mom").get()).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 202 || response.code == 404) return@withContext null
            if (!response.isSuccessful) error("MOM fetch failed (${response.code}): ${safeMessage(text)}")
            Mom.fromJson(text, "cloud")
        }
    }

    private fun execute(builder: Request.Builder): String {
        val request = authorized(builder).build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("TANU API failed (${response.code}): ${safeMessage(payload)}")
            return payload
        }
    }

    private fun authorized(builder: Request.Builder): Request.Builder {
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder
    }

    private fun safeMessage(raw: String): String = runCatching {
        JSONObject(raw).optString("detail").take(300)
    }.getOrDefault(raw.take(300))
}
