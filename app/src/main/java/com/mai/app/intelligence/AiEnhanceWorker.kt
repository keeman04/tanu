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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
        val sourceTranscript = meeting.transcript.trim()

        fun markUnavailable(message: String) {
            db.replaceIntelligence(
                id = meeting.id,
                transcript = meeting.transcript,
                summary = message,
                decisions = emptyList(),
                actions = emptyList()
            )
        }

        if (backend.isBlank() || sourceTranscript.isBlank()) {
            markUnavailable(
                if (sourceTranscript.isBlank())
                    "Audio saved safely, but the live multilingual transcript was unavailable. No unreliable MOM was generated."
                else
                    "MAI intelligence service is not configured. The transcript is preserved; no unreliable MOM was generated."
            )
            return@withContext Result.success()
        }

        try {
            val payload = JSONObject()
                .put("transcript", sourceTranscript)
                .put("participants", JSONArray().apply { meeting.participants.forEach { put(it.name) } })
                .toString()
                .toByteArray(Charsets.UTF_8)

            val connection = (URL("$backend/api/mom").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (code !in 200..299) {
                if ((code >= 500 || code == 429) && runAttemptCount < 3) return@withContext Result.retry()
                markUnavailable("MAI could not finalize the English transcript and MOM. The live transcript and original audio are preserved.")
                return@withContext Result.success()
            }

            val json = JSONObject(body)
            val englishTranscript = json.optString("english_transcript").trim()
            val summary = json.optString("summary").trim()
            if (englishTranscript.isBlank() || summary.isBlank()) {
                markUnavailable("MAI returned an incomplete result. The live transcript and original audio are preserved.")
                return@withContext Result.success()
            }

            val validNames = meeting.participants.map { it.name }
            val actionsArray = json.optJSONArray("actions") ?: JSONArray()
            val actions = (0 until actionsArray.length()).map { index ->
                val item = actionsArray.getJSONObject(index)
                val owner = item.optString("owner").trim().takeIf { candidate ->
                    candidate.isNotBlank() && validNames.any { it.equals(candidate, ignoreCase = true) }
                }?.let { candidate -> validNames.first { it.equals(candidate, ignoreCase = true) } }
                ActionRecord(
                    text = item.optString("text").trim(),
                    owner = owner,
                    due = item.optString("due").trim().takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                )
            }.filter { it.text.isNotBlank() }

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
            Result.success()
        } catch (_: Throwable) {
            if (runAttemptCount < 3) Result.retry()
            else {
                markUnavailable("MAI could not finalize the English transcript and MOM after retrying. The live transcript and original audio are preserved.")
                Result.success()
            }
        }
    }
}
