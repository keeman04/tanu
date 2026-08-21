package com.tanu.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var store: MeetingStore
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var mom: TextView
    private lateinit var title: EditText
    private lateinit var stopButton: Button
    private lateinit var retryButton: Button
    private lateinit var shareButton: Button
    private var meetingId: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val incomingMeeting = intent?.getStringExtra(RecordingService.EXTRA_MEETING_ID) ?: return
            if (incomingMeeting != meetingId) return
            val state = intent.getStringExtra(RecordingService.EXTRA_STATE).orEmpty()
            val message = intent.getStringExtra(RecordingService.EXTRA_MESSAGE).orEmpty()
            val queued = intent.getIntExtra(RecordingService.EXTRA_QUEUED, 0)
            status.text = "${state.uppercase()}\n$message\nQueued: $queued"
            refreshFromStore()
            stopButton.isEnabled = state == "recording"
            retryButton.isEnabled = state in setOf("failed", "partial", "ready")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = MeetingStore(this)
        meetingId = getPreferences(MODE_PRIVATE).getString("meeting_id", null)
        setContentView(buildUi())
        requestRequiredPermissions()
        refreshFromStore()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RecordingService.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshFromStore()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        root.addView(TextView(this).apply {
            text = "TANU Core Pipeline"
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = "Android Phase 1 • in-person meeting recorder"
            textSize = 15f
        })
        title = EditText(this).apply {
            hint = "Meeting title"
            setText("TANU Meeting")
        }
        root.addView(title)

        val startButton = Button(this).apply {
            text = "Start Meeting"
            setOnClickListener { startMeeting() }
        }
        root.addView(startButton)

        stopButton = Button(this).apply {
            text = "Stop & Generate MOM"
            isEnabled = false
            setOnClickListener { stopMeeting() }
        }
        root.addView(stopButton)

        retryButton = Button(this).apply {
            text = "Retry Processing Saved Audio"
            isEnabled = meetingId != null
            setOnClickListener { retryProcessing() }
        }
        root.addView(retryButton)

        shareButton = Button(this).apply {
            text = "Share MOM"
            isEnabled = false
            setOnClickListener { shareMom() }
        }
        root.addView(shareButton)

        status = TextView(this).apply {
            text = "READY\nTap Start Meeting."
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }
        root.addView(status)

        root.addView(TextView(this).apply { text = "TRANSCRIPT"; textSize = 18f })
        transcript = TextView(this).apply {
            text = "Transcript will appear here as chunks finish."
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 28)
        }
        root.addView(transcript)

        root.addView(TextView(this).apply { text = "MINUTES OF MEETING"; textSize = 18f })
        mom = TextView(this).apply {
            text = "MOM will appear after Stop."
            setTextIsSelectable(true)
            setPadding(0, 12, 0, 40)
        }
        root.addView(mom)

        return ScrollView(this).apply { addView(root) }
    }

    private fun startMeeting() {
        if (!hasRecordPermission()) {
            requestRequiredPermissions()
            status.text = "PERMISSION\nAllow microphone access, then tap Start Meeting again."
            return
        }
        val id = UUID.randomUUID().toString()
        meetingId = id
        getPreferences(MODE_PRIVATE).edit().putString("meeting_id", id).apply()
        val cleanTitle = title.text.toString().trim().ifBlank { "TANU Meeting" }
        store.initializeMeeting(id, cleanTitle)
        transcript.text = "Waiting for the first 20-second chunk…"
        mom.text = "MOM will appear after Stop."
        shareButton.isEnabled = false
        retryButton.isEnabled = false
        stopButton.isEnabled = true
        status.text = "STARTING\nOpening microphone…"
        val intent = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_MEETING_ID, id)
            .putExtra(RecordingService.EXTRA_TITLE, cleanTitle)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun stopMeeting() {
        stopButton.isEnabled = false
        status.text = "FINISHING\nClosing the final chunk and generating MOM…"
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    private fun retryProcessing() {
        val id = meetingId ?: return
        retryButton.isEnabled = false
        status.text = "RETRYING\nProcessing saved audio…"
        val intent = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_RETRY)
            .putExtra(RecordingService.EXTRA_MEETING_ID, id)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun refreshFromStore() {
        val id = meetingId ?: return
        val text = store.orderedTranscript(id)
        if (text.isNotBlank()) transcript.text = text
        val meetingMom = store.readMom(id)
        if (meetingMom != null) {
            mom.text = meetingMom.displayText()
            shareButton.isEnabled = true
            retryButton.isEnabled = true
        }
    }

    private fun shareMom() {
        val id = meetingId ?: return
        val meetingMom = store.readMom(id) ?: return
        val shareText = buildString {
            appendLine("TANU — Minutes of Meeting")
            appendLine(store.title(id))
            appendLine()
            append(meetingMom.displayText())
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, shareText),
            "Share TANU MOM"
        ))
    }

    private fun hasRecordPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1001)
    }
}
