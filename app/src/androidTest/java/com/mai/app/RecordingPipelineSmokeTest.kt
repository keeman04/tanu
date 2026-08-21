package com.mai.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mai.app.data.MaiDb
import com.mai.app.data.Participant
import com.mai.app.pipeline.MaiPipelineDatabase
import com.mai.app.recording.RecordingBus
import com.mai.app.recording.RecordingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingPipelineSmokeTest {
    @Test
    fun startStopPersistsSafeEncodedAudioChunk() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("mai_settings", Context.MODE_PRIVATE).edit()
            .remove("backend_url")
            .remove("backend_token")
            .putInt("audio_retention_days", 0)
            .commit()
        RecordingBus.reset()

        val db = MaiDb(context)
        val id = db.createMeeting(
            "CI Recording Smoke",
            listOf(Participant("CI Participant", "+919999999999"))
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ContextCompat.startForegroundService(
                    activity,
                    Intent(activity, RecordingService::class.java)
                        .setAction(RecordingService.ACTION_START)
                        .putExtra(RecordingService.EXTRA_MEETING_ID, id)
                )
            }

            waitUntil(12_000) { RecordingBus.state.value.active }
            assertTrue("Recording service did not become active", RecordingBus.state.value.active)

            // Silence is sufficient: this test validates the real microphone -> PCM -> encoder -> DB path.
            delay(3_000)

            scenario.onActivity { activity ->
                activity.startService(
                    Intent(activity, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
                )
            }

            waitUntil(70_000) {
                val state = RecordingBus.state.value
                !state.active && state.status == "ready"
            }
        }

        val finalState = RecordingBus.state.value
        assertFalse("Recording service did not stop", finalState.active)
        assertTrue("Recording pipeline did not reach ready: ${finalState.status} / ${finalState.error}", finalState.status == "ready")

        val meeting = db.getMeeting(id)
        assertNotNull(meeting)
        assertNotNull(meeting!!.endedAt)
        assertTrue("Local fallback MOM should be available", meeting.summary.isNotBlank())
        assertTrue("Meeting should be usable without cloud", meeting.status == "ready")

        val audioRoot = meeting.audioPath?.let(::File)
        assertTrue("Audio directory is missing", audioRoot?.isDirectory == true)
        val encoded = audioRoot!!.listFiles { file ->
            file.isFile && file.length() > 0L && file.extension.lowercase() in setOf("ogg", "m4a", "aac")
        }.orEmpty()
        assertTrue("No encoded MAI audio chunk was persisted", encoded.isNotEmpty())

        val pipelineChunks = MaiPipelineDatabase.get(context).dao().chunks(id)
        assertTrue("No persistent chunk record was created", pipelineChunks.isNotEmpty())
        assertTrue("Persisted chunk checksum is missing", pipelineChunks.all { it.sha256.length == 64 })
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs
        while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) delay(100)
    }
}
