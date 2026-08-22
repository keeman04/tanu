package com.mai.app.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.StatFs
import androidx.core.content.ContextCompat

object RecordingPreflight {
    private const val MIB = 1024L * 1024L
    const val START_MIN_BYTES = 100L * MIB
    const val WARNING_BYTES = 250L * MIB
    const val CRITICAL_BYTES = 40L * MIB

    data class StorageState(
        val freeBytes: Long,
        val estimatedMinutes: Long,
        val warning: String?,
        val critical: Boolean
    )

    fun blockingIssue(context: Context): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return "Microphone permission is required to record."
        }
        val storage = storageState(context)
        if (storage.freeBytes < START_MIN_BYTES) {
            return "Not enough free storage to start safely. Free at least 100 MB and try again."
        }
        return null
    }

    fun storageState(context: Context): StorageState {
        val free = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        // Conservative estimate at 64 kbps even though the current AAC target is lower.
        val minutes = if (free == Long.MAX_VALUE) Long.MAX_VALUE else free / 8_000L / 60L
        val critical = free != Long.MAX_VALUE && free < CRITICAL_BYTES
        val warning = when {
            free == Long.MAX_VALUE -> null
            critical -> "Storage critically low. MAI will stop safely to protect the recording."
            free < WARNING_BYTES -> "Storage low · about ${minutes.coerceAtLeast(1)} min conservative recording capacity left."
            else -> null
        }
        return StorageState(free, minutes, warning, critical)
    }
}
