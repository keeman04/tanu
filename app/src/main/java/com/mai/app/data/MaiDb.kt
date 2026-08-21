package com.mai.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID


data class Participant(val name: String, val phone: String)

data class MeetingRecord(
    val id: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long?,
    val participants: List<Participant>,
    val transcript: String,
    val summary: String,
    val decisions: List<String>,
    val actions: List<ActionRecord>,
    val audioPath: String?,
    val audioExpiresAt: Long?,
    val status: String
)

data class ActionRecord(
    val text: String,
    val owner: String?,
    val due: String?
)

class MaiDb(context: Context) : SQLiteOpenHelper(context, "mai.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE meetings(
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                participants TEXT NOT NULL,
                transcript TEXT NOT NULL DEFAULT '',
                summary TEXT NOT NULL DEFAULT '',
                decisions TEXT NOT NULL DEFAULT '[]',
                actions TEXT NOT NULL DEFAULT '[]',
                audio_path TEXT,
                audio_expires_at INTEGER,
                status TEXT NOT NULL DEFAULT 'recording'
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun createMeeting(title: String, participants: List<Participant>, startedAt: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("title", title.ifBlank { "Meeting" })
            put("started_at", startedAt)
            put("participants", participantsToJson(participants))
            put("status", "recording")
        }
        writableDatabase.insertOrThrow("meetings", null, values)
        return id
    }

    fun finishMeeting(
        id: String,
        endedAt: Long,
        transcript: String,
        summary: String,
        decisions: List<String>,
        actions: List<ActionRecord>,
        audioPath: String,
        audioExpiresAt: Long
    ) {
        val values = ContentValues().apply {
            put("ended_at", endedAt)
            put("transcript", transcript)
            put("summary", summary)
            put("decisions", JSONArray(decisions).toString())
            put("actions", actionsToJson(actions))
            put("audio_path", audioPath)
            put("audio_expires_at", audioExpiresAt)
            put("status", "ready")
        }
        writableDatabase.update("meetings", values, "id=?", arrayOf(id))
    }

    fun markAudioDeleted(id: String) {
        val values = ContentValues().apply { putNull("audio_path") }
        writableDatabase.update("meetings", values, "id=?", arrayOf(id))
    }

    fun getMeeting(id: String): MeetingRecord? {
        readableDatabase.query("meetings", null, "id=?", arrayOf(id), null, null, null).use { c ->
            return if (c.moveToFirst()) fromCursor(c) else null
        }
    }

    fun listMeetings(): List<MeetingRecord> {
        val out = mutableListOf<MeetingRecord>()
        readableDatabase.query("meetings", null, null, null, null, null, "started_at DESC").use { c ->
            while (c.moveToNext()) out += fromCursor(c)
        }
        return out
    }

    fun expiredAudio(now: Long): List<MeetingRecord> {
        val out = mutableListOf<MeetingRecord>()
        readableDatabase.query(
            "meetings", null,
            "audio_path IS NOT NULL AND audio_expires_at IS NOT NULL AND audio_expires_at<=? AND status='ready' AND summary<>''",
            arrayOf(now.toString()), null, null, null
        ).use { c -> while (c.moveToNext()) out += fromCursor(c) }
        return out
    }

    private fun fromCursor(c: android.database.Cursor): MeetingRecord {
        fun idx(name: String) = c.getColumnIndexOrThrow(name)
        return MeetingRecord(
            id = c.getString(idx("id")),
            title = c.getString(idx("title")),
            startedAt = c.getLong(idx("started_at")),
            endedAt = if (c.isNull(idx("ended_at"))) null else c.getLong(idx("ended_at")),
            participants = participantsFromJson(c.getString(idx("participants"))),
            transcript = c.getString(idx("transcript")),
            summary = c.getString(idx("summary")),
            decisions = jsonStrings(c.getString(idx("decisions"))),
            actions = actionsFromJson(c.getString(idx("actions"))),
            audioPath = if (c.isNull(idx("audio_path"))) null else c.getString(idx("audio_path")),
            audioExpiresAt = if (c.isNull(idx("audio_expires_at"))) null else c.getLong(idx("audio_expires_at")),
            status = c.getString(idx("status"))
        )
    }

    private fun participantsToJson(items: List<Participant>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("name", it.name).put("phone", it.phone)) }
    }.toString()

    private fun participantsFromJson(raw: String): List<Participant> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Participant(it.getString("name"), it.getString("phone")) } }
    }

    private fun actionsToJson(items: List<ActionRecord>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("text", it.text).put("owner", it.owner).put("due", it.due)) }
    }.toString()

    private fun actionsFromJson(raw: String): List<ActionRecord> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let {
                ActionRecord(it.getString("text"), it.optString("owner").takeIf { s -> s.isNotBlank() && s != "null" }, it.optString("due").takeIf { s -> s.isNotBlank() && s != "null" })
            }
        }
    }

    private fun jsonStrings(raw: String): List<String> {
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
