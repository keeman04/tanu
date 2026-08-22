package com.mai.app.intelligence

import com.mai.app.data.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomEngineTest {
    private val start = 1_787_286_600_000L

    @Test fun extractsAndDedupesActions() {
        val people = listOf(Participant("Ravi", "+919999999999"))
        val text = "Ravi will send the revised quotation tomorrow. Ravi will send the revised quotation tomorrow. We agreed option B is final."
        val result = MomEngine.generate(text, people, start)
        assertEquals(1, result.actions.size)
        assertEquals("Ravi", result.actions.first().owner)
        assertTrue(result.decisions.isNotEmpty())
    }

    @Test fun emptyTranscriptProducesNoInventedMom() {
        val result = MomEngine.generate("", emptyList(), start)
        assertTrue(result.summary.contains("pending multilingual processing", ignoreCase = true))
        assertTrue(result.decisions.isEmpty())
        assertTrue(result.actions.isEmpty())
    }

    @Test fun ownerMatchingIsCaseInsensitive() {
        val result = MomEngine.generate("rAvI will prepare the launch note tomorrow", listOf(Participant("Ravi", "+919999999999")), start)
        assertEquals("Ravi", result.actions.first().owner)
    }

    @Test fun tomorrowCreatesDueDate() {
        val result = MomEngine.generate("Ravi will send the file tomorrow", listOf(Participant("Ravi", "9999999999")), start)
        assertFalse(result.actions.first().due.isNullOrBlank())
    }

    @Test fun neutralDiscussionDoesNotCreateAction() {
        val result = MomEngine.generate("The team discussed the new venue and the weather was pleasant", emptyList(), start)
        assertTrue(result.actions.isEmpty())
    }

    @Test fun decisionLanguageIsDetected() {
        val result = MomEngine.generate("We confirmed the Saturday launch and selected option B", emptyList(), start)
        assertEquals(1, result.decisions.size)
    }

    @Test fun unknownOwnerRemainsUnassigned() {
        val result = MomEngine.generate("Someone should prepare the final document", listOf(Participant("Ravi", "9999999999")), start)
        assertNull(result.actions.first().owner)
    }

    @Test fun sameTaskWithDifferentOwnersBecomesOneAction() {
        val people = listOf(Participant("Ravi", "9999999999"), Participant("Manoj", "8888888888"))
        val result = MomEngine.generate(
            "Ravi will send the revised quotation tomorrow. Manoj will send the revised quotation tomorrow.",
            people,
            start
        )
        assertEquals(1, result.actions.size)
        assertTrue(result.actions.first().owner.orEmpty().contains("Ravi"))
        assertTrue(result.actions.first().owner.orEmpty().contains("Manoj"))
    }

    @Test fun nextWeekCreatesDueDate() {
        val result = MomEngine.generate("Ravi should schedule the review next week", listOf(Participant("Ravi", "9999999999")), start)
        assertFalse(result.actions.first().due.isNullOrBlank())
    }
}
