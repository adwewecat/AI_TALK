package com.adwewecat.nana.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.adwewecat.nana.core.model.CallRecordingRecord
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.MemoryCategory
import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.RecordingSource
import com.adwewecat.nana.core.model.SyncStatus
import com.adwewecat.nana.core.sync.SyncFolderIds

/** Local Room entity mirroring [ChatMessage]. */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["createdAtMs"]),
        Index(value = ["synced"]),
        Index(value = ["sessionId"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val persona: String,
    val content: String,
    val createdAtMs: Long,
    val synced: Boolean,
)

/** Local Room entity mirroring [MemoryFact]. */
@Entity(
    tableName = "memory_facts",
    indices = [
        Index(value = ["synced"]),
        Index(value = ["updatedAtMs"]),
    ],
)
data class MemoryFactEntity(
    @PrimaryKey val id: String,
    val category: String,
    val key: String,
    val value: String,
    val confidence: Float,
    val updatedAtMs: Long,
    val synced: Boolean,
)

/** Local Room entity mirroring [CallRecordingRecord]. */
@Entity(
    tableName = "call_recordings",
    indices = [
        Index(value = ["syncStatus"]),
        Index(value = ["startedAtMs"]),
        Index(value = ["sourceUri"]),
    ],
)
data class CallRecordingEntity(
    @PrimaryKey val id: String,
    val phoneNumber: String,
    val contactName: String,
    val source: String,
    val startedAtMs: Long,
    val durationSec: Int,
    val sourceUri: String,
    val stagedPath: String?,
    val fileSizeBytes: Long,
    val mimeType: String,
    val driveFileId: String?,
    val resumeSessionUri: String?,
    val uploadedBytes: Long,
    val syncStatus: String,
    val syncedAtMs: Long?,
    val aiSummary: String?,
)

/** Single-row Room entity (`id = 1`) storing Drive folder IDs and cached manifest JSON. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ROW_ID,
    val rootFolderId: String? = null,
    val profileFolderId: String? = null,
    val historyFolderId: String? = null,
    val recordingsFolderId: String? = null,
    val manifestFileId: String? = null,
    val manifestJson: String? = null,
    val lastSyncedAtMs: Long? = null,
) {
    /** Reconstructs [SyncFolderIds] if all four core Drive folder IDs have been populated. */
    fun toFolderIds(): SyncFolderIds? {
        val root = rootFolderId?.takeIf { it.isNotBlank() } ?: return null
        val profile = profileFolderId?.takeIf { it.isNotBlank() } ?: return null
        val history = historyFolderId?.takeIf { it.isNotBlank() } ?: return null
        val recordings = recordingsFolderId?.takeIf { it.isNotBlank() } ?: return null
        return SyncFolderIds(
            rootId = root,
            profileId = profile,
            historyId = history,
            recordingsId = recordings,
            manifestFileId = manifestFileId,
        )
    }

    companion object {
        const val SINGLETON_ROW_ID = 1
    }
}

/** Converts [MessageEntity] to domain [ChatMessage]. */
fun MessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    sessionId = sessionId,
    role = role,
    persona = PersonaMode.entries.firstOrNull { it.name == persona } ?: PersonaMode.NANA,
    content = content,
    createdAtMs = createdAtMs,
    synced = synced,
)

/** Converts domain [ChatMessage] to [MessageEntity]. */
fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    sessionId = sessionId,
    role = role,
    persona = persona.name,
    content = content,
    createdAtMs = createdAtMs,
    synced = synced,
)

/** Converts [MemoryFactEntity] to domain [MemoryFact]. */
fun MemoryFactEntity.toDomain(): MemoryFact = MemoryFact(
    id = id,
    category = MemoryCategory.entries.firstOrNull { it.name == category } ?: MemoryCategory.FACT,
    key = key,
    value = value,
    confidence = confidence,
    updatedAtMs = updatedAtMs,
    synced = synced,
)

/** Converts domain [MemoryFact] to [MemoryFactEntity]. */
fun MemoryFact.toEntity(): MemoryFactEntity = MemoryFactEntity(
    id = id,
    category = category.name,
    key = key,
    value = value,
    confidence = confidence,
    updatedAtMs = updatedAtMs,
    synced = synced,
)

/** Converts [CallRecordingEntity] to domain [CallRecordingRecord]. */
fun CallRecordingEntity.toDomain(): CallRecordingRecord = CallRecordingRecord(
    id = id,
    phoneNumber = phoneNumber,
    contactName = contactName,
    source = RecordingSource.entries.firstOrNull { it.name == source } ?: RecordingSource.ORIGINOS_NATIVE,
    startedAtMs = startedAtMs,
    durationSec = durationSec,
    sourceUri = sourceUri,
    stagedPath = stagedPath,
    fileSizeBytes = fileSizeBytes,
    mimeType = mimeType,
    driveFileId = driveFileId,
    resumeSessionUri = resumeSessionUri,
    uploadedBytes = uploadedBytes,
    syncStatus = SyncStatus.entries.firstOrNull { it.name == syncStatus } ?: SyncStatus.PENDING,
    syncedAtMs = syncedAtMs,
    aiSummary = aiSummary,
)

/** Converts domain [CallRecordingRecord] to [CallRecordingEntity]. */
fun CallRecordingRecord.toEntity(): CallRecordingEntity = CallRecordingEntity(
    id = id,
    phoneNumber = phoneNumber,
    contactName = contactName,
    source = source.name,
    startedAtMs = startedAtMs,
    durationSec = durationSec,
    sourceUri = sourceUri,
    stagedPath = stagedPath,
    fileSizeBytes = fileSizeBytes,
    mimeType = mimeType,
    driveFileId = driveFileId,
    resumeSessionUri = resumeSessionUri,
    uploadedBytes = uploadedBytes,
    syncStatus = syncStatus.name,
    syncedAtMs = syncedAtMs,
    aiSummary = aiSummary,
)
