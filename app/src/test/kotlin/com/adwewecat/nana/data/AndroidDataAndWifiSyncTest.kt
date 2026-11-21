package com.adwewecat.nana.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.CallRecordingRecord
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.MemoryCategory
import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.ProviderType
import com.adwewecat.nana.core.model.RecordingSource
import com.adwewecat.nana.core.model.SyncStatus
import com.adwewecat.nana.core.sync.GoogleDriveClient
import com.adwewecat.nana.core.sync.SyncFolderIds
import com.adwewecat.nana.core.sync.SyncManifest
import com.adwewecat.nana.core.sync.WifiNetworkChecker
import com.adwewecat.nana.data.auth.GoogleDriveAuthManager
import com.adwewecat.nana.data.local.AppSettings
import com.adwewecat.nana.data.local.EncryptedApiKeyVault
import com.adwewecat.nana.data.local.NaNaDatabase
import com.adwewecat.nana.data.local.RoomSyncStorageGateway
import com.adwewecat.nana.data.local.toEntity
import com.adwewecat.nana.data.sync.AndroidWifiNetworkChecker
import com.adwewecat.nana.data.sync.DriveSyncDependencies
import com.adwewecat.nana.data.sync.DriveSyncDependenciesProvider
import com.adwewecat.nana.data.sync.DriveSyncScheduler
import com.adwewecat.nana.data.sync.DriveSyncWorker
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import net.openid.appauth.AppAuthConfiguration
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenResponse
import net.openid.appauth.connectivity.ConnectionBuilder
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidDataAndWifiSyncTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: NaNaDatabase
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = NaNaDatabase.createInMemory(context)
        server = MockWebServer()
        server.start()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test(timeout = 15_000)
    fun roomGatewayDeduplicatesRestoredMessagesAndFacts() = runTest {
        val gateway = RoomSyncStorageGateway(db)
        val localUnsyncedMsg = sampleMessage("msg-local", "Em ơi nhớ nhắc anh", createdAtMs = 1_793_500_000_000L, synced = false)
        val localUnsyncedFact = sampleMemory("mem-local", "coffee", "Bạc xỉu", synced = false)
        db.messageDao().upsert(localUnsyncedMsg.toEntity())
        db.memoryFactDao().upsert(localUnsyncedFact.toEntity())

        val restoredMsg1 = sampleMessage("msg-restored-1", "Chào anh!", createdAtMs = 1_793_500_010_000L, synced = false)
        val restoredMsg2 = sampleMessage("msg-restored-2", "Ngủ ngon nha", createdAtMs = 1_793_500_020_000L, synced = true)
        val restoredFact1 = sampleMemory("mem-restored-1", "drink", "Trà đào", synced = false)
        val restoredFact2 = sampleMemory("mem-restored-2", "pet", "Mèo Mun", synced = true)

        // Merge twice with overlapping IDs (including local unsynced IDs & duplicates within batch).
        val messageBatch = listOf(localUnsyncedMsg.copy(content = "Overwritten?"), restoredMsg1, restoredMsg2, restoredMsg1)
        val factBatch = listOf(localUnsyncedFact.copy(value = "Overwritten?"), restoredFact1, restoredFact2, restoredFact1)

        gateway.mergeRestoredMessages(messageBatch)
        gateway.mergeRestoredMessages(messageBatch)
        gateway.mergeRestoredMemoryFacts(factBatch)
        gateway.mergeRestoredMemoryFacts(factBatch)

        val monthMessages = gateway.getAllMessagesForMonth("2026-11")
        assertEquals(3, monthMessages.size)
        assertEquals(listOf("msg-local", "msg-restored-1", "msg-restored-2"), monthMessages.map { it.id })
        assertEquals("Em ơi nhớ nhắc anh", monthMessages.first().content)
        assertFalse(monthMessages.first().synced)
        assertTrue(monthMessages[1].synced)
        assertTrue(monthMessages[2].synced)

        val unsyncedMessages = gateway.getUnsyncedMessages()
        assertEquals(listOf("msg-local"), unsyncedMessages.map { it.id })

        val allFacts = gateway.getAllMemoryFacts()
        assertEquals(3, allFacts.size)
        assertEquals(setOf("mem-local", "mem-restored-1", "mem-restored-2"), allFacts.map { it.id }.toSet())
        assertEquals("Bạc xỉu", allFacts.first { it.id == "mem-local" }.value)
        assertFalse(allFacts.first { it.id == "mem-local" }.synced)
        assertTrue(allFacts.first { it.id == "mem-restored-1" }.synced)

        // Verify SyncState folder IDs & manifest coexist without overwriting each other.
        val folders = SyncFolderIds("root", "profile", "history", "recordings", "manifest-id")
        val manifest = SyncManifest(
            schemaVersion = 1,
            updatedAtMs = 123_456L,
            memoryFileId = "mem-file",
            historyFileIds = mapOf("2026-11" to "hist-file"),
            recordingDriveIds = mapOf("rec-1" to "drv-rec-1"),
        )
        gateway.saveFolderIds(folders)
        gateway.saveManifest(manifest, syncedAtMs = 123_456L)
        gateway.saveFolderIds(folders.copy(manifestFileId = "manifest-id-2"))

        assertEquals(folders.copy(manifestFileId = "manifest-id-2"), gateway.getSavedFolderIds())
        assertEquals(manifest, gateway.getSavedManifest())
    }

    @Test(timeout = 15_000)
    fun androidWifiNetworkCheckerRejectsCellularAndMeteredWifi() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = shadowOf(cm)
        val checker = AndroidWifiNetworkChecker(context)

        // Ensure an active network object exists in ShadowConnectivityManager.
        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                android.net.NetworkInfo.State.CONNECTED,
            ),
        )
        val activeNetwork = cm.activeNetwork
        assertNotNull(activeNetwork)

        // 1. Cellular network -> false
        val cellularCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(cellularCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        shadowCm.setNetworkCapabilities(activeNetwork, cellularCaps)
        assertFalse(checker.isUnmeteredWifiConnected())

        // 2. Metered Wi-Fi -> false
        val meteredWifiCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(meteredWifiCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
        shadowCm.setNetworkCapabilities(activeNetwork, meteredWifiCaps)
        assertFalse(checker.isUnmeteredWifiConnected())

        // 3. Unmetered Wi-Fi without Internet -> false
        val noInternetWifiCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(noInternetWifiCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        shadowCm.setNetworkCapabilities(activeNetwork, noInternetWifiCaps)
        assertFalse(checker.isUnmeteredWifiConnected())

        // 4. Unmetered Wi-Fi with Internet -> true
        val unmeteredWifiCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(unmeteredWifiCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        shadowCm.setNetworkCapabilities(activeNetwork, unmeteredWifiCaps)
        assertTrue(checker.isUnmeteredWifiConnected())

        // 5. No active network -> false
        shadowCm.setDefaultNetworkActive(false)
        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(null)
        assertFalse(checker.isUnmeteredWifiConnected())
    }

    @Test(timeout = 15_000)
    fun driveSyncSchedulerEnqueuesWithStrictUnmeteredConstraint() {
        val scheduler = DriveSyncScheduler(context)
        val oneTimeRequest = scheduler.buildOneTimeSyncRequest()
        assertEquals(NetworkType.UNMETERED, oneTimeRequest.workSpec.constraints.requiredNetworkType)

        scheduler.enqueueSync()
        val workManager = WorkManager.getInstance(context)
        val enqueuedInfos = workManager.getWorkInfosForUniqueWork("nana_wifi_sync").get()
        assertEquals(1, enqueuedInfos.size)
        assertEquals(WorkInfo.State.ENQUEUED, enqueuedInfos.single().state)
        assertEquals(NetworkType.UNMETERED, enqueuedInfos.single().constraints.requiredNetworkType)

        scheduler.schedulePeriodicWifiSync()
        val periodicInfos = workManager.getWorkInfosForUniqueWork("nana_wifi_periodic_sync").get()
        assertEquals(1, periodicInfos.size)
        assertEquals(NetworkType.UNMETERED, periodicInfos.single().constraints.requiredNetworkType)
    }

    @Test(timeout = 15_000)
    fun authManagerFlagsNeedsReauthOnInvalidGrantWithoutClearingVault() {
        val prefs = context.getSharedPreferences("test_vault_invalid_grant", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val vault = EncryptedApiKeyVault(prefs)

        val savedKeys = listOf(
            ApiKeyEntry(
                id = "k-1",
                label = "Primary Gemini",
                provider = ProviderType.GEMINI_AI_STUDIO,
                apiKey = "AIzaTestKey123",
                modelId = "gemini-2.5-flash",
                priority = 1,
                isFreeTier = false,
            ),
        )
        val savedSettings = AppSettings(
            wakeWordEnabled = false,
            preferredGeminiVoice = "Zephyr",
            keepRecordingsForever = true,
            recordingRetentionDays = 14,
        )
        vault.saveKeys(savedKeys)
        vault.saveSettings(savedSettings)

        val tokenUrl = server.url("/oauth2/token").toString()
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
            Uri.parse(tokenUrl),
        )
        val clientId = "test-client-123.apps.googleusercontent.com"
        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse("com.googleusercontent.apps.test-client-123:/oauth2redirect"),
        ).build()
        val authResponse = AuthorizationResponse.Builder(authRequest)
            .setAuthorizationCode("auth-code-123")
            .setState(authRequest.state)
            .build()
        val expiredTokenResponse = TokenResponse.Builder(authResponse.createTokenExchangeRequest())
            .setAccessToken("expired-access-token")
            .setTokenType("Bearer")
            .setAccessTokenExpirationTime(System.currentTimeMillis() - 60_000L)
            .setRefreshToken("revoked-refresh-token")
            .build()
        val authState = AuthState(authResponse, null).apply {
            update(expiredTokenResponse, null)
        }
        vault.saveAuthStateJson(authState.jsonSerializeString())

        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""),
        )

        val appAuthConfig = AppAuthConfiguration.Builder()
            .setSkipIssuerHttpsCheck(true)
            .setConnectionBuilder(
                ConnectionBuilder { uri ->
                    URL(uri.toString()).openConnection() as HttpURLConnection
                },
            )
            .build()

        val authManager = GoogleDriveAuthManager(
            context = context,
            vault = vault,
            clientId = clientId,
            appAuthConfiguration = appAuthConfig,
        )

        assertTrue(authManager.isOAuthConfigured())
        assertTrue(authManager.authStateFlow.value.isSignedIn)
        assertFalse(authManager.authStateFlow.value.needsReauth)

        val token = runWithRobolectricLooper {
            authManager.getFreshAccessToken()
        }

        assertNull(token)
        assertTrue(authManager.authStateFlow.value.needsReauth)
        assertFalse(authManager.authStateFlow.value.isSignedIn)

        // Review Focus #5: vault keys and user settings must remain intact!
        assertEquals(savedKeys, vault.loadKeys())
        assertEquals(savedSettings, vault.loadSettings())
    }

    @Test(timeout = 20_000)
    fun driveSyncWorkerCancelsOnWifiDropAndHandles401AndRetentionCleanup() = runBlocking {
        val prefs = context.getSharedPreferences("test_vault_worker", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val vault = EncryptedApiKeyVault(prefs)
        val savedKey = ApiKeyEntry(
            id = "k-worker",
            label = "Gemini",
            provider = ProviderType.GEMINI_AI_STUDIO,
            apiKey = "AIzaWorkerKey",
            modelId = "gemini-2.5-flash",
            priority = 1,
            isFreeTier = false,
        )
        vault.saveKeys(listOf(savedKey))
        vault.saveSettings(AppSettings(keepRecordingsForever = false, recordingRetentionDays = 7))

        val dayMs = 86_400_000L
        val nowMs = 30L * dayMs
        val oldSyncedFile = tempFolder.newFile("old_synced.m4a").apply { writeBytes(ByteArray(128) { 1 }) }
        val pendingFile = tempFolder.newFile("pending_call.m4a").apply { writeBytes(ByteArray(512 * 1024) { 2 }) }

        val msgUnsynced = sampleMessage("msg-worker-1", "Anh ơi", synced = false)
        db.messageDao().upsert(msgUnsynced.toEntity())
        db.callRecordingDao().upsert(
            CallRecordingRecord(
                id = "rec-old-synced",
                phoneNumber = "0900000001",
                contactName = "Old Call",
                source = RecordingSource.ORIGINOS_NATIVE,
                startedAtMs = nowMs - 10L * dayMs,
                durationSec = 30,
                sourceUri = "content://old/1",
                stagedPath = oldSyncedFile.absolutePath,
                fileSizeBytes = oldSyncedFile.length(),
                mimeType = "audio/mp4",
                driveFileId = "drv-old-1",
                syncStatus = SyncStatus.SYNCED,
                syncedAtMs = nowMs - 8L * dayMs,
            ).toEntity(),
        )
        db.callRecordingDao().upsert(
            CallRecordingRecord(
                id = "rec-pending",
                phoneNumber = "0900000002",
                contactName = "Pending Call",
                source = RecordingSource.IN_APP,
                startedAtMs = nowMs - 1_000L,
                durationSec = 45,
                sourceUri = "content://pending/2",
                stagedPath = pendingFile.absolutePath,
                fileSizeBytes = pendingFile.length(),
                mimeType = "audio/mp4",
                syncStatus = SyncStatus.PENDING,
            ).toEntity(),
        )
        RoomSyncStorageGateway(db).saveFolderIds(
            SyncFolderIds("f-root", "f-profile", "f-history", "f-rec", "f-manifest"),
        )

        val tokenUrl = server.url("/oauth2/token").toString()
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
            Uri.parse(tokenUrl),
        )
        val clientId = "worker-client.apps.googleusercontent.com"
        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse("com.googleusercontent.apps.worker-client:/oauth2redirect"),
        ).build()
        val authResponse = AuthorizationResponse.Builder(authRequest)
            .setAuthorizationCode("code-worker")
            .setState(authRequest.state)
            .build()
        val validTokenResponse = TokenResponse.Builder(authResponse.createTokenExchangeRequest())
            .setAccessToken("fresh-worker-access-token")
            .setTokenType("Bearer")
            .setAccessTokenExpirationTime(System.currentTimeMillis() + 3_600_000L)
            .setRefreshToken("worker-refresh-token")
            .build()
        val authState = AuthState(authResponse, null).apply { update(validTokenResponse, null) }
        vault.saveAuthStateJson(authState.jsonSerializeString())

        val authManager = GoogleDriveAuthManager(
            context = context,
            vault = vault,
            clientId = clientId,
        )

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = shadowOf(cm)
        @Suppress("DEPRECATION")
        shadowCm.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                android.net.NetworkInfo.State.CONNECTED,
            ),
        )
        val network = cm.activeNetwork!!
        val unmeteredWifiCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(unmeteredWifiCaps).apply {
            addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        shadowCm.setNetworkCapabilities(network, unmeteredWifiCaps)

        val wifiConnected = AtomicBoolean(true)
        val wifiChecker = object : WifiNetworkChecker {
            override fun isUnmeteredWifiConnected(): Boolean = wifiConnected.get()
        }

        val scenario = AtomicInteger(0) // 0 = drop wifi via NetworkCallback mid-request; 1 = 401; 2 = complete + cleanup
        val requestStartedLatch = CountDownLatch(1)
        val baseUrl = server.url("/").toString().trimEnd('/')

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when (scenario.get()) {
                    0 -> {
                        requestStartedLatch.countDown()
                        // Simulate slow Drive call so NetworkCallback cancels it mid-flight.
                        MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"id":"id-slow"}""")
                            .setHeadersDelay(5, TimeUnit.SECONDS)
                    }
                    1 -> MockResponse().setResponseCode(401).setBody("""{"error":{"code":401}}""")
                    else -> when {
                        request.method == "GET" && path.startsWith("/drive/v3/files") ->
                            MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                        request.method == "PATCH" && path.startsWith("/upload/drive/v3/files/") ->
                            MockResponse().setResponseCode(200).setBody("""{"id":"id-hist-1"}""")
                        request.method == "POST" && path.contains("uploadType=multipart") ->
                            MockResponse().setResponseCode(200).setBody("""{"id":"id-hist-1"}""")
                        request.method == "POST" && path.contains("uploadType=resumable") ->
                            MockResponse().setResponseCode(200).addHeader("Location", "$baseUrl/resumable-sess")
                        request.method == "PUT" && path == "/resumable-sess" -> {
                            val range = request.getHeader("Content-Range").orEmpty()
                            if (range.startsWith("bytes 0-")) {
                                MockResponse().setResponseCode(308).addHeader("Range", "bytes=0-262143")
                            } else {
                                MockResponse().setResponseCode(200).setBody("""{"id":"drv-rec-pending"}""")
                            }
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
        }

        val depsProvider = object : DriveSyncDependenciesProvider {
            override fun provideDriveSyncDependencies(): DriveSyncDependencies = DriveSyncDependencies(
                database = db,
                vault = vault,
                authManager = authManager,
                wifiChecker = wifiChecker,
                driveClient = GoogleDriveClient(OkHttpClient(), apiBaseUrl = baseUrl),
                clockMs = { nowMs },
            )
        }
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = DriveSyncWorker(appContext, workerParameters, depsProvider)
        }

        // Phase A: Live NetworkCallback cancels active sync immediately when Wi-Fi drops.
        val workerA = TestListenableWorkerBuilder<DriveSyncWorker>(context)
            .setWorkerFactory(factory)
            .build()
        val jobA = async(Dispatchers.IO) { workerA.doWork() }
        assertTrue(requestStartedLatch.await(5, TimeUnit.SECONDS))
        wifiConnected.set(false)
        val meteredCaps = ShadowNetworkCapabilities.newInstance()
        shadowOf(meteredCaps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowCm.setNetworkCapabilities(network, meteredCaps)
        shadowCm.networkCallbacks.forEach { it.onCapabilitiesChanged(network, meteredCaps) }
        val resultA = jobA.await()
        assertEquals(ListenableWorker.Result.retry(), resultA)
        assertTrue(pendingFile.exists())

        // Phase B: HTTP 401 -> flags needsReauth = true without wiping Room or Vault.
        scenario.set(1)
        wifiConnected.set(true)
        shadowCm.setNetworkCapabilities(network, unmeteredWifiCaps)
        val workerB = TestListenableWorkerBuilder<DriveSyncWorker>(context)
            .setWorkerFactory(factory)
            .build()
        val resultB = workerB.doWork()
        assertEquals(ListenableWorker.Result.failure(), resultB)
        assertTrue(authManager.authStateFlow.value.needsReauth)
        assertEquals(1, db.messageDao().getUnsynced().size)
        assertEquals(listOf(savedKey), vault.loadKeys())
        assertTrue(oldSyncedFile.exists())

        // Phase C: Re-authenticated & Wi-Fi active -> completes sync and deletes old verified staged file.
        authState.update(validTokenResponse, null)
        vault.saveNeedsReauth(false)
        vault.saveAuthStateJson(authState.jsonSerializeString())
        val refreshedAuthManager = GoogleDriveAuthManager(context, vault, clientId)
        val depsProviderC = object : DriveSyncDependenciesProvider {
            override fun provideDriveSyncDependencies(): DriveSyncDependencies = DriveSyncDependencies(
                database = db,
                vault = vault,
                authManager = refreshedAuthManager,
                wifiChecker = wifiChecker,
                driveClient = GoogleDriveClient(OkHttpClient(), apiBaseUrl = baseUrl),
                clockMs = { nowMs },
            )
        }
        val factoryC = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = DriveSyncWorker(appContext, workerParameters, depsProviderC)
        }
        scenario.set(2)
        val workerC = TestListenableWorkerBuilder<DriveSyncWorker>(context)
            .setWorkerFactory(factoryC)
            .build()
        val resultC = workerC.doWork()
        assertEquals(ListenableWorker.Result.success(), resultC)
        assertEquals(0, db.messageDao().getUnsynced().size)
        assertFalse(oldSyncedFile.exists())
        assertNull(db.callRecordingDao().getById("rec-old-synced")?.stagedPath)
        assertTrue(pendingFile.exists())
        assertEquals(SyncStatus.SYNCED.name, db.callRecordingDao().getById("rec-pending")?.syncStatus)
    }

    @Test(timeout = 15_000)
    fun driveSyncWorkerAndAppContainerShareGlobalSyncMutex() = runBlocking {
        val prefs = context.getSharedPreferences("test_vault_mutex", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val vault = EncryptedApiKeyVault(prefs)

        val tokenUrl = server.url("/oauth2/token").toString()
        val serviceConfig = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
            Uri.parse(tokenUrl),
        )
        val clientId = "mutex-client.apps.googleusercontent.com"
        val authRequest = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse("com.googleusercontent.apps.mutex-client:/oauth2redirect"),
        ).build()
        val authResponse = AuthorizationResponse.Builder(authRequest)
            .setAuthorizationCode("code-mutex")
            .setState(authRequest.state)
            .build()
        val validTokenResponse = TokenResponse.Builder(authResponse.createTokenExchangeRequest())
            .setAccessToken("fresh-mutex-access-token")
            .setTokenType("Bearer")
            .setAccessTokenExpirationTime(System.currentTimeMillis() + 3_600_000L)
            .setRefreshToken("mutex-refresh-token")
            .build()
        val authState = AuthState(authResponse, null).apply { update(validTokenResponse, null) }
        vault.saveAuthStateJson(authState.jsonSerializeString())
        db.messageDao().upsert(sampleMessage("msg-mutex-1", "Test mutex", synced = false).toEntity())
        RoomSyncStorageGateway(db).saveFolderIds(
            SyncFolderIds("f-root", "f-profile", "f-history", "f-rec", "f-manifest"),
        )

        val restoreReachedServer = AtomicBoolean(false)
        val workerActive = AtomicBoolean(false)
        val workerInDriveLatch = CountDownLatch(1)
        val releaseWorkerLatch = CountDownLatch(1)
        val baseUrl = server.url("/").toString().trimEnd('/')
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("X-Caller") == "restore") {
                    restoreReachedServer.set(true)
                } else if (workerActive.get()) {
                    workerInDriveLatch.countDown()
                    releaseWorkerLatch.await(5, TimeUnit.SECONDS)
                }
                return when (request.method) {
                    "GET" -> MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                    else -> MockResponse().setResponseCode(200).setBody("""{"id":"drv-mutex-1"}""")
                }
            }
        }

        val restoreClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-Caller", "restore").build())
            }
            .build()
        val authManager = GoogleDriveAuthManager(context, vault, clientId)
        val container = com.adwewecat.nana.di.AppContainer(
            context = context,
            database = db,
            vault = vault,
            httpClient = OkHttpClient(),
            driveClient = GoogleDriveClient(restoreClient, apiBaseUrl = baseUrl),
            authManager = authManager,
        )
        assertTrue(container.syncMutex === DriveSyncWorker.GLOBAL_SYNC_MUTEX)
        assertTrue(container.provideDriveSyncDependencies().syncMutex === DriveSyncWorker.GLOBAL_SYNC_MUTEX)

        val depsProvider = object : DriveSyncDependenciesProvider {
            override fun provideDriveSyncDependencies(): DriveSyncDependencies = DriveSyncDependencies(
                database = db,
                vault = vault,
                authManager = authManager,
                wifiChecker = object : WifiNetworkChecker {
                    override fun isUnmeteredWifiConnected(): Boolean = true
                },
                driveClient = GoogleDriveClient(OkHttpClient(), apiBaseUrl = baseUrl),
            )
        }
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = DriveSyncWorker(appContext, workerParameters, depsProvider)
        }
        val worker = TestListenableWorkerBuilder<DriveSyncWorker>(context)
            .setWorkerFactory(factory)
            .build()

        workerActive.set(true)
        val workerJob = async(Dispatchers.IO) { worker.doWork() }
        assertTrue(workerInDriveLatch.await(5, TimeUnit.SECONDS))

        val restoreJob = async(Dispatchers.Default) {
            container.runRestore(allowMetered = true)
        }
        kotlinx.coroutines.delay(150)
        assertFalse("runRestore must wait while DriveSyncWorker holds GLOBAL_SYNC_MUTEX", restoreReachedServer.get())
        assertFalse(restoreJob.isCompleted)

        workerActive.set(false)
        releaseWorkerLatch.countDown()
        assertEquals(ListenableWorker.Result.success(), workerJob.await())
        val restoreOutcome = restoreJob.await()
        assertEquals(com.adwewecat.nana.core.sync.SyncOutcome.COMPLETED, restoreOutcome)
        assertTrue(restoreReachedServer.get())
    }

    private fun <T> runWithRobolectricLooper(block: suspend () -> T): T = runBlocking {
        val deferred = async(Dispatchers.Default) { block() }
        val mainLooper = shadowOf(Looper.getMainLooper())
        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!deferred.isCompleted && System.nanoTime() < deadlineNs) {
            mainLooper.idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for Robolectric looper task", deferred.isCompleted)
        deferred.await()
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
        confidence = 0.9f,
        updatedAtMs = 1_793_500_000_000L,
        synced = synced,
    )
}
