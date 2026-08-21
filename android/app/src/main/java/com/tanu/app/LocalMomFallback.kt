package com.tanu.app

object LocalMomFallback {
    fun generate(transcript: String): Mom {
        val clean = transcript.trim()
        return Mom(
            summary = if (clean.isBlank()) "No usable transcript was available. Audio is preserved for retry." else clean.replace('\n', ' ').take(700),
            decisions = emptyList(),
            actions = emptyList(),
            followUps = listOf("Cloud MOM is unavailable. Retry when TANU is online for structured decisions and action items."),
            source = "local-fallback"
        )
    }
}
