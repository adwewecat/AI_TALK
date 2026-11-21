package com.adwewecat.nana.core.sync

import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.MemoryFact
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Reinstall-safe restore coordinator that downloads `AI_TALK_NaNa/manifest.json`,
 * memory facts, and monthly message logs from Drive and merges them idempotently by ID.
 */
class DriveRestoreCoordinator(
    private val wifiChecker: WifiNetworkChecker,
    private val driveClient: GoogleDriveClient,
    private val storage: SyncStorageGateway,
) {
    /**
     * Restores backed-up memories and messages from Drive if present.
     * By default enforces unmetered Wi-Fi unless [allowMeteredOverride] is explicitly `true`.
     */
    suspend fun restoreIfAvailable(
        accessToken: String,
        allowMeteredOverride: Boolean = false,
    ): SyncOutcome {
        if (!allowMeteredOverride && !wifiChecker.isUnmeteredWifiConnected()) {
            return SyncOutcome.PAUSED_NO_WIFI
        }
        if (accessToken.isBlank()) {
            return SyncOutcome.NEEDS_REAUTH
        }

        val effectiveChecker = if (allowMeteredOverride) ALLOW_ANY_NETWORK else wifiChecker

        return try {
            val rootId = driveClient.findFileByName(
                accessToken = accessToken,
                name = DriveLayout.ROOT_FOLDER,
                parentId = null,
                mimeType = GoogleDriveClient.FOLDER_MIME_TYPE,
                wifiChecker = effectiveChecker,
            ) ?: return SyncOutcome.COMPLETED

            val manifestFileId = driveClient.findFileByName(
                accessToken = accessToken,
                name = DriveLayout.MANIFEST_FILE,
                parentId = rootId,
                mimeType = null,
                wifiChecker = effectiveChecker,
            ) ?: return SyncOutcome.COMPLETED

            val manifestRaw = driveClient.downloadTextFile(
                accessToken = accessToken,
                fileId = manifestFileId,
                wifiChecker = effectiveChecker,
            )
            if (manifestRaw.isBlank()) {
                return SyncOutcome.COMPLETED
            }

            val manifest = DriveLayout.json.decodeFromString<SyncManifest>(manifestRaw)

            manifest.memoryFileId?.takeIf { it.isNotBlank() }?.let { memoryFileId ->
                val memoryRaw = driveClient.downloadTextFile(
                    accessToken = accessToken,
                    fileId = memoryFileId,
                    wifiChecker = effectiveChecker,
                )
                if (memoryRaw.isNotBlank()) {
                    val facts = DriveLayout.json.decodeFromString<List<MemoryFact>>(memoryRaw)
                    storage.mergeRestoredMemoryFacts(facts)
                }
            }

            for ((_, historyFileId) in manifest.historyFileIds.toSortedMap()) {
                if (historyFileId.isBlank()) continue
                val historyRaw = driveClient.downloadTextFile(
                    accessToken = accessToken,
                    fileId = historyFileId,
                    wifiChecker = effectiveChecker,
                )
                val messages = parseHistoryMessages(historyRaw)
                if (messages.isNotEmpty()) {
                    storage.mergeRestoredMessages(messages)
                }
            }

            storage.saveManifest(manifest, manifest.updatedAtMs)
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

    private fun parseHistoryMessages(raw: String): List<ChatMessage> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            return DriveLayout.json.decodeFromString<List<ChatMessage>>(trimmed)
        }
        return trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> DriveLayout.json.decodeFromString<ChatMessage>(line) }
            .toList()
    }

    private companion object {
        val ALLOW_ANY_NETWORK = object : WifiNetworkChecker {
            override fun isUnmeteredWifiConnected(): Boolean = true
        }
    }
}
