package com.tanu.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

class SpeechChunkEncoder {
    data class Result(val file: File, val codec: String, val mimeType: String)

    fun encode(pcmFile: File, outputBase: File): Result {
        return if (supports(MediaFormat.MIMETYPE_AUDIO_OPUS)) {
            val out = File(outputBase.parentFile, outputBase.nameWithoutExtension + ".ogg")
            encodeWithCodec(
                pcmFile = pcmFile,
                outputFile = out,
                mime = MediaFormat.MIMETYPE_AUDIO_OPUS,
                bitrate = 24_000,
                muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
            )
            Result(out, "opus", "audio/ogg")
        } else {
            val out = File(outputBase.parentFile, outputBase.nameWithoutExtension + ".m4a")
            encodeWithCodec(
                pcmFile = pcmFile,
                outputFile = out,
                mime = MediaFormat.MIMETYPE_AUDIO_AAC,
                bitrate = 32_000,
                muxerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            Result(out, "aac", "audio/mp4")
        }
    }

    private fun supports(mime: String): Boolean {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        return list.codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }

    private fun encodeWithCodec(
        pcmFile: File,
        outputFile: File,
        mime: String,
        bitrate: Int,
        muxerFormat: Int
    ) {
        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) outputFile.delete()

        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        val codec = MediaCodec.createEncoderByType(mime)
        val muxer = MediaMuxer(outputFile.absolutePath, muxerFormat)
        var muxerStarted = false
        var trackIndex = -1
        var inputEnded = false
        var outputEnded = false
        var pcmBytesQueued = 0L
        val info = MediaCodec.BufferInfo()

        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            FileInputStream(pcmFile).use { input ->
                val scratch = ByteArray(8192)
                while (!outputEnded) {
                    if (!inputEnded) {
                        val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val buffer = codec.getInputBuffer(inputIndex) ?: error("No encoder input buffer")
                            buffer.clear()
                            val max = minOf(buffer.remaining(), scratch.size)
                            val read = input.read(scratch, 0, max)
                            if (read < 0) {
                                val pts = presentationTimeUs(pcmBytesQueued)
                                codec.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEnded = true
                            } else {
                                buffer.put(scratch, 0, read)
                                val pts = presentationTimeUs(pcmBytesQueued)
                                codec.queueInputBuffer(inputIndex, 0, read, pts, 0)
                                pcmBytesQueued += read
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "Encoder output format changed twice" }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        else -> if (outputIndex >= 0) {
                            val encoded = codec.getOutputBuffer(outputIndex) ?: error("No encoder output buffer")
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(muxerStarted) { "Muxer has not started" }
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, encoded, info)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
        check(outputFile.exists() && outputFile.length() > 0) { "Encoded audio file is empty" }
    }

    private fun presentationTimeUs(pcmBytes: Long): Long {
        val frames = pcmBytes / (BYTES_PER_SAMPLE * CHANNELS)
        return frames * 1_000_000L / SAMPLE_RATE
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val BYTES_PER_SAMPLE = 2
        private const val TIMEOUT_US = 10_000L
    }
}
