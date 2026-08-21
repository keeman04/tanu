package com.tanu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class ApiClient(
    private val baseUrl: String = BuildConfig.TANU_API_BASE_URL.trimEnd('/'),
    private val token: String = BuildConfig.TANU_API_TOKEN
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()

    suspend fun transcribeChunk(meetingId: String, index: Int, file: File): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("meeting_id", meetingId)
            .addFormDataPart("chunk_index", index.toString())
            .addFormDataPart("audio", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .build()
        val request = authorized(
            Request.Builder()
                .url("$baseUrl/v1/transcriptions/chunks")
                .post(body)
        ).build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Transcription failed (${response.code}): ${safeMessage(payload)}")
            JSONObject(payload).getString("text").trim()
        }
    }

    suspend fun generateMom(title: String, transcript: String): Mom = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("title", title)
            .put("transcript", transcript)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = authorized(
            Request.Builder()
                .url("$baseUrl/v1/mom")
                .post(payload)
        ).build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("MOM failed (${response.code}): ${safeMessage(body)}")
            parseMom(JSONObject(body))
        }
    }

    private fun authorized(builder: Request.Builder): Request.Builder {
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        return builder
    }

    private fun parseMom(json: JSONObject): Mom {
        val decisions = json.getJSONArray("decisions").let { array ->
            buildList { for (i in 0 until array.length()) add(array.optString(i)) }
        }
        val actions = json.getJSONArray("actions").let { array ->
            buildList {
                for (i in 0 until array.length()) {
                    val action = array.getJSONObject(i)
                    add(ActionItem(
                        task = action.optString("task"),
                        owner = action.optString("owner"),
                        dueDate = action.optString("dueDate")
                    ))
                }
            }
        }
        val followUps = json.getJSONArray("followUps").let { array ->
            buildList { for (i in 0 until array.length()) add(array.optString(i)) }
        }
        return Mom(
            summary = json.getString("summary"),
            decisions = decisions,
            actions = actions,
            followUps = followUps,
            source = "cloud"
        )
    }

    private fun safeMessage(raw: String): String = runCatching {
        JSONObject(raw).optString("detail").take(240)
    }.getOrDefault(raw.take(240))
}
