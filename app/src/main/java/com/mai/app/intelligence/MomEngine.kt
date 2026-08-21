package com.mai.app.intelligence

import com.mai.app.data.ActionRecord
import com.mai.app.data.Participant
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import kotlin.math.max

data class MomResult(
    val summary: String,
    val decisions: List<String>,
    val actions: List<ActionRecord>,
    val followUps: List<String>
)

object MomEngine {
    private val decisionWords = listOf(
        "decided", "decision", "final", "approved", "agreed", "confirmed", "go with",
        "selected", "choose", "chosen", "lock it", "finalize", "finalise"
    )
    private val actionWords = listOf(
        " will ", "need to", "needs to", "should", "must", "please", "can you", "send ",
        "prepare ", "create ", "share ", "call ", "check ", "follow up", "update ", "complete "
    )
    private val followWords = listOf("follow up", "follow-up", "revisit", "next meeting", "check back", "pending", "circle back")

    fun generate(transcript: String, participants: List<Participant>, meetingStart: Long): MomResult {
        val lines = transcript
            .split(Regex("[\\n.!?]+"))
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length >= 8 }
        val unique = dedupe(lines)
        val decisions = dedupe(unique.filter { line -> decisionWords.any { line.contains(it, ignoreCase = true) } }).take(8)
        val actions = unique.filter { line -> actionWords.any { line.lowercase().contains(it) } }
            .map { line ->
                val owners = participants.filter { p -> line.contains(p.name, ignoreCase = true) }.map { it.name }.distinct()
                ActionRecord(cleanAction(line), owners.joinToString(", ").takeIf(String::isNotBlank), resolveDue(line, meetingStart))
            }
            .let(::dedupeActions)
            .take(12)
        val followUps = dedupe(unique.filter { line -> followWords.any { line.contains(it, ignoreCase = true) } }).take(8)
        val summaryLines = unique.filter { it.length >= 14 }.take(3)
        val summary = if (summaryLines.isEmpty()) "Meeting recorded." else summaryLines.joinToString(". ").let { if (it.endsWith('.')) it else "$it." }
        return MomResult(summary, decisions, actions, followUps)
    }

    private fun cleanAction(s: String): String = s.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun dedupe(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (line in lines) if (out.none { similarity(it, line) >= 0.72 }) out += line
        return out
    }

    private fun dedupeActions(items: List<ActionRecord>): List<ActionRecord> {
        val out = mutableListOf<ActionRecord>()
        for (item in items) {
            val existing = out.indexOfFirst { similarity(it.text, item.text) >= 0.72 }
            if (existing < 0) out += item
            else {
                val old = out[existing]
                val owners = (old.owner.orEmpty().split(',') + item.owner.orEmpty().split(','))
                    .map(String::trim).filter(String::isNotBlank).distinct()
                out[existing] = old.copy(
                    owner = owners.joinToString(", ").takeIf(String::isNotBlank),
                    due = old.due ?: item.due
                )
            }
        }
        return out
    }

    private fun similarity(a: String, b: String): Double {
        val aa = tokens(a)
        val bb = tokens(b)
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        return aa.intersect(bb).size.toDouble() / max(1.0, aa.union(bb).size.toDouble())
    }

    private fun tokens(s: String): Set<String> = s.lowercase()
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length > 2 && it !in setOf("the", "and", "for", "with", "that", "this") }
        .toSet()

    private fun resolveDue(text: String, meetingStart: Long): String? {
        val base = Instant.ofEpochMilli(meetingStart).atZone(ZoneId.systemDefault()).toLocalDate()
        val lower = text.lowercase()
        val date = when {
            "today" in lower -> base
            "tomorrow" in lower -> base.plusDays(1)
            else -> {
                val target = mapOf(
                    "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
                    "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
                    "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
                    "sunday" to DayOfWeek.SUNDAY
                ).entries.firstOrNull { it.key in lower }?.value
                target?.let { base.with(TemporalAdjusters.nextOrSame(it)) }
            }
        }
        return date?.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
    }
}
