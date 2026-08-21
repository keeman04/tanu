package com.mai.app.pipeline

import com.mai.app.data.ActionRecord
import org.json.JSONArray
import org.json.JSONObject

data class CloudMom(
    val summary: String,
    val decisions: List<String>,
    val actions: List<ActionRecord>,
    val followUps: List<String>
) {
    companion object {
        fun fromJson(raw: String): CloudMom {
            val json = JSONObject(raw)
            fun strings(name: String): List<String> {
                val arr = json.optJSONArray(name) ?: JSONArray()
                return buildList { for (i in 0 until arr.length()) arr.optString(i).trim().takeIf(String::isNotBlank)?.let(::add) }
            }
            val actionArray = json.optJSONArray("actions") ?: JSONArray()
            val actions = buildList {
                for (i in 0 until actionArray.length()) {
                    val a = actionArray.optJSONObject(i) ?: continue
                    val task = a.optString("task").trim()
                    if (task.isNotBlank()) {
                        add(ActionRecord(
                            text = task,
                            owner = a.optString("owner").trim().takeIf(String::isNotBlank),
                            due = a.optString("dueDate").trim().takeIf(String::isNotBlank)
                        ))
                    }
                }
            }
            return CloudMom(json.optString("summary").trim(), strings("decisions"), actions, strings("followUps"))
        }
    }
}

data class ServerChunk(
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val state: String,
    val text: String?
)

data class MeetingUpdate(
    val state: String,
    val totalChunks: Int,
    val pendingChunks: Int,
    val transcribedChunks: Int,
    val chunks: List<ServerChunk>
)

data class AskAnswer(
    val answer: String,
    val sources: List<String> = emptyList(),
    val cloud: Boolean = false
)
