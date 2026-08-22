package com.mai.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mai.app.data.ActionRecord
import com.mai.app.data.MaiDb
import com.mai.app.data.Participant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaiDataInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun meetingSearchActionAndDeleteLifecycleWorks() {
        val db = MaiDb(context)
        val id = db.createMeeting(
            "Pricing Review",
            listOf(Participant("Ravi", "+919999999999")),
            startedAt = 1_800_000_000_000L
        )
        db.finishMeeting(
            id = id,
            endedAt = 1_800_000_060_000L,
            transcript = "We agreed the new pricing. Ravi will share the quotation tomorrow.",
            summary = "The new pricing was agreed.",
            decisions = listOf("New pricing approved"),
            actions = listOf(ActionRecord("Share the quotation", "Ravi", "24 Aug 2026")),
            audioPath = null,
            audioExpiresAt = null
        )

        assertTrue(db.searchMeetings("pricing").any { it.id == id })
        assertTrue(db.searchMeetings("Ravi").any { it.id == id })

        db.updateActionDone(id, 0, true)
        assertEquals(true, db.getMeeting(id)?.actions?.first()?.done)

        db.deleteMeeting(id)
        assertNull(db.getMeeting(id))
    }

    @Test
    fun interruptedMeetingKeepsCheckpointedTranscript() {
        val db = MaiDb(context)
        val id = db.createMeeting("Recovery Test", listOf(Participant("Manoj", "9999999999")))
        db.checkpointMeeting(id, "Checkpointed transcript survives", null)
        db.recoverInterruptedMeetings()

        val recovered = db.getMeeting(id)
        assertEquals("interrupted", recovered?.status)
        assertEquals("Checkpointed transcript survives", recovered?.transcript)
        db.deleteMeeting(id)
    }

    @Test
    fun meetingDoesNotBecomeReadyUntilFinalIntelligenceArrives() {
        val db = MaiDb(context)
        val id = db.createMeeting("Processing State", listOf(Participant("Ravi", "9999999999")))
        db.finishMeeting(
            id = id,
            endedAt = 1_800_000_060_000L,
            transcript = "Live preview only",
            summary = "Audio saved safely. Processing.",
            decisions = emptyList(),
            actions = emptyList(),
            audioPath = null,
            audioExpiresAt = null,
            status = "processing"
        )
        assertEquals("processing", db.getMeeting(id)?.status)

        db.replaceIntelligence(
            id = id,
            transcript = "Verified final transcript",
            summary = "Verified final MOM",
            decisions = listOf("Approved"),
            actions = listOf(ActionRecord("Send final file", "Ravi", "2026-08-24"))
        )
        assertEquals("ready", db.getMeeting(id)?.status)
        assertEquals("Verified final transcript", db.getMeeting(id)?.transcript)
        db.deleteMeeting(id)
    }
}
