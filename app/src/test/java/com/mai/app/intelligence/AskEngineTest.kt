package com.mai.app.intelligence

import com.mai.app.data.ActionRecord
import com.mai.app.data.MeetingRecord
import com.mai.app.data.Participant
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class AskEngineTest {
    private fun meeting(
        title: String = "Pricing Review",
        summary: String = "The team agreed to test the new family package price.",
        decisions: List<String> = listOf("We agreed the family package price is 1,499."),
        actions: List<ActionRecord> = emptyList(),
        transcript: String = "Ravi discussed pricing and the September launch plan."
    ) = MeetingRecord(
        id = "meeting-1",
        title = title,
        startedAt = System.currentTimeMillis(),
        endedAt = System.currentTimeMillis(),
        participants = listOf(Participant("Ravi", "+919999999999")),
        transcript = transcript,
        summary = summary,
        decisions = decisions,
        actions = actions,
        followUps = emptyList(),
        audioPath = null,
        audioExpiresAt = null,
        status = "ready"
    )

    @Test
    fun findsPricingDecisionFromSavedEvidence() {
        val answer = AskEngine.localAnswer("What did we decide about pricing?", listOf(meeting()))
        assertTrue(answer.answer.contains("1,499"))
        assertTrue(answer.sources.contains("Pricing Review"))
    }

    @Test
    fun findsParticipantMention() {
        val answer = AskEngine.localAnswer("Find meetings mentioning Ravi", listOf(meeting()))
        assertTrue(answer.answer.contains("Pricing Review"))
    }

    @Test
    fun reportsOverdueActions() {
        val due = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
        val item = ActionRecord("Send the revised quotation", "Ravi", due)
        val answer = AskEngine.localAnswer("Show overdue commitments", listOf(meeting(actions = listOf(item))))
        assertTrue(answer.answer.contains("Send the revised quotation"))
        assertTrue(answer.answer.contains("Ravi"))
    }
}
