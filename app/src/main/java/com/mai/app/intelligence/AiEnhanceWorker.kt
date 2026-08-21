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
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class AiEnhanceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val KEY_MEETING_ID = "meeting_id"
        fun input(meetingId: String) = Data.Builder().putString(KEY_MEETING_ID, meetingId).build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val backend = BuildConfig.MAI_BACKEND_URL.trim().trimEnd('/')
        if (backend.isBlank()) return@withContext Result.success()
        val id = inputData.getString(KEY_MEETING_ID) ?: return@withContext Result.failure()
        val db = MaiDb(applicationContext)
        val meeting = db.getMeeting(id) ?: return@withContext Result.success()
        val audio = meeting.audioPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
            ?: return@withContext Result.success()

        try {
            val boundary = "MAI-${UUID.randomUUID()}"
            val connection = (URL("$backend/v1/meetings/process").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 15 * 60_000
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
                if (BuildConfig.MAI_GATEWAY_TOKEN.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${BuildConfig.MAI_GATEWAY_TOKEN}")
                }
            }

            BufferedOutputStream(connection.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                field("meeting_id", meeting.id)
                field("title", meeting.title)
                field("started_at", meeting.startedAt.toString())
                field("participants", JSONArray().apply {
                    meeting.participants.forEach { put(JSONObject().put("name", it.name).put("phone", it.phone)) }
                }.toString())
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"meeting.aac\"\r\nContent-Type: audio/aac\r\n\r\n".toByteArray())
                audio.inputStream().use { input -> input.copyTo(out, 64 * 1024) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) {
                return@withContext if (code >= 500 || code == 429) Result.retry() else Result.success()
            }

            val json = JSONObject(body)
            val actionsArray = json.optJSONArray("actions") ?: JSONArray()
            val actions = (0 until actionsArray.length()).map { index ->
                val item = actionsArray.getJSONObject(index)
                ActionRecord(
                    text = item.optString("text").trim(),
                    owner = item.optString("owner").takeIf { it.isNotBlank() && it != "null" },
                    due = item.optString("due").takeIf { it.isNotBlank() && it != "null" }
                )
            }.filter { it.text.isNotBlank() }
            val decisionsArray = json.optJSONArray("decisions") ?: JSONArray()
            val decisions = (0 until decisionsArray.length()).map { decisionsArray.optString(it) }.filter(String::isNotBlank)

            db.replaceIntelligence(
                id = meeting.id,
                transcript = json.optString("transcript").ifBlank { meeting.transcript },
                summary = json.optString("summary").ifBlank { meeting.summary },
                decisions = decisions.ifEmpty { meeting.decisions },
                actions = actions.ifEmpty { meeting.actions }
            )
            Result.success()
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}
