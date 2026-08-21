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

    // MAI's home screen and meeting setup must never depend on the speech engine.
    // Audio is the source of truth; speech recognition is an optional enhancement.
    private val _ready = MutableStateFlow(true)
    val ready: StateFlow<Boolean> = _ready

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Volatile private var loading = false
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mai-speech-model").apply { isDaemon = true }
    }

    /**
     * Launch-safe entry point used by the UI. Intentionally does not touch Vosk, JNA,
     * native libraries, or the bundled model. This keeps a normal app cold start isolated
     * from speech-engine compatibility problems on individual Android devices.
     */
    fun ensure(context: Context) {
        context.applicationContext
        _ready.value = true
    }

    /**
     * Called only after the user has explicitly started a meeting. Loading happens on a
     * background thread and recording does not wait for it.
     */
    fun ensureForRecording(context: Context) {
        loadModel(context.applicationContext)
    }

    private fun loadModel(appContext: Context) {
        if (model != null || loading) return
        loading = true
        _error.value = null

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
