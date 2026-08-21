package com.mai.app.recording

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vosk.Model
import org.vosk.android.StorageService
import java.util.concurrent.Executors

object SpeechModelHolder {
    @Volatile var model: Model? = null
        private set

    // The app must never be gated by speech-model availability. Audio recording is the
    // source of truth and remains usable while the model is loading or if it fails.
    private val _ready = MutableStateFlow(true)
    val ready: StateFlow<Boolean> = _ready

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Volatile private var loading = false
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mai-speech-model").apply { isDaemon = true }
    }

    fun ensure(context: Context) {
        if (model != null || loading) return
        loading = true
        _error.value = null
        val appContext = context.applicationContext

        executor.execute {
            try {
                val assets = appContext.assets.list("model-en-us")?.toList().orEmpty()
                if (assets.isEmpty()) {
                    _error.value = "Offline speech model is missing"
                    loading = false
                    return@execute
                }

                StorageService.unpack(
                    appContext,
                    "model-en-us",
                    "model",
                    { loaded ->
                        model = loaded
                        _error.value = null
                        loading = false
                    },
                    { ex ->
                        _error.value = ex.message ?: "Speech model failed"
                        loading = false
                    }
                )
            } catch (t: Throwable) {
                _error.value = t.message ?: "Speech model failed"
                loading = false
            }
        }
    }
}
