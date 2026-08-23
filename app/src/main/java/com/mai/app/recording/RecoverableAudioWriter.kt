package com.mai.app.recording

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Uses one continuous AAC encoder per service lifetime while rotating ADTS output every
 * ~15 seconds. If Android kills and redelivers the recording service, already committed
 * audio is moved into a crash-safe carry file before a fresh encoder is created. Finalize
 * concatenates the carry + current encoder output, so process restart never overwrites the
 * earlier part of the meeting.
 */
class RecoverableAudioWriter(
    context: Context,
    private val meetingId: String,
    private val sampleRate: Int = 16_000,
    private val channels: Int = 1
) : Closeable {
    companion object {
        private const val PCM_BYTES_PER_SAMPLE = 2L
        private const val CHUNK_SECONDS = 15L

        private data class CarryState(
            val committedLength: Long,
            val consumedLength: Long,
            val consumedModified: Long
        )

        fun chunkDirectory(context: Context, meetingId: String): File =
            File(File(context.filesDir, "audio"), "$meetingId.chunks")

        fun finalFile(context: Context, meetingId: String): File =
            File(File(context.filesDir, "audio"), "$meetingId.aac")

        private fun carryFile(context: Context, meetingId: String): File =
            File(File(context.filesDir, "audio"), "$meetingId.carry.aac")

        private fun carryStateFile(context: Context, meetingId: String): File =
            File(File(context.filesDir, "audio"), "$meetingId.carry.state")

        /** Recover valid ADTS frames from checkpoint chunks into the normal final path. */
        fun recover(context: Context, meetingId: String): File? {
            val root = File(context.filesDir, "audio").apply { mkdirs() }
            val chunks = chunkDirectory(context, meetingId)
            val final = finalFile(context, meetingId)
            if (!chunks.exists()) return final.takeIf { it.isFile && it.length() > 0L }

            val sources = chunks.listFiles()
                .orEmpty()
                .filter { it.isFile && (it.extension == "aac" || it.extension == "part") }
                .sortedBy { it.name }
            if (sources.isEmpty()) {
                chunks.deleteRecursively()
                return final.takeIf { it.isFile && it.length() > 0L }
            }

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
                chunks.deleteRecursively()
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

        /**
         * Called before a new encoder starts. Existing final audio is absorbed first, then
         * any newer checkpoint chunks are recovered and absorbed. A small sidecar records
         * the fsynced carry length so a process death during append can be rolled back and
         * retried without duplication or truncation.
         */
        private fun preserveExistingBeforeEncoder(context: Context, meetingId: String) {
            val root = File(context.filesDir, "audio").apply { mkdirs() }
            normalizeCarry(context, meetingId)

            finalFile(context, meetingId)
                .takeIf { it.isFile && it.length() > 0L }
                ?.let { absorbIntoCarry(context, meetingId, it) }

            if (chunkDirectory(context, meetingId).exists()) {
                recover(context, meetingId)?.let { absorbIntoCarry(context, meetingId, it) }
            }
            root.mkdirs()
        }

        private fun normalizeCarry(context: Context, meetingId: String): CarryState {
            val carry = carryFile(context, meetingId)
            val stateFile = carryStateFile(context, meetingId)
            val state = readCarryState(stateFile)
            if (!carry.exists()) {
                if (stateFile.exists()) stateFile.delete()
                return CarryState(0L, 0L, 0L)
            }

            // A carry without a sidecar can only be an interrupted first append from this
            // implementation. No bytes were committed yet, so discard the partial append.
            if (state == null) {
                runCatching { RandomAccessFile(carry, "rw").use { it.setLength(0L) } }
                val reset = CarryState(0L, 0L, 0L)
                writeCarryState(stateFile, reset)
                return reset
            }

            val safeLength = state.committedLength.coerceIn(0L, carry.length())
            if (carry.length() != safeLength) {
                runCatching { RandomAccessFile(carry, "rw").use { it.setLength(safeLength) } }
            }
            return state.copy(committedLength = safeLength)
        }

        private fun absorbIntoCarry(context: Context, meetingId: String, recovered: File) {
            if (!recovered.isFile || recovered.length() <= 0L) return
            val carry = carryFile(context, meetingId)
            val stateFile = carryStateFile(context, meetingId)
            var state = normalizeCarry(context, meetingId)
            if (!stateFile.exists()) {
                writeCarryState(stateFile, state)
            }

            val signatureLength = recovered.length()
            val signatureModified = recovered.lastModified()
            val alreadyCommitted =
                state.consumedLength == signatureLength &&
                    state.consumedModified == signatureModified &&
                    carry.isFile && carry.length() == state.committedLength

            if (!alreadyCommitted) {
                FileInputStream(recovered).use { input ->
                    FileOutputStream(carry, true).use { output ->
                        input.copyTo(output)
                        output.flush()
                        runCatching { output.fd.sync() }
                    }
                }
                state = CarryState(carry.length(), signatureLength, signatureModified)
                writeCarryState(stateFile, state)
            }

            // Delete only after the carry length + source signature have been committed.
            // If the process dies before this point, the next startup recognizes the same
            // source signature and will not append it twice.
            if (recovered.delete()) {
                writeCarryState(stateFile, state.copy(consumedLength = 0L, consumedModified = 0L))
            }
        }

        private fun finishCarry(context: Context, meetingId: String): File? {
            val carry = carryFile(context, meetingId)
            val final = finalFile(context, meetingId)
            val stateFile = carryStateFile(context, meetingId)
            normalizeCarry(context, meetingId)
            if (!carry.isFile || carry.length() <= 0L) {
                stateFile.delete()
                return final.takeIf { it.isFile && it.length() > 0L }
            }

            if (final.exists()) final.delete()
            if (!carry.renameTo(final)) {
                FileInputStream(carry).use { input ->
                    FileOutputStream(final).use { output ->
                        input.copyTo(output)
                        output.flush()
                        runCatching { output.fd.sync() }
                    }
                }
                carry.delete()
            }
            stateFile.delete()
            return final.takeIf { it.isFile && it.length() > 0L }
        }

        private fun readCarryState(file: File): CarryState? = runCatching {
            val parts = file.readText().trim().split('|')
            if (parts.size != 3) return@runCatching null
            CarryState(parts[0].toLong(), parts[1].toLong(), parts[2].toLong())
        }.getOrNull()

        private fun writeCarryState(file: File, state: CarryState) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use { output ->
                output.write("${state.committedLength}|${state.consumedLength}|${state.consumedModified}".toByteArray())
                output.flush()
                runCatching { output.fd.sync() }
            }
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
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

    private val appContext = context.applicationContext
    private val chunkDir = chunkDirectory(appContext, meetingId).apply {
        preserveExistingBeforeEncoder(appContext, meetingId)
        deleteRecursively()
        mkdirs()
    }
    private val targetPcmBytes = sampleRate.toLong() * PCM_BYTES_PER_SAMPLE * channels * CHUNK_SECONDS
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private var chunkIndex = 0
    private var pcmBytesInChunk = 0L
    private var ptsUs = 0L
    private var currentPart: File? = null
    private var output: FileOutputStream? = null
    private var closed = false
    @Volatile private var checkpointed = carryFile(appContext, meetingId).let { it.isFile && it.length() > 512L }

    init {
        finalFile(appContext, meetingId).delete()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        openChunk()
    }

    val recoveryPath: String get() = chunkDir.absolutePath
    val hasDurableAudio: Boolean get() = checkpointed || chunkDir.listFiles().orEmpty().any { it.extension == "aac" && it.length() > 0L }

    fun writePcm(data: ByteArray, length: Int) {
        if (closed || length <= 0) return
        var offset = 0
        while (offset < length) {
            val inputIndex = codec.dequeueInputBuffer(10_000L)
            if (inputIndex < 0) {
                drain(end = false, timeoutUs = 0L)
                continue
            }
            val input = codec.getInputBuffer(inputIndex) ?: continue
            input.clear()
            val count = minOf(input.remaining(), length - offset)
            input.put(data, offset, count)
            codec.queueInputBuffer(inputIndex, 0, count, ptsUs, 0)
            ptsUs += ((count / 2.0 / channels) / sampleRate * 1_000_000L).toLong()
            offset += count
            pcmBytesInChunk += count
            drain(end = false, timeoutUs = 0L)

            if (pcmBytesInChunk >= targetPcmBytes) {
                drain(end = false, timeoutUs = 10_000L)
                commitChunk(openNext = true)
            }
        }
    }

    fun checkpoint() {
        if (closed) return
        drain(end = false, timeoutUs = 0L)
        output?.flush()
        runCatching { output?.fd?.sync() }
        if ((currentPart?.length() ?: 0L) > 512L) checkpointed = true
    }

    fun finalizeFile(context: Context): File? {
        close()
        recover(context, meetingId)?.let { absorbIntoCarry(context, meetingId, it) }
        return finishCarry(context, meetingId)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            queueEndOfStream()
            drain(end = true, timeoutUs = 10_000L)
        } finally {
            commitChunk(openNext = false)
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    private fun openChunk() {
        currentPart = File(chunkDir, "%06d.part".format(chunkIndex))
        output = FileOutputStream(currentPart!!)
        pcmBytesInChunk = 0L
    }

    private fun commitChunk(openNext: Boolean) {
        val part = currentPart
        val stream = output
        output = null
        currentPart = null
        runCatching { stream?.flush() }
        runCatching { stream?.fd?.sync() }
        runCatching { stream?.close() }

        if (part != null && part.exists() && part.length() > 0L) {
            val committed = File(chunkDir, "%06d.aac".format(chunkIndex))
            if (committed.exists()) committed.delete()
            if (!part.renameTo(committed)) {
                runCatching {
                    FileInputStream(part).use { input ->
                        FileOutputStream(committed).use { target ->
                            input.copyTo(target)
                            target.flush()
                            runCatching { target.fd.sync() }
                        }
                    }
                    part.delete()
                }
            }
            checkpointed = checkpointed || committed.length() > 512L
            chunkIndex++
        } else {
            part?.delete()
        }
        if (openNext && !closed) openChunk()
    }

    private fun queueEndOfStream() {
        repeat(20) {
            val index = codec.dequeueInputBuffer(10_000L)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
            drain(end = false, timeoutUs = 0L)
        }
    }

    private fun drain(end: Boolean, timeoutUs: Long) {
        val info = MediaCodec.BufferInfo()
        var idlePolls = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10_000L else timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end || ++idlePolls >= 30) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index >= 0 -> {
                    idlePolls = 0
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val payload = ByteArray(info.size)
                        buffer.get(payload)
                        output?.write(adtsHeader(payload.size + 7))
                        output?.write(payload)
                    }
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (eos) return
                }
            }
        }
    }

    private fun adtsHeader(packetLen: Int): ByteArray {
        val freqIdx = when (sampleRate) {
            96_000 -> 0; 88_200 -> 1; 64_000 -> 2; 48_000 -> 3; 44_100 -> 4; 32_000 -> 5
            24_000 -> 6; 22_050 -> 7; 16_000 -> 8; 12_000 -> 9; 11_025 -> 10; 8_000 -> 11; else -> 8
        }
        val profile = 2
        return ByteArray(7).also { header ->
            header[0] = 0xFF.toByte()
            header[1] = 0xF1.toByte()
            header[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (channels shr 2)).toByte()
            header[3] = (((channels and 3) shl 6) + (packetLen shr 11)).toByte()
            header[4] = ((packetLen and 0x7FF) shr 3).toByte()
            header[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
            header[6] = 0xFC.toByte()
        }
    }
}
