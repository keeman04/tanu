package com.mai.app.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update


data class RecordingSnapshot(
    val active: Boolean = false,
    val meetingId: String? = null,
    val startedAt: Long = 0L,
    val elapsedMs: Long = 0L,
    val level: Float = 0f,
    val levels: List<Float> = List(48) { 0f },
    val transcript: String = "",
    val partial: String = "",
    val status: String = "idle",
    val audioSafe: Boolean = false,
    val storageWarning: String? = null,
    val interruption: String? = null,
    val error: String? = null
)

object RecordingBus {
    private val _state = MutableStateFlow(RecordingSnapshot())
    val state: StateFlow<RecordingSnapshot> = _state
    fun update(block: (RecordingSnapshot) -> RecordingSnapshot) { _state.update(block) }
    fun reset() { _state.value = RecordingSnapshot() }
}
