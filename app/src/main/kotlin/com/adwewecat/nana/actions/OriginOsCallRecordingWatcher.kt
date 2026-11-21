package com.adwewecat.nana.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Watches and scans a user-granted OriginOS SAF folder (e.g. `Record/Call`) using
 * [DocumentsContract], copying complete non-empty audio files into `files/call_recordings_staging/`
 * and feeding them through [CallRecordingIngestionCoordinator].
 */
open class OriginOsCallRecordingWatcher(
    private val context: Context,
    private val coordinator: CallRecordingIngestionCoordinator,
) {

    /** Persists read access to the SAF directory URI returned by `OpenDocumentTree`. */
    fun persistTreePermission(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /** Returns true when read access to [treeUri] is currently persisted. */
    fun hasPersistedPermission(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == treeUri && permission.isReadPermission
        }

    /** Returns (and ensures) the local private directory holding staged call recordings. */
    fun stagingDirectory(): File =
        File(context.filesDir, STAGING_DIR_NAME).apply { mkdirs() }

    /** Scans [treeUri] for new non-empty audio files, stages them locally, and queues Wi-Fi sync. */
    open suspend fun scanAndIngest(treeUri: Uri): Int {
        val candidates = withContext(Dispatchers.IO) {
            discoverAudioCandidates(treeUri)
        }
        if (candidates.isEmpty()) {
            return 0
        }
        return coordinator.ingestNewRecordings(candidates) { candidate ->
            withContext(Dispatchers.IO) {
                copyCandidateToStaging(candidate)
            }
        }
    }

    private fun discoverAudioCandidates(treeUri: Uri): List<DiscoveredRecording> {
        val rootDocId = runCatching {
            if (DocumentsContract.isDocumentUri(context, treeUri)) {
                DocumentsContract.getDocumentId(treeUri)
            } else {
                DocumentsContract.getTreeDocumentId(treeUri)
            }
        }.getOrNull() ?: return emptyList()

        val results = ArrayList<DiscoveredRecording>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootDocId to 0)

        while (queue.isNotEmpty()) {
            val (docId, depth) = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val cursor = runCatching {
                context.contentResolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)
            }.getOrNull() ?: continue

            cursor.use { c ->
                val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (c.moveToNext()) {
                    val childId = if (idIdx >= 0) c.getString(idIdx).orEmpty() else ""
                    if (childId.isBlank()) continue

                    val name = if (nameIdx >= 0) c.getString(nameIdx).orEmpty() else ""
                    val mime = if (mimeIdx >= 0) c.getString(mimeIdx).orEmpty() else ""

                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_FOLDER_DEPTH) {
                            queue.add(childId to depth + 1)
                        }
                        continue
                    }

                    if (!isSupportedAudioFile(name, mime)) {
                        continue
                    }

                    val sizeBytes = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                    val lastModifiedMs = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)

                    results.add(
                        DiscoveredRecording(
                            sourceUri = documentUri.toString(),
                            fileName = name.ifBlank { "call_recording.m4a" },
                            sizeBytes = sizeBytes,
                            mimeType = mime.ifBlank { CallRecordingIngestionCoordinator.inferAudioMimeType(name) },
                            lastModifiedMs = lastModifiedMs,
                            contactHint = extractContactHint(name),
                        ),
                    )
                }
            }
        }
        return results
    }

    private fun copyCandidateToStaging(candidate: DiscoveredRecording): String? {
        if (candidate.sizeBytes <= 0L) {
            return null
        }
        val sourceUri = runCatching { Uri.parse(candidate.sourceUri) }.getOrNull() ?: return null
        val safeBase = sanitizeFileName(candidate.fileName)
        val stagedDir = stagingDirectory()
        val tempFile = File(stagedDir, "${UUID.randomUUID().toString().take(8)}_$safeBase.tmp")
        val finalFile = File(stagedDir, "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}_$safeBase")

        return try {
            var copiedBytes = 0L
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copiedBytes += read
                    }
                    output.flush()
                }
            } ?: return null

            // Guard against partially flushed dialer files changing size during copy.
            if (copiedBytes <= 0L || copiedBytes != candidate.sizeBytes) {
                tempFile.delete()
                return null
            }

            if (tempFile.renameTo(finalFile)) {
                finalFile.absolutePath
            } else {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
                finalFile.absolutePath
            }
        } catch (_: Throwable) {
            tempFile.delete()
            finalFile.delete()
            null
        }
    }

    private fun isSupportedAudioFile(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("audio/", ignoreCase = true)) {
            return true
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun extractContactHint(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        return base
            .removePrefix("Record_")
            .removePrefix("Call_")
            .replace('_', ' ')
            .trim()
            .take(64)
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
            .ifBlank { "recording.m4a" }
            .takeLast(80)

    companion object {
        const val STAGING_DIR_NAME = "call_recordings_staging"
        private const val MAX_FOLDER_DEPTH = 3

        private val AUDIO_EXTENSIONS = setOf(
            "m4a", "amr", "aac", "mp3", "wav", "ogg", "3gp", "opus", "flac",
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
