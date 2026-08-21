package com.mai.app.intelligence

import com.mai.app.data.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MomEngineTest {
    private val start = 1_787_286_600_000L

    @Test
    fun extractsAndDedupesActions() {
        val people = listOf(Participant("Ravi", "+919999999999"))
        val text = "Ravi will send the revised quotation tomorrow. Ravi will send the revised quotation tomorrow. We agreed option B is final."
        val result = MomEngine.generate(text, people, start)
        assertEquals(1, result.actions.size)
        assertEquals("Ravi", result.actions.first().owner)
        assertTrue(result.decisions.isNotEmpty())
    }

    @Test
    fun emptyTranscriptProducesSafeMom() {
        val result = MomEngine.generate("", emptyList(), start)
        assertEquals("Meeting recorded.", result.summary)
        assertTrue(result.decisions.isEmpty())
        assertTrue(result.actions.isEmpty())
        assertTrue(result.followUps.isEmpty())
    }

    @Test
    fun ownerMatchingIsCaseInsensitive() {
        val people = listOf(Participant("Ravi", "+919999999999"))
        val result = MomEngine.generate("rAvI will prepare the launch note tomorrow", people, start)
        assertEquals(1, result.actions.size)
        assertEquals("Ravi", result.actions.first().owner)
    }

    @Test
    fun tomorrowCreatesDueDate() {
        val result = MomEngine.generate("Ravi will send the file tomorrow", listOf(Participant("Ravi", "9999999999")), start)
        assertEquals(1, result.actions.size)
        assertFalse(result.actions.first().due.isNullOrBlank())
    }

    @Test
    fun neutralDiscussionDoesNotCreateAction() {
        val result = MomEngine.generate("The team discussed the new venue and the weather was pleasant", emptyList(), start)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun decisionLanguageIsDetected() {
        val result = MomEngine.generate("We confirmed the Saturday launch and selected option B", emptyList(), start)
        assertEquals(1, result.decisions.size)
        assertTrue(result.decisions.first().contains("confirmed", ignoreCase = true))
    }

    @Test
    fun unknownOwnerRemainsUnassigned() {
        val result = MomEngine.generate("Someone should prepare the final document", listOf(Participant("Ravi", "9999999999")), start)
        assertEquals(1, result.actions.size)
        assertNull(result.actions.first().owner)
    }

    @Test
    fun followUpLanguageIsSeparated() {
        val result = MomEngine.generate("We should follow up with the venue tomorrow. We will revisit the menu in the next meeting.", emptyList(), start)
        assertTrue(result.followUps.isNotEmpty())
    }

    @Test
    fun unicodeTamilLinesCanBeDeduped() {
        val text = "நாளை விலை பற்றி மீண்டும் பேசலாம். நாளை விலை பற்றி மீண்டும் பேசலாம்."
        val result = MomEngine.generate(text, emptyList(), start)
        assertFalse(result.summary.contains("நாளை விலை பற்றி மீண்டும் பேசலாம். நாளை விலை பற்றி மீண்டும் பேசலாம்"))
    }
}
