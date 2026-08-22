package com.mai.app.recording

import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.vosk.Model
import org.vosk.android.StorageService
import java.util.concurrent.Executors

object SpeechModelHolder {
    @Volatile var model: Model? = null
        private set

    // The app UI and the recording service must never depend on speech recognition.
    // Audio capture is the source of truth; STT is an optional enhancement.
    private val _ready = MutableStateFlow(true)
    val ready: StateFlow<Boolean> = _ready

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Volatile private var loading = false
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mai-speech-model").apply { isDaemon = true }
    }

    fun ensure(context: Context) {
        context.applicationContext
        _ready.value = true
    }

    /**
     * Loads Vosk only on devices where the native page-size path is known-safe for this
     * candidate. 16 KiB devices still record normally and can use the MAI backend after
     * the meeting; we deliberately prefer a missing live transcript over losing audio.
     */
    fun ensureForRecording(context: Context) {
        val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(4096L)
        if (pageSize > 4096L) {
            _error.value = "Live offline transcript is disabled on this device; audio recording remains active."
            _ready.value = true
            return
        }
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
