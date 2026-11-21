package com.adwewecat.nana.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.adwewecat.nana.core.ai.AiChatRouter
import com.adwewecat.nana.core.ai.ClaudeClient
import com.adwewecat.nana.core.ai.GeminiClient
import com.adwewecat.nana.core.ai.LocalFallbackTts
import com.adwewecat.nana.core.ai.OpenAiClient
import com.adwewecat.nana.core.ai.TtsRouter
import com.adwewecat.nana.core.model.ActionExecutionResult
import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.DeviceActionExecutor
import com.adwewecat.nana.core.model.DeviceCommand
import com.adwewecat.nana.core.model.MemoryCategory
import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.ProviderType
import com.adwewecat.nana.core.model.QuotaState
import com.adwewecat.nana.core.quota.KeyPoolManager
import com.adwewecat.nana.core.quota.NaNaQuotaStateMachine
import com.adwewecat.nana.data.local.NaNaDatabase
import com.adwewecat.nana.data.local.toEntity
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NaNaVoiceOrchestratorTest {

    private lateinit var context: Context
    private lateinit var db: NaNaDatabase
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = NaNaDatabase.createInMemory(context)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        File(context.filesDir, "tired_consent_prompt.pcm").delete()
        File(context.filesDir, "vosk-model-vn").deleteRecursively()
    }

    @Test(timeout = 15_000)
    fun wakeWordWhileTiredPlaysExactConsentPromptAndSwitchesToEmGaiOnOk() = runTest {
        val keyPool = KeyPoolManager(
            initialKeys = listOf(
                paidGeminiKey("paid-1"),
                freeGeminiKey("free-1"),
            ),
            clockMs = { 10_000L },
        )
        keyPool.markChatQuotaExhausted("paid-1")

        val stateMachine = NaNaQuotaStateMachine(keyPool)
        stateMachine.onAllPaidChatKeysExhausted()

        val fakeAudioPlayer = FakeAudioPlaybackEngine()
        val fakeLocalTts = FakeLocalFallbackTts()
        val fakeExecutor = FakeDeviceActionExecutor()
        val fakeSync = FakeSyncTrigger()

        val orchestrator = createOrchestrator(
            keyPool = keyPool,
            stateMachine = stateMachine,
            audioPlayer = fakeAudioPlayer,
            localTts = fakeLocalTts,
            actionExecutor = fakeExecutor,
            syncTrigger = fakeSync,
        )

        orchestrator.onWakeWordDetected()

        val awaitingSnapshot = orchestrator.uiState.value
        assertEquals(VoiceLoopState.AWAITING_CONSENT, awaitingSnapshot.loopState)
        assertEquals(QuotaState.NANA_TIRED_AWAITING_CONSENT, awaitingSnapshot.quotaState)
        assertEquals(PersonaMode.NANA, awaitingSnapshot.activePersona)
        assertEquals(
            "Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?",
            awaitingSnapshot.statusMessage,
        )
        assertEquals(
            listOf("Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?" to PersonaMode.NANA),
            fakeLocalTts.spokenCalls,
        )
        assertEquals(0, server.requestCount)

        orchestrator.onUserUtterance(transcript = "ok", wavBytes = null)

        val sisterSnapshot = orchestrator.uiState.value
        assertEquals(VoiceLoopState.LISTENING, sisterSnapshot.loopState)
        assertEquals(QuotaState.ACTIVE_LITTLE_SISTER, sisterSnapshot.quotaState)
        assertEquals(PersonaMode.LITTLE_SISTER, sisterSnapshot.activePersona)
        assertEquals(2, fakeLocalTts.spokenCalls.size)
        assertEquals(PersonaMode.LITTLE_SISTER, fakeLocalTts.spokenCalls.last().second)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = 15_000)
    fun localFastCommandBypassesLlmAndExecutesImmediately() = runTest {
        val keyPool = KeyPoolManager(
            initialKeys = listOf(paidGeminiKey("paid-1")),
            clockMs = { 10_000L },
        )
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val fakeAudioPlayer = FakeAudioPlaybackEngine()
        val fakeLocalTts = FakeLocalFallbackTts()
        val fakeExecutor = FakeDeviceActionExecutor()
        val fakeSync = FakeSyncTrigger()

        val orchestrator = createOrchestrator(
            keyPool = keyPool,
            stateMachine = stateMachine,
            audioPlayer = fakeAudioPlayer,
            localTts = fakeLocalTts,
            actionExecutor = fakeExecutor,
            syncTrigger = fakeSync,
        )

        orchestrator.onWakeWordDetected()
        assertEquals(VoiceLoopState.LISTENING, orchestrator.uiState.value.loopState)

        orchestrator.onUserUtterance(transcript = "bắt máy", wavBytes = null)

        assertEquals(listOf<DeviceCommand>(DeviceCommand.AnswerIncomingCall), fakeExecutor.executedCommands)
        assertEquals(0, server.requestCount)
        assertEquals(1, fakeLocalTts.spokenCalls.size)
    }

    @Test(timeout = 15_000)
    fun conversationTurnSavesMessagesEnqueuesWifiSyncAndStaysInContinuousLoop() = runTest {
        val replyText = "Dạ anh nghỉ ngơi một chút đi nha, để em pha trà cho anh nhé!"
        val ttsPcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val encodedPcm = Base64.getEncoder().encodeToString(ttsPcm)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              { "text": "$replyText" }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              {
                                "inlineData": {
                                  "mimeType": "audio/L16;codec=pcm;rate=24000",
                                  "data": "$encodedPcm"
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val keyPool = KeyPoolManager(
            initialKeys = listOf(paidGeminiKey("paid-1")),
            clockMs = { 10_000L },
        )
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val fakeAudioPlayer = FakeAudioPlaybackEngine()
        val fakeLocalTts = FakeLocalFallbackTts()
        val fakeExecutor = FakeDeviceActionExecutor()
        val fakeSync = FakeSyncTrigger()

        val orchestrator = createOrchestrator(
            keyPool = keyPool,
            stateMachine = stateMachine,
            audioPlayer = fakeAudioPlayer,
            localTts = fakeLocalTts,
            actionExecutor = fakeExecutor,
            syncTrigger = fakeSync,
        )

        orchestrator.onWakeWordDetected()
        orchestrator.onUserUtterance(transcript = "Hôm nay anh hơi mệt", wavBytes = null)

        val savedMessages = db.messageDao().getAll()
        assertEquals(2, savedMessages.size)
        assertEquals("user", savedMessages[0].role)
        assertEquals("Hôm nay anh hơi mệt", savedMessages[0].content)
        assertFalse(savedMessages[0].synced)
        assertEquals("assistant", savedMessages[1].role)
        assertEquals(replyText, savedMessages[1].content)
        assertEquals(PersonaMode.NANA.name, savedMessages[1].persona)
        assertFalse(savedMessages[1].synced)
        assertEquals(savedMessages[0].sessionId, savedMessages[1].sessionId)

        assertEquals(1, fakeSync.enqueueCount)
        assertEquals(1, fakeAudioPlayer.playedChunks.size)
        assertArrayEquals(ttsPcm, fakeAudioPlayer.playedChunks.single().first)
        assertEquals(24_000, fakeAudioPlayer.playedChunks.single().second)
        assertEquals(VoiceLoopState.LISTENING, orchestrator.uiState.value.loopState)

        orchestrator.onUserUtterance(transcript = "tạm biệt", wavBytes = null)

        assertEquals(VoiceLoopState.IDLE_WAKE, orchestrator.uiState.value.loopState)
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 15_000)
    fun cachedTiredPromptPlaysWithoutPaidCallsAndConsentTimeoutReturnsIdle() = runTest {
        val keyPool = KeyPoolManager(
            initialKeys = listOf(paidGeminiKey("paid-1")),
            clockMs = { 10_000L },
        )
        keyPool.markChatQuotaExhausted("paid-1")

        val stateMachine = NaNaQuotaStateMachine(keyPool)
        stateMachine.onAllPaidChatKeysExhausted()

        val fakeAudioPlayer = FakeAudioPlaybackEngine()
        val cachedPcm = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        fakeAudioPlayer.cacheTiredPrompt(cachedPcm)
        val fakeLocalTts = FakeLocalFallbackTts()

        val orchestrator = createOrchestrator(
            keyPool = keyPool,
            stateMachine = stateMachine,
            audioPlayer = fakeAudioPlayer,
            localTts = fakeLocalTts,
            actionExecutor = FakeDeviceActionExecutor(),
            syncTrigger = FakeSyncTrigger(),
        )

        orchestrator.onWakeWordDetected()

        assertEquals(VoiceLoopState.AWAITING_CONSENT, orchestrator.uiState.value.loopState)
        assertEquals(1, fakeAudioPlayer.playedChunks.size)
        assertArrayEquals(cachedPcm, fakeAudioPlayer.playedChunks.single().first)
        assertTrue(fakeLocalTts.spokenCalls.isEmpty())
        assertEquals(0, server.requestCount)

        orchestrator.onConsentTimeout()

        assertEquals(VoiceLoopState.IDLE_WAKE, orchestrator.uiState.value.loopState)
        assertEquals(QuotaState.NANA_TIRED, orchestrator.uiState.value.quotaState)
        assertEquals(1, fakeLocalTts.spokenCalls.size)
        assertEquals(NaNaQuotaStateMachine.DECLINED_GOODBYE, fakeLocalTts.spokenCalls.single().first)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = 15_000)
    fun androidAudioPlayerCachesTiredPromptAndVoskDegradesGracefullyWithoutModel() {
        val audioPlayer = AndroidAudioPlayer(context)
        assertEquals(null, audioPlayer.getCachedTiredPrompt())

        val samplePcm = byteArrayOf(0x05, 0x06, 0x07, 0x08)
        audioPlayer.cacheTiredPrompt(samplePcm)
        val cached = audioPlayer.getCachedTiredPrompt()
        assertArrayEquals(samplePcm, cached)

        val wakeEngine = VoskWakeWordEngine(context)
        assertFalse(wakeEngine.isModelAvailable())
        wakeEngine.start(onWakeDetected = {}, onMicLevel = {})
        wakeEngine.stop()

        val modelDir = File(context.filesDir, "vosk-model-vn").apply { mkdirs() }
        File(modelDir, "README").writeText("placeholder")
        assertTrue(wakeEngine.isModelAvailable())
        assertEquals(null, VoskWakeWordEngine.recognizeLocalCommandFromPcm(context, byteArrayOf(1, 2, 3, 4)))
    }

    @Test(timeout = 15_000)
    fun extractMemoriesDeduplicatesFactsByCategoryAndNormalizedKeyAcrossSessions() = runTest {
        db.memoryFactDao().upsert(
            MemoryFact(
                id = "mem_existing_1",
                category = MemoryCategory.PREFERENCE,
                key = "morning_drink",
                value = "Trà đá",
                confidence = 0.8f,
                updatedAtMs = 100_000L,
                synced = true,
            ).toEntity(),
        )
        db.messageDao().upsert(
            com.adwewecat.nana.core.model.ChatMessage(
                id = "msg-1",
                sessionId = "s-1",
                role = "user",
                persona = PersonaMode.NANA,
                content = "Dạo này sáng anh đổi sang uống cà phê sữa đá và ngủ lúc 11 giờ đêm nha.",
                createdAtMs = 200_000L,
            ).toEntity(),
        )

        val extractedJson = """
            [
              {"category":"PREFERENCE","key":"Morning_Drink","value":"Cà phê sữa đá","confidence":0.95},
              {"category":"HABIT","key":"sleep_time","value":"11 giờ đêm","confidence":0.9}
            ]
        """.trimIndent().replace("\"", "\\\"")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "parts": [
                              { "text": "$extractedJson" }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val keyPool = KeyPoolManager(initialKeys = listOf(paidGeminiKey("paid-1")), clockMs = { 10_000L })
        val orchestrator = createOrchestrator(
            keyPool = keyPool,
            stateMachine = NaNaQuotaStateMachine(keyPool),
            audioPlayer = FakeAudioPlaybackEngine(),
            localTts = FakeLocalFallbackTts(),
            actionExecutor = FakeDeviceActionExecutor(),
            syncTrigger = FakeSyncTrigger(),
        )

        orchestrator.extractMemoriesFromRecentSession()

        val allFacts = db.memoryFactDao().getAll()
        assertEquals(2, allFacts.size)
        val drinkFact = allFacts.first { it.id == "mem_existing_1" }
        assertEquals("Cà phê sữa đá", drinkFact.value)
        assertFalse(drinkFact.synced)
        assertTrue(allFacts.any { it.key == "sleep_time" && it.value == "11 giờ đêm" })
    }

    @Test(timeout = 15_000)
    fun sessionStopTriggersBackgroundExtractionAndPreCachesTiredPromptOnce() = runBlocking {
        val primaryPcm1 = Base64.getEncoder().encodeToString(byteArrayOf(0x01, 0x02))
        val precachePcm = byteArrayOf(0x09, 0x08, 0x07, 0x06)
        val precachePcmB64 = Base64.getEncoder().encodeToString(precachePcm)
        val primaryPcm2 = Base64.getEncoder().encodeToString(byteArrayOf(0x04, 0x05))
        val extractedJson = """[{"category":"PREFERENCE","key":"favorite_tea","value":"Trà đào","confidence":0.9}]"""
            .replace("\"", "\\\"")

        // 1: Turn 1 chat, 2: Turn 1 TTS, 3: One-shot TIRED_CONSENT_PROMPT pre-cache
        server.enqueue(geminiTextResponse("Dạ em chào anh!"))
        server.enqueue(geminiAudioResponse(primaryPcm1))
        server.enqueue(geminiAudioResponse(precachePcmB64))
        // 4: Turn 2 chat, 5: Turn 2 TTS (must NOT re-trigger pre-cache)
        server.enqueue(geminiTextResponse("Dạ em nhớ anh thích trà đào nha!"))
        server.enqueue(geminiAudioResponse(primaryPcm2))
        // 6: Automatic MemoryExtractor on stopSession()
        server.enqueue(geminiTextResponse(extractedJson))
        // 7: Simulated error on later direct extraction must NOT flip NANA_TIRED
        server.enqueue(MockResponse().setResponseCode(500).setBody("server error"))

        val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val keyPool = KeyPoolManager(initialKeys = listOf(paidGeminiKey("paid-1")), clockMs = { 10_000L })
            val stateMachine = NaNaQuotaStateMachine(keyPool)
            val fakeAudioPlayer = FakeAudioPlaybackEngine()
            val orchestrator = createOrchestrator(
                keyPool = keyPool,
                stateMachine = stateMachine,
                audioPlayer = fakeAudioPlayer,
                localTts = FakeLocalFallbackTts(),
                actionExecutor = FakeDeviceActionExecutor(),
                syncTrigger = FakeSyncTrigger(),
                backgroundScope = bgScope,
            )

            orchestrator.onWakeWordDetected()
            orchestrator.onUserUtterance(transcript = "Chào Na Na", wavBytes = null)

            var waitMs = 0
            while (fakeAudioPlayer.getCachedTiredPrompt() == null && waitMs < 3_000) {
                delay(20)
                waitMs += 20
            }
            assertArrayEquals(precachePcm, fakeAudioPlayer.getCachedTiredPrompt())
            assertEquals(3, server.requestCount)

            // Even if cache is cleared externally, AtomicBoolean ensures pre-cache only runs once.
            fakeAudioPlayer.clearCachedTiredPrompt()
            orchestrator.onUserUtterance(transcript = "Anh thích uống trà đào", wavBytes = null)
            delay(100)
            assertEquals(null, fakeAudioPlayer.getCachedTiredPrompt())
            assertEquals(5, server.requestCount)

            orchestrator.stopSession()
            waitMs = 0
            while (db.memoryFactDao().getAll().isEmpty() && waitMs < 3_000) {
                delay(20)
                waitMs += 20
            }
            val facts = db.memoryFactDao().getAll()
            assertEquals(1, facts.size)
            assertEquals("Trà đào", facts.single().value)
            assertEquals(QuotaState.ACTIVE_NANA, stateMachine.state)

            // Failed background extraction must leave ACTIVE_NANA untouched.
            orchestrator.extractMemoriesFromRecentSession()
            assertEquals(QuotaState.ACTIVE_NANA, stateMachine.state)
        } finally {
            bgScope.cancel()
        }
    }

    private fun geminiTextResponse(text: String) = MockResponse()
        .setResponseCode(200)
        .setBody("""{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}""")

    private fun geminiAudioResponse(base64Pcm: String) = MockResponse()
        .setResponseCode(200)
        .setBody(
            """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"$base64Pcm"}}]}}]}""",
        )

    private fun createOrchestrator(
        keyPool: KeyPoolManager,
        stateMachine: NaNaQuotaStateMachine,
        audioPlayer: AudioPlaybackEngine,
        localTts: LocalFallbackTts,
        actionExecutor: DeviceActionExecutor,
        syncTrigger: SyncTrigger,
        backgroundScope: CoroutineScope? = null,
    ): NaNaVoiceOrchestrator {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val httpClient = OkHttpClient()
        val geminiClient = GeminiClient(httpClient, vertexBaseUrl = baseUrl, aiStudioBaseUrl = baseUrl)
        val openAiClient = OpenAiClient(httpClient, baseUrl = baseUrl)
        val claudeClient = ClaudeClient(httpClient, baseUrl = baseUrl)

        return NaNaVoiceOrchestrator(
            stateMachine = stateMachine,
            aiChatRouter = AiChatRouter(keyPool, stateMachine, geminiClient, openAiClient, claudeClient),
            ttsRouter = TtsRouter(keyPool, geminiClient, openAiClient, localTts),
            localFallbackTts = localTts,
            actionExecutor = actionExecutor,
            audioPlayer = audioPlayer,
            messageDao = db.messageDao(),
            memoryFactDao = db.memoryFactDao(),
            syncTrigger = syncTrigger,
            clockMs = { 1_793_500_000_000L },
            backgroundScope = backgroundScope,
        )
    }

    private fun paidGeminiKey(id: String) = ApiKeyEntry(
        id = id,
        label = "Paid $id",
        provider = ProviderType.GEMINI_AI_STUDIO,
        apiKey = "AIzaSyntheticPaidKey-$id",
        modelId = "gemini-2.5-flash",
        priority = 1,
        isFreeTier = false,
    )

    private fun freeGeminiKey(id: String) = ApiKeyEntry(
        id = id,
        label = "Free $id",
        provider = ProviderType.FREE_GEMINI,
        apiKey = "AIzaSyntheticFreeKey-$id",
        modelId = "gemini-2.5-flash-lite",
        priority = 1,
        isFreeTier = true,
    )

    private class FakeAudioPlaybackEngine : AudioPlaybackEngine {
        val playedChunks = mutableListOf<Pair<ByteArray, Int>>()
        private var cachedTiredPrompt: ByteArray? = null

        override suspend fun playPcm(pcmBytes: ByteArray, sampleRateHz: Int) {
            playedChunks.add(pcmBytes to sampleRateHz)
        }

        override fun getCachedTiredPrompt(): ByteArray? = cachedTiredPrompt

        override fun cacheTiredPrompt(pcmBytes: ByteArray) {
            cachedTiredPrompt = pcmBytes
        }

        fun clearCachedTiredPrompt() {
            cachedTiredPrompt = null
        }
    }

    private class FakeLocalFallbackTts : LocalFallbackTts {
        val spokenCalls = mutableListOf<Pair<String, PersonaMode>>()

        override suspend fun speak(text: String, persona: PersonaMode): Boolean {
            spokenCalls.add(text to persona)
            return true
        }
    }

    private class FakeDeviceActionExecutor : DeviceActionExecutor {
        val executedCommands = mutableListOf<DeviceCommand>()

        override suspend fun execute(command: DeviceCommand): ActionExecutionResult {
            executedCommands.add(command)
            return ActionExecutionResult(
                succeeded = true,
                spokenFeedback = "Dạ em thực hiện xong rồi ạ!",
            )
        }
    }

    private class FakeSyncTrigger : SyncTrigger {
        var enqueueCount = 0

        override fun enqueueWifiSync() {
            enqueueCount++
        }
    }
}
