package com.mai.app.recording

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Stores a meeting as independently recoverable ADTS/AAC chunks while recording.
 * On a normal Stop (or next app launch after a process death) the valid AAC frames are
 * concatenated into a single playback file. A damaged/incomplete tail frame is ignored.
 */
class RecoverableAudioWriter(
    context: Context,
    private val meetingId: String,
    private val sampleRate: Int = 16_000
) : Closeable {
    companion object {
        private const val PCM_BYTES_PER_SAMPLE = 2L
        private const val CHANNELS = 1L
        private const val CHUNK_SECONDS = 15L

        fun chunkDirectory(context: Context, meetingId: String): File =
            File(File(context.filesDir, "audio"), "$meetingId.chunks")

        fun finalFile(context: Context, meetingId: String): File =
            File(File(context.filesDir, "audio"), "$meetingId.aac")

        fun recover(context: Context, meetingId: String): File? {
            val root = File(context.filesDir, "audio").apply { mkdirs() }
            val chunks = chunkDirectory(context, meetingId)
            val final = finalFile(context, meetingId)
            if (!chunks.exists()) return final.takeIf { it.isFile && it.length() > 0L }

            val sources = chunks.listFiles()
                .orEmpty()
                .filter { it.isFile && (it.extension == "aac" || it.extension == "part") }
                .sortedBy { it.name }
            if (sources.isEmpty()) return final.takeIf { it.isFile && it.length() > 0L }

            val temp = File(root, "$meetingId.recovering")
            runCatching { temp.delete() }
            var validBytes = 0L
            FileOutputStream(temp).use { output ->
                sources.forEach { source -> validBytes += copyValidAdtsFrames(source, output) }
                output.flush()
                runCatching { output.fd.sync() }
            }
            if (validBytes <= 0L) {
                temp.delete()
                return final.takeIf { it.isFile && it.length() > 0L }
            }

            if (final.exists()) final.delete()
            if (!temp.renameTo(final)) {
                FileInputStream(temp).use { input ->
                    FileOutputStream(final).use { output ->
                        input.copyTo(output)
                        output.flush()
                        runCatching { output.fd.sync() }
                    }
                }
                temp.delete()
            }
            chunks.deleteRecursively()
            return final.takeIf { it.isFile && it.length() > 0L }
        }

        private fun copyValidAdtsFrames(source: File, output: FileOutputStream): Long {
            val data = runCatching { source.readBytes() }.getOrDefault(ByteArray(0))
            var offset = 0
            var written = 0L
            while (offset + 7 <= data.size) {
                val sync = ((data[offset].toInt() and 0xFF) == 0xFF) &&
                    ((data[offset + 1].toInt() and 0xF0) == 0xF0)
                if (!sync) {
                    offset++
                    continue
                }
                val frameLength = ((data[offset + 3].toInt() and 0x03) shl 11) or
                    ((data[offset + 4].toInt() and 0xFF) shl 3) or
                    ((data[offset + 5].toInt() and 0xE0) shr 5)
                if (frameLength < 7 || offset + frameLength > data.size) break
                output.write(data, offset, frameLength)
                written += frameLength
                offset += frameLength
            }
            return written
        }
    }

    private val chunkDir = chunkDirectory(context, meetingId).apply {
        deleteRecursively()
        mkdirs()
    }
    private val targetPcmBytes = sampleRate.toLong() * PCM_BYTES_PER_SAMPLE * CHANNELS * CHUNK_SECONDS
    private var chunkIndex = 0
    private var pcmBytesInChunk = 0L
    private var currentPart: File? = null
    private var sink: AacAdtsSink? = null
    private var closed = false
    @Volatile private var checkpointed = false

    init {
        finalFile(context, meetingId).delete()
        openChunk()
    }

    val recoveryPath: String get() = chunkDir.absolutePath
    val hasDurableAudio: Boolean get() = checkpointed || chunkDir.listFiles().orEmpty().any { it.extension == "aac" && it.length() > 0L }

    fun writePcm(data: ByteArray, length: Int) {
        if (closed || length <= 0) return
        sink?.writePcm(data, length)
        pcmBytesInChunk += length
        if (pcmBytesInChunk >= targetPcmBytes) commitChunk(openNext = true)
    }

    fun checkpoint() {
        if (closed) return
        sink?.checkpoint()
        if ((currentPart?.length() ?: 0L) > 512L) checkpointed = true
    }

    fun finalizeFile(context: Context): File? {
        close()
        return recover(context, meetingId)
    }

    override fun close() {
        if (closed) return
        closed = true
        commitChunk(openNext = false)
    }

    private fun openChunk() {
        currentPart = File(chunkDir, "%06d.part".format(chunkIndex))
        sink = AacAdtsSink(currentPart!!, sampleRate)
        pcmBytesInChunk = 0L
    }

    private fun commitChunk(openNext: Boolean) {
        val part = currentPart
        val currentSink = sink
        sink = null
        currentPart = null
        runCatching { currentSink?.close() }
        if (part != null && part.exists() && part.length() > 0L) {
            val committed = File(chunkDir, "%06d.aac".format(chunkIndex))
            if (committed.exists()) committed.delete()
            if (!part.renameTo(committed)) {
                runCatching {
                    FileInputStream(part).use { input ->
                        FileOutputStream(committed).use { output ->
                            input.copyTo(output)
                            output.flush()
                            runCatching { output.fd.sync() }
                        }
                    }
                    part.delete()
                }
            }
            checkpointed = checkpointed || committed.length() > 512L
            chunkIndex++
        }
        if (openNext && !closed) openChunk()
    }
}
