package com.mai.app.intelligence

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.mai.app.BuildConfig
import com.mai.app.auth.MaiDeviceAuth
import com.mai.app.data.ActionRecord
import com.mai.app.data.MaiDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Resumable final MAI intelligence worker.
 *
 * Android never waits for a multi-hour AI request. It uploads the saved AAC in small,
 * restart-safe pieces, starts a persistent server job, and then performs short status polls.
 * WorkManager can stop/restart this worker at any point without losing the server job.
 */
class AiEnhanceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_MEETING_ID = "meeting_id"
        private const val UPLOAD_CHUNK_BYTES = 5 * 1024 * 1024
        private const val MAX_CHUNKS_PER_RUN = 4
        fun input(meetingId: String) = Data.Builder().putString(KEY_MEETING_ID, meetingId).build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString(KEY_MEETING_ID) ?: return@withContext Result.failure()
        val db = MaiDb(applicationContext)
        var meeting = db.getMeeting(id) ?: return@withContext Result.success()
        val backend = BuildConfig.MAI_BACKEND_URL.trim().trimEnd('/')
        val audio = meeting.audioPath?.let(::File)?.takeIf { it.isFile && it.length() > 512L }

        if (audio == null) {
            db.updateStatus(id, "processing_failed", "Final processing could not start because the saved meeting audio is unavailable.")
            return@withContext Result.success()
        }
        if (backend.isBlank()) {
            db.updateStatus(id, "recorded", "Audio saved safely. Final multilingual processing is waiting for a configured MAI backend.")
            return@withContext Result.success()
        }

        val auth = MaiDeviceAuth(applicationContext)
        if (!auth.isActivated()) {
            auth.close()
            db.updateStatus(id, "recorded", "Audio saved safely. Activate this device in MAI before final AI processing.")
            return@withContext Result.success()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.MINUTES)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.MINUTES)
            .build()

        try {
            fun token(force: Boolean = false): String = auth.sessionToken(force)

            fun execute(requestFactory: (String) -> Request): okhttp3.Response {
                var response = client.newCall(requestFactory(token())).execute()
                if (response.code == 401 || response.code == 403) {
                    response.close()
                    MaiDeviceAuth.invalidateSession()
                    response = client.newCall(requestFactory(token(force = true))).execute()
                }
                return response
            }

            var jobId = meeting.aiJobId
            if (jobId.isNullOrBlank()) {
                val languageMode = applicationContext
                    .getSharedPreferences("mai_settings", Context.MODE_PRIVATE)
                    .getString("transcription_language_mode", "auto")
                    ?.takeIf { it.isNotBlank() }
                    ?: "auto"
                val people = JSONArray().apply {
                    meeting.participants.forEach { put(JSONObject().put("name", it.name).put("phone", it.phone)) }
                }
                val initJson = JSONObject()
                    .put("meeting_id", meeting.id)
                    .put("title", meeting.title)
                    .put("started_at", meeting.startedAt.toString())
                    .put("participants", people)
                    .put("language_mode", languageMode)
                    .put("audio_size", audio.length())
                    .toString()
                val response = execute { session ->
                    Request.Builder()
                        .url("$backend/v1/meetings/jobs/init")
                        .post(initJson.toRequestBody("application/json".toMediaType()))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer $session")
                        .build()
                }
                response.use {
                    if (!it.isSuccessful) return@withContext retryOrFail(db, id, "Could not create the secure MAI processing job (${it.code}).")
                    jobId = JSONObject(it.body?.string().orEmpty()).optString("job_id").trim()
                }
                if (jobId.isNullOrBlank()) return@withContext retryOrFail(db, id, "MAI server did not return a processing job id.")
                db.setAiJobId(id, jobId)
                meeting = db.getMeeting(id) ?: meeting
            }

            val state = getState(client, backend, jobId!!, auth)
                ?: return@withContext retryOrFail(db, id, "Could not read MAI processing status.")
            var uploaded = state.optLong("uploaded_bytes", 0L)
            val expected = state.optLong("expected_bytes", audio.length())
            var serverStatus = state.optString("status", "uploading")

            if (uploaded < expected) {
                db.updateStatus(id, "processing", "Uploading the saved recording safely · ${percent(uploaded, expected)}%")
                RandomAccessFile(audio, "r").use { input ->
                    var chunks = 0
                    while (uploaded < expected && chunks < MAX_CHUNKS_PER_RUN) {
                        val count = minOf(UPLOAD_CHUNK_BYTES.toLong(), expected - uploaded).toInt()
                        val buffer = ByteArray(count)
                        input.seek(uploaded)
                        input.readFully(buffer)
                        val offset = uploaded
                        val response = execute { session ->
                            Request.Builder()
                                .url("$backend/v1/meetings/jobs/$jobId/audio?offset=$offset")
                                .put(buffer.toRequestBody("application/octet-stream".toMediaType()))
                                .header("Accept", "application/json")
                                .header("Authorization", "Bearer $session")
                                .build()
                        }
                        response.use {
                            if (it.code == 409) return@withContext Result.retry()
                            if (!it.isSuccessful) return@withContext retryOrFail(db, id, "Meeting upload paused (${it.code}); MAI will resume from the last confirmed byte.")
                            val json = JSONObject(it.body?.string().orEmpty())
                            uploaded = json.optLong("uploaded_bytes", uploaded + count)
                            serverStatus = json.optString("status", "uploading")
                        }
                        chunks++
                    }
                }
                if (uploaded < expected) {
                    db.updateStatus(id, "processing", "Uploading the saved recording safely · ${percent(uploaded, expected)}%")
                    return@withContext Result.retry()
                }
            }

            if (serverStatus == "uploading" || serverStatus == "failed") {
                val path = if (serverStatus == "failed") "retry" else "start"
                val response = execute { session ->
                    Request.Builder()
                        .url("$backend/v1/meetings/jobs/$jobId/$path")
                        .post(ByteArray(0).toRequestBody(null))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer $session")
                        .build()
                }
                response.use {
                    if (!it.isSuccessful) return@withContext retryOrFail(db, id, "MAI processing could not start (${it.code}).")
                    serverStatus = JSONObject(it.body?.string().orEmpty()).optString("status", "queued")
                }
            }

            val latest = getState(client, backend, jobId!!, auth)
                ?: return@withContext retryOrFail(db, id, "Could not refresh MAI processing status.")
            serverStatus = latest.optString("status", serverStatus)
            val progress = latest.optInt("progress", 10).coerceIn(0, 100)

            when (serverStatus) {
                "ready" -> {
                    val result = latest.optJSONObject("result")
                    if (result == null) return@withContext retryOrFail(db, id, "MAI completed without a readable result.")
                    applyResult(db, meeting, result)
                    return@withContext Result.success()
                }
                "failed" -> {
                    val reason = latest.optString("error").trim().take(220)
                    db.updateStatus(
                        id,
                        "processing_failed",
                        if (reason.isBlank()) "MAI processing failed. The original audio is preserved and the server job can be retried." else "MAI processing failed: $reason"
                    )
                    return@withContext Result.success()
                }
                else -> {
                    val label = when (serverStatus) {
                        "queued" -> "Queued securely on MAI server"
                        "transcribing" -> "Transcribing complete recording"
                        "diarizing" -> "Separating speaker turns"
                        "translating" -> "Preparing verified English transcript"
                        "mom" -> "Generating structured MOM"
                        else -> "Processing complete recording"
                    }
                    db.updateStatus(id, "processing", "$label · $progress%")
                    return@withContext Result.retry()
                }
            }
        } catch (_: Throwable) {
            return@withContext retryOrFail(db, id, "Network unavailable. Audio and server progress are preserved; MAI will resume automatically.")
        } finally {
            auth.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun getState(client: OkHttpClient, backend: String, jobId: String, auth: MaiDeviceAuth): JSONObject? {
        fun call(session: String) = client.newCall(
            Request.Builder()
                .url("$backend/v1/meetings/jobs/$jobId")
                .get()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $session")
                .build()
        ).execute()
        var response = call(auth.sessionToken())
        if (response.code == 401 || response.code == 403) {
            response.close()
            MaiDeviceAuth.invalidateSession()
            response = call(auth.sessionToken(forceRefresh = true))
        }
        return response.use { if (it.isSuccessful) JSONObject(it.body?.string().orEmpty()) else null }
    }

    private fun applyResult(db: MaiDb, meeting: com.mai.app.data.MeetingRecord, json: JSONObject) {
        val englishTranscript = json.optString("transcript").trim()
        val summary = json.optString("summary").trim()
        require(englishTranscript.isNotBlank() && summary.isNotBlank()) { "Final MAI result is incomplete" }
        val validNames = meeting.participants.map { it.name }
        fun validatedOwner(raw: String): String? {
            val pieces = raw.split(Regex("\\s*/\\s*")).map(String::trim).filter(String::isNotBlank)
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
                due = item.optString("due").trim().takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            )
        }
        val decisionsArray = json.optJSONArray("decisions") ?: JSONArray()
        val decisions = (0 until decisionsArray.length())
            .map { decisionsArray.optString(it).trim() }
            .filter(String::isNotBlank)
            .distinct()
        db.replaceIntelligence(meeting.id, englishTranscript, summary, decisions, actions)
    }

    private fun retryOrFail(db: MaiDb, id: String, message: String): Result {
        return if (runAttemptCount < 20) {
            db.updateStatus(id, "processing", message)
            Result.retry()
        } else {
            db.updateStatus(id, "processing_failed", "$message The original audio is preserved.")
            Result.success()
        }
    }

    private fun percent(value: Long, total: Long): Int =
        if (total <= 0L) 0 else ((value * 100L) / total).toInt().coerceIn(0, 100)
}
