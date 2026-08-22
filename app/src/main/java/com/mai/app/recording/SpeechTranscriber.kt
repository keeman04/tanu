package com.mai.app.recording

import android.content.Context
import java.io.Closeable

/**
 * V1.1.2 accuracy guard.
 *
 * The previous APK used an offline English Vosk model as a best-effort live preview.
 * That preview is misleading for Tamil/Tanglish and could look like a real transcript.
 * MAI therefore disables device-side speech recognition completely in this build.
 *
 * The original AAC recording remains the source of truth. Final transcription must come
 * from the configured MAI backend using the multilingual OpenAI transcription pipeline.
 * Until a certified realtime multilingual path is connected, live UI should show recording
 * state/waveform only rather than fabricated text.
 */
class SpeechTranscriber(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") private val sampleRate: Int = 16_000,
    private val onUpdate: (transcript: String, partial: String) -> Unit,
    @Suppress("UNUSED_PARAMETER") private val onWarning: (String) -> Unit
) : Closeable {
    init {
        onUpdate("", "")
    }

    fun offer(@Suppress("UNUSED_PARAMETER") data: ByteArray, @Suppress("UNUSED_PARAMETER") length: Int) = Unit

    fun transcript(): String = ""
    fun partial(): String = ""

    override fun close() {
        onUpdate("", "")
    }
}
