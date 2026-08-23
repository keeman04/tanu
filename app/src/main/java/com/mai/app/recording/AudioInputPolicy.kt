package com.mai.app.recording

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord

/**
 * Applies a best-effort input preference. Android may still override routing for calls or
 * device policy, so MAI continues monitoring the actual routedDevice independently.
 */
object AudioInputPolicy {
    private val externalPriority = listOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    )

    fun apply(context: Context, recorder: AudioRecord): String? {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList() }
            .getOrDefault(emptyList())
        if (inputs.isEmpty()) return null

        val prefs = context.getSharedPreferences("mai_settings", Context.MODE_PRIVATE)
        val savedId = prefs.getInt("preferred_audio_device_id", -1)
        val preferred = inputs.firstOrNull { it.id == savedId }
            ?: externalPriority.firstNotNullOfOrNull { type -> inputs.firstOrNull { it.type == type } }
            ?: inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?: return null

        val accepted = runCatching { recorder.setPreferredDevice(preferred) }.getOrDefault(false)
        if (!accepted) return null
        prefs.edit().putInt("preferred_audio_device_id", preferred.id).apply()
        return label(preferred)
    }

    fun label(device: AudioDeviceInfo?): String = when (device?.type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset microphone"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB microphone"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone microphone"
        null -> "microphone"
        else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "microphone"
    }
}
