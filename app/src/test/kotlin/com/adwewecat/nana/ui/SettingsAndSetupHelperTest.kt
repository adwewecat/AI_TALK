package com.adwewecat.nana.ui

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.adwewecat.nana.MainActivity
import com.adwewecat.nana.core.ai.ClaudeClient
import com.adwewecat.nana.core.ai.GeminiClient
import com.adwewecat.nana.core.ai.OpenAiClient
import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.CallRecordingRecord
import com.adwewecat.nana.core.model.KeyStatus
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.ProviderType
import com.adwewecat.nana.core.model.QuotaState
import com.adwewecat.nana.core.model.RecordingSource
import com.adwewecat.nana.core.sync.SyncOutcome
import com.adwewecat.nana.data.local.EncryptedApiKeyVault
import com.adwewecat.nana.data.local.NaNaDatabase
import com.adwewecat.nana.data.local.toEntity
import com.adwewecat.nana.di.AppContainer
import com.adwewecat.nana.ui.settings.ApiKeyMasker
import com.adwewecat.nana.ui.settings.OriginOsSetupHelper
import com.adwewecat.nana.voice.NaNaVoiceService
import com.adwewecat.nana.voice.VoiceLoopState
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAndSetupHelperTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("nana_robolectric_vault", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("nana_robolectric_vault", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test(timeout = 15_000)
    fun maskApiKeyHidesMiddleCharactersSafely() {
        assertEquals("AQ.Ab8...1234", ApiKeyMasker.mask("AQ.Ab8RN6ExampleSecretKey1234"))
        assertEquals("***", ApiKeyMasker.mask("shortkey"))
        assertEquals("***", ApiKeyMasker.mask("1234567890"))
        assertEquals("***", ApiKeyMasker.mask(""))
        assertEquals("AIzaSy...9876", ApiKeyMasker.mask("  AIzaSyDummyValueForUnitTest9876  "))
    }

    @Test(timeout = 15_000)
    fun originOsSetupHelperProvidesValidFallbackIntents() {
        val autostart = OriginOsSetupHelper.autostartIntent(context)
        val battery = OriginOsSetupHelper.batteryOptimizationIntent(context)
        val notificationListener = OriginOsSetupHelper.notificationListenerIntent()
        val accessibility = OriginOsSetupHelper.accessibilitySettingsIntent()
        val appDetails = OriginOsSetupHelper.appDetailsIntent(context)

        assertNotNull(autostart)
        assertNotNull(battery)
        assertNotNull(notificationListener)
        assertNotNull(accessibility)
        assertNotNull(appDetails)

        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, notificationListener.action)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, accessibility.action)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appDetails.action)
        assertEquals("package:${context.packageName}", appDetails.data?.toString())
        assertTrue(autostart.action != null || autostart.component != null)
        assertTrue(battery.action != null || battery.component != null)
    }

    @Test(timeout = 15_000)
    fun appContainerWiresRealServicesCleanlyWithoutHardcodedKeys() = runTest {
        val container = AppContainer(context)

        assertTrue(container.vault.loadKeys().isEmpty())
        assertTrue(container.keyPool.snapshot().isEmpty())
        assertEquals(QuotaState.ACTIVE_NANA, container.quotaStateMachine.state)
        assertNotNull(container.aiChatRouter)
        assertNotNull(container.ttsRouter)
        assertNotNull(container.voiceOrchestrator)
        assertNotNull(container.syncScheduler)
        assertNotNull(container.authManager)
        assertNotNull(container.callRecordingWatcher)
        assertNotNull(container.inAppCallRecorder)
        assertNotNull(container.callStateMonitor)

        // Verify Gemini provider auto-detection on save, cooldown recovery, and Wi-Fi gate.
        val syntheticEntry = ApiKeyEntry(
            id = "test-gem-1",
            label = "Test Gemini",
            provider = ProviderType.GEMINI_AI_STUDIO,
            apiKey = "AQ.SyntheticVertexKey123456",
            modelId = "gemini-2.5-flash",
            priority = 1,
            isFreeTier = false,
        )
        container.saveApiKey(syntheticEntry)
        val saved = container.keysFlow.value.single()
        assertEquals(ProviderType.GEMINI_VERTEX_EXPRESS, saved.provider)
        assertEquals(KeyStatus.HEALTHY, saved.status)

        container.keyPool.markChatQuotaExhausted("test-gem-1")
        container.quotaStateMachine.onAllPaidChatKeysExhausted()
        assertEquals(QuotaState.NANA_TIRED, container.quotaStateMachine.state)

        container.reviveNaNaNow()
        assertEquals(QuotaState.ACTIVE_NANA, container.quotaStateMachine.state)
        assertEquals(KeyStatus.HEALTHY, container.keysFlow.value.single().status)

        assertEquals("Chưa nhập API key.", container.testApiKey(syntheticEntry.copy(apiKey = "")))
        assertEquals(SyncOutcome.PAUSED_NO_WIFI, container.runManualWifiSync())
    }

    @Test(timeout = 15_000)
    fun createVaultSafelyFailsLoudlyOnNonRobolectricFingerprint() {
        val outcome = runCatching {
            AppContainer.createVaultSafely(context, fingerprint = "vivo/PD2408/PD2408:16/UP1A")
        }
        assertTrue(
            "Non-Robolectric fingerprint must not fall back silently to plaintext prefs",
            outcome.isFailure,
        )
    }

    @Test(timeout = 15_000)
    fun testApiKeyProbesTtsSummarizeScansGeminiKeysAndChatReviveResetUiState() = runTest {
        val server = MockWebServer()
        server.start()
        val db = NaNaDatabase.createInMemory(context)
        val audioFile = File.createTempFile("nana_call_summary_", ".m4a", context.cacheDir).apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        }
        try {
            val baseUrl = server.url("/").toString().trimEnd('/')
            val httpClient = OkHttpClient()
            val encodedPcm = Base64.getEncoder().encodeToString(byteArrayOf(0x11, 0x22, 0x33, 0x44))
            var failGeminiTtsProbe = false

            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    val apiKey = request.getHeader("x-goog-api-key").orEmpty()
                    val body = request.body.readUtf8()

                    return when {
                        path == "/v1/audio/speech" -> MockResponse()
                            .setResponseCode(200)
                            .setBody(okio.Buffer().write(byteArrayOf(0x01, 0x02, 0x03, 0x04)))

                        path.contains("tts", ignoreCase = true) -> {
                            if (failGeminiTtsProbe) {
                                MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"message":"Quota exceeded"}}""")
                            } else {
                                MockResponse().setResponseCode(200).setBody(
                                    """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"$encodedPcm"}}]}}]}""",
                                )
                            }
                        }

                        body.contains("tóm tắt") -> {
                            if (apiKey == "AIzaQuotaSummaryKey") {
                                MockResponse().setResponseCode(429).setBody("""{"error":{"code":429,"message":"RESOURCE_EXHAUSTED"}}""")
                            } else {
                                MockResponse().setResponseCode(200).setBody(
                                    """{"candidates":[{"content":{"parts":[{"text":"Cuộc gọi chốt lịch họp 9h sáng."}]}}]}""",
                                )
                            }
                        }

                        body.contains("Hãy trích xuất") -> MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"candidates":[{"content":{"parts":[{"text":"[]"}]}}]}""")

                        else -> MockResponse()
                            .setResponseCode(200)
                            .setBody("""{"candidates":[{"content":{"parts":[{"text":"Dạ chào anh nha!"}]}}]}""")
                    }
                }
            }

            val prefs = context.getSharedPreferences("test_app_container_fixes", Context.MODE_PRIVATE).apply {
                edit().clear().commit()
            }
            val container = AppContainer(
                context = context,
                database = db,
                vault = EncryptedApiKeyVault(prefs),
                httpClient = httpClient,
                geminiClient = GeminiClient(httpClient, vertexBaseUrl = baseUrl, aiStudioBaseUrl = baseUrl),
                openAiClient = OpenAiClient(httpClient, baseUrl = baseUrl),
                claudeClient = ClaudeClient(httpClient, baseUrl = baseUrl),
            )

            // 1. testApiKey probes TTS endpoints for isTtsOnly / -tts models.
            val geminiTtsKey = ApiKeyEntry(
                id = "gem-tts",
                label = "Gemini TTS",
                provider = ProviderType.GEMINI_AI_STUDIO,
                apiKey = "AIzaGeminiTtsOnlyKey",
                modelId = "gemini-2.5-flash-preview-tts",
                priority = 1,
                isFreeTier = false,
                isTtsOnly = true,
            )
            assertTrue(container.testApiKey(geminiTtsKey).startsWith("Hợp lệ (TTS"))
            failGeminiTtsProbe = true
            assertTrue(container.testApiKey(geminiTtsKey).contains("Hết quota"))
            failGeminiTtsProbe = false

            val openAiTtsKey = ApiKeyEntry(
                id = "oai-tts",
                label = "OpenAI TTS",
                provider = ProviderType.OPENAI,
                apiKey = "sk-openaitts-key",
                modelId = "tts-1",
                priority = 2,
                isFreeTier = false,
                isTtsOnly = true,
            )
            assertTrue(container.testApiKey(openAiTtsKey).startsWith("Hợp lệ (TTS"))

            // 2. summarizeCallRecording skips Claude (#1), fails over quota-exhausted Gemini (#2), succeeds on Gemini (#3).
            container.saveApiKey(
                ApiKeyEntry(
                    id = "claude-1",
                    label = "Claude",
                    provider = ProviderType.CLAUDE,
                    apiKey = "sk-ant-1",
                    modelId = "claude-sonnet-4-5",
                    priority = 1,
                    isFreeTier = false,
                ),
            )
            container.saveApiKey(
                ApiKeyEntry(
                    id = "gem-2",
                    label = "Gemini Quota",
                    provider = ProviderType.GEMINI_AI_STUDIO,
                    apiKey = "AIzaQuotaSummaryKey",
                    modelId = "gemini-2.5-flash",
                    priority = 2,
                    isFreeTier = false,
                ),
            )
            container.saveApiKey(
                ApiKeyEntry(
                    id = "gem-3",
                    label = "Gemini Healthy",
                    provider = ProviderType.GEMINI_AI_STUDIO,
                    apiKey = "AIzaGoodSummaryKey",
                    modelId = "gemini-2.5-flash",
                    priority = 3,
                    isFreeTier = false,
                ),
            )
            val record = CallRecordingRecord(
                id = "rec-sum-1",
                phoneNumber = "0901234567",
                contactName = "Anh Minh",
                source = RecordingSource.ORIGINOS_NATIVE,
                startedAtMs = 1_000L,
                durationSec = 30,
                sourceUri = "content://calls/1",
                stagedPath = audioFile.absolutePath,
                fileSizeBytes = audioFile.length(),
                mimeType = "audio/mp4",
            )
            db.callRecordingDao().upsert(record.toEntity())

            val summary = container.summarizeCallRecording(record)
            assertEquals("Cuộc gọi chốt lịch họp 9h sáng.", summary)
            assertEquals(KeyStatus.HEALTHY, container.keysFlow.value.first { it.id == "claude-1" }.status)
            assertEquals(KeyStatus.COOLDOWN, container.keysFlow.value.first { it.id == "gem-2" }.status)
            assertEquals("Cuộc gọi chốt lịch họp 9h sáng.", db.callRecordingDao().getById("rec-sum-1")?.aiSummary)

            // 3. sendTextChat returns orb to IDLE_WAKE when NaNaVoiceService is not running.
            assertFalse(NaNaVoiceService.isRunning.value)
            container.sendTextChat("Chào Na Na")
            assertEquals(VoiceLoopState.IDLE_WAKE, container.voiceOrchestrator.uiState.value.loopState)

            // 4. reviveNaNaNow updates voiceOrchestrator.uiState immediately from LITTLE_SISTER/LISTENING to ACTIVE_NANA/NANA.
            container.keyPool.markChatQuotaExhausted("claude-1")
            container.keyPool.markChatQuotaExhausted("gem-3")
            container.quotaStateMachine.onAllPaidChatKeysExhausted()
            container.voiceOrchestrator.onWakeWordDetected()
            container.voiceOrchestrator.onUserUtterance("ok", wavBytes = null)
            assertEquals(QuotaState.ACTIVE_LITTLE_SISTER, container.voiceOrchestrator.uiState.value.quotaState)
            assertEquals(PersonaMode.LITTLE_SISTER, container.voiceOrchestrator.uiState.value.activePersona)
            assertEquals(VoiceLoopState.LISTENING, container.voiceOrchestrator.uiState.value.loopState)

            container.reviveNaNaNow()
            assertEquals(QuotaState.ACTIVE_NANA, container.voiceOrchestrator.uiState.value.quotaState)
            assertEquals(PersonaMode.NANA, container.voiceOrchestrator.uiState.value.activePersona)
            assertEquals(VoiceLoopState.IDLE_WAKE, container.voiceOrchestrator.uiState.value.loopState)
        } finally {
            audioFile.delete()
            db.close()
            server.shutdown()
        }
    }

    @Test(timeout = 15_000)
    fun toggleVoiceServiceFalseDoesNotStartServiceWhenAlreadyStopped() {
        assertFalse(NaNaVoiceService.isRunning.value)
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val shadowApp = shadowOf(context as Application)
        shadowApp.clearStartedServices()

        activity.toggleVoiceService(false)

        assertNull(shadowApp.nextStartedService)
    }
}
