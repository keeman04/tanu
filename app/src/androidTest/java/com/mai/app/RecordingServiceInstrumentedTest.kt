package com.mai.app

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mai.app.data.MaiDb
import com.mai.app.data.Participant
import com.mai.app.recording.RecordingBus
import com.mai.app.recording.RecordingService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingServiceInstrumentedTest {
    @Test
    fun startingForegroundRecordingNeverCrashesTheAppProcess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation

        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) {
            automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            val db = MaiDb(context)
            val id = db.createMeeting(
                "Recorder smoke test",
                listOf(Participant("Test Participant", "+919999999999"))
            )

            ContextCompat.startForegroundService(
                context,
                Intent(context, RecordingService::class.java)
                    .setAction(RecordingService.ACTION_START)
                    .putExtra(RecordingService.EXTRA_MEETING_ID, id)
            )

            // A CI emulator may not expose a real microphone. That is intentional: even
            // when AudioRecord cannot initialize, MAI must report/recover instead of
            // terminating the app process.
            Thread.sleep(4_000)
            assertNotNull(db.getMeeting(id))
            assertTrue(
                RecordingBus.state.value.meetingId == id ||
                    RecordingBus.state.value.status in setOf("error", "ready", "recording", "listening", "voice", "securing")
            )

            context.startService(
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
            )
            Thread.sleep(1_500)
            assertNotNull(db.getMeeting(id))
            db.deleteMeeting(id)
        }
    }
}
