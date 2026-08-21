package com.mai.app.pipeline

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

@Entity(tableName = "pipeline_meetings")
data class PipelineMeetingEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val startedAtMs: Long,
    val state: String = "recording",
    val expectedChunks: Int? = null,
    val lastServerSyncMs: Long = 0L
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
interface PipelineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeeting(meeting: PipelineMeetingEntity)

    @Query("SELECT * FROM pipeline_meetings WHERE id=:id LIMIT 1")
    suspend fun meeting(id: String): PipelineMeetingEntity?

    @Query("SELECT * FROM pipeline_meetings WHERE state IN ('recording','finalizing','processing')")
    suspend fun activeMeetings(): List<PipelineMeetingEntity>

    @Query("UPDATE pipeline_meetings SET state=:state, expectedChunks=:expected WHERE id=:id")
    suspend fun updateMeetingState(id: String, state: String, expected: Int?)

    @Query("UPDATE pipeline_meetings SET lastServerSyncMs=:timeMs WHERE id=:id")
    suspend fun markServerSync(id: String, timeMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunk(chunk: AudioChunkEntity)

    @Update
    suspend fun updateChunk(chunk: AudioChunkEntity)

    @Query("SELECT * FROM audio_chunks WHERE meetingId=:meetingId AND sequence=:sequence LIMIT 1")
    suspend fun chunk(meetingId: String, sequence: Int): AudioChunkEntity?

    @Query("SELECT * FROM audio_chunks WHERE meetingId=:meetingId ORDER BY sequence")
    suspend fun chunks(meetingId: String): List<AudioChunkEntity>

    @Query("SELECT COALESCE(MAX(sequence),-1) FROM audio_chunks WHERE meetingId=:meetingId")
    suspend fun maxSequence(meetingId: String): Int

    @Query("SELECT COUNT(*) FROM audio_chunks WHERE meetingId=:meetingId")
    suspend fun chunkCount(meetingId: String): Int

    @Query("SELECT COUNT(*) FROM audio_chunks WHERE meetingId=:meetingId AND state IN ('recorded','queued','uploading','failed')")
    suspend fun pendingUploadCount(meetingId: String): Int

    @Query("SELECT COALESCE(SUM(sizeBytes),0) FROM audio_chunks WHERE meetingId=:meetingId")
    suspend fun localAudioBytes(meetingId: String): Long

    @Query("SELECT * FROM audio_chunks WHERE state IN ('recorded','queued','uploading','failed') ORDER BY meetingId,sequence")
    suspend fun recoverableChunks(): List<AudioChunkEntity>

    @Query("UPDATE audio_chunks SET state=:state,retryCount=:retries,lastError=:error WHERE meetingId=:meetingId AND sequence=:sequence")
    suspend fun markFailure(meetingId: String, sequence: Int, state: String, retries: Int, error: String?)

    @Query("UPDATE audio_chunks SET state=:state,uploadedAtMs=:timeMs,lastError=NULL WHERE meetingId=:meetingId AND sequence=:sequence")
    suspend fun markUploaded(meetingId: String, sequence: Int, state: String, timeMs: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranscript(segment: TranscriptSegmentEntity)

    @Query("SELECT * FROM transcript_segments WHERE meetingId=:meetingId ORDER BY sequence")
    suspend fun transcript(meetingId: String): List<TranscriptSegmentEntity>

    @Query("DELETE FROM audio_chunks WHERE meetingId=:meetingId")
    suspend fun deleteChunks(meetingId: String)

    @Query("DELETE FROM transcript_segments WHERE meetingId=:meetingId")
    suspend fun deleteTranscript(meetingId: String)

    @Query("DELETE FROM pipeline_meetings WHERE id=:meetingId")
    suspend fun deleteMeeting(meetingId: String)
}

@Database(
    entities = [PipelineMeetingEntity::class, AudioChunkEntity::class, TranscriptSegmentEntity::class],
    version = 1,
    exportSchema = true
)
abstract class MaiPipelineDatabase : RoomDatabase() {
    abstract fun dao(): PipelineDao

    companion object {
        @Volatile private var instance: MaiPipelineDatabase? = null
        fun get(context: Context): MaiPipelineDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MaiPipelineDatabase::class.java,
                "mai_pipeline.db"
            ).build().also { instance = it }
        }
    }
}
