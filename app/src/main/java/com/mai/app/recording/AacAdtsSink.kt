package com.mai.app.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream

class AacAdtsSink(file: File, private val sampleRate: Int = 16000, private val channels: Int = 1) : Closeable {
    private val out = FileOutputStream(file)
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private var ptsUs = 0L
    private var closed = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun writePcm(data: ByteArray, length: Int) {
        if (closed || length <= 0) return
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) { drain(false); continue }
            val input = codec.getInputBuffer(index) ?: continue
            input.clear()
            val n = minOf(input.remaining(), length - offset)
            input.put(data, offset, n)
            codec.queueInputBuffer(index, 0, n, ptsUs, 0)
            ptsUs += ((n / 2.0) / sampleRate * 1_000_000L).toLong()
            offset += n
            drain(false)
        }
    }

    fun checkpoint() {
        if (closed) return
        drain(false)
        out.flush()
        runCatching { out.fd.sync() }
    }

    private fun drain(end: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (index >= 0) {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val payload = ByteArray(info.size)
                    buffer.get(payload)
                    out.write(adtsHeader(payload.size + 7))
                    out.write(payload)
                }
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
                if (eos) break
            }
        }
    }

    private fun adtsHeader(packetLen: Int): ByteArray {
        val freqIdx = when (sampleRate) {
            96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4; 32000 -> 5
            24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9; 11025 -> 10; 8000 -> 11; else -> 8
        }
        val profile = 2
        return ByteArray(7).also { h ->
            h[0] = 0xFF.toByte(); h[1] = 0xF1.toByte()
            h[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (channels shr 2)).toByte()
            h[3] = (((channels and 3) shl 6) + (packetLen shr 11)).toByte()
            h[4] = ((packetLen and 0x7FF) shr 3).toByte()
            h[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
            h[6] = 0xFC.toByte()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { out.flush() }
            runCatching { out.fd.sync() }
            runCatching { out.close() }
        }
    }
}
