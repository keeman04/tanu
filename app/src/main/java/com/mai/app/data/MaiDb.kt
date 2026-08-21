package com.mai.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Participant(val name: String, val phone: String)

data class ActionRecord(
    val text: String,
    val owner: String?,
    val due: String?,
    val done: Boolean = false
)

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
                audio_path TEXT,
                audio_expires_at INTEGER,
                status TEXT NOT NULL DEFAULT 'recording'
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2 keeps the same SQL columns; action completion is stored inside actions JSON.
        // Opening v1 data therefore needs no destructive migration.
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

    fun checkpointMeeting(id: String, transcript: String, audioPath: String?) {
        writableDatabase.update("meetings", ContentValues().apply {
            put("transcript", transcript)
            if (!audioPath.isNullOrBlank()) put("audio_path", audioPath)
        }, "id=?", arrayOf(id))
    }

    fun recoverInterruptedMeetings(now: Long = System.currentTimeMillis()) {
        writableDatabase.update("meetings", ContentValues().apply {
            put("status", "interrupted")
            put("ended_at", now)
        }, "status='recording'", null)
    }

    fun finishMeeting(
        id: String,
        endedAt: Long,
        transcript: String,
        summary: String,
        decisions: List<String>,
        actions: List<ActionRecord>,
        audioPath: String?,
        audioExpiresAt: Long?
    ) {
        writableDatabase.update("meetings", ContentValues().apply {
            put("ended_at", endedAt)
            put("transcript", transcript)
            put("summary", summary)
            put("decisions", JSONArray(decisions).toString())
            put("actions", actionsToJson(actions))
            if (audioPath == null) putNull("audio_path") else put("audio_path", audioPath)
            if (audioExpiresAt == null) putNull("audio_expires_at") else put("audio_expires_at", audioExpiresAt)
            put("status", "ready")
        }, "id=?", arrayOf(id))
    }

    fun updateActionDone(meetingId: String, actionIndex: Int, done: Boolean) {
        val meeting = getMeeting(meetingId) ?: return
        if (actionIndex !in meeting.actions.indices) return
        val updated = meeting.actions.toMutableList().also { it[actionIndex] = it[actionIndex].copy(done = done) }
        writableDatabase.update("meetings", ContentValues().apply {
            put("actions", actionsToJson(updated))
        }, "id=?", arrayOf(meetingId))
    }

    fun markAudioDeleted(id: String) {
        writableDatabase.update("meetings", ContentValues().apply {
            putNull("audio_path")
            putNull("audio_expires_at")
        }, "id=?", arrayOf(id))
    }

    fun deleteMeeting(id: String) {
        getMeeting(id)?.audioPath?.let { runCatching { java.io.File(it).delete() } }
        writableDatabase.delete("meetings", "id=?", arrayOf(id))
    }

    fun getMeeting(id: String): MeetingRecord? = readableDatabase
        .query("meetings", null, "id=?", arrayOf(id), null, null, null)
        .use { if (it.moveToFirst()) fromCursor(it) else null }

    fun listMeetings(): List<MeetingRecord> = queryMeetings(null, null)

    fun searchMeetings(query: String): List<MeetingRecord> {
        val q = query.trim()
        if (q.isBlank()) return listMeetings()
        val like = "%$q%"
        return queryMeetings(
            "title LIKE ? COLLATE NOCASE OR transcript LIKE ? COLLATE NOCASE OR summary LIKE ? COLLATE NOCASE OR decisions LIKE ? COLLATE NOCASE OR actions LIKE ? COLLATE NOCASE OR participants LIKE ? COLLATE NOCASE",
            arrayOf(like, like, like, like, like, like)
        )
    }

    fun expiredAudio(now: Long): List<MeetingRecord> = queryMeetings(
        "audio_path IS NOT NULL AND audio_expires_at IS NOT NULL AND audio_expires_at<=? AND status='ready' AND summary<>''",
        arrayOf(now.toString())
    )

    private fun queryMeetings(selection: String?, args: Array<String>?): List<MeetingRecord> {
        val out = mutableListOf<MeetingRecord>()
        readableDatabase.query("meetings", null, selection, args, null, null, "started_at DESC").use { c ->
            while (c.moveToNext()) out += fromCursor(c)
        }
        return out
    }

    private fun fromCursor(c: Cursor): MeetingRecord {
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

    private fun participantsFromJson(raw: String): List<Participant> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Participant(it.getString("name"), it.getString("phone")) } }
    }.getOrDefault(emptyList())

    private fun actionsToJson(items: List<ActionRecord>): String = JSONArray().apply {
        items.forEach { put(JSONObject().put("text", it.text).put("owner", it.owner).put("due", it.due).put("done", it.done)) }
    }.toString()

    private fun actionsFromJson(raw: String): List<ActionRecord> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let {
                ActionRecord(
                    text = it.getString("text"),
                    owner = it.optString("owner").takeIf { s -> s.isNotBlank() && s != "null" },
                    due = it.optString("due").takeIf { s -> s.isNotBlank() && s != "null" },
                    done = it.optBoolean("done", false)
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun jsonStrings(raw: String): List<String> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())
}
