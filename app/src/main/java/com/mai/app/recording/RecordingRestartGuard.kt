package com.mai.app.recording

import android.content.Context

/**
 * Tiny persistent heartbeat used only to distinguish an Android service redelivery from a
 * genuinely abandoned/interrupted meeting after process death. It contains no meeting audio
 * or transcript data.
 */
object RecordingRestartGuard {
    private const val PREFS = "mai_recording_restart"
    private const val ACTIVE_ID = "active_meeting_id"
    private const val HEARTBEAT = "active_heartbeat_ms"

    fun markActive(context: Context, meetingId: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ACTIVE_ID, meetingId)
            .putLong(HEARTBEAT, System.currentTimeMillis())
            .apply()
    }

    fun heartbeat(context: Context, meetingId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(ACTIVE_ID, null) == meetingId) {
            prefs.edit().putLong(HEARTBEAT, System.currentTimeMillis()).apply()
        }
    }

    fun activeMeetingId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ACTIVE_ID, null)
            ?.takeIf { it.isNotBlank() }

    fun isFresh(context: Context, meetingId: String, maxAgeMs: Long = 15_000L): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(ACTIVE_ID, null) != meetingId) return false
        val heartbeat = prefs.getLong(HEARTBEAT, 0L)
        return heartbeat > 0L && System.currentTimeMillis() - heartbeat <= maxAgeMs
    }

    fun clear(context: Context, meetingId: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getString(ACTIVE_ID, null)
        if (meetingId == null || current == meetingId) {
            prefs.edit().remove(ACTIVE_ID).remove(HEARTBEAT).apply()
        }
    }
}
