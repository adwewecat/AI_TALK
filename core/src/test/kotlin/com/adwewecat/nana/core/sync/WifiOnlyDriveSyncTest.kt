package com.adwewecat.nana.core.sync

import com.adwewecat.nana.core.model.CallRecordingRecord
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.MemoryCategory
import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.RecordingSource
import com.adwewecat.nana.core.model.SyncStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WifiOnlyDriveSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    @Test
    fun syncAbortsImmediatelyWithoutNetworkCallsWhenNotOnUnmeteredWifi() = runTest {
        val stagedFile = createPatternFile("call_no_wifi.m4a", 32_768)
        val storage = FakeSyncStorageGateway(
            messages = listOf(sampleMessage("msg-1", "Anh về chưa?", synced = false)),
            memories = listOf(sampleMemory("mem-1", "drink", "Trà đào", synced = false)),
            recordings = listOf(sampleRecording("rec-1", stagedFile)),
        )
        val wifiChecker = object : WifiNetworkChecker {
            override fun isUnmeteredWifiConnected(): Boolean = false
        }
        val driveClient = GoogleDriveClient(httpClient = httpClient, apiBaseUrl = baseUrl())
        val coordinator = WifiOnlySyncCoordinator(
            wifiChecker = wifiChecker,
            driveClient = driveClient,
            storage = storage,
            clockMs = { 10_000L },
        )

        val outcome = coordinator.syncNow("token-abc")

        assertEquals(SyncOutcome.PAUSED_NO_WIFI, outcome)
        assertEquals(0, server.requestCount)
        assertEquals(1, storage.getUnsyncedMessages().size)
        assertTrue(storage.hasUnsyncedMemoryFacts())
        assertEquals(SyncStatus.PENDING, storage.getPendingRecordings().single().syncStatus)
        assertEquals(0L, storage.getPendingRecordings().single().uploadedBytes)
        assertTrue(stagedFile.exists())
    }

    @Test
    fun resumableUploadAbortsMidTransferWhenWifiDropsAndResumesCleanly() = runTest {
        val totalBytes = 600 * 1024
        val chunkSize = 256 * 1024 // 262_144
        val stagedFile = createPatternFile("call_600kb.m4a", totalBytes)
        val expectedBytes = stagedFile.readBytes()

        val storage = FakeSyncStorageGateway(
            recordings = listOf(sampleRecording("rec-600k", stagedFile)),
            savedFolderIds = SyncFolderIds(
                rootId = "folder-root",
                profileId = "folder-profile",
                historyId = "folder-history",
                recordingsId = "folder-recordings",
                manifestFileId = "file-manifest",
            ),
        )

        val wifiConnected = AtomicBoolean(true)
        val wifiChecker = object : WifiNetworkChecker {
            override fun isUnmeteredWifiConnected(): Boolean = wifiConnected.get()
        }

        val sessionPath = "/upload/drive/v3/files?uploadType=resumable&upload_id=sess-600k"
        val receivedUploadBytes = ByteArrayOutputStream()
        val observedContentRanges = mutableListOf<String>()
        var initiateCalls = 0
        var chunkPutCalls = 0

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "POST" && path == "/upload/drive/v3/files?uploadType=resumable" -> {
                        initiateCalls++
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Location", "${baseUrl()}$sessionPath")
                    }

                    request.method == "PUT" && path == sessionPath -> {
                        chunkPutCalls++
                        val contentRange = request.getHeader("Content-Range").orEmpty()
                        observedContentRanges += contentRange
                        val chunkBytes = request.body.readByteArray()
                        receivedUploadBytes.write(chunkBytes)

                        when (chunkPutCalls) {
                            1 -> {
                                // Drop Wi-Fi immediately after chunk #1 succeeds so chunk #2 never sends.
                                wifiConnected.set(false)
                                MockResponse()
                                    .setResponseCode(308)
                                    .addHeader("Range", "bytes=0-262143")
                            }

                            2 -> MockResponse()
                                .setResponseCode(308)
                                .addHeader("Range", "bytes=0-524287")

                            3 -> MockResponse()
                                .setResponseCode(200)
                                .setBody("""{"id":"drive-rec-600k"}""")

                            else -> MockResponse().setResponseCode(500)
                        }
                    }

                    request.method == "PATCH" && path == "/upload/drive/v3/files/file-manifest?uploadType=multipart" -> {
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"id":"file-manifest"}""")
                    }

                    else -> MockResponse().setResponseCode(404).setBody("""{"error":"Unexpected $path"}""")
                }
            }
        }

        val driveClient = GoogleDriveClient(httpClient = httpClient, apiBaseUrl = baseUrl())
        val coordinator = WifiOnlySyncCoordinator(
            wifiChecker = wifiChecker,
            driveClient = driveClient,
            storage = storage,
            clockMs = { 50_000L },
            chunkSizeBytes = chunkSize,
        )

        // Phase 1: Chunk #1 uploads, then Wi-Fi drops before Chunk #2.
        val firstOutcome = coordinator.syncNow("token-resumable")

        assertEquals(SyncOutcome.PAUSED_NO_WIFI, firstOutcome)
        assertEquals(1, initiateCalls)
        assertEquals(1, chunkPutCalls)
        assertEquals(listOf("bytes 0-262143/614400"), observedContentRanges)
        assertTrue(stagedFile.exists())

        val pausedRecord = storage.recordingById("rec-600k")!!
        assertEquals(SyncStatus.PENDING, pausedRecord.syncStatus)
        assertEquals(262_144L, pausedRecord.uploadedBytes)
        assertEquals("${baseUrl()}$sessionPath", pausedRecord.resumeSessionUri)
        assertNull(pausedRecord.driveFileId)

        // Phase 2: Wi-Fi reconnects -> resumes cleanly from byte 262_144 without re-initiating session.
        wifiConnected.set(true)
        val secondOutcome = coordinator.syncNow("token-resumable")

        assertEquals(SyncOutcome.COMPLETED, secondOutcome)
        assertEquals(1, initiateCalls)
        assertEquals(3, chunkPutCalls)
        assertEquals(
            listOf(
                "bytes 0-262143/614400",
                "bytes 262144-524287/614400",
                "bytes 524288-614399/614400",
            ),
            observedContentRanges,
        )
        assertArrayEquals(expectedBytes, receivedUploadBytes.toByteArray())

        val syncedRecord = storage.recordingById("rec-600k")!!
        assertEquals(SyncStatus.SYNCED, syncedRecord.syncStatus)
        assertEquals("drive-rec-600k", syncedRecord.driveFileId)
        assertEquals(50_000L, syncedRecord.syncedAtMs)
        assertEquals("drive-rec-600k", storage.getSavedManifest()?.recordingDriveIds?.get("rec-600k"))
    }

    @Test
    fun unauthorized401ReturnsNeedsReauthWithoutDeletingLocalData() = runTest {
        val secretToken = "ya29.synthetic-secret-oauth-token-7day"
        val stagedFile = createPatternFile("call_401.m4a", 16_384)
        val storage = FakeSyncStorageGateway(
            messages = listOf(sampleMessage("msg-keep", "Nhớ lưu tin này nha", synced = false)),
            memories = listOf(sampleMemory("mem-keep", "pet", "Mèo Mướp", synced = false)),
            recordings = listOf(sampleRecording("rec-keep", stagedFile)),
        )
        val wifiChecker = object : WifiNetworkChecker {
            override fun isUnmeteredWifiConnected(): Boolean = true
        }

        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":401,"message":"Invalid credentials for $secretToken"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"code":401,"message":"Invalid token $secretToken"}}"""),
        )

        val driveClient = GoogleDriveClient(httpClient = httpClient, apiBaseUrl = baseUrl())
        val coordinator = WifiOnlySyncCoordinator(
            wifiChecker = wifiChecker,
            driveClient = driveClient,
            storage = storage,
        )

        val syncOutcome = coordinator.syncNow(secretToken)
        assertEquals(SyncOutcome.NEEDS_REAUTH, syncOutcome)
        assertEquals(1, storage.getUnsyncedMessages().size)
        assertTrue(storage.hasUnsyncedMemoryFacts())
        assertEquals(1, storage.getPendingRecordings().size)
        assertTrue(stagedFile.exists())

        var thrown: DriveUnauthorizedException? = null
        try {
            driveClient.downloadTextFile(secretToken, "any-file-id", wifiChecker)
        } catch (e: DriveUnauthorizedException) {
            thrown = e
        }
        assertTrue(thrown != null)
        assertFalse((thrown?.message ?: "").contains(secretToken))
    }

    @Test
    fun restoreMergesManifestMemoriesAndMessagesIdempotently() = runTest {
        val existingMessage = sampleMessage("msg-1", "Tin cũ", createdAtMs = 1_793_500_000_000L, synced = true)
        val existingMemory = sampleMemory("mem-1", "city", "Sài Gòn", synced = true)
        val storage = FakeSyncStorageGateway(
            messages = listOf(existingMessage),
            memories = listOf(existingMemory),
        )

        val restoredMem2 = sampleMemory("mem-2", "song", "Lofi chill", synced = true)
        val restoredMsg2 = sampleMessage("msg-2", "Dạ em nhớ rồi anh ơi!", createdAtMs = 1_793_500_010_000L, synced = true)
        val manifest = SyncManifest(
            schemaVersion = 1,
            updatedAtMs = 99_000L,
            memoryFileId = "drv-mem-file",
            historyFileIds = mapOf("2026-11" to "drv-hist-2026-11"),
            recordingDriveIds = mapOf("rec-1" to "drv-rec-1"),
        )
        val manifestJson = json.encodeToString(manifest)
        val memoryJson = json.encodeToString(listOf(existingMemory, restoredMem2))
        val historyJsonl = listOf(existingMessage, restoredMsg2).joinToString("\n") {
            json.encodeToString(it)
        }

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.startsWith("/drive/v3/files?") && path.contains("AI_TALK_NaNa") ->
                        MockResponse().setResponseCode(200).setBody("""{"files":[{"id":"drv-root"}]}""")

                    request.method == "GET" && path.startsWith("/drive/v3/files?") && path.contains("manifest.json") ->
                        MockResponse().setResponseCode(200).setBody("""{"files":[{"id":"drv-manifest"}]}""")

                    request.method == "GET" && path == "/drive/v3/files/drv-manifest?alt=media" ->
                        MockResponse().setResponseCode(200).setBody(manifestJson)

                    request.method == "GET" && path == "/drive/v3/files/drv-mem-file?alt=media" ->
                        MockResponse().setResponseCode(200).setBody(memoryJson)

                    request.method == "GET" && path == "/drive/v3/files/drv-hist-2026-11?alt=media" ->
                        MockResponse().setResponseCode(200).setBody(historyJsonl)

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val wifiConnected = AtomicBoolean(false)
        val wifiChecker = object : WifiNetworkChecker {
            override fun isUnmeteredWifiConnected(): Boolean = wifiConnected.get()
        }
        val driveClient = GoogleDriveClient(httpClient = httpClient, apiBaseUrl = baseUrl())
        val restoreCoordinator = DriveRestoreCoordinator(
            wifiChecker = wifiChecker,
            driveClient = driveClient,
            storage = storage,
        )

        // Default strict mode aborts without network calls if not on unmetered Wi-Fi.
        assertEquals(
            SyncOutcome.PAUSED_NO_WIFI,
            restoreCoordinator.restoreIfAvailable("token-restore", allowMeteredOverride = false),
        )
        assertEquals(0, server.requestCount)

        // Metered override works even when unmetered Wi-Fi is off, and repeat Wi-Fi run stays idempotent.
        val firstRestore = restoreCoordinator.restoreIfAvailable("token-restore", allowMeteredOverride = true)
        assertEquals(SyncOutcome.COMPLETED, firstRestore)
        assertEquals(2, storage.getAllMemoryFacts().size)
        assertEquals(2, storage.allMessages().size)

        wifiConnected.set(true)
        val secondRestore = restoreCoordinator.restoreIfAvailable("token-restore", allowMeteredOverride = false)
        assertEquals(SyncOutcome.COMPLETED, secondRestore)
        assertEquals(listOf("mem-1", "mem-2"), storage.getAllMemoryFacts().map { it.id }.sorted())
        assertEquals(listOf("msg-1", "msg-2"), storage.allMessages().map { it.id }.sorted())
        assertEquals(manifest, storage.getSavedManifest())
    }

    @Test
    fun retentionPolicyNeverDeletesUnsyncedOrRecentRecordings() {
        val dayMs = 86_400_000L
        val nowMs = 30L * dayMs
        val dummyFile = tempFolder.newFile("dummy.m4a")

        val records = listOf(
            sampleRecording("rec-pending", dummyFile, syncStatus = SyncStatus.PENDING, driveFileId = null, syncedAtMs = null),
            sampleRecording("rec-uploading", dummyFile, syncStatus = SyncStatus.UPLOADING, driveFileId = null, syncedAtMs = null),
            sampleRecording("rec-failed", dummyFile, syncStatus = SyncStatus.FAILED, driveFileId = null, syncedAtMs = null),
            sampleRecording("rec-no-drive-id", dummyFile, syncStatus = SyncStatus.SYNCED, driveFileId = null, syncedAtMs = nowMs - 10L * dayMs),
            sampleRecording("rec-too-recent", dummyFile, syncStatus = SyncStatus.SYNCED, driveFileId = "drv-recent", syncedAtMs = nowMs - (7L * dayMs - 1L)),
            sampleRecording("rec-no-staged-path", null, syncStatus = SyncStatus.SYNCED, driveFileId = "drv-null", syncedAtMs = nowMs - 10L * dayMs),
            sampleRecording("rec-exact-7d", dummyFile, syncStatus = SyncStatus.SYNCED, driveFileId = "drv-7d", syncedAtMs = nowMs - 7L * dayMs),
            sampleRecording("rec-old-14d", dummyFile, syncStatus = SyncStatus.SYNCED, driveFileId = "drv-14d", syncedAtMs = nowMs - 14L * dayMs),
        )

        val defaultPolicy = CallRecordingRetentionPolicy(retentionDaysAfterVerifiedSync = 7, keepForever = false)
        val eligible = defaultPolicy.selectStagedFilesEligibleForCleanup(records, nowMs)
        assertEquals(listOf("rec-exact-7d", "rec-old-14d"), eligible.map { it.id })

        val keepForeverPolicy = CallRecordingRetentionPolicy(retentionDaysAfterVerifiedSync = 7, keepForever = true)
        assertEquals(emptyList<CallRecordingRecord>(), keepForeverPolicy.selectStagedFilesEligibleForCleanup(records, nowMs))
    }

    @Test
    fun syncCreatesDriveFolderLayoutAndPatchesExistingFilesOnSubsequentSync() = runTest {
        val storage = FakeSyncStorageGateway(
            messages = listOf(sampleMessage("msg-1", "Chào Na Na", createdAtMs = 1_793_500_000_000L, synced = false)),
            memories = listOf(sampleMemory("mem-1", "tea", "Trà sữa ít đường", synced = false)),
        )
        val wifiChecker = object : WifiNetworkChecker {
            override fun isUnmeteredWifiConnected(): Boolean = true
        }

        val createdFolders = mutableListOf<String>()
        val patchPaths = mutableListOf<String>()
        var postMultipartCount = 0

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.startsWith("/drive/v3/files?") ->
                        MockResponse().setResponseCode(200).setBody("""{"files":[]}""")

                    request.method == "POST" && path == "/drive/v3/files" -> {
                        val body = request.body.readUtf8()
                        val folderId = when {
                            body.contains("AI_TALK_NaNa") -> "f-root"
                            body.contains("\"profile\"") -> "f-profile"
                            body.contains("\"history\"") -> "f-history"
                            body.contains("call_recordings") -> "f-rec"
                            else -> "f-unknown"
                        }
                        createdFolders += folderId
                        MockResponse().setResponseCode(200).setBody("""{"id":"$folderId"}""")
                    }

                    request.method == "POST" && path == "/upload/drive/v3/files?uploadType=multipart" -> {
                        postMultipartCount++
                        val body = request.body.readUtf8()
                        val id = when {
                            body.contains("memory_facts.json") -> "id-mem-1"
                            body.contains("messages_2026-11.jsonl") -> "id-hist-1"
                            body.contains("manifest.json") -> "id-manifest-1"
                            else -> "id-other"
                        }
                        MockResponse().setResponseCode(200).setBody("""{"id":"$id"}""")
                    }

                    request.method == "PATCH" && path.startsWith("/upload/drive/v3/files/") -> {
                        patchPaths += path
                        val id = path.substringAfter("/upload/drive/v3/files/").substringBefore("?")
                        MockResponse().setResponseCode(200).setBody("""{"id":"$id"}""")
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val driveClient = GoogleDriveClient(httpClient = httpClient, apiBaseUrl = baseUrl())
        val coordinator = WifiOnlySyncCoordinator(
            wifiChecker = wifiChecker,
            driveClient = driveClient,
            storage = storage,
            clockMs = { 77_000L },
        )

        assertEquals(SyncOutcome.COMPLETED, coordinator.syncNow("token-layout"))
        assertEquals(listOf("f-root", "f-profile", "f-history", "f-rec"), createdFolders)
        assertEquals(3, postMultipartCount)
        assertEquals(0, patchPaths.size)
        assertEquals("id-mem-1", storage.getSavedManifest()?.memoryFileId)
        assertEquals("id-hist-1", storage.getSavedManifest()?.historyFileIds?.get("2026-11"))
        assertEquals("id-manifest-1", storage.getSavedFolderIds()?.manifestFileId)

        // Second sync with a new message should PATCH existing history & manifest files instead of creating duplicates.
        storage.mergeOrAddUnsyncedMessage(sampleMessage("msg-2", "Ngủ ngon nhé", createdAtMs = 1_793_500_020_000L, synced = false))
        assertEquals(SyncOutcome.COMPLETED, coordinator.syncNow("token-layout"))
        assertEquals(3, postMultipartCount)
        assertEquals(
            listOf(
                "/upload/drive/v3/files/id-hist-1?uploadType=multipart",
                "/upload/drive/v3/files/id-manifest-1?uploadType=multipart",
            ),
            patchPaths,
        )
    }

    @Test
    fun partialSyncPreservesUploadedManifestIdsAcrossWifiDrop() = runTest {
        val stagedFile = createPatternFile("call_partial.m4a", 300_000)
        val storage = FakeSyncStorageGateway(
            memories = listOf(sampleMemory("mem-early", "food", "Bún bò", synced = false)),
            recordings = listOf(sampleRecording("rec-partial", stagedFile)),
            savedFolderIds = SyncFolderIds("f-root", "f-profile", "f-history", "f-rec"),
        )
        val wifiConnected = AtomicBoolean(true)
        val wifiChecker = object : WifiNetworkChecker {
            override fun isUnmeteredWifiConnected(): Boolean = wifiConnected.get()
        }

        val sessionPath = "/upload/drive/v3/files?uploadType=resumable&upload_id=sess-partial"
        var capturedManifestBody: String? = null

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    request.method == "GET" && path.startsWith("/drive/v3/files?") ->
                        MockResponse().setResponseCode(200).setBody("""{"files":[]}""")

                    request.method == "POST" && path == "/upload/drive/v3/files?uploadType=multipart" -> {
                        val body = request.body.readUtf8()
                        val id = if (body.contains("memory_facts.json")) "drv-mem-early" else "drv-manifest"
                        if (body.contains("manifest.json")) {
                            capturedManifestBody = body
                        }
                        MockResponse().setResponseCode(200).setBody("""{"id":"$id"}""")
                    }

                    request.method == "POST" && path == "/upload/drive/v3/files?uploadType=resumable" -> {
                        // Drop Wi-Fi right after initiation so chunk #1 aborts before sending.
                        wifiConnected.set(false)
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Location", "${baseUrl()}$sessionPath")
                    }

                    request.method == "PUT" && path == sessionPath -> {
                        val range = request.getHeader("Content-Range").orEmpty()
                        if (range.startsWith("bytes 0-")) {
                            MockResponse().setResponseCode(308)
                        } else {
                            MockResponse().setResponseCode(200).setBody("""{"id":"drv-rec-partial"}""")
                        }
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val driveClient = GoogleDriveClient(httpClient = httpClient, apiBaseUrl = baseUrl())
        val coordinator = WifiOnlySyncCoordinator(
            wifiChecker = wifiChecker,
            driveClient = driveClient,
            storage = storage,
            clockMs = { 88_000L },
        )

        assertEquals(SyncOutcome.PAUSED_NO_WIFI, coordinator.syncNow("token-partial"))
        assertFalse(storage.hasUnsyncedMemoryFacts())

        // Resume on Wi-Fi: final manifest must still include drv-mem-early alongside drv-rec-partial!
        wifiConnected.set(true)
        assertEquals(SyncOutcome.COMPLETED, coordinator.syncNow("token-partial"))
        assertEquals("drv-mem-early", storage.getSavedManifest()?.memoryFileId)
        assertEquals("drv-rec-partial", storage.getSavedManifest()?.recordingDriveIds?.get("rec-partial"))
        assertTrue(capturedManifestBody.orEmpty().contains("drv-mem-early"))
    }

    @Test
    fun expiredResumableSession404ReinitiatesAndServer503ReturnsRetryableError() = runTest {
        val stagedFile = createPatternFile("call_expired.m4a", 10_000)
        val staleSessionUrl = "${baseUrl()}/upload/drive/v3/files?uploadType=resumable&upload_id=stale"
        val freshSessionPath = "/upload/drive/v3/files?uploadType=resumable&upload_id=fresh"
        val staleRec = sampleRecording("rec-exp", stagedFile).copy(
            resumeSessionUri = staleSessionUrl,
            uploadedBytes = 4_096L,
            syncStatus = SyncStatus.PENDING,
        )
        val storage = FakeSyncStorageGateway(
            recordings = listOf(staleRec),
            savedFolderIds = SyncFolderIds("f-root", "f-profile", "f-history", "f-rec", "f-manifest"),
        )
        val wifiChecker = object : WifiNetworkChecker {
            override fun isUnmeteredWifiConnected(): Boolean = true
        }

        // First test: 503 during PUT returns RETRYABLE_ERROR while preserving session progress.
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"backend unavailable"}"""))

        val driveClient = GoogleDriveClient(httpClient = httpClient, apiBaseUrl = baseUrl())
        val coordinator = WifiOnlySyncCoordinator(
            wifiChecker = wifiChecker,
            driveClient = driveClient,
            storage = storage,
            clockMs = { 99_000L },
        )

        assertEquals(SyncOutcome.RETRYABLE_ERROR, coordinator.syncNow("token-retry"))
        assertEquals(SyncStatus.PENDING, storage.recordingById("rec-exp")?.syncStatus)
        assertEquals(staleSessionUrl, storage.recordingById("rec-exp")?.resumeSessionUri)
        assertEquals(4_096L, storage.recordingById("rec-exp")?.uploadedBytes)

        // Second test: 404 expired session triggers fresh initiation and succeeds cleanly.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Location", "${baseUrl()}$freshSessionPath"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"drv-rec-fresh"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"f-manifest"}"""))

        assertEquals(SyncOutcome.COMPLETED, coordinator.syncNow("token-retry"))
        val synced = storage.recordingById("rec-exp")!!
        assertEquals(SyncStatus.SYNCED, synced.syncStatus)
        assertEquals("drv-rec-fresh", synced.driveFileId)
    }

    private fun createPatternFile(name: String, sizeBytes: Int): File {
        val file = tempFolder.newFile(name)
        val bytes = ByteArray(sizeBytes) { index -> ((index * 31 + 7) and 0xFF).toByte() }
        file.writeBytes(bytes)
        return file
    }

    private fun sampleMessage(
        id: String,
        content: String,
        createdAtMs: Long = 1_793_500_000_000L,
        synced: Boolean = false,
    ) = ChatMessage(
        id = id,
        sessionId = "session-1",
        role = "user",
        persona = PersonaMode.NANA,
        content = content,
        createdAtMs = createdAtMs,
        synced = synced,
    )

    private fun sampleMemory(
        id: String,
        key: String,
        value: String,
        synced: Boolean = false,
    ) = MemoryFact(
        id = id,
        category = MemoryCategory.PREFERENCE,
        key = key,
        value = value,
        confidence = 0.95f,
        updatedAtMs = 1_793_500_000_000L,
        synced = synced,
    )

    private fun sampleRecording(
        id: String,
        stagedFile: File?,
        syncStatus: SyncStatus = SyncStatus.PENDING,
        driveFileId: String? = null,
        syncedAtMs: Long? = null,
    ) = CallRecordingRecord(
        id = id,
        phoneNumber = "0901234567",
        contactName = "Anh Yêu",
        source = RecordingSource.ORIGINOS_NATIVE,
        startedAtMs = 1_793_500_000_000L,
        durationSec = 120,
        sourceUri = "content://originos/call/$id.m4a",
        stagedPath = stagedFile?.absolutePath,
        fileSizeBytes = stagedFile?.length() ?: 1024L,
        mimeType = "audio/mp4",
        driveFileId = driveFileId,
        resumeSessionUri = null,
        uploadedBytes = 0L,
        syncStatus = syncStatus,
        syncedAtMs = syncedAtMs,
    )

    private class FakeSyncStorageGateway(
        messages: List<ChatMessage> = emptyList(),
        memories: List<MemoryFact> = emptyList(),
        recordings: List<CallRecordingRecord> = emptyList(),
        private var savedFolderIds: SyncFolderIds? = null,
        private var savedManifest: SyncManifest? = null,
    ) : SyncStorageGateway {
        private val messageMap = linkedMapOf<String, ChatMessage>().apply {
            messages.forEach { put(it.id, it) }
        }
        private val memoryMap = linkedMapOf<String, MemoryFact>().apply {
            memories.forEach { put(it.id, it) }
        }
        private val recordingMap = linkedMapOf<String, CallRecordingRecord>().apply {
            recordings.forEach { put(it.id, it) }
        }

        fun allMessages(): List<ChatMessage> = messageMap.values.toList()
        fun recordingById(id: String): CallRecordingRecord? = recordingMap[id]
        fun mergeOrAddUnsyncedMessage(message: ChatMessage) {
            messageMap[message.id] = message
        }

        override suspend fun getUnsyncedMessages(): List<ChatMessage> =
            messageMap.values.filter { !it.synced }

        override suspend fun getAllMessagesForMonth(yearMonth: String): List<ChatMessage> =
            messageMap.values.filter { utcYearMonth(it.createdAtMs) == yearMonth }

        override suspend fun markMessagesSynced(ids: List<String>) {
            ids.forEach { id ->
                messageMap[id]?.let { messageMap[id] = it.copy(synced = true) }
            }
        }

        override suspend fun getAllMemoryFacts(): List<MemoryFact> = memoryMap.values.toList()

        override suspend fun hasUnsyncedMemoryFacts(): Boolean =
            memoryMap.values.any { !it.synced }

        override suspend fun markAllMemoryFactsSynced() {
            memoryMap.keys.toList().forEach { id ->
                memoryMap[id]?.let { memoryMap[id] = it.copy(synced = true) }
            }
        }

        override suspend fun getPendingRecordings(): List<CallRecordingRecord> =
            recordingMap.values.filter { it.syncStatus == SyncStatus.PENDING || it.syncStatus == SyncStatus.FAILED }

        override suspend fun updateRecordingProgress(
            id: String,
            sessionUri: String?,
            uploadedBytes: Long,
            status: SyncStatus,
        ) {
            recordingMap[id]?.let {
                recordingMap[id] = it.copy(
                    resumeSessionUri = sessionUri,
                    uploadedBytes = uploadedBytes,
                    syncStatus = status,
                )
            }
        }

        override suspend fun markRecordingSynced(id: String, driveFileId: String, syncedAtMs: Long) {
            recordingMap[id]?.let {
                recordingMap[id] = it.copy(
                    driveFileId = driveFileId,
                    syncStatus = SyncStatus.SYNCED,
                    syncedAtMs = syncedAtMs,
                )
            }
        }

        override suspend fun getSavedFolderIds(): SyncFolderIds? = savedFolderIds

        override suspend fun saveFolderIds(ids: SyncFolderIds) {
            savedFolderIds = ids
        }

        override suspend fun getSavedManifest(): SyncManifest? = savedManifest

        override suspend fun saveManifest(manifest: SyncManifest, syncedAtMs: Long) {
            savedManifest = manifest
        }

        override suspend fun mergeRestoredMemoryFacts(facts: List<MemoryFact>) {
            facts.forEach { fact ->
                if (!memoryMap.containsKey(fact.id)) {
                    memoryMap[fact.id] = fact.copy(synced = true)
                }
            }
        }

        override suspend fun mergeRestoredMessages(messages: List<ChatMessage>) {
            messages.forEach { msg ->
                if (!messageMap.containsKey(msg.id)) {
                    messageMap[msg.id] = msg.copy(synced = true)
                }
            }
        }

        private fun utcYearMonth(createdAtMs: Long): String =
            YEAR_MONTH_FORMATTER.format(Instant.ofEpochMilli(createdAtMs).atZone(ZoneOffset.UTC))

        private companion object {
            val YEAR_MONTH_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        }
    }
}
