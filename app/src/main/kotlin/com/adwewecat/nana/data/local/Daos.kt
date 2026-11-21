package com.adwewecat.nana.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.adwewecat.nana.core.model.SyncStatus
import kotlinx.coroutines.flow.Flow

/** DAO for chat messages with idempotent restore inserts. */
@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(messages: List<MessageEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE synced = 0 ORDER BY createdAtMs ASC, id ASC")
    suspend fun getUnsynced(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE createdAtMs >= :startMs AND createdAtMs < :endMs ORDER BY createdAtMs ASC, id ASC")
    suspend fun getInRange(startMs: Long, endMs: Long): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY createdAtMs ASC, id ASC")
    suspend fun getAll(): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY createdAtMs ASC, id ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAtMs ASC, id ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)
}

/** DAO for durable user memory facts with idempotent restore inserts. */
@Dao
interface MemoryFactDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(fact: MemoryFactEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(facts: List<MemoryFactEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: MemoryFactEntity)

    @Query("SELECT * FROM memory_facts ORDER BY updatedAtMs DESC, id ASC")
    suspend fun getAll(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts ORDER BY updatedAtMs DESC, id ASC")
    fun observeAll(): Flow<List<MemoryFactEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM memory_facts WHERE synced = 0)")
    suspend fun hasUnsynced(): Boolean

    @Query("UPDATE memory_facts SET synced = 1 WHERE synced = 0")
    suspend fun markAllSynced()

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteById(id: String)
}

/** DAO for staged call recordings and resumable Google Drive upload progress. */
@Dao
interface CallRecordingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(recording: CallRecordingEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(recording: CallRecordingEntity): Long

    @Query("SELECT * FROM call_recordings WHERE syncStatus IN (:statuses) ORDER BY startedAtMs ASC, id ASC")
    suspend fun getByStatuses(statuses: List<String>): List<CallRecordingEntity>

    @Query("SELECT * FROM call_recordings ORDER BY startedAtMs DESC, id ASC")
    suspend fun getAll(): List<CallRecordingEntity>

    @Query("SELECT * FROM call_recordings ORDER BY startedAtMs DESC, id ASC")
    fun observeAll(): Flow<List<CallRecordingEntity>>

    @Query("SELECT * FROM call_recordings WHERE id = :id")
    suspend fun getById(id: String): CallRecordingEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM call_recordings WHERE sourceUri = :sourceUri)")
    suspend fun existsBySourceUri(sourceUri: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM call_recordings WHERE sourceUri = :sourceUri AND fileSizeBytes = :fileSizeBytes)")
    suspend fun existsBySourceAndSize(sourceUri: String, fileSizeBytes: Long): Boolean

    @Query(
        "UPDATE call_recordings SET resumeSessionUri = :sessionUri, uploadedBytes = :uploadedBytes, syncStatus = :status WHERE id = :id",
    )
    suspend fun updateProgress(id: String, sessionUri: String?, uploadedBytes: Long, status: String)

    @Query(
        "UPDATE call_recordings SET driveFileId = :driveFileId, syncStatus = :status, syncedAtMs = :syncedAtMs WHERE id = :id",
    )
    suspend fun markSynced(id: String, driveFileId: String, status: String, syncedAtMs: Long)

    @Query("UPDATE call_recordings SET syncStatus = :pendingStatus WHERE syncStatus = :uploadingStatus")
    suspend fun resetUploadingToPending(
        uploadingStatus: String = SyncStatus.UPLOADING.name,
        pendingStatus: String = SyncStatus.PENDING.name,
    )

    @Query("UPDATE call_recordings SET stagedPath = NULL WHERE id = :id")
    suspend fun clearStagedPath(id: String)

    @Query("UPDATE call_recordings SET aiSummary = :summary WHERE id = :id")
    suspend fun updateAiSummary(id: String, summary: String?)
}

/** DAO for singleton Drive folder IDs and manifest state (`id = 1`). */
@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncStateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)
}
