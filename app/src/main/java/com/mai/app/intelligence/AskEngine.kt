package com.mai.app.intelligence

import android.content.Context
import com.mai.app.data.MeetingRecord
import com.mai.app.pipeline.AskAnswer
import com.mai.app.pipeline.CloudApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

object AskEngine {
    suspend fun ask(context: Context, question: String, meetings: List<MeetingRecord>): AskAnswer {
        val q = question.trim()
        if (q.isBlank()) return AskAnswer("Ask me about a meeting, decision, action, person or follow-up.")
        val api = CloudApi(context)
        if (api.configured) {
            runCatching { api.ask(q) }.getOrNull()?.takeIf { it.answer.isNotBlank() }?.let { return it }
        }
        return withContext(Dispatchers.Default) { localAnswer(q, meetings) }
    }

    fun localAnswer(question: String, meetings: List<MeetingRecord>): AskAnswer {
        if (meetings.isEmpty()) return AskAnswer("No meetings have been recorded yet.")
        val lower = question.lowercase()
        val filteredByTime = meetings.filter { matchesTime(lower, it.startedAt) }
        val pool = if (filteredByTime.isNotEmpty()) filteredByTime else meetings

        if ("overdue" in lower || "pending action" in lower || "commitment" in lower) {
            val today = LocalDate.now()
            val rows = pool.flatMap { m ->
                m.actions.mapNotNull { a ->
                    val due = parseDue(a.due) ?: return@mapNotNull null
                    if (due.isAfter(today)) null else Triple(m, a, due)
                }
            }.sortedBy { it.third }.take(8)
            if (rows.isEmpty()) return AskAnswer("I couldn't find any dated overdue actions in your saved meetings.")
            return AskAnswer(
                rows.joinToString("\n") { (m, a, due) -> "• ${a.text}${a.owner?.let { " — $it" }.orEmpty()} — due ${due.format(DISPLAY)}" },
                rows.map { it.first.title }.distinct().take(5)
            )
        }

        val wantsDecisions = "decision" in lower || "decide" in lower || "agreed" in lower
        val wantsActions = "action" in lower || "task" in lower || "follow up" in lower || "follow-up" in lower
        val queryTokens = tokens(question).filterNot { it in STOP }
        val ranked = pool.map { meeting -> meeting to score(meeting, queryTokens) }
            .filter { it.second > 0 || queryTokens.isEmpty() }
            .sortedByDescending { it.second }
            .take(5)
        if (ranked.isEmpty()) return AskAnswer("I couldn't find that in your saved MAI meetings. Try a person, topic, meeting name or date.")

        val lines = mutableListOf<String>()
        val sources = mutableListOf<String>()
        ranked.forEach { (m, _) ->
            val selected = when {
                wantsDecisions && m.decisions.isNotEmpty() -> m.decisions.take(3)
                wantsActions && m.actions.isNotEmpty() -> m.actions.take(3).map { a ->
                    listOfNotNull(a.text, a.owner?.let { "Owner: $it" }, a.due?.let { "Due: $it" }).joinToString(" · ")
                }
                m.summary.isNotBlank() -> listOf(m.summary)
                else -> listOf(snippet(m.transcript, queryTokens))
            }.filter(String::isNotBlank)
            if (selected.isNotEmpty()) {
                lines += "${m.title}: ${selected.joinToString(" | ")}"
                sources += m.title
            }
        }
        return AskAnswer(lines.take(5).joinToString("\n\n").ifBlank { "I found the meetings, but there isn't enough processed text to answer yet." }, sources.distinct(), false)
    }

    private fun score(m: MeetingRecord, tokens: List<String>): Int {
        if (tokens.isEmpty()) return 1
        val title = m.title.lowercase()
        val people = m.participants.joinToString(" ") { it.name }.lowercase()
        val important = (m.summary + " " + m.decisions.joinToString(" ") + " " + m.actions.joinToString(" ") { it.text }).lowercase()
        val transcript = m.transcript.lowercase()
        return tokens.sumOf { token ->
            (if (title.contains(token)) 8 else 0) +
                (if (people.contains(token)) 7 else 0) +
                (if (important.contains(token)) 4 else 0) +
                (if (transcript.contains(token)) 1 else 0)
        }
    }

    private fun snippet(text: String, tokens: List<String>): String {
        if (text.isBlank()) return "Transcript not available yet."
        val lines = text.lines().filter(String::isNotBlank)
        return lines.firstOrNull { line -> tokens.any { line.contains(it, ignoreCase = true) } }?.take(320)
            ?: lines.first().take(320)
    }

    private fun matchesTime(q: String, startedAt: Long): Boolean {
        val date = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when {
            "today" in q -> date == today
            "yesterday" in q -> date == today.minusDays(1)
            "this week" in q -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                !date.isBefore(monday) && !date.isAfter(today)
            }
            else -> true
        }
    }

    private fun parseDue(raw: String?): LocalDate? = raw?.let { runCatching { LocalDate.parse(it, DISPLAY) }.getOrNull() }
    private fun tokens(s: String): List<String> = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .split(Regex("\\s+")).filter { it.length > 1 }

    private val DISPLAY = DateTimeFormatter.ofPattern("dd MMM yyyy")
    private val STOP = setOf("what", "did", "we", "the", "and", "about", "show", "me", "find", "from", "meeting", "meetings")
}
