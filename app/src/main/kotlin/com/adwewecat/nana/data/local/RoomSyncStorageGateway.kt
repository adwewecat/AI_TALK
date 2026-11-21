package com.adwewecat.nana.data.local

import androidx.room.withTransaction
import com.adwewecat.nana.core.model.CallRecordingRecord
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.SyncStatus
import com.adwewecat.nana.core.sync.SyncFolderIds
import com.adwewecat.nana.core.sync.SyncManifest
import com.adwewecat.nana.core.sync.SyncStorageGateway
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room-backed [SyncStorageGateway] implementing idempotent restore merges and resumable sync state. */
class RoomSyncStorageGateway(
    private val db: NaNaDatabase,
) : SyncStorageGateway {

    override suspend fun getUnsyncedMessages(): List<ChatMessage> =
        db.messageDao().getUnsynced().map { it.toDomain() }

    override suspend fun getAllMessagesForMonth(yearMonth: String): List<ChatMessage> {
        val parsed = try {
            YearMonth.parse(yearMonth)
        } catch (_: DateTimeParseException) {
            return emptyList()
        }
        val startMs = parsed.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val endMs = parsed.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return db.messageDao().getInRange(startMs, endMs).map { it.toDomain() }
    }

    override suspend fun markMessagesSynced(ids: List<String>) {
        if (ids.isEmpty()) return
        db.withTransaction {
            // Chunk below SQLite's 999 bind parameter limit.
            ids.chunked(SQLITE_IN_CHUNK_SIZE).forEach { chunk ->
                db.messageDao().markSynced(chunk)
            }
        }
    }

    override suspend fun getAllMemoryFacts(): List<MemoryFact> =
        db.memoryFactDao().getAll().map { it.toDomain() }

    override suspend fun hasUnsyncedMemoryFacts(): Boolean =
        db.memoryFactDao().hasUnsynced()

    override suspend fun markAllMemoryFactsSynced() {
        db.memoryFactDao().markAllSynced()
    }

    override suspend fun getPendingRecordings(): List<CallRecordingRecord> =
        db.callRecordingDao()
            .getByStatuses(listOf(SyncStatus.PENDING.name, SyncStatus.FAILED.name))
            .map { it.toDomain() }

    override suspend fun updateRecordingProgress(
        id: String,
        sessionUri: String?,
        uploadedBytes: Long,
        status: SyncStatus,
    ) {
        db.callRecordingDao().updateProgress(
            id = id,
            sessionUri = sessionUri,
            uploadedBytes = uploadedBytes,
            status = status.name,
        )
    }

    override suspend fun markRecordingSynced(id: String, driveFileId: String, syncedAtMs: Long) {
        db.callRecordingDao().markSynced(
            id = id,
            driveFileId = driveFileId,
            status = SyncStatus.SYNCED.name,
            syncedAtMs = syncedAtMs,
        )
    }

    override suspend fun getSavedFolderIds(): SyncFolderIds? =
        db.syncStateDao().get()?.toFolderIds()

    override suspend fun saveFolderIds(ids: SyncFolderIds) {
        db.withTransaction {
            val current = db.syncStateDao().get() ?: SyncStateEntity()
            db.syncStateDao().upsert(
                current.copy(
                    id = SyncStateEntity.SINGLETON_ROW_ID,
                    rootFolderId = ids.rootId,
                    profileFolderId = ids.profileId,
                    historyFolderId = ids.historyId,
                    recordingsFolderId = ids.recordingsId,
                    manifestFileId = ids.manifestFileId,
                ),
            )
        }
    }

    override suspend fun getSavedManifest(): SyncManifest? {
        val raw = db.syncStateDao().get()?.manifestJson?.takeIf { it.isNotBlank() } ?: return null
        return try {
            json.decodeFromString<SyncManifest>(raw)
        } catch (_: SerializationException) {
            null
        }
    }

    override suspend fun saveManifest(manifest: SyncManifest, syncedAtMs: Long) {
        val encoded = json.encodeToString(manifest)
        db.withTransaction {
            val current = db.syncStateDao().get() ?: SyncStateEntity()
            db.syncStateDao().upsert(
                current.copy(
                    id = SyncStateEntity.SINGLETON_ROW_ID,
                    manifestJson = encoded,
                    lastSyncedAtMs = syncedAtMs,
                ),
            )
        }
    }

    override suspend fun mergeRestoredMemoryFacts(facts: List<MemoryFact>) {
        if (facts.isEmpty()) return
        db.memoryFactDao().insertAllIgnore(facts.map { it.copy(synced = true).toEntity() })
    }

    override suspend fun mergeRestoredMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        db.messageDao().insertAllIgnore(messages.map { it.copy(synced = true).toEntity() })
    }

    /** Resets recordings left in `UPLOADING` after a crash or cancellation back to `PENDING` while retaining byte offsets. */
    suspend fun resetStaleUploadingRecordings() {
        db.callRecordingDao().resetUploadingToPending()
    }

    /** Returns all local call recording records for retention evaluation. */
    suspend fun getAllRecordings(): List<CallRecordingRecord> =
        db.callRecordingDao().getAll().map { it.toDomain() }

    /** Clears [CallRecordingRecord.stagedPath] after the local audio file is safely purged. */
    suspend fun clearRecordingStagedPath(id: String) {
        db.callRecordingDao().clearStagedPath(id)
    }

    private companion object {
        const val SQLITE_IN_CHUNK_SIZE = 500
        val json = Json { ignoreUnknownKeys = true }
    }
}
