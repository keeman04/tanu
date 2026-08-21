package com.tanu.app

import org.json.JSONArray
import org.json.JSONObject

data class ActionItem(val task: String, val owner: String = "", val dueDate: String = "")

data class Mom(
    val summary: String,
    val decisions: List<String>,
    val actions: List<ActionItem>,
    val followUps: List<String>,
    val source: String
) {
    fun displayText(): String = buildString {
        appendLine("SUMMARY")
        appendLine(summary)
        if (decisions.isNotEmpty()) {
            appendLine(); appendLine("DECISIONS")
            decisions.forEach { appendLine("• $it") }
        }
        if (actions.isNotEmpty()) {
            appendLine(); appendLine("ACTIONS")
            actions.forEach { action ->
                append("• ${action.task}")
                if (action.owner.isNotBlank()) append(" — ${action.owner}")
                if (action.dueDate.isNotBlank()) append(" — due ${action.dueDate}")
                appendLine()
            }
        }
        if (followUps.isNotEmpty()) {
            appendLine(); appendLine("FOLLOW-UP")
            followUps.forEach { appendLine("• $it") }
        }
    }.trim()

    fun toJson(): String = JSONObject()
        .put("summary", summary)
        .put("decisions", JSONArray(decisions))
        .put("actions", JSONArray().apply {
            actions.forEach { put(JSONObject().put("task", it.task).put("owner", it.owner).put("dueDate", it.dueDate)) }
        })
        .put("followUps", JSONArray(followUps))
        .put("source", source)
        .toString()

    companion object {
        fun fromJson(raw: String, sourceOverride: String? = null): Mom {
            val json = JSONObject(raw)
            fun strings(name: String): List<String> {
                val a = json.optJSONArray(name) ?: JSONArray()
                return buildList { for (i in 0 until a.length()) add(a.optString(i)) }
            }
            val actionArray = json.optJSONArray("actions") ?: JSONArray()
            val actions = buildList {
                for (i in 0 until actionArray.length()) {
                    val a = actionArray.optJSONObject(i) ?: continue
                    add(ActionItem(a.optString("task"), a.optString("owner"), a.optString("dueDate")))
                }
            }
            return Mom(
                summary = json.optString("summary"),
                decisions = strings("decisions"),
                actions = actions,
                followUps = strings("followUps"),
                source = sourceOverride ?: json.optString("source", "cloud")
            )
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
    val chunks: List<ServerChunk>,
    val rollingSummaries: List<RollingSummaryEntity>
)
