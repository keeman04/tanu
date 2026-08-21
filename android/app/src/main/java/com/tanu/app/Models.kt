package com.tanu.app

data class ActionItem(
    val task: String,
    val owner: String = "",
    val dueDate: String = ""
)

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
            appendLine()
            appendLine("DECISIONS")
            decisions.forEach { appendLine("• $it") }
        }
        if (actions.isNotEmpty()) {
            appendLine()
            appendLine("ACTIONS")
            actions.forEach { action ->
                append("• ${action.task}")
                if (action.owner.isNotBlank()) append(" — ${action.owner}")
                if (action.dueDate.isNotBlank()) append(" — due ${action.dueDate}")
                appendLine()
            }
        }
        if (followUps.isNotEmpty()) {
            appendLine()
            appendLine("FOLLOW-UP")
            followUps.forEach { appendLine("• $it") }
        }
    }.trim()
}
