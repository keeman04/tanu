package com.tanu.app

import android.content.Context
import java.io.File
import java.security.MessageDigest

object PipelineFiles {
    fun meetingDir(context: Context, meetingId: String): File =
        File(context.filesDir, "meetings/$meetingId").apply { mkdirs() }

    fun audioDir(context: Context, meetingId: String): File =
        File(meetingDir(context, meetingId), "audio").apply { mkdirs() }

    fun tempPcm(context: Context, meetingId: String, sequence: Int): File =
        File(audioDir(context, meetingId), "chunk-${sequence.toString().padStart(6, '0')}.pcm.tmp")

    fun encodedBase(context: Context, meetingId: String, sequence: Int): File =
        File(audioDir(context, meetingId), "chunk-${sequence.toString().padStart(6, '0')}.audio")

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
