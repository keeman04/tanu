package com.mai.app.recording

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Detects Android microphone silencing (for example a call/competing recorder) and
 * headset/Bluetooth/USB route changes without requiring phone-state permission.
 */
class MicHealthMonitor(
    context: Context,
    private val recorder: AudioRecord,
    private val onEvent: (message: String, silenced: Boolean) -> Unit
) : Closeable {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val silenced = AtomicBoolean(false)
    private var lastRouteKey: String? = routeKey(recorder.routedDevice)
    private var registered = false

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
            val own = configs.orEmpty().firstOrNull { it.clientAudioSessionId == recorder.audioSessionId }
            val nowSilenced = own?.isClientSilenced == true
            val previous = silenced.getAndSet(nowSilenced)
            if (nowSilenced != previous) {
                onEvent(
                    if (nowSilenced) {
                        "Microphone temporarily unavailable because a call or another app has capture priority. Audio during this interruption cannot be captured; MAI will resume automatically when Android restores the mic."
                    } else {
                        "Microphone restored. MAI resumed recording automatically."
                    },
                    nowSilenced
                )
            }
        }
    }

    init {
        AudioInputPolicy.apply(context, recorder)?.let { preferred ->
            onEvent("Using $preferred for this meeting.", false)
        }
        lastRouteKey = routeKey(recorder.routedDevice)
        runCatching {
            audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            registered = true
        }
    }

    fun isSilenced(): Boolean = silenced.get()

    fun pollRouteChange(): String? {
        val device = recorder.routedDevice
        val key = routeKey(device)
        val previous = lastRouteKey
        if (key == previous) return null
        lastRouteKey = key
        if (previous == null) return null
        return "Audio input changed to ${AudioInputPolicy.label(device)}. MAI continued recording on the active Android input."
    }

    override fun close() {
        if (registered) runCatching { audioManager.unregisterAudioRecordingCallback(callback) }
        registered = false
    }

    private fun routeKey(device: AudioDeviceInfo?): String? = device?.let { "${it.id}:${it.type}" }
}
