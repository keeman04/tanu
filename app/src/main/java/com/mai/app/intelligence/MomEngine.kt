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
    val actions: List<ActionRecord>
)

object MomEngine {
    private val decisionWords = listOf(
        "decided", "decision", "final", "approved", "agreed", "confirmed", "go with",
        "selected", "choose", "chosen", "lock it", "finalize", "finalise", "proceed with"
    )
    private val actionWords = listOf(
        " will ", "need to", "needs to", "should", "must", "please", "can you", "send ",
        "prepare ", "create ", "share ", "call ", "check ", "follow up", "update ", "complete ",
        "arrange ", "review ", "confirm ", "schedule "
    )
    private val fillerStarts = listOf("okay", "ok ", "yeah", "yes ", "right ", "so ", "actually ", "basically ")

    fun generate(transcript: String, participants: List<Participant>, meetingStart: Long): MomResult {
        val lines = transcript
            .split(Regex("[\\n.!?]+"))
            .map(::normalizeSentence)
            .filter { it.length >= 8 && it.any(Char::isLetter) }

        val unique = dedupe(lines)
        val decisions = dedupe(
            unique.filter { line -> decisionWords.any { line.contains(it, ignoreCase = true) } }
        ).map(::cleanDecision).take(8)

        val actions = unique
            .filter { line -> actionWords.any { line.lowercase().contains(it) } }
            .map { line ->
                val mentioned = participants.filter { p -> line.contains(p.name, ignoreCase = true) }.map { it.name }
                ActionRecord(
                    text = cleanAction(line),
                    owner = mentioned.distinct().takeIf { it.isNotEmpty() }?.joinToString(" / "),
                    due = resolveDue(line, meetingStart)
                )
            }
            .let(::dedupeActions)
            .take(12)

        val summaryCandidates = unique
            .filter { it.length in 14..220 }
            .filterNot { candidate -> actions.any { similarity(candidate, it.text) >= .82 } }
            .take(3)
        val summary = when {
            summaryCandidates.isNotEmpty() -> summaryCandidates.joinToString(". ").trim().let { if (it.endsWith('.')) it else "$it." }
            decisions.isNotEmpty() -> decisions.take(2).joinToString(". ").let { if (it.endsWith('.')) it else "$it." }
            transcript.isBlank() -> "Meeting recorded. No live transcript was available; review the saved audio if needed."
            else -> "Meeting recorded."
        }
        return MomResult(summary, decisions, actions)
    }

    private fun normalizeSentence(raw: String): String {
        var s = raw.trim().replace(Regex("\\s+"), " ")
        var changed = true
        while (changed) {
            changed = false
            val lower = s.lowercase()
            fillerStarts.firstOrNull { lower.startsWith(it) }?.let { filler ->
                s = s.drop(filler.length).trimStart(',', ':', '-', ' ')
                changed = true
            }
        }
        return s
    }

    private fun cleanAction(s: String): String = s.trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun cleanDecision(s: String): String = s.trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    private fun dedupe(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (line in lines) {
            val index = out.indexOfFirst { similarity(it, line) >= 0.68 }
            if (index < 0) out += line
            else if (line.length < out[index].length && line.length >= 12) out[index] = line
        }
        return out
    }

    private fun dedupeActions(items: List<ActionRecord>): List<ActionRecord> {
        val out = mutableListOf<ActionRecord>()
        for (item in items) {
            // Deliberately ignore owner while matching: the same task mentioned with different
            // names is one canonical action. Owners/dates are merged instead of duplicating it.
            val existing = out.indexOfFirst { similarity(stripNames(it.text), stripNames(item.text)) >= 0.64 }
            if (existing < 0) out += item
            else {
                val old = out[existing]
                out[existing] = old.copy(
                    text = if (old.text.length <= item.text.length) old.text else item.text,
                    owner = mergeOwners(old.owner, item.owner),
                    due = old.due ?: item.due
                )
            }
        }
        return out
    }

    private fun mergeOwners(a: String?, b: String?): String? = (listOfNotNull(a, b)
        .flatMap { it.split(" / ") }
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase))
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" / ")

    private fun stripNames(s: String): String = s.replace(Regex("^[A-Z][A-Za-z.-]{1,24}\\s+"), "")

    private fun similarity(a: String, b: String): Double {
        val aa = tokens(a)
        val bb = tokens(b)
        if (aa.isEmpty() || bb.isEmpty()) return 0.0
        val intersection = aa.intersect(bb).size.toDouble()
        val union = aa.union(bb).size.toDouble()
        return intersection / max(1.0, union)
    }

    private fun tokens(s: String): Set<String> = s.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length > 2 && it !in setOf("the", "and", "for", "with", "that", "this", "will", "please", "need", "needs", "should") }
        .toSet()

    private fun resolveDue(text: String, meetingStart: Long): String? {
        val zone = ZoneId.systemDefault()
        val base = Instant.ofEpochMilli(meetingStart).atZone(zone).toLocalDate()
        val lower = text.lowercase()
        val date = when {
            "today" in lower -> base
            "tomorrow" in lower -> base.plusDays(1)
            "next week" in lower -> base.plusWeeks(1)
            else -> {
                val target = mapOf(
                    "monday" to DayOfWeek.MONDAY,
                    "tuesday" to DayOfWeek.TUESDAY,
                    "wednesday" to DayOfWeek.WEDNESDAY,
                    "thursday" to DayOfWeek.THURSDAY,
                    "friday" to DayOfWeek.FRIDAY,
                    "saturday" to DayOfWeek.SATURDAY,
                    "sunday" to DayOfWeek.SUNDAY
                ).entries.firstOrNull { it.key in lower }?.value
                target?.let { base.with(TemporalAdjusters.nextOrSame(it)) }
            }
        }
        return date?.format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
    }
}
