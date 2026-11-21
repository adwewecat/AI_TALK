package com.adwewecat.nana.core.sync

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Google Drive REST API v3 client enforcing pre-request and pre-chunk unmetered Wi-Fi checks
 * and token redaction across all error paths.
 */
class GoogleDriveClient(
    private val httpClient: OkHttpClient,
    private val apiBaseUrl: String = "https://www.googleapis.com",
) {
    private val normalizedBaseUrl = apiBaseUrl.trimEnd('/')

    /** Finds the first non-trashed Drive file or folder ID matching [name], optional [parentId], and [mimeType]. */
    suspend fun findFileByName(
        accessToken: String,
        name: String,
        parentId: String?,
        mimeType: String? = null,
        wifiChecker: WifiNetworkChecker,
    ): String? {
        val clauses = buildList {
            add("name = '${escapeQueryLiteral(name)}'")
            add("trashed = false")
            if (!parentId.isNullOrBlank()) {
                add("'${escapeQueryLiteral(parentId)}' in parents")
            }
            if (!mimeType.isNullOrBlank()) {
                add("mimeType = '${escapeQueryLiteral(mimeType)}'")
            }
        }
        val url = "$normalizedBaseUrl/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", clauses.joinToString(" and "))
            .addQueryParameter("fields", "files(id,name,mimeType)")
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("pageSize", "10")
            .build()

        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        val response = executeCall(requestBuilder, accessToken, wifiChecker)
        ensureSuccess(response, accessToken, "findFileByName")

        return try {
            json.decodeFromString<DriveFileListResponse>(response.bodyText())
                .files
                .firstOrNull()
                ?.id
                ?.takeIf { it.isNotBlank() }
        } catch (e: SerializationException) {
            throw IOException(redactToken(e.message ?: "Malformed Drive file list JSON", accessToken))
        }
    }

    /** Finds an existing Drive folder under [parentId] or creates it if absent. */
    suspend fun findOrCreateFolder(
        accessToken: String,
        name: String,
        parentId: String?,
        wifiChecker: WifiNetworkChecker,
    ): String {
        findFileByName(
            accessToken = accessToken,
            name = name,
            parentId = parentId,
            mimeType = FOLDER_MIME_TYPE,
            wifiChecker = wifiChecker,
        )?.let { return it }

        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME_TYPE)
            if (!parentId.isNullOrBlank()) {
                putJsonArray("parents") { add(JsonPrimitive(parentId)) }
            }
        }
        val requestBuilder = Request.Builder()
            .url("$normalizedBaseUrl/drive/v3/files")
            .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))

        val response = executeCall(requestBuilder, accessToken, wifiChecker)
        ensureSuccess(response, accessToken, "createFolder")
        return parseFileId(response.bodyText(), accessToken, "createFolder")
    }

    /** Creates or updates a small UTF-8 text/JSON file using Drive v3 `uploadType=multipart`. */
    suspend fun upsertJsonFile(
        accessToken: String,
        parentId: String,
        fileName: String,
        content: String,
        contentMimeType: String = "application/json",
        existingFileId: String? = null,
        wifiChecker: WifiNetworkChecker,
    ): String {
        val targetId = existingFileId?.takeIf { it.isNotBlank() }
            ?: findFileByName(
                accessToken = accessToken,
                name = fileName,
                parentId = parentId,
                mimeType = null,
                wifiChecker = wifiChecker,
            )

        val response = executeMultipartUpsert(
            accessToken = accessToken,
            parentId = parentId,
            fileName = fileName,
            content = content,
            contentMimeType = contentMimeType,
            existingFileId = targetId,
            wifiChecker = wifiChecker,
        )

        val finalResponse = if (targetId != null && response.code == 404) {
            // Re-create if the cached Drive file ID was deleted remotely.
            executeMultipartUpsert(
                accessToken = accessToken,
                parentId = parentId,
                fileName = fileName,
                content = content,
                contentMimeType = contentMimeType,
                existingFileId = null,
                wifiChecker = wifiChecker,
            )
        } else {
            response
        }

        ensureSuccess(finalResponse, accessToken, "upsertJsonFile")
        return parseFileId(finalResponse.bodyText(), accessToken, "upsertJsonFile")
    }

    /** Downloads the raw UTF-8 text content of a Drive file by [fileId]. */
    suspend fun downloadTextFile(
        accessToken: String,
        fileId: String,
        wifiChecker: WifiNetworkChecker? = null,
    ): String {
        val url = "$normalizedBaseUrl/drive/v3/files".toHttpUrl().newBuilder()
            .addPathSegment(fileId)
            .addQueryParameter("alt", "media")
            .build()

        val requestBuilder = Request.Builder()
            .url(url)
            .get()

        val response = executeCall(requestBuilder, accessToken, wifiChecker)
        ensureSuccess(response, accessToken, "downloadTextFile")
        return response.bodyText()
    }

    /** Initiates a Drive v3 resumable upload session and returns the `Location` session URI. */
    suspend fun initiateResumableUpload(
        accessToken: String,
        parentId: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        wifiChecker: WifiNetworkChecker,
    ): String {
        require(totalBytes >= 0L) { "totalBytes must be >= 0" }
        val metadata = buildJsonObject {
            put("name", fileName)
            put("mimeType", mimeType)
            if (parentId.isNotBlank()) {
                putJsonArray("parents") { add(JsonPrimitive(parentId)) }
            }
        }

        val requestBuilder = Request.Builder()
            .url("$normalizedBaseUrl/upload/drive/v3/files?uploadType=resumable")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", totalBytes.toString())
            .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))

        val response = executeCall(requestBuilder, accessToken, wifiChecker)
        ensureSuccess(response, accessToken, "initiateResumableUpload")

        val location = response.locationHeader?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IOException("Missing Location header in resumable upload response")
        return if (location.startsWith("/")) "$normalizedBaseUrl$location" else location
    }

    /**
     * Uploads [file] to [sessionUri] in [chunkSizeBytes] slices starting at [startOffset].
     * Verifies [wifiChecker] immediately before every chunk request and reports acknowledged byte offsets.
     */
    suspend fun uploadChunkedResumable(
        accessToken: String,
        sessionUri: String,
        file: File,
        startOffset: Long,
        chunkSizeBytes: Int = 262_144,
        wifiChecker: WifiNetworkChecker,
        onChunkUploaded: suspend (Long) -> Unit,
    ): String {
        require(chunkSizeBytes > 0) { "chunkSizeBytes must be > 0" }
        require(startOffset >= 0L) { "startOffset must be >= 0" }

        val totalBytes = withContext(Dispatchers.IO) {
            if (!file.exists() || !file.isFile) {
                throw IOException("Recording file not found: ${file.name}")
            }
            val length = file.length()
            if (length <= 0L) {
                throw IOException("Recording file is empty: ${file.name}")
            }
            length
        }

        var offset = startOffset
        if (offset >= totalBytes) {
            val probeResult = probeSessionStatus(accessToken, sessionUri, totalBytes, wifiChecker)
            when (probeResult) {
                is SessionProbeResult.Completed -> {
                    onChunkUploaded(totalBytes)
                    return probeResult.fileId
                }
                is SessionProbeResult.Incomplete -> {
                    offset = probeResult.nextOffset
                    onChunkUploaded(offset)
                }
            }
        }

        while (offset < totalBytes) {
            ensureUnmeteredWifi(wifiChecker)

            val chunkLen = minOf(chunkSizeBytes.toLong(), totalBytes - offset).toInt()
            val endInclusive = offset + chunkLen - 1L
            val expectedNextOffset = offset + chunkLen

            val chunkBytes = readFileSlice(file, offset, chunkLen)
            val requestBuilder = Request.Builder()
                .url(sessionUri)
                .header("Content-Range", "bytes $offset-$endInclusive/$totalBytes")
                .put(chunkBytes.toRequestBody(OCTET_STREAM_MEDIA_TYPE))

            val response = executeCall(requestBuilder, accessToken, wifiChecker)
            when (response.code) {
                401 -> throw DriveUnauthorizedException("Google Drive returned 401 Unauthorized (uploadChunk)")
                404, 410 -> throw ResumableSessionExpiredException(
                    "Google Drive resumable session expired (HTTP ${response.code})",
                )
                200, 201 -> {
                    onChunkUploaded(totalBytes)
                    return parseFileId(response.bodyText(), accessToken, "uploadChunkComplete")
                }
                308 -> {
                    val acknowledgedOffset = parseRangeNextOffset(response.rangeHeader, totalBytes)
                        ?: expectedNextOffset
                    if (acknowledgedOffset <= offset) {
                        throw IOException("Resumable upload stalled at byte $offset")
                    }
                    offset = acknowledgedOffset
                    onChunkUploaded(offset)
                    if (offset >= totalBytes) {
                        throw IOException("Drive returned 308 Resume Incomplete after final byte")
                    }
                }
                else -> throw IOException(
                    formatHttpError(response.code, response.bodyText(), accessToken, "uploadChunk"),
                )
            }
        }

        throw IOException("Resumable upload ended without completion file ID")
    }

    private suspend fun executeMultipartUpsert(
        accessToken: String,
        parentId: String,
        fileName: String,
        content: String,
        contentMimeType: String,
        existingFileId: String?,
        wifiChecker: WifiNetworkChecker,
    ): DriveHttpResponse {
        val metadata = buildJsonObject {
            put("name", fileName)
            put("mimeType", contentMimeType)
            if (existingFileId == null && parentId.isNotBlank()) {
                putJsonArray("parents") { add(JsonPrimitive(parentId)) }
            }
        }

        val multipartBody = MultipartBody.Builder()
            .setType(MULTIPART_RELATED_MEDIA_TYPE)
            .addPart(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .addPart(content.toRequestBody(contentMimeType.toMediaType()))
            .build()

        val requestBuilder = if (existingFileId == null) {
            Request.Builder()
                .url("$normalizedBaseUrl/upload/drive/v3/files?uploadType=multipart")
                .post(multipartBody)
        } else {
            val url = "$normalizedBaseUrl/upload/drive/v3/files".toHttpUrl().newBuilder()
                .addPathSegment(existingFileId)
                .addQueryParameter("uploadType", "multipart")
                .build()
            Request.Builder()
                .url(url)
                .patch(multipartBody)
        }

        return executeCall(requestBuilder, accessToken, wifiChecker)
    }

    private suspend fun probeSessionStatus(
        accessToken: String,
        sessionUri: String,
        totalBytes: Long,
        wifiChecker: WifiNetworkChecker,
    ): SessionProbeResult {
        val requestBuilder = Request.Builder()
            .url(sessionUri)
            .header("Content-Range", "bytes */$totalBytes")
            .put(ByteArray(0).toRequestBody(null))

        val response = executeCall(requestBuilder, accessToken, wifiChecker)
        return when (response.code) {
            401 -> throw DriveUnauthorizedException("Google Drive returned 401 Unauthorized (probeSession)")
            404, 410 -> throw ResumableSessionExpiredException(
                "Google Drive resumable session expired (HTTP ${response.code})",
            )
            200, 201 -> SessionProbeResult.Completed(
                parseFileId(response.bodyText(), accessToken, "probeSessionComplete"),
            )
            308 -> SessionProbeResult.Incomplete(
                parseRangeNextOffset(response.rangeHeader, totalBytes) ?: 0L,
            )
            else -> throw IOException(
                formatHttpError(response.code, response.bodyText(), accessToken, "probeSession"),
            )
        }
    }

    private suspend fun executeCall(
        requestBuilder: Request.Builder,
        accessToken: String,
        wifiChecker: WifiNetworkChecker?,
    ): DriveHttpResponse {
        ensureUnmeteredWifi(wifiChecker)
        val trimmedToken = accessToken.trim()
        if (trimmedToken.isEmpty()) {
            throw DriveUnauthorizedException("Missing Google Drive access token")
        }

        val request = requestBuilder
            .header("Authorization", "Bearer $trimmedToken")
            .build()
        val call = httpClient.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isActive) return
                        val safeMessage = redactToken(e.message ?: "Google Drive network failure", trimmedToken)
                        continuation.resumeWithException(IOException(safeMessage))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use { res ->
                                val bytes = res.body?.bytes() ?: ByteArray(0)
                                val headers: Headers = res.headers
                                if (continuation.isActive) {
                                    continuation.resume(
                                        DriveHttpResponse(
                                            code = res.code,
                                            bodyBytes = bytes,
                                            locationHeader = headers["Location"],
                                            rangeHeader = headers["Range"],
                                        ),
                                    )
                                }
                            }
                        } catch (e: IOException) {
                            if (continuation.isActive) {
                                val safeMessage = redactToken(e.message ?: "Google Drive stream read error", trimmedToken)
                                continuation.resumeWithException(IOException(safeMessage))
                            }
                        }
                    }
                },
            )
        }
    }

    private suspend fun readFileSlice(file: File, offset: Long, length: Int): ByteArray =
        withContext(Dispatchers.IO) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(length)
                var readTotal = 0
                while (readTotal < length) {
                    val read = raf.read(buffer, readTotal, length - readTotal)
                    if (read < 0) {
                        throw IOException("Unexpected EOF reading ${file.name} at offset $offset")
                    }
                    readTotal += read
                }
                buffer
            }
        }

    private fun ensureSuccess(response: DriveHttpResponse, accessToken: String, operation: String) {
        if (response.code == 401) {
            throw DriveUnauthorizedException("Google Drive returned 401 Unauthorized ($operation)")
        }
        if (response.code !in 200..299) {
            throw IOException(formatHttpError(response.code, response.bodyText(), accessToken, operation))
        }
    }

    private fun parseFileId(rawBody: String, accessToken: String, operation: String): String {
        val parsed = try {
            json.decodeFromString<DriveFileId>(rawBody)
        } catch (e: SerializationException) {
            throw IOException(redactToken(e.message ?: "Malformed Drive JSON ($operation)", accessToken))
        }
        return parsed.id?.takeIf { it.isNotBlank() }
            ?: throw IOException("Missing Drive file ID in $operation response")
    }

    private fun parseRangeNextOffset(rangeHeader: String?, totalBytes: Long): Long? {
        val match = rangeHeader?.let { RANGE_END_REGEX.find(it) } ?: return null
        val endInclusive = match.groupValues[1].toLongOrNull() ?: return null
        return (endInclusive + 1L).coerceIn(0L, totalBytes)
    }

    private fun escapeQueryLiteral(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")

    private fun redactToken(text: String, accessToken: String): String {
        val trimmed = accessToken.trim()
        val sanitized = if (trimmed.isNotEmpty()) text.replace(trimmed, "[REDACTED]") else text
        return sanitized.trim().take(400)
    }

    private fun formatHttpError(code: Int, rawBody: String, accessToken: String, operation: String): String {
        val safeBody = redactToken(rawBody, accessToken)
        return if (safeBody.isBlank()) {
            "Google Drive $operation failed with HTTP $code"
        } else {
            "Google Drive $operation failed with HTTP $code: $safeBody"
        }
    }

    private class DriveHttpResponse(
        val code: Int,
        val bodyBytes: ByteArray,
        val locationHeader: String?,
        val rangeHeader: String?,
    ) {
        fun bodyText(): String = bodyBytes.toString(Charsets.UTF_8)
    }

    private sealed interface SessionProbeResult {
        data class Completed(val fileId: String) : SessionProbeResult
        data class Incomplete(val nextOffset: Long) : SessionProbeResult
    }

    @Serializable
    private data class DriveFileListResponse(
        val files: List<DriveFileId> = emptyList(),
    )

    @Serializable
    private data class DriveFileId(
        val id: String? = null,
    )

    companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
        private val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val MULTIPART_RELATED_MEDIA_TYPE = "multipart/related".toMediaType()
        private val RANGE_END_REGEX = Regex("""bytes=\d+-(\d+)""")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
