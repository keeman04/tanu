package com.tanu.app

object LocalMomFallback {
    fun generate(transcript: String): Mom {
        val clean = transcript.trim()
        val summary = if (clean.isBlank()) {
            "No usable transcript was available. The meeting audio is preserved for retry."
        } else {
            clean.replace('\n', ' ').take(700)
        }
        return Mom(
            summary = summary,
            decisions = emptyList(),
            actions = emptyList(),
            followUps = listOf("Cloud MOM was unavailable. Retry when TANU is online for structured decisions and action items."),
            source = "local-fallback"
        )
    }
}
