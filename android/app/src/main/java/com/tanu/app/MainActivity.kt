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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : Activity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var dao: TanuDao
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var mom: TextView
    private lateinit var metrics: TextView
    private lateinit var title: EditText
    private lateinit var stopButton: Button
    private lateinit var retryButton: Button
    private lateinit var shareButton: Button
    private var meetingId: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getStringExtra(RecordingService.EXTRA_MEETING_ID) ?: return
            if (id != meetingId) return
            val state = intent.getStringExtra(RecordingService.EXTRA_STATE).orEmpty()
            val message = intent.getStringExtra(RecordingService.EXTRA_MESSAGE).orEmpty()
            val pending = intent.getIntExtra(RecordingService.EXTRA_PENDING, 0)
            val bytes = intent.getLongExtra(RecordingService.EXTRA_STORAGE_BYTES, 0L)
            status.text = "${state.uppercase()}\n$message"
            metrics.text = "Pending upload: $pending • Local audio: ${formatBytes(bytes)}"
            stopButton.isEnabled = state == "recording"
            retryButton.isEnabled = state in setOf("failed", "partial", "ready")
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dao = TanuDatabase.get(this).dao()
        meetingId = getPreferences(MODE_PRIVATE).getString("meeting_id", null)
        setContentView(buildUi())
        requestRequiredPermissions()
        refresh()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RecordingService.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume(); refresh()
    }

    override fun onDestroy() {
        uiScope.cancel(); super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        root.addView(TextView(this).apply { text = "TANU"; textSize = 30f })
        root.addView(TextView(this).apply { text = "Android Phase 1 • long-meeting pipeline"; textSize = 15f })
        title = EditText(this).apply { hint = "Meeting title"; setText("TANU Meeting") }
        root.addView(title)
        root.addView(Button(this).apply { text = "Start Meeting"; setOnClickListener { startMeeting() } })
        stopButton = Button(this).apply { text = "Stop & Generate MOM"; isEnabled = false; setOnClickListener { stopMeeting() } }
        root.addView(stopButton)
        retryButton = Button(this).apply { text = "Retry / Recover"; isEnabled = meetingId != null; setOnClickListener { retry() } }
        root.addView(retryButton)
        shareButton = Button(this).apply { text = "Share MOM"; isEnabled = false; setOnClickListener { shareMom() } }
        root.addView(shareButton)
        status = TextView(this).apply { text = "READY\nTap Start Meeting."; textSize = 16f; setPadding(0, 24, 0, 8) }
        metrics = TextView(this).apply { text = "Audio is stored locally before upload."; setPadding(0, 0, 0, 24) }
        root.addView(status); root.addView(metrics)
        root.addView(TextView(this).apply { text = "TRANSCRIPT"; textSize = 18f })
        transcript = TextView(this).apply { text = "Transcript appears while the meeting is running."; setTextIsSelectable(true); setPadding(0, 12, 0, 28) }
        root.addView(transcript)
        root.addView(TextView(this).apply { text = "MINUTES OF MEETING"; textSize = 18f })
        mom = TextView(this).apply { text = "MOM appears after finalization."; setTextIsSelectable(true); setPadding(0, 12, 0, 40) }
        root.addView(mom)
        return ScrollView(this).apply { addView(root) }
    }

    private fun startMeeting() {
        if (!hasRecordPermission()) {
            requestRequiredPermissions(); status.text = "PERMISSION\nAllow microphone access, then tap Start again."; return
        }
        val id = UUID.randomUUID().toString()
        meetingId = id
        getPreferences(MODE_PRIVATE).edit().putString("meeting_id", id).apply()
        val cleanTitle = title.text.toString().trim().ifBlank { "TANU Meeting" }
        uiScope.launch { dao.upsertMeeting(MeetingEntity(id, cleanTitle, System.currentTimeMillis())) }
        transcript.text = "Waiting for the first transcript chunk…"
        mom.text = "MOM will appear after Stop."
        shareButton.isEnabled = false; retryButton.isEnabled = false; stopButton.isEnabled = true
        val service = Intent(this, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_MEETING_ID, id)
            .putExtra(RecordingService.EXTRA_TITLE, cleanTitle)
        startForegroundService(service)
    }

    private fun stopMeeting() {
        stopButton.isEnabled = false
        status.text = "FINISHING\nSecuring final audio…"
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    private fun retry() {
        PipelineRecovery.schedule(this)
        meetingId?.let { SyncMeetingWorker.enqueue(this, it) }
        status.text = "RETRYING\nUploading saved chunks and syncing transcript…"
    }

    private fun refresh() {
        val id = meetingId ?: return
        uiScope.launch {
            val segments = withContext(Dispatchers.IO) { dao.transcript(id) }
            if (segments.isNotEmpty()) transcript.text = segments.joinToString("\n") { it.text }
            val meeting = withContext(Dispatchers.IO) { dao.meeting(id) }
            val chunks = withContext(Dispatchers.IO) { dao.chunks(id) }
            val pending = chunks.count { it.state !in setOf(ChunkState.UPLOADED, ChunkState.TRANSCRIBING, ChunkState.TRANSCRIBED) }
            val bytes = chunks.sumOf { it.sizeBytes }
            metrics.text = "Chunks: ${chunks.size} • Pending upload: $pending • Local audio: ${formatBytes(bytes)}"
            val rawMom = meeting?.finalMomJson
            if (!rawMom.isNullOrBlank()) {
                val parsed = runCatching { Mom.fromJson(rawMom, meeting.finalMomSource) }.getOrNull()
                if (parsed != null) {
                    mom.text = parsed.displayText(); shareButton.isEnabled = true; retryButton.isEnabled = true
                }
            }
        }
    }

    private fun shareMom() {
        val id = meetingId ?: return
        uiScope.launch {
            val meeting = withContext(Dispatchers.IO) { dao.meeting(id) } ?: return@launch
            val parsed = meeting.finalMomJson?.let { runCatching { Mom.fromJson(it, meeting.finalMomSource) }.getOrNull() } ?: return@launch
            val text = "TANU — Minutes of Meeting\n${meeting.title}\n\n${parsed.displayText()}"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share TANU MOM"))
        }
    }

    private fun hasRecordPermission() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1001)
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
