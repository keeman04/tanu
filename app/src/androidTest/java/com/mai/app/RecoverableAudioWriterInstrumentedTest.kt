package com.mai.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mai.app.recording.RecoverableAudioWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecoverableAudioWriterInstrumentedTest {
    @Test
    fun pcmIsFinalizedIntoPlayableSizedAacAndChunksAreRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "writer-${UUID.randomUUID()}"
        val writer = RecoverableAudioWriter(context, id)
        val pcm = ByteArray(8_192) { index -> ((index * 13) and 0x7F).toByte() }

        repeat(70) {
            writer.writePcm(pcm, pcm.size)
            if (it % 8 == 0) writer.checkpoint()
        }

        val final = writer.finalizeFile(context)
        assertNotNull(final)
        assertTrue(final!!.isFile)
        assertTrue(final.length() > 512L)
        assertFalse(RecoverableAudioWriter.chunkDirectory(context, id).exists())
        final.delete()
    }

    @Test
    fun recoveryIgnoresGarbageAfterLastCompleteAacFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "recover-${UUID.randomUUID()}"
        val writer = RecoverableAudioWriter(context, id)
        val pcm = ByteArray(8_192) { index -> ((index * 7) and 0x5F).toByte() }

        repeat(25) {
            writer.writePcm(pcm, pcm.size)
            if (it % 5 == 0) writer.checkpoint()
        }
        writer.close()

        val chunkDir = RecoverableAudioWriter.chunkDirectory(context, id)
        val last = chunkDir.listFiles().orEmpty().filter { it.isFile }.maxByOrNull { it.name }
        assertNotNull(last)
        FileOutputStream(last!!, true).use { it.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)) }

        val recovered = RecoverableAudioWriter.recover(context, id)
        assertNotNull(recovered)
        assertTrue(recovered!!.length() > 512L)
        assertFalse(chunkDir.exists())
        recovered.delete()
    }

    @Test
    fun newEncoderForSameMeetingPreservesAudioFromPreviousServiceLifetime() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pcm = ByteArray(8_192) { index -> ((index * 11) and 0x6F).toByte() }

        val baselineId = "baseline-${UUID.randomUUID()}"
        val baselineWriter = RecoverableAudioWriter(context, baselineId)
        repeat(35) {
            baselineWriter.writePcm(pcm, pcm.size)
            if (it % 6 == 0) baselineWriter.checkpoint()
        }
        val baseline = baselineWriter.finalizeFile(context)
        assertNotNull(baseline)
        val oneLifetimeBytes = baseline!!.length()
        assertTrue(oneLifetimeBytes > 512L)
        baseline.delete()

        val id = "restart-${UUID.randomUUID()}"
        val firstLifetime = RecoverableAudioWriter(context, id)
        repeat(35) {
            firstLifetime.writePcm(pcm, pcm.size)
            if (it % 6 == 0) firstLifetime.checkpoint()
        }
        // A real process death does not finalize the meeting. close() models the codec/chunk
        // boundary while intentionally leaving the meeting unfinished for the next writer.
        firstLifetime.close()

        val secondLifetime = RecoverableAudioWriter(context, id)
        repeat(35) {
            secondLifetime.writePcm(pcm, pcm.size)
            if (it % 6 == 0) secondLifetime.checkpoint()
        }
        val combined = secondLifetime.finalizeFile(context)
        assertNotNull(combined)
        assertTrue(combined!!.isFile)
        // The final AAC must contain materially more than a single lifetime, proving the
        // second encoder did not erase the chunks produced before the restart.
        assertTrue(combined.length() > (oneLifetimeBytes * 3L / 2L))
        assertFalse(RecoverableAudioWriter.chunkDirectory(context, id).exists())
        combined.delete()
    }
}
