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
    val followUps: List<String>,
    val audioPath: String?,
    val audioExpiresAt: Long?,
    val status: String
)

data class ActionRecord(val text: String, val owner: String?, val due: String?)

class MaiDb(context: Context) : SQLiteOpenHelper(context, "mai.db", null, 2) {
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
                follow_ups TEXT NOT NULL DEFAULT '[]',
                audio_path TEXT,
                audio_expires_at INTEGER,
                status TEXT NOT NULL DEFAULT 'recording'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_meetings_started ON meetings(started_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE meetings ADD COLUMN follow_ups TEXT NOT NULL DEFAULT '[]'") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_meetings_started ON meetings(started_at DESC)") }
        }
    }

    fun createMeeting(title: String, participants: List<Participant>, startedAt: Long = System.currentTimeMillis()): String {
        require(participants.isNotEmpty()) { "At least one participant is required" }
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("meetings", null, ContentValues().apply {
            put("id", id)
            put("title", title.trim().ifBlank { "Meeting" })
            put("started_at", startedAt)
            put("participants", participantsToJson(participants))
            put("status", "recording")
        })
        return id
    }

    fun finishMeeting(
        id: String,
        endedAt: Long,
        transcript: String,
        summary: String,
        decisions: List<String>,
        actions: List<ActionRecord>,
        followUps: List<String> = emptyList(),
        audioPath: String,
        audioExpiresAt: Long?,
        status: String = "ready"
    ) {
        writableDatabase.update("meetings", ContentValues().apply {
            put("ended_at", endedAt)
            put("transcript", transcript)
            put("summary", summary)
            put("decisions", JSONArray(decisions).toString())
            put("actions", actionsToJson(actions))
            put("follow_ups", JSONArray(followUps).toString())
            put("audio_path", audioPath)
            if (audioExpiresAt == null) putNull("audio_expires_at") else put("audio_expires_at", audioExpiresAt)
            put("status", status)
        }, "id=?", arrayOf(id))
    }

    fun markProcessing(id: String) {
        writableDatabase.update("meetings", ContentValues().apply { put("status", "processing") }, "id=?", arrayOf(id))
    }

    fun markReady(id: String) {
        writableDatabase.update("meetings", ContentValues().apply { put("status", "ready") }, "id=?", arrayOf(id))
    }

    fun updateTranscript(id: String, transcript: String) {
        if (transcript.isBlank()) return
        writableDatabase.update("meetings", ContentValues().apply { put("transcript", transcript) }, "id=?", arrayOf(id))
    }

    fun applyCloudResult(
        id: String,
        transcript: String,
        summary: String,
        decisions: List<String>,
        actions: List<ActionRecord>,
        followUps: List<String>
    ) {
        writableDatabase.update("meetings", ContentValues().apply {
            if (transcript.isNotBlank()) put("transcript", transcript)
            put("summary", summary)
            put("decisions", JSONArray(decisions).toString())
            put("actions", actionsToJson(actions))
            put("follow_ups", JSONArray(followUps).toString())
            put("status", "ready")
        }, "id=?", arrayOf(id))
    }

    fun markAudioDeleted(id: String) {
        writableDatabase.update("meetings", ContentValues().apply { putNull("audio_path") }, "id=?", arrayOf(id))
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

    fun searchMeetings(query: String): List<MeetingRecord> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return listMeetings()
        return listMeetings().filter { m ->
            val haystack = buildString {
                append(m.title).append(' ').append(m.transcript).append(' ').append(m.summary).append(' ')
                append(m.decisions.joinToString(" ")).append(' ').append(m.followUps.joinToString(" ")).append(' ')
                append(m.actions.joinToString(" ") { "${it.text} ${it.owner.orEmpty()} ${it.due.orEmpty()}" }).append(' ')
                append(m.participants.joinToString(" ") { "${it.name} ${it.phone}" })
            }.lowercase()
            tokens.all(haystack::contains)
        }
    }

    fun expiredAudio(now: Long): List<MeetingRecord> {
        val out = mutableListOf<MeetingRecord>()
        readableDatabase.query(
            "meetings", null,
            "audio_path IS NOT NULL AND audio_expires_at IS NOT NULL AND audio_expires_at<=? AND status IN ('ready','processing')",
            arrayOf(now.toString()), null, null, null
        ).use { c -> while (c.moveToNext()) out += fromCursor(c) }
        return out
    }

    fun deleteMeeting(id: String) {
        writableDatabase.delete("meetings", "id=?", arrayOf(id))
    }

    private fun fromCursor(c: android.database.Cursor): MeetingRecord {
        fun idx(name: String) = c.getColumnIndexOrThrow(name)
        val followIndex = c.getColumnIndex("follow_ups")
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
            followUps = if (followIndex >= 0) jsonStrings(c.getString(followIndex)) else emptyList(),
            audioPath = if (c.isNull(idx("audio_path"))) null else c.getString(idx("audio_path")),
            audioExpiresAt = if (c.isNull(idx("audio_expires_at"))) null else c.getLong(idx("audio_expires_at")),
            status = c.getString(idx("status"))
        )
    }

    private fun participantsToJson(items: List<Participant>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("name", it.name).put("phone", it.phone)) }
    }.toString()

    private fun participantsFromJson(raw: String): List<Participant> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Participant(it.getString("name"), it.getString("phone")) } }
    }.getOrDefault(emptyList())

    private fun actionsToJson(items: List<ActionRecord>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("text", it.text).put("owner", it.owner).put("due", it.due)) }
    }.toString()

    private fun actionsFromJson(raw: String): List<ActionRecord> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let {
                ActionRecord(
                    it.optString("text"),
                    it.optString("owner").takeIf { s -> s.isNotBlank() && s != "null" },
                    it.optString("due").takeIf { s -> s.isNotBlank() && s != "null" }
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun jsonStrings(raw: String): List<String> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }.filter(String::isNotBlank)
    }.getOrDefault(emptyList())
}
