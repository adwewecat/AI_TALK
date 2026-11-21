package com.adwewecat.nana.core.sync

import com.adwewecat.nana.core.model.CallRecordingRecord
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.SyncStatus
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persistent Drive manifest stored at `AI_TALK_NaNa/manifest.json` for reinstall recovery. */
@Serializable
data class SyncManifest(
    val schemaVersion: Int = 1,
    val updatedAtMs: Long,
    val memoryFileId: String? = null,
    val historyFileIds: Map<String, String> = emptyMap(),
    val recordingDriveIds: Map<String, String> = emptyMap(),
)

/** Outcome returned by sync and restore coordinators for WorkManager/UI state mapping. */
enum class SyncOutcome {
    COMPLETED,
    PAUSED_NO_WIFI,
    NEEDS_REAUTH,
    RETRYABLE_ERROR,
}

/** Cached Google Drive folder and manifest file IDs for `AI_TALK_NaNa/`. */
data class SyncFolderIds(
    val rootId: String,
    val profileId: String,
    val historyId: String,
    val recordingsId: String,
    val manifestFileId: String? = null,
)

/** Persistence contract bridging `:core` sync/restore coordinators to Room or test fakes. */
interface SyncStorageGateway {
    suspend fun getUnsyncedMessages(): List<ChatMessage>
    suspend fun getAllMessagesForMonth(yearMonth: String): List<ChatMessage>
    suspend fun markMessagesSynced(ids: List<String>)
    suspend fun getAllMemoryFacts(): List<MemoryFact>
    suspend fun hasUnsyncedMemoryFacts(): Boolean
    suspend fun markAllMemoryFactsSynced()
    suspend fun getPendingRecordings(): List<CallRecordingRecord>
    suspend fun updateRecordingProgress(id: String, sessionUri: String?, uploadedBytes: Long, status: SyncStatus)
    suspend fun markRecordingSynced(id: String, driveFileId: String, syncedAtMs: Long)
    suspend fun getSavedFolderIds(): SyncFolderIds?
    suspend fun saveFolderIds(ids: SyncFolderIds)
    suspend fun getSavedManifest(): SyncManifest?
    suspend fun saveManifest(manifest: SyncManifest, syncedAtMs: Long)
    suspend fun mergeRestoredMemoryFacts(facts: List<MemoryFact>)
    suspend fun mergeRestoredMessages(messages: List<ChatMessage>)
}

internal object DriveLayout {
    const val ROOT_FOLDER = "AI_TALK_NaNa"
    const val PROFILE_FOLDER = "profile"
    const val HISTORY_FOLDER = "history"
    const val RECORDINGS_FOLDER = "call_recordings"
    const val MANIFEST_FILE = "manifest.json"
    const val MEMORY_FILE = "memory_facts.json"

    val json = Json { ignoreUnknownKeys = true }

    private val yearMonthFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    private val recordingTimestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

    fun yearMonthUtc(epochMs: Long): String =
        yearMonthFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))

    fun historyFileName(yearMonth: String): String = "messages_$yearMonth.jsonl"

    fun recordingFileName(recording: CallRecordingRecord, file: File): String {
        val ts = recordingTimestampFormatter.format(
            Instant.ofEpochMilli(recording.startedAtMs).atZone(ZoneOffset.UTC),
        )
        val rawLabel = recording.contactName.ifBlank { recording.phoneNumber }.ifBlank { "call" }
        val safeLabel = rawLabel
            .replace(Regex("""[^\p{L}\p{N}._-]+"""), "_")
            .trim('_')
            .take(48)
            .ifBlank { "call" }
        val ext = file.extension.takeIf { it.isNotBlank() } ?: extensionFromMime(recording.mimeType)
        val idSuffix = recording.id.replace(Regex("""[^A-Za-z0-9_-]"""), "").take(8).ifBlank { "rec" }
        return "${ts}_${safeLabel}_${idSuffix}.$ext"
    }

    private fun extensionFromMime(mimeType: String): String = when {
        mimeType.contains("wav", ignoreCase = true) -> "wav"
        mimeType.contains("mpeg", ignoreCase = true) || mimeType.contains("mp3", ignoreCase = true) -> "mp3"
        mimeType.contains("amr", ignoreCase = true) -> "amr"
        else -> "m4a"
    }
}

/**
 * Coordinates strict Wi-Fi-only Google Drive backups of memory facts, monthly message logs,
 * resumable call recordings, and the root `manifest.json` index.
 */
class WifiOnlySyncCoordinator(
    private val wifiChecker: WifiNetworkChecker,
    private val driveClient: GoogleDriveClient,
    private val storage: SyncStorageGateway,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val chunkSizeBytes: Int = 262_144,
) {
    /** Synchronizes all pending local data to Google Drive strictly over unmetered Wi-Fi. */
    suspend fun syncNow(accessToken: String): SyncOutcome {
        if (!wifiChecker.isUnmeteredWifiConnected()) {
            return SyncOutcome.PAUSED_NO_WIFI
        }
        if (accessToken.isBlank()) {
            return SyncOutcome.NEEDS_REAUTH
        }

        return try {
            val hasUnsyncedMemories = storage.hasUnsyncedMemoryFacts()
            val unsyncedMessages = storage.getUnsyncedMessages()
            val pendingRecordings = storage.getPendingRecordings()

            if (!hasUnsyncedMemories && unsyncedMessages.isEmpty() && pendingRecordings.isEmpty()) {
                return SyncOutcome.COMPLETED
            }

            var folders = ensureFolders(accessToken)
            val baseManifest = storage.getSavedManifest() ?: SyncManifest(updatedAtMs = clockMs())
            var memoryFileId = baseManifest.memoryFileId
            val historyFileIds = baseManifest.historyFileIds.toMutableMap()
            val recordingDriveIds = baseManifest.recordingDriveIds.toMutableMap()
            var manifestDirty = false

            fun currentManifestSnapshot(timestampMs: Long = clockMs()): SyncManifest = SyncManifest(
                schemaVersion = 1,
                updatedAtMs = timestampMs,
                memoryFileId = memoryFileId,
                historyFileIds = historyFileIds.toMap(),
                recordingDriveIds = recordingDriveIds.toMap(),
            )

            if (hasUnsyncedMemories) {
                val sortedFacts = storage.getAllMemoryFacts()
                    .sortedWith(compareBy<MemoryFact> { it.updatedAtMs }.thenBy { it.id })
                val factsJson = DriveLayout.json.encodeToString(sortedFacts)
                memoryFileId = driveClient.upsertJsonFile(
                    accessToken = accessToken,
                    parentId = folders.profileId,
                    fileName = DriveLayout.MEMORY_FILE,
                    content = factsJson,
                    contentMimeType = "application/json",
                    existingFileId = memoryFileId,
                    wifiChecker = wifiChecker,
                )
                storage.markAllMemoryFactsSynced()
                storage.saveManifest(currentManifestSnapshot(), clockMs())
                manifestDirty = true
            }

            if (unsyncedMessages.isNotEmpty()) {
                val messagesByMonth = unsyncedMessages.groupBy { DriveLayout.yearMonthUtc(it.createdAtMs) }
                for ((yearMonth, unsyncedForMonth) in messagesByMonth.toSortedMap()) {
                    val fullMonth = storage.getAllMessagesForMonth(yearMonth)
                        .sortedWith(compareBy<ChatMessage> { it.createdAtMs }.thenBy { it.id })
                    val jsonl = fullMonth.joinToString("\n") { DriveLayout.json.encodeToString(it) }
                    val fileId = driveClient.upsertJsonFile(
                        accessToken = accessToken,
                        parentId = folders.historyId,
                        fileName = DriveLayout.historyFileName(yearMonth),
                        content = jsonl,
                        contentMimeType = "application/x-ndjson",
                        existingFileId = historyFileIds[yearMonth],
                        wifiChecker = wifiChecker,
                    )
                    historyFileIds[yearMonth] = fileId
                    storage.markMessagesSynced(unsyncedForMonth.map { it.id })
                    storage.saveManifest(currentManifestSnapshot(), clockMs())
                    manifestDirty = true
                }
            }

            for (recording in pendingRecordings) {
                val uploadedId = syncSingleRecording(accessToken, folders, recording) ?: continue
                recordingDriveIds[recording.id] = uploadedId
                storage.saveManifest(currentManifestSnapshot(), clockMs())
                manifestDirty = true
            }

            if (manifestDirty) {
                val now = clockMs()
                val updatedManifest = currentManifestSnapshot(now)
                val manifestJson = DriveLayout.json.encodeToString(updatedManifest)
                val manifestFileId = driveClient.upsertJsonFile(
                    accessToken = accessToken,
                    parentId = folders.rootId,
                    fileName = DriveLayout.MANIFEST_FILE,
                    content = manifestJson,
                    contentMimeType = "application/json",
                    existingFileId = folders.manifestFileId,
                    wifiChecker = wifiChecker,
                )
                if (manifestFileId != folders.manifestFileId) {
                    folders = folders.copy(manifestFileId = manifestFileId)
                    storage.saveFolderIds(folders)
                }
                storage.saveManifest(updatedManifest, now)
            }

            SyncOutcome.COMPLETED
        } catch (_: WifiDroppedException) {
            SyncOutcome.PAUSED_NO_WIFI
        } catch (_: DriveUnauthorizedException) {
            SyncOutcome.NEEDS_REAUTH
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            SyncOutcome.RETRYABLE_ERROR
        } catch (_: SerializationException) {
            SyncOutcome.RETRYABLE_ERROR
        }
    }

    private suspend fun ensureFolders(accessToken: String): SyncFolderIds {
        storage.getSavedFolderIds()?.let { return it }

        val rootId = driveClient.findOrCreateFolder(
            accessToken = accessToken,
            name = DriveLayout.ROOT_FOLDER,
            parentId = null,
            wifiChecker = wifiChecker,
        )
        val profileId = driveClient.findOrCreateFolder(
            accessToken = accessToken,
            name = DriveLayout.PROFILE_FOLDER,
            parentId = rootId,
            wifiChecker = wifiChecker,
        )
        val historyId = driveClient.findOrCreateFolder(
            accessToken = accessToken,
            name = DriveLayout.HISTORY_FOLDER,
            parentId = rootId,
            wifiChecker = wifiChecker,
        )
        val recordingsId = driveClient.findOrCreateFolder(
            accessToken = accessToken,
            name = DriveLayout.RECORDINGS_FOLDER,
            parentId = rootId,
            wifiChecker = wifiChecker,
        )
        val folderIds = SyncFolderIds(
            rootId = rootId,
            profileId = profileId,
            historyId = historyId,
            recordingsId = recordingsId,
        )
        storage.saveFolderIds(folderIds)
        return folderIds
    }

    private suspend fun syncSingleRecording(
        accessToken: String,
        folders: SyncFolderIds,
        recording: CallRecordingRecord,
    ): String? {
        val localFile = recording.stagedPath?.takeIf { it.isNotBlank() }?.let(::File)
        if (localFile == null || !localFile.exists() || !localFile.isFile || localFile.length() <= 0L) {
            storage.updateRecordingProgress(
                id = recording.id,
                sessionUri = recording.resumeSessionUri,
                uploadedBytes = recording.uploadedBytes,
                status = SyncStatus.FAILED,
            )
            return null
        }

        val mimeType = recording.mimeType.ifBlank { "audio/mp4" }
        val driveFileName = DriveLayout.recordingFileName(recording, localFile)
        var activeSessionUri = recording.resumeSessionUri?.takeIf { it.isNotBlank() }
        var activeUploadedBytes = recording.uploadedBytes.coerceIn(0L, localFile.length())

        if (activeSessionUri == null) {
            activeSessionUri = driveClient.initiateResumableUpload(
                accessToken = accessToken,
                parentId = folders.recordingsId,
                fileName = driveFileName,
                mimeType = mimeType,
                totalBytes = localFile.length(),
                wifiChecker = wifiChecker,
            )
            activeUploadedBytes = 0L
        }

        storage.updateRecordingProgress(
            id = recording.id,
            sessionUri = activeSessionUri,
            uploadedBytes = activeUploadedBytes,
            status = SyncStatus.UPLOADING,
        )

        val driveFileId = try {
            uploadWithExpiredSessionRecovery(
                accessToken = accessToken,
                recordingsFolderId = folders.recordingsId,
                fileName = driveFileName,
                mimeType = mimeType,
                file = localFile,
                initialSessionUri = activeSessionUri,
                initialOffset = activeUploadedBytes,
                onProgress = { sessionUri, uploadedBytes ->
                    activeSessionUri = sessionUri
                    activeUploadedBytes = uploadedBytes
                    storage.updateRecordingProgress(
                        id = recording.id,
                        sessionUri = sessionUri,
                        uploadedBytes = uploadedBytes,
                        status = SyncStatus.UPLOADING,
                    )
                },
            )
        } catch (e: WifiDroppedException) {
            storage.updateRecordingProgress(recording.id, activeSessionUri, activeUploadedBytes, SyncStatus.PENDING)
            throw e
        } catch (e: DriveUnauthorizedException) {
            storage.updateRecordingProgress(recording.id, activeSessionUri, activeUploadedBytes, SyncStatus.PENDING)
            throw e
        } catch (e: IOException) {
            storage.updateRecordingProgress(recording.id, activeSessionUri, activeUploadedBytes, SyncStatus.PENDING)
            throw e
        }

        storage.markRecordingSynced(
            id = recording.id,
            driveFileId = driveFileId,
            syncedAtMs = clockMs(),
        )
        return driveFileId
    }

    private suspend fun uploadWithExpiredSessionRecovery(
        accessToken: String,
        recordingsFolderId: String,
        fileName: String,
        mimeType: String,
        file: File,
        initialSessionUri: String,
        initialOffset: Long,
        onProgress: suspend (sessionUri: String, uploadedBytes: Long) -> Unit,
    ): String {
        return try {
            driveClient.uploadChunkedResumable(
                accessToken = accessToken,
                sessionUri = initialSessionUri,
                file = file,
                startOffset = initialOffset,
                chunkSizeBytes = chunkSizeBytes,
                wifiChecker = wifiChecker,
                onChunkUploaded = { bytes -> onProgress(initialSessionUri, bytes) },
            )
        } catch (_: ResumableSessionExpiredException) {
            val freshSessionUri = driveClient.initiateResumableUpload(
                accessToken = accessToken,
                parentId = recordingsFolderId,
                fileName = fileName,
                mimeType = mimeType,
                totalBytes = file.length(),
                wifiChecker = wifiChecker,
            )
            onProgress(freshSessionUri, 0L)
            driveClient.uploadChunkedResumable(
                accessToken = accessToken,
                sessionUri = freshSessionUri,
                file = file,
                startOffset = 0L,
                chunkSizeBytes = chunkSizeBytes,
                wifiChecker = wifiChecker,
                onChunkUploaded = { bytes -> onProgress(freshSessionUri, bytes) },
            )
        }
    }
}
