package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.DeviceCommand
import com.adwewecat.nana.core.model.KeyStatus
import com.adwewecat.nana.core.model.MemoryCategory
import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.ProviderType
import com.adwewecat.nana.core.model.QuotaState
import com.adwewecat.nana.core.quota.KeyPoolManager
import com.adwewecat.nana.core.quota.NaNaQuotaStateMachine
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiAndTtsRouterTest {

    private lateinit var server: MockWebServer
    private lateinit var httpClient: OkHttpClient
    private lateinit var geminiClient: GeminiClient
    private lateinit var openAiClient: OpenAiClient
    private lateinit var claudeClient: ClaudeClient
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient()
        val base = server.url("/").toString().trimEnd('/')
        geminiClient = GeminiClient(
            httpClient = httpClient,
            vertexBaseUrl = base,
            aiStudioBaseUrl = base,
        )
        openAiClient = OpenAiClient(httpClient = httpClient, baseUrl = base)
        claudeClient = ClaudeClient(httpClient = httpClient, baseUrl = base)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun paidGeminiKey(
        id: String = "gem-paid-1",
        apiKey: String = "AQ.synthetic-vertex-key-1",
        provider: ProviderType = ProviderType.GEMINI_VERTEX_EXPRESS,
        priority: Int = 1,
    ) = ApiKeyEntry(
        id = id,
        label = "Gemini $id",
        provider = provider,
        apiKey = apiKey,
        modelId = "gemini-2.5-flash",
        priority = priority,
        isFreeTier = false,
    )

    private fun paidOpenAiKey(
        id: String = "oai-paid-2",
        apiKey: String = "sk-synthetic-openai-2",
        priority: Int = 2,
        isTtsOnly: Boolean = false,
    ) = ApiKeyEntry(
        id = id,
        label = "OpenAI $id",
        provider = ProviderType.OPENAI,
        apiKey = apiKey,
        modelId = "gpt-4o-mini",
        priority = priority,
        isFreeTier = false,
        isTtsOnly = isTtsOnly,
    )

    private fun paidClaudeKey(
        id: String = "cld-paid-1",
        apiKey: String = "sk-ant-synthetic-claude-1",
        priority: Int = 1,
    ) = ApiKeyEntry(
        id = id,
        label = "Claude $id",
        provider = ProviderType.CLAUDE,
        apiKey = apiKey,
        modelId = "claude-3-5-sonnet-latest",
        priority = priority,
        isFreeTier = false,
    )

    private fun freeGeminiKey(
        id: String = "gem-free-1",
        apiKey: String = "AIzaSyntheticFreeKey1",
        priority: Int = 1,
    ) = ApiKeyEntry(
        id = id,
        label = "Free Gemini $id",
        provider = ProviderType.FREE_GEMINI,
        apiKey = apiKey,
        modelId = "gemini-2.5-flash-lite",
        priority = priority,
        isFreeTier = true,
    )

    private class FakeLocalFallbackTts(var shouldSucceed: Boolean = true) : LocalFallbackTts {
        val spokenCalls = mutableListOf<Pair<String, PersonaMode>>()

        override suspend fun speak(text: String, persona: PersonaMode): Boolean {
            spokenCalls.add(text to persona)
            return shouldSucceed
        }
    }

    @Test
    fun chatRouterSilentlyFailsOverFromKey1ToKey2() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":429,"message":"Quota exhausted","status":"RESOURCE_EXHAUSTED"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "Dạ em bật đèn pin cho anh liền nè!",
                            "tool_calls": [
                              {
                                "id": "call_1",
                                "type": "function",
                                "function": {
                                  "name": "toggle_flashlight",
                                  "arguments": "{\"enabled\":true}"
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
            initialKeys = listOf(
                paidGeminiKey(id = "key-1", priority = 1),
                paidOpenAiKey(id = "key-2", priority = 2),
            ),
            clockMs = { 10_000L },
        )
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val router = AiChatRouter(keyPool, stateMachine, geminiClient, openAiClient, claudeClient)

        val reply = router.sendTurn(
            history = emptyList(),
            memories = emptyList(),
            userText = "Bật đèn pin giúp anh",
        )

        assertEquals("Dạ em bật đèn pin cho anh liền nè!", reply.replyText)
        assertEquals(PersonaMode.NANA, reply.persona)
        assertEquals(listOf(DeviceCommand.ToggleFlashlight(enabled = true)), reply.toolCalls)
        assertFalse(reply.quotaExhaustedNotice)
        assertEquals("key-2", reply.usedKeyId)

        assertEquals(QuotaState.ACTIVE_NANA, stateMachine.state)
        assertEquals(KeyStatus.COOLDOWN, keyPool.snapshot().first { it.id == "key-1" }.status)
        assertEquals(KeyStatus.HEALTHY, keyPool.snapshot().first { it.id == "key-2" }.status)

        val req1 = server.takeRequest()
        assertTrue(req1.path!!.contains("generateContent"))
        val req2 = server.takeRequest()
        assertEquals("/v1/chat/completions", req2.path)
    }

    @Test
    fun chatRouterTransitionsToTiredWhenAllPaidKeysExhausted() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":429,"message":"Quota exceeded"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key"}}"""),
        )

        val keyPool = KeyPoolManager(
            initialKeys = listOf(
                paidGeminiKey(id = "paid-1", priority = 1),
                paidOpenAiKey(id = "paid-2", priority = 2),
                freeGeminiKey(id = "free-1", priority = 0),
            ),
            clockMs = { 50_000L },
        )
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val router = AiChatRouter(keyPool, stateMachine, geminiClient, openAiClient, claudeClient)

        val reply = router.sendTurn(
            history = emptyList(),
            memories = emptyList(),
            userText = "Kể chuyện cho anh nghe đi",
        )

        assertTrue(reply.quotaExhaustedNotice)
        assertEquals(PersonaMode.NANA, reply.persona)
        assertEquals(NaNaQuotaStateMachine.QUOTA_EXHAUSTED_NOTICE, reply.replyText)
        assertTrue(reply.toolCalls.isEmpty())
        assertNull(reply.usedKeyId)

        assertEquals(QuotaState.NANA_TIRED, stateMachine.state)
        assertEquals(KeyStatus.COOLDOWN, keyPool.snapshot().first { it.id == "paid-1" }.status)
        assertEquals(KeyStatus.INVALID, keyPool.snapshot().first { it.id == "paid-2" }.status)
        assertEquals(KeyStatus.HEALTHY, keyPool.snapshot().first { it.id == "free-1" }.status)
    }

    @Test
    fun chatRouterUsesFreeKeyInLittleSisterModeWithSharedMemory() = runTest {
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
                              { "text": "Dạ em nhớ anh thích trà sữa ít đường mà!" }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val keyPool = KeyPoolManager(
            initialKeys = listOf(
                paidGeminiKey(id = "paid-1", priority = 1),
                freeGeminiKey(id = "free-1", priority = 1),
            ),
            clockMs = { 10_000L },
        )
        keyPool.markChatQuotaExhausted("paid-1")
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        stateMachine.onAllPaidChatKeysExhausted()
        stateMachine.onWakeWord()
        val consent = stateMachine.onConsentUtterance("ok")
        assertEquals(QuotaState.ACTIVE_LITTLE_SISTER, consent.state)

        val sharedMemories = listOf(
            MemoryFact(
                id = "mem-1",
                category = MemoryCategory.PREFERENCE,
                key = "do_uong",
                value = "Trà sữa ít đường",
                confidence = 0.95f,
                updatedAtMs = 200_000L,
            ),
        )

        val router = AiChatRouter(keyPool, stateMachine, geminiClient, openAiClient, claudeClient)
        val reply = router.sendTurn(
            history = emptyList(),
            memories = sharedMemories,
            userText = "Em còn nhớ anh thích uống gì không?",
        )

        assertEquals("Dạ em nhớ anh thích trà sữa ít đường mà!", reply.replyText)
        assertEquals(PersonaMode.LITTLE_SISTER, reply.persona)
        assertFalse(reply.quotaExhaustedNotice)
        assertEquals("free-1", reply.usedKeyId)

        val recorded = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash-lite:generateContent", recorded.path)
        assertEquals("AIzaSyntheticFreeKey1", recorded.getHeader("x-goog-api-key"))
        val payload = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val sysPrompt = payload["systemInstruction"]!!.jsonObject["parts"]!!
            .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(sysPrompt.contains("Em gái"))
        assertTrue(sysPrompt.contains("Trà sữa ít đường"))

        // If free key fails later, Little Sister returns a gentle in-character Vietnamese message without raw HTTP codes.
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error 500"))
        val offlineReply = router.sendTurn(emptyList(), sharedMemories, "Alo")
        assertEquals(PersonaMode.LITTLE_SISTER, offlineReply.persona)
        assertFalse(offlineReply.quotaExhaustedNotice)
        assertNull(offlineReply.usedKeyId)
        assertFalse(offlineReply.replyText.contains("500"))
        assertFalse(offlineReply.replyText.contains("HTTP", ignoreCase = true))
        assertTrue(offlineReply.replyText.isNotBlank())
    }

    @Test
    fun ttsRouterUnsupportedAqKeyFallsBackWithoutBenchingChatKey() = runTest {
        // 1. Vertex AQ. key fails TTS with 404 on both preview and GA TTS models -> Unsupported
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":404,"message":"Preview TTS model not supported on Vertex Express"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":404,"message":"GA TTS model not supported on Vertex Express"}}"""),
        )
        // 2. OpenAI key succeeds on TTS
        val expectedPcm = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(expectedPcm)),
        )

        val keyPool = KeyPoolManager(
            initialKeys = listOf(
                paidGeminiKey(id = "aq-1", apiKey = "AQ.synthetic-vertex-chat", priority = 1),
                paidOpenAiKey(id = "oai-2", apiKey = "sk-synthetic-oai-tts", priority = 2),
            ),
            clockMs = { 10_000L },
        )
        val localFallback = FakeLocalFallbackTts()
        val ttsRouter = TtsRouter(keyPool, geminiClient, openAiClient, localFallback)

        val spoken = ttsRouter.synthesizeOrSpeak("Chào anh nha!", PersonaMode.NANA)
        assertTrue(spoken is SpokenOutput.CloudPcm)
        assertArrayEquals(expectedPcm, (spoken as SpokenOutput.CloudPcm).audio.pcmBytes)
        assertTrue(localFallback.spokenCalls.isEmpty())

        // Review Focus #1: aq-1 has ttsSupported = false, YET remains HEALTHY and top chat key!
        val aqSnapshot = keyPool.snapshot().first { it.id == "aq-1" }
        assertFalse(aqSnapshot.ttsSupported)
        assertEquals(KeyStatus.HEALTHY, aqSnapshot.status)
        assertEquals(0L, aqSnapshot.cooldownUntilMs)
        assertEquals("aq-1", keyPool.nextHealthyPaidChatKey()?.id)

        // When remaining cloud TTS hits 429 QuotaExceeded, TtsRouter falls back to local TTS and STILL does not bench chat key!
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"message":"TTS rate limit exceeded"}}"""),
        )
        val fallbackSpoken = ttsRouter.synthesizeOrSpeak("Ngủ ngon nha anh!", PersonaMode.NANA)
        assertEquals(SpokenOutput.SpokenByLocalFallback, fallbackSpoken)
        assertEquals(listOf("Ngủ ngon nha anh!" to PersonaMode.NANA), localFallback.spokenCalls)

        val oaiSnapshot = keyPool.snapshot().first { it.id == "oai-2" }
        assertEquals(KeyStatus.HEALTHY, oaiSnapshot.status)
        assertEquals(0L, oaiSnapshot.cooldownUntilMs)
        assertEquals("aq-1", keyPool.nextHealthyPaidChatKey()?.id)
    }

    @Test
    fun ttsRouterSynthesizesWithGeminiOrOpenAiEvenWhenClaudeAnsweredChat() = runTest {
        // 1. Claude answers chat
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "msg_1",
                      "content": [
                        { "type": "text", "text": "Dạ em đây anh ơi!" }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        // 2. Gemini AIza key synthesizes TTS audio
        val pcm = byteArrayOf(0x05, 0x06, 0x07, 0x08)
        val encodedPcm = Base64.getEncoder().encodeToString(pcm)
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
            initialKeys = listOf(
                paidClaudeKey(id = "claude-1", priority = 1),
                paidGeminiKey(
                    id = "gemini-tts-2",
                    apiKey = "AIzaSyntheticAiStudioTts2",
                    provider = ProviderType.GEMINI_AI_STUDIO,
                    priority = 2,
                ),
            ),
            clockMs = { 10_000L },
        )
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val chatRouter = AiChatRouter(keyPool, stateMachine, geminiClient, openAiClient, claudeClient)
        val localFallback = FakeLocalFallbackTts()
        val ttsRouter = TtsRouter(keyPool, geminiClient, openAiClient, localFallback)

        val reply = chatRouter.sendTurn(emptyList(), emptyList(), "Na Na ơi")
        assertEquals("claude-1", reply.usedKeyId)
        assertEquals("Dạ em đây anh ơi!", reply.replyText)

        val spoken = ttsRouter.synthesizeOrSpeak(reply.replyText, reply.persona)
        assertTrue(spoken is SpokenOutput.CloudPcm)
        assertArrayEquals(pcm, (spoken as SpokenOutput.CloudPcm).audio.pcmBytes)

        val req1 = server.takeRequest()
        assertEquals("/v1/messages", req1.path)
        val req2 = server.takeRequest()
        assertEquals("/v1beta/models/gemini-2.5-flash-preview-tts:generateContent", req2.path)

        // In LITTLE_SISTER mode with only paid keys configured, TTS goes straight to localFallback without cloud calls.
        val sisterSpoken = ttsRouter.synthesizeOrSpeak("Em nghe nè!", PersonaMode.LITTLE_SISTER)
        assertEquals(SpokenOutput.SpokenByLocalFallback, sisterSpoken)
        assertEquals(1, localFallback.spokenCalls.size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun memoryExtractorParsesJsonWrappedInMarkdownFence() {
        val extractor = MemoryExtractor()
        val prompt = extractor.buildExtractionPrompt(
            listOf(
                ChatMessage("1", "s1", "user", PersonaMode.NANA, "Anh thích uống cà phê sữa đá buổi sáng.", 1_000L),
                ChatMessage("2", "s1", "assistant", PersonaMode.NANA, "Dạ em nhớ rồi ạ!", 2_000L),
            ),
        )
        assertTrue(prompt.contains("cà phê sữa đá"))
        assertTrue(prompt.contains("PREFERENCE"))

        val rawResponse = """
            Đây là các thông tin em ghi nhớ được nè:
            ```json
            [
              {
                "category": "PREFERENCE",
                "key": "morning_drink",
                "value": "Cà phê sữa đá",
                "confidence": 0.94
              },
              {
                "category": "SCHEDULE",
                "key": "wake_up_time",
                "value": "7 giờ sáng",
                "confidence": 0.85
              },
              {
                "category": "HABIT",
                "key": "",
                "value": "Missing key should be skipped",
                "confidence": 0.8
              },
              {
                "category": "UNKNOWN_CAT",
                "key": "pet_name",
                "value": "Miu",
                "confidence": "not-a-number"
              },
              "malformed-string-entry"
            ]
            ```
        """.trimIndent()

        val facts = extractor.parseExtractedFacts(rawResponse, nowMs = 999_000L)
        assertEquals(3, facts.size)

        assertEquals(MemoryCategory.PREFERENCE, facts[0].category)
        assertEquals("morning_drink", facts[0].key)
        assertEquals("Cà phê sữa đá", facts[0].value)
        assertEquals(0.94f, facts[0].confidence, 0.001f)
        assertEquals(999_000L, facts[0].updatedAtMs)
        assertFalse(facts[0].synced)

        assertEquals(MemoryCategory.SCHEDULE, facts[1].category)
        assertEquals("wake_up_time", facts[1].key)
        assertEquals("7 giờ sáng", facts[1].value)

        // Unknown category falls back safely to FACT and non-numeric confidence falls back cleanly.
        assertEquals(MemoryCategory.FACT, facts[2].category)
        assertEquals("pet_name", facts[2].key)
        assertEquals("Miu", facts[2].value)
        assertTrue(facts[2].confidence in 0.0f..1.0f)

        assertTrue(extractor.parseExtractedFacts("not json at all", nowMs = 1L).isEmpty())
        assertTrue(extractor.parseExtractedFacts("```json\n[]\n```", nowMs = 1L).isEmpty())

        val wrappedFacts = extractor.parseExtractedFacts(
            """{"facts":[{"category":"HABIT","key":"sleep","value":"11pm","confidence":0.9}]}""",
            nowMs = 2L,
        )
        assertEquals(1, wrappedFacts.size)
        assertEquals(MemoryCategory.HABIT, wrappedFacts[0].category)
        assertEquals("sleep", wrappedFacts[0].key)
    }

    @Test
    fun chatRouterTranscribesAudioViaWhisperBeforeClaudeChat() = runTest {
        // 1. OpenAI Whisper transcribes audio
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"Mở báo thức 6 giờ rưỡi nha em"}"""),
        )
        // 2. Claude responds to transcribed text
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "msg_2",
                      "content": [
                        { "type": "text", "text": "Dạ em đặt báo thức 6 giờ rưỡi rồi ạ!" },
                        {
                          "type": "tool_use",
                          "id": "t1",
                          "name": "set_alarm",
                          "input": { "hour": 6, "minute": 30 }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val keyPool = KeyPoolManager(
            initialKeys = listOf(
                paidClaudeKey(id = "claude-1", priority = 1),
                paidOpenAiKey(id = "openai-2", priority = 2),
            ),
            clockMs = { 10_000L },
        )
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val router = AiChatRouter(keyPool, stateMachine, geminiClient, openAiClient, claudeClient)

        val reply = router.sendTurn(
            history = emptyList(),
            memories = emptyList(),
            userText = null,
            userAudio = AudioAttachment(bytes = byteArrayOf(0x52, 0x49, 0x46, 0x46), mimeType = "audio/wav"),
        )

        assertEquals("claude-1", reply.usedKeyId)
        assertEquals("Dạ em đặt báo thức 6 giờ rưỡi rồi ạ!", reply.replyText)
        assertEquals(listOf(DeviceCommand.SetAlarm(6, 30, "")), reply.toolCalls)

        val req1 = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req1.path)
        val req2 = server.takeRequest()
        assertEquals("/v1/messages", req2.path)
        val claudeBody = json.parseToJsonElement(req2.body.readUtf8()).jsonObject
        val userMessage = claudeBody["messages"]!!.jsonArray.last().jsonObject
        assertEquals("Mở báo thức 6 giờ rưỡi nha em", userMessage["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun personaPromptBuilderCapsMemoriesAt40MostRecentAndEnforcesVoiceFriendlyRules() {
        val memories = (1..50).map { i ->
            MemoryFact(
                id = "m-$i",
                category = MemoryCategory.FACT,
                key = "fact_$i",
                value = "val_$i",
                confidence = 0.9f,
                updatedAtMs = i * 1_000L,
            )
        }
        val prompt = PersonaPromptBuilder.buildSystemPrompt(PersonaMode.NANA, memories)
        assertTrue(prompt.contains("Na Na"))
        assertTrue(prompt.contains("val_50"))
        assertTrue(prompt.contains("val_11"))
        assertFalse(prompt.contains("val_10\n"))
        assertFalse(prompt.contains("val_1\n"))

        val style = PersonaPromptBuilder.buildTtsStyleInstruction(PersonaMode.NANA)
        assertNotNull(style)
        assertTrue(style.isNotBlank())
    }

    @Test
    fun personaPromptBuilderAndRouterInjectVietnamUtcPlus7DateTime() = runTest {
        // 2026-11-21T23:54:00Z == Sunday 2026-11-22 06:54 in Asia/Ho_Chi_Minh (UTC+7).
        val fixedEpochMs = java.time.Instant.parse("2026-11-21T23:54:00Z").toEpochMilli()
        val prompt = PersonaPromptBuilder.buildSystemPrompt(
            persona = PersonaMode.NANA,
            memories = emptyList(),
            nowMs = fixedEpochMs,
        )
        assertTrue("Expected UTC+7 marker in prompt: $prompt", prompt.contains("UTC+7"))
        assertTrue("Expected 06:54 Vietnam time in prompt: $prompt", prompt.contains("06:54"))
        assertTrue("Expected 22/11/2026 Vietnam date in prompt: $prompt", prompt.contains("22/11/2026"))
        assertTrue("Expected Chủ Nhật in prompt: $prompt", prompt.contains("Chủ Nhật"))

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"Dạ bây giờ là 6 giờ 54 phút sáng rồi anh ơi!"}]}}]}"""),
        )
        val keyPool = KeyPoolManager(initialKeys = listOf(paidGeminiKey(id = "gem-time", priority = 1)))
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val router = AiChatRouter(
            keyPool = keyPool,
            stateMachine = stateMachine,
            geminiClient = geminiClient,
            openAiClient = openAiClient,
            claudeClient = claudeClient,
            clockMs = { fixedEpochMs },
        )

        router.sendTurn(emptyList(), emptyList(), "Mấy giờ rồi em?")
        val req = server.takeRequest()
        val payload = json.parseToJsonElement(req.body.readUtf8()).jsonObject
        val sysText = payload["systemInstruction"]!!.jsonObject["parts"]!!
            .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Router must inject UTC+7 time into systemInstruction", sysText.contains("06:54"))
        assertTrue("Router must inject UTC+7 date into systemInstruction", sysText.contains("22/11/2026"))
    }

    @Test
    fun chatRouterExecutesSilentWebSearchWithoutOpeningBrowserAndSynthesizesSpokenAnswer() = runTest {
        // 1. Model calls search_live_info (or even web_search without user saying "mở web")
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
                                "functionCall": {
                                  "name": "search_live_info",
                                  "args": { "query": "giá vàng hôm nay thế giới và Mi Hồng" }
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
        // 2 & 3. Bing RSS + Google News RSS return live gold prices
        val rssXml = """
            <rss version="2.0"><channel>
              <item>
                <title>Giá vàng Mi Hồng hôm nay 133,5 triệu đồng/lượng</title>
                <description>Vàng thế giới XAU 3.575 USD/oz, Mi Hồng mua vào 13,2 triệu bán ra 13,35 triệu/chỉ.</description>
                <pubDate>Sun, 22 Nov 2026 09:00:00 GMT</pubDate>
              </item>
            </channel></rss>
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssXml))
        server.enqueue(MockResponse().setResponseCode(200).setBody(rssXml))
        // 4. Second Gemini call synthesizes natural spoken Vietnamese answer
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
                              { "text": "Dạ hôm nay giá vàng thế giới khoảng 3.575 đô một ounce, còn tại Mi Hồng giá bán ra là 133,5 triệu đồng một lượng nha anh!" }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val base = server.url("/").toString().trimEnd('/')
        val silentSearch = SilentWebSearchClient(
            httpClient = httpClient,
            bingBaseUrl = base,
            googleNewsBaseUrl = base,
            duckDuckGoBaseUrl = base,
        )
        val keyPool = KeyPoolManager(initialKeys = listOf(paidGeminiKey(id = "gem-gold", priority = 1)))
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val router = AiChatRouter(
            keyPool = keyPool,
            stateMachine = stateMachine,
            geminiClient = geminiClient,
            openAiClient = openAiClient,
            claudeClient = claudeClient,
            silentWebSearchClient = silentSearch,
        )

        val reply = router.sendTurn(
            history = emptyList(),
            memories = emptyList(),
            userText = "Giá vàng hôm nay thế giới và Mi Hồng thế nào?",
        )

        assertTrue("Expected spoken gold price answer, got: ${reply.replyText}", reply.replyText.contains("133,5 triệu"))
        assertTrue("Browser must NOT be opened when user did not ask to open web", reply.toolCalls.isEmpty())
    }

    @Test
    fun chatRouterHandlesUnclearAudioWithoutHallucinatingAndSupportsStopSessionMarker() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"[UNCLEAR_AUDIO] Dạ em nghe chưa rõ, anh nói lại giúp em nha?"}]}}]}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"[STOP_SESSION] Dạ em tạm nghỉ đây, khi nào cần anh cứ gọi Na Na nha!"}]}}]}""",
                ),
        )

        val keyPool = KeyPoolManager(initialKeys = listOf(paidGeminiKey(id = "gem-unclear", priority = 1)))
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val router = AiChatRouter(
            keyPool = keyPool,
            stateMachine = stateMachine,
            geminiClient = geminiClient,
            openAiClient = openAiClient,
            claudeClient = claudeClient,
        )

        val unclearReply = router.sendTurn(
            history = emptyList(),
            memories = emptyList(),
            userText = null,
            userAudio = AudioAttachment(bytes = byteArrayOf(1, 2, 3, 4), mimeType = "audio/wav"),
        )
        assertTrue(unclearReply.unclearAudio)
        assertEquals("Dạ em nghe chưa rõ, anh nói lại giúp em nha?", unclearReply.replyText)
        assertTrue(unclearReply.toolCalls.isEmpty())

        val stopReply = router.sendTurn(
            history = emptyList(),
            memories = emptyList(),
            userText = null,
            userAudio = AudioAttachment(bytes = byteArrayOf(5, 6, 7, 8), mimeType = "audio/wav"),
        )
        assertFalse(stopReply.unclearAudio)
        assertEquals("Dạ em tạm nghỉ đây, khi nào cần anh cứ gọi Na Na nha!", stopReply.replyText)
        assertEquals(listOf<DeviceCommand>(DeviceCommand.StopSession), stopReply.toolCalls)
    }

    @Test
    fun memoryExtractorGuardsNonFiniteConfidenceValues() {
        val extractor = MemoryExtractor()
        val raw = """
            [
              {"category":"FACT","key":"nan_num","value":"v1","confidence":"NaN"},
              {"category":"FACT","key":"inf_num","value":"v2","confidence":"Infinity"},
              {"category":"FACT","key":"neg_inf","value":"v3","confidence":"-Infinity"}
            ]
        """.trimIndent()
        val facts = extractor.parseExtractedFacts(raw, nowMs = 5_000L)
        assertEquals(3, facts.size)
        facts.forEach { fact ->
            assertTrue(fact.confidence.isFinite())
            assertTrue(fact.confidence in 0.0f..1.0f)
        }
    }

    @Test
    fun chatRouterSkipsNonGeminiKeysOnEmptyAudioTranscriptAndFallsBackToGemini() = runTest {
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
                              { "text": "Dạ em nghe giọng anh qua Gemini rồi nè!" }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val keyPool = KeyPoolManager(
            initialKeys = listOf(
                paidClaudeKey(id = "claude-1", priority = 1),
                paidGeminiKey(id = "gemini-2", priority = 2),
            ),
            clockMs = { 10_000L },
        )
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val router = AiChatRouter(keyPool, stateMachine, geminiClient, openAiClient, claudeClient)

        val reply = router.sendTurn(
            history = emptyList(),
            memories = emptyList(),
            userText = null,
            userAudio = AudioAttachment(bytes = byteArrayOf(0x52, 0x49, 0x46, 0x46), mimeType = "audio/wav"),
        )

        assertEquals("gemini-2", reply.usedKeyId)
        assertEquals("Dạ em nghe giọng anh qua Gemini rồi nè!", reply.replyText)
        assertEquals(1, server.requestCount)
        assertTrue(server.takeRequest().path!!.contains("generateContent"))
        assertEquals(KeyStatus.HEALTHY, keyPool.snapshot().first { it.id == "claude-1" }.status)
    }
}
