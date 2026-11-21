package com.adwewecat.nana.actions

import com.adwewecat.nana.core.model.CallRecordingRecord
import com.adwewecat.nana.core.model.RecordingSource
import com.adwewecat.nana.core.model.SyncStatus
import com.adwewecat.nana.data.local.CallRecordingDao
import com.adwewecat.nana.data.local.toEntity
import com.adwewecat.nana.voice.SyncTrigger
import java.util.UUID

/** Metadata for a discovered call recording candidate prior to local staging and Drive sync. */
data class DiscoveredRecording(
    val sourceUri: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val lastModifiedMs: Long,
    val contactHint: String = "",
)

/** Storage abstraction backing call recording deduplication and `PENDING` persistence. */
interface RecordingStore {
    suspend fun existsBySourceAndSize(sourceUri: String, sizeBytes: Long): Boolean
    suspend fun insertPending(record: CallRecordingRecord)
}

/** Room-backed [RecordingStore] delegating directly to [CallRecordingDao]. */
class RoomRecordingStore(
    private val dao: CallRecordingDao,
) : RecordingStore {

    override suspend fun existsBySourceAndSize(sourceUri: String, sizeBytes: Long): Boolean =
        dao.existsBySourceAndSize(sourceUri, sizeBytes)

    override suspend fun insertPending(record: CallRecordingRecord) {
        dao.insertIgnore(record.toEntity())
    }
}

/**
 * Coordinates safe call recording ingestion with strict 0-byte filtering and
 * `(sourceUri, fileSizeBytes)` deduplication so incomplete files are ignored and finalized
 * recordings are staged once as `PENDING` before enqueueing Wi-Fi Drive backup.
 */
class CallRecordingIngestionCoordinator(
    private val store: RecordingStore,
    private val syncTrigger: SyncTrigger,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val recordingSource: RecordingSource = RecordingSource.ORIGINOS_NATIVE,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Stages eligible non-empty recordings from [candidates] via [copyToStaging], inserts
     * `PENDING` records, and triggers Wi-Fi sync if at least one new recording was staged.
     */
    suspend fun ingestNewRecordings(
        candidates: List<DiscoveredRecording>,
        copyToStaging: suspend (DiscoveredRecording) -> String?,
    ): Int {
        var ingestedCount = 0
        val seenInBatch = HashSet<Pair<String, Long>>()

        for (candidate in candidates) {
            if (candidate.sourceUri.isBlank() || candidate.sizeBytes <= 0L) {
                continue
            }
            val dedupKey = candidate.sourceUri to candidate.sizeBytes
            if (!seenInBatch.add(dedupKey)) {
                continue
            }
            if (store.existsBySourceAndSize(candidate.sourceUri, candidate.sizeBytes)) {
                continue
            }

            val stagedPath = copyToStaging(candidate)?.takeIf { it.isNotBlank() } ?: continue
            val phoneHint = extractPhoneNumber(candidate.contactHint.ifBlank { candidate.fileName })
            val contactName = candidate.contactHint.trim()
            val startedAtMs = candidate.lastModifiedMs.takeIf { it > 0L } ?: clockMs()

            store.insertPending(
                CallRecordingRecord(
                    id = idGenerator(),
                    phoneNumber = phoneHint,
                    contactName = contactName,
                    source = recordingSource,
                    startedAtMs = startedAtMs,
                    durationSec = 0,
                    sourceUri = candidate.sourceUri,
                    stagedPath = stagedPath,
                    fileSizeBytes = candidate.sizeBytes,
                    mimeType = candidate.mimeType.ifBlank { inferAudioMimeType(candidate.fileName) },
                    syncStatus = SyncStatus.PENDING,
                ),
            )
            ingestedCount++
        }

        if (ingestedCount > 0) {
            syncTrigger.enqueueWifiSync()
        }
        return ingestedCount
    }

    private fun extractPhoneNumber(raw: String): String =
        PHONE_HINT_REGEX.find(raw)?.value.orEmpty()

    companion object {
        private val PHONE_HINT_REGEX = Regex("\\+?\\d{7,15}")

        internal fun inferAudioMimeType(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "wav" -> "audio/wav"
                "mp3" -> "audio/mpeg"
                "amr" -> "audio/amr"
                "ogg", "opus" -> "audio/ogg"
                else -> "audio/mp4"
            }
        }
    }
}
