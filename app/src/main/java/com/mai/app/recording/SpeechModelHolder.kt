package com.mai.app.recording

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vosk.Model
import org.vosk.android.StorageService

object SpeechModelHolder {
    @Volatile var model: Model? = null
        private set
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    @Volatile private var loading = false

    fun ensure(context: Context) {
        if (model != null || loading) return
        loading = true
        try {
            val assets = context.assets.list("model-en-us")?.toList().orEmpty()
            if (assets.isEmpty()) {
                _error.value = "Offline speech model is missing"
                loading = false
                return
            }
            StorageService.unpack(
                context.applicationContext,
                "model-en-us",
                "model",
                { loaded -> model = loaded; _ready.value = true; loading = false },
                { ex -> _error.value = ex.message ?: "Speech model failed"; loading = false }
            )
        } catch (t: Throwable) {
            _error.value = t.message ?: "Speech model failed"
            loading = false
        }
    }
}
