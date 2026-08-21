package com.mai.app.intelligence

import com.mai.app.data.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MomEngineTest {
    @Test fun extractsAndDedupesActions() {
        val p = listOf(Participant("Ravi", "+919999999999"))
        val text = "Ravi will send the revised quotation tomorrow. Ravi will send the revised quotation tomorrow. We agreed option B is final."
        val result = MomEngine.generate(text, p, 1_787_286_600_000L)
        assertEquals(1, result.actions.size)
        assertEquals("Ravi", result.actions.first().owner)
        assertTrue(result.decisions.isNotEmpty())
    }
}
