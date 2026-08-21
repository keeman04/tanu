package com.tanu.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "meetings")
data class MeetingEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val createdAtMs: Long,
    val state: String = "recording",
    val startedAtMs: Long = createdAtMs,
    val endedAtMs: Long? = null,
    val lastServerSyncMs: Long = 0L,
    val finalMomJson: String? = null,
    val finalMomSource: String? = null
)

@Entity(
    tableName = "audio_chunks",
    primaryKeys = ["meetingId", "sequence"],
    indices = [Index("meetingId"), Index("state")]
)
data class AudioChunkEntity(
    val meetingId: String,
    val sequence: Int,
    val localPath: String,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val sha256: String,
    val codec: String,
    val mimeType: String,
    val state: String = ChunkState.RECORDED,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val uploadedAtMs: Long? = null,
    val transcribedAtMs: Long? = null
)

@Entity(
    tableName = "transcript_segments",
    primaryKeys = ["meetingId", "sequence"],
    indices = [Index("meetingId")]
)
data class TranscriptSegmentEntity(
    val meetingId: String,
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "rolling_summaries",
    primaryKeys = ["meetingId", "windowStartMs"],
    indices = [Index("meetingId")]
)
data class RollingSummaryEntity(
    val meetingId: String,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val json: String,
    val createdAtMs: Long = System.currentTimeMillis()
)

object ChunkState {
    const val RECORDED = "recorded"
    const val QUEUED = "queued"
    const val UPLOADING = "uploading"
    const val UPLOADED = "uploaded"
    const val TRANSCRIBING = "transcribing"
    const val TRANSCRIBED = "transcribed"
    const val FAILED = "failed"
}

@Dao
interface TanuDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeeting(meeting: MeetingEntity)

    @Query("SELECT * FROM meetings WHERE id = :id LIMIT 1")
    suspend fun meeting(id: String): MeetingEntity?

    @Query("SELECT * FROM meetings WHERE id = :id LIMIT 1")
    fun observeMeeting(id: String): Flow<MeetingEntity?>

    @Query("UPDATE meetings SET state = :state, endedAtMs = :endedAtMs WHERE id = :id")
    suspend fun updateMeetingState(id: String, state: String, endedAtMs: Long?)

    @Query("UPDATE meetings SET lastServerSyncMs = :timeMs WHERE id = :id")
    suspend fun markServerSync(id: String, timeMs: Long)

    @Query("UPDATE meetings SET finalMomJson = :json, finalMomSource = :source, state = 'ready' WHERE id = :id")
    suspend fun saveFinalMom(id: String, json: String, source: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunk(chunk: AudioChunkEntity)

    @Update
    suspend fun updateChunk(chunk: AudioChunkEntity)

    @Query("SELECT * FROM audio_chunks WHERE meetingId = :meetingId ORDER BY sequence")
    suspend fun chunks(meetingId: String): List<AudioChunkEntity>

    @Query("SELECT * FROM audio_chunks WHERE meetingId = :meetingId AND sequence = :sequence LIMIT 1")
    suspend fun chunk(meetingId: String, sequence: Int): AudioChunkEntity?

    @Query("SELECT COALESCE(MAX(sequence), -1) FROM audio_chunks WHERE meetingId = :meetingId")
    suspend fun maxSequence(meetingId: String): Int

    @Query("SELECT COUNT(*) FROM audio_chunks WHERE meetingId = :meetingId AND state NOT IN ('uploaded','transcribing','transcribed')")
    suspend fun pendingUploadCount(meetingId: String): Int

    @Query("SELECT COUNT(*) FROM audio_chunks WHERE meetingId = :meetingId AND state != 'transcribed'")
    suspend fun pendingTranscriptCount(meetingId: String): Int

    @Query("SELECT COALESCE(SUM(sizeBytes),0) FROM audio_chunks WHERE meetingId = :meetingId")
    suspend fun localAudioBytes(meetingId: String): Long

    @Query("SELECT * FROM audio_chunks WHERE state IN ('recorded','queued','failed') ORDER BY meetingId, sequence")
    suspend fun recoverableChunks(): List<AudioChunkEntity>

    @Query("UPDATE audio_chunks SET state = :state, retryCount = :retryCount, lastError = :error WHERE meetingId = :meetingId AND sequence = :sequence")
    suspend fun updateChunkFailure(meetingId: String, sequence: Int, state: String, retryCount: Int, error: String?)

    @Query("UPDATE audio_chunks SET state = :state, uploadedAtMs = :timeMs, lastError = NULL WHERE meetingId = :meetingId AND sequence = :sequence")
    suspend fun markUploaded(meetingId: String, sequence: Int, state: String, timeMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranscript(segment: TranscriptSegmentEntity)

    @Query("SELECT * FROM transcript_segments WHERE meetingId = :meetingId ORDER BY sequence")
    suspend fun transcript(meetingId: String): List<TranscriptSegmentEntity>

    @Query("SELECT * FROM transcript_segments WHERE meetingId = :meetingId ORDER BY sequence")
    fun observeTranscript(meetingId: String): Flow<List<TranscriptSegmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRollingSummary(summary: RollingSummaryEntity)

    @Query("DELETE FROM rolling_summaries WHERE meetingId = :meetingId")
    suspend fun clearRollingSummaries(meetingId: String)

    @Query("SELECT * FROM rolling_summaries WHERE meetingId = :meetingId ORDER BY windowStartMs")
    suspend fun rollingSummaries(meetingId: String): List<RollingSummaryEntity>
}

@Database(
    entities = [MeetingEntity::class, AudioChunkEntity::class, TranscriptSegmentEntity::class, RollingSummaryEntity::class],
    version = 1,
    exportSchema = true
)
abstract class TanuDatabase : RoomDatabase() {
    abstract fun dao(): TanuDao

    companion object {
        @Volatile private var INSTANCE: TanuDatabase? = null
        fun get(context: Context): TanuDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                TanuDatabase::class.java,
                "tanu.db"
            ).build().also { INSTANCE = it }
        }
    }
}
