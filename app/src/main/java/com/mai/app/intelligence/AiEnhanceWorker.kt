package com.mai.app.intelligence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.mai.app.BuildConfig
import com.mai.app.data.ActionRecord
import com.mai.app.data.MaiDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Final MAI intelligence pass.
 *
 * The saved AAC file is the source of truth. The live transcript is intentionally not
 * submitted for the final result because network gaps or realtime latency must never make
 * speech disappear from the authoritative transcript/MOM.
 */
class AiEnhanceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_MEETING_ID = "meeting_id"
        fun input(meetingId: String) = Data.Builder().putString(KEY_MEETING_ID, meetingId).build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_MEETING_ID) ?: return@withContext Result.failure()
        val db = MaiDb(applicationContext)
        val meeting = db.getMeeting(id) ?: return@withContext Result.success()
        val backend = BuildConfig.MAI_BACKEND_URL.trim().trimEnd('/')
        val audio = meeting.audioPath?.let(::File)?.takeIf { it.isFile && it.length() > 512L }

        if (audio == null) {
            db.updateStatus(
                id,
                "processing_failed",
                "Final processing could not start because the saved meeting audio is unavailable."
            )
            return@withContext Result.success()
        }

        if (backend.isBlank()) {
            db.updateStatus(
                id,
                "recorded",
                "Audio saved safely. Final Tamil/English/Tanglish transcription is waiting for a configured MAI backend."
            )
            return@withContext Result.success()
        }

        db.updateStatus(
            id,
            "processing",
            "Audio saved safely. MAI is transcribing the complete recording and generating the final MOM."
        )

        val participantsJson = JSONArray().apply {
            meeting.participants.forEach { person ->
                put(JSONObject().put("name", person.name).put("phone", person.phone))
            }
        }.toString()

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("meeting_id", meeting.id)
            .addFormDataPart("title", meeting.title)
            .addFormDataPart("started_at", meeting.startedAt.toString())
            .addFormDataPart("participants", participantsJson)
            .addFormDataPart("audio", audio.name, audio.asRequestBody("audio/aac".toMediaType()))
            .build()

        val requestBuilder = Request.Builder()
            .url("$backend/v1/meetings/process")
            .post(body)
            .header("Accept", "application/json")
        BuildConfig.MAI_GATEWAY_TOKEN.trim().takeIf(String::isNotBlank)?.let {
            requestBuilder.header("Authorization", "Bearer $it")
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.MINUTES)
            .readTimeout(30, TimeUnit.MINUTES)
            .callTimeout(45, TimeUnit.MINUTES)
            .build()

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val retryable = response.code == 408 || response.code == 429 || response.code >= 500
                    if (retryable && runAttemptCount < 4) {
                        db.updateStatus(id, "processing", "MAI processing was interrupted and will retry automatically.")
                        return@withContext Result.retry()
                    }
                    db.updateStatus(
                        id,
                        "processing_failed",
                        "MAI could not finalize this meeting. The original audio is preserved and can be retried."
                    )
                    return@withContext Result.success()
                }

                val json = JSONObject(responseBody)
                val englishTranscript = json.optString("transcript").trim()
                val summary = json.optString("summary").trim()
                if (englishTranscript.isBlank() || summary.isBlank()) {
                    if (runAttemptCount < 4) return@withContext Result.retry()
                    db.updateStatus(
                        id,
                        "processing_failed",
                        "MAI received an incomplete final result. The original audio is preserved."
                    )
                    return@withContext Result.success()
                }

                val validNames = meeting.participants.map { it.name }
                fun validatedOwner(raw: String): String? {
                    val pieces = raw.split(Regex("\\s*/\\s*"))
                        .map(String::trim)
                        .filter(String::isNotBlank)
                    if (pieces.isEmpty()) return null
                    val resolved = pieces.map { candidate ->
                        validNames.firstOrNull { it.equals(candidate, ignoreCase = true) } ?: return null
                    }.distinct()
                    return resolved.joinToString(" / ").takeIf(String::isNotBlank)
                }

                val actionsArray = json.optJSONArray("actions") ?: JSONArray()
                val actions = (0 until actionsArray.length()).mapNotNull { index ->
                    val item = actionsArray.optJSONObject(index) ?: return@mapNotNull null
                    val text = item.optString("text").trim()
                    if (text.isBlank()) return@mapNotNull null
                    ActionRecord(
                        text = text,
                        owner = validatedOwner(item.optString("owner").trim()),
                        due = item.optString("due").trim()
                            .takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                    )
                }

                val decisionsArray = json.optJSONArray("decisions") ?: JSONArray()
                val decisions = (0 until decisionsArray.length())
                    .map { decisionsArray.optString(it).trim() }
                    .filter(String::isNotBlank)
                    .distinct()

                db.replaceIntelligence(
                    id = meeting.id,
                    transcript = englishTranscript,
                    summary = summary,
                    decisions = decisions,
                    actions = actions
                )
                return@withContext Result.success()
            }
        } catch (_: Throwable) {
            if (runAttemptCount < 4) {
                db.updateStatus(id, "processing", "Network unavailable. MAI will retry the full recording automatically.")
                Result.retry()
            } else {
                db.updateStatus(
                    id,
                    "processing_failed",
                    "MAI could not reach the processing service after retrying. The original audio is preserved."
                )
                Result.success()
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
