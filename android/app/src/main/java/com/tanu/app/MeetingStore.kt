package com.tanu.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MeetingStore(context: Context) {
    private val root = File(context.filesDir, "meetings").apply { mkdirs() }
    private val lock = Any()

    fun initializeMeeting(meetingId: String, title: String) = synchronized(lock) {
        val dir = meetingDir(meetingId)
        val metadata = File(dir, "metadata.json")
        if (!metadata.exists()) {
            metadata.writeText(JSONObject().put("id", meetingId).put("title", title).toString())
        }
    }

    fun title(meetingId: String): String = synchronized(lock) {
        val file = File(meetingDir(meetingId), "metadata.json")
        if (!file.exists()) return@synchronized "Meeting"
        runCatching { JSONObject(file.readText()).optString("title", "Meeting") }.getOrDefault("Meeting")
    }

    fun chunkFile(meetingId: String, index: Int): File =
        File(meetingDir(meetingId), "chunk-${index.toString().padStart(5, '0')}.wav")

    fun chunkFiles(meetingId: String): List<File> = meetingDir(meetingId)
        .listFiles { file -> file.name.startsWith("chunk-") && file.extension == "wav" }
        ?.sortedBy { chunkIndex(it) }
        .orEmpty()

    fun missingChunkFiles(meetingId: String): List<File> {
        val completed = transcriptMap(meetingId).keys
        return chunkFiles(meetingId).filter { chunkIndex(it) !in completed }
    }

    fun chunkIndex(file: File): Int = file.nameWithoutExtension
        .removePrefix("chunk-")
        .toIntOrNull() ?: -1

    fun saveTranscriptSegment(meetingId: String, index: Int, text: String) = synchronized(lock) {
        if (text.isBlank()) return@synchronized
        val map = transcriptMapLocked(meetingId).toMutableMap()
        map[index] = text.trim()
        writeTranscriptLocked(meetingId, map)
    }

    fun transcriptMap(meetingId: String): Map<Int, String> = synchronized(lock) {
        transcriptMapLocked(meetingId)
    }

    fun orderedTranscript(meetingId: String): String = transcriptMap(meetingId)
        .toSortedMap()
        .values
        .joinToString("\n")
        .trim()

    fun saveMom(meetingId: String, mom: Mom) = synchronized(lock) {
        val actions = JSONArray().apply {
            mom.actions.forEach { action ->
                put(JSONObject()
                    .put("task", action.task)
                    .put("owner", action.owner)
                    .put("dueDate", action.dueDate))
            }
        }
        val json = JSONObject()
            .put("summary", mom.summary)
            .put("decisions", JSONArray(mom.decisions))
            .put("actions", actions)
            .put("followUps", JSONArray(mom.followUps))
            .put("source", mom.source)
        File(meetingDir(meetingId), "mom.json").writeText(json.toString())
    }

    fun readMom(meetingId: String): Mom? = synchronized(lock) {
        val file = File(meetingDir(meetingId), "mom.json")
        if (!file.exists()) return@synchronized null
        runCatching {
            val json = JSONObject(file.readText())
            Mom(
                summary = json.getString("summary"),
                decisions = json.getJSONArray("decisions").toStringList(),
                actions = json.getJSONArray("actions").toActionItems(),
                followUps = json.getJSONArray("followUps").toStringList(),
                source = json.optString("source", "unknown")
            )
        }.getOrNull()
    }

    private fun meetingDir(meetingId: String): File = File(root, meetingId).apply { mkdirs() }

    private fun transcriptMapLocked(meetingId: String): Map<Int, String> {
        val file = File(meetingDir(meetingId), "transcript.json")
        if (!file.exists()) return emptyMap()
        return runCatching {
            val array = JSONArray(file.readText())
            buildMap {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    put(item.getInt("index"), item.getString("text"))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun writeTranscriptLocked(meetingId: String, map: Map<Int, String>) {
        val array = JSONArray()
        map.toSortedMap().forEach { (index, text) ->
            array.put(JSONObject().put("index", index).put("text", text))
        }
        File(meetingDir(meetingId), "transcript.json").writeText(array.toString())
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        for (i in 0 until length()) add(optString(i))
    }

    private fun JSONArray.toActionItems(): List<ActionItem> = buildList {
        for (i in 0 until length()) {
            val item = getJSONObject(i)
            add(ActionItem(
                task = item.optString("task"),
                owner = item.optString("owner"),
                dueDate = item.optString("dueDate")
            ))
        }
    }
}
