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
                        "Microphone temporarily interrupted by a call or another app. MAI is preserving the meeting."
                    } else {
                        "Microphone restored. Recording has resumed."
                    },
                    nowSilenced
                )
            }
        }
    }

    init {
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
        return "Audio input changed to ${routeLabel(device)}. Recording continued."
    }

    override fun close() {
        if (registered) runCatching { audioManager.unregisterAudioRecordingCallback(callback) }
        registered = false
    }

    private fun routeKey(device: AudioDeviceInfo?): String? =
        device?.let { "${it.id}:${it.type}" }

    private fun routeLabel(device: AudioDeviceInfo?): String = when (device?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired headphones"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone microphone"
        null -> "microphone"
        else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "microphone"
    }
}
