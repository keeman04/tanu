package com.tanu.app

import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMomFallbackTest {
    @Test
    fun fallbackPreservesUsableTranscript() {
        val mom = LocalMomFallback.generate("We agreed to test TANU tomorrow. Ravi will check the Android build.")
        assertTrue(mom.summary.contains("TANU"))
        assertTrue(mom.followUps.isNotEmpty())
    }
}
