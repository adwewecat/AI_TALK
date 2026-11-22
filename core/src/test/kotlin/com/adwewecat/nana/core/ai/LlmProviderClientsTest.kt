package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.DeviceCommand
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.ProviderType
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmProviderClientsTest {

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

    private fun sampleHistory(): List<ChatMessage> = listOf(
        ChatMessage(
            id = "m1",
            sessionId = "s1",
            role = "user",
            persona = PersonaMode.NANA,
            content = "Chào Na Na",
            createdAtMs = 1_000L,
        ),
        ChatMessage(
            id = "m2",
            sessionId = "s1",
            role = "assistant",
            persona = PersonaMode.NANA,
            content = "Dạ Na Na nghe nè anh ơi!",
            createdAtMs = 2_000L,
        ),
    )

    private fun geminiKey(
        apiKey: String,
        provider: ProviderType,
        modelId: String = "gemini-2.5-flash",
    ) = ApiKeyEntry(
        id = "gem-1",
        label = "Gemini Key",
        provider = provider,
        apiKey = apiKey,
        modelId = modelId,
        priority = 1,
        isFreeTier = provider == ProviderType.FREE_GEMINI,
    )

    private fun openAiKey(
        apiKey: String = "sk-synthetic-openai-key",
        modelId: String = "gpt-4o-mini",
    ) = ApiKeyEntry(
        id = "oai-1",
        label = "OpenAI Key",
        provider = ProviderType.OPENAI,
        apiKey = apiKey,
        modelId = modelId,
        priority = 2,
        isFreeTier = false,
    )

    private fun claudeKey(
        apiKey: String = "sk-ant-synthetic-claude-key",
        modelId: String = "claude-3-5-sonnet-latest",
    ) = ApiKeyEntry(
        id = "cld-1",
        label = "Claude Key",
        provider = ProviderType.CLAUDE,
        apiKey = apiKey,
        modelId = modelId,
        priority = 3,
        isFreeTier = false,
    )

    @Test
    fun geminiVertexExpressUsesAiplatformEndpointAndHeader() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "candidates": [
                        {
                          "content": {
                            "role": "model",
                            "parts": [
                              { "text": "Dạ để em mở nhạc chill cho anh nha!" },
                              {
                                "functionCall": {
                                  "name": "open_youtube_search",
                                  "args": {
                                    "query": "nhạc lofi chill",
                                    "auto_play": true
                                  }
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

        val client = GeminiClient(
            httpClient = httpClient,
            vertexBaseUrl = baseUrl(),
            aiStudioBaseUrl = "http://127.0.0.1:1",
        )
        val key = geminiKey(
            apiKey = "AQ.synthetic-vertex-key-123",
            provider = ProviderType.GEMINI_VERTEX_EXPRESS,
            modelId = "gemini-2.5-flash",
        )

        val result = client.generate(
            key = key,
            request = LlmRequest(
                systemPrompt = "Bạn là Na Na dễ thương.",
                history = sampleHistory(),
                userText = "Mở youtube tìm nhạc lofi chill cho anh",
                enableTools = true,
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/v1/publishers/google/models/gemini-2.5-flash:generateContent",
            recorded.path,
        )
        assertEquals("AQ.synthetic-vertex-key-123", recorded.getHeader("x-goog-api-key"))

        val payload = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val systemText = payload["systemInstruction"]!!.jsonObject["parts"]!!
            .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("Bạn là Na Na dễ thương.", systemText)

        val contents = payload["contents"]!!.jsonArray
        assertEquals(3, contents.size)
        assertEquals("user", contents[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("user", contents[2].jsonObject["role"]!!.jsonPrimitive.content)

        val declarations = payload["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals(13, declarations.size)

        assertTrue(result is LlmResult.Success)
        val success = result as LlmResult.Success
        assertEquals("Dạ để em mở nhạc chill cho anh nha!", success.replyText)
        assertEquals(
            listOf(DeviceCommand.OpenYouTubeSearch(query = "nhạc lofi chill", autoPlay = true)),
            success.toolCalls,
        )
    }

    @Test
    fun geminiAiStudioUsesGenerativelanguageEndpointAndInlineAudio() = runTest {
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
                              { "text": "Em nghe rõ rồi anh ơi!" }
                            ]
                          }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val audioBytes = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x10, 0x20, 0x30)
        val client = GeminiClient(
            httpClient = httpClient,
            vertexBaseUrl = "http://127.0.0.1:1",
            aiStudioBaseUrl = baseUrl(),
        )
        val key = geminiKey(
            apiKey = "AIzaSyntheticAiStudioKey456",
            provider = ProviderType.GEMINI_AI_STUDIO,
            modelId = "gemini-2.5-flash-lite",
        )

        val result = client.generate(
            key = key,
            request = LlmRequest(
                systemPrompt = "Em là Na Na.",
                history = emptyList(),
                userText = null,
                userAudio = AudioAttachment(bytes = audioBytes, mimeType = "audio/wav"),
                enableTools = false,
            ),
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(
            "/v1beta/models/gemini-2.5-flash-lite:generateContent",
            recorded.path,
        )
        assertEquals("AIzaSyntheticAiStudioKey456", recorded.getHeader("x-goog-api-key"))

        val payload = json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertFalse(payload.containsKey("tools"))
        val inlineData = payload["contents"]!!.jsonArray[0].jsonObject["parts"]!!
            .jsonArray[0].jsonObject["inlineData"]!!.jsonObject
        assertEquals("audio/wav", inlineData["mimeType"]!!.jsonPrimitive.content)
        assertEquals(
            Base64.getEncoder().encodeToString(audioBytes),
            inlineData["data"]!!.jsonPrimitive.content,
        )

        assertTrue(result is LlmResult.Success)
        assertEquals("Em nghe rõ rồi anh ơi!", (result as LlmResult.Success).replyText)
    }

    @Test
    fun geminiReturnsQuotaExceededOn429AndInvalidKeyOn403() = runTest {
        val secretKey = "AQ.synthetic-secret-key-999"
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":429,"message":"Rate limit hit for $secretKey","status":"RESOURCE_EXHAUSTED"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(400)
                .setBody("""{"error":{"code":400,"message":"Quota exceeded: RESOURCE_EXHAUSTED"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":403,"message":"API key $secretKey not valid","status":"PERMISSION_DENIED"}}"""),
        )

        val client = GeminiClient(
            httpClient = httpClient,
            vertexBaseUrl = baseUrl(),
            aiStudioBaseUrl = baseUrl(),
        )
        val key = geminiKey(apiKey = secretKey, provider = ProviderType.GEMINI_VERTEX_EXPRESS)
        val request = LlmRequest(systemPrompt = "Na Na", history = emptyList(), userText = "Alo")

        val rateLimit = client.generate(key, request)
        assertTrue(rateLimit is LlmResult.QuotaExceeded)
        val quotaResult = rateLimit as LlmResult.QuotaExceeded
        assertEquals(429, quotaResult.httpCode)
        assertFalse(quotaResult.detail.contains(secretKey))

        val bodyQuota = client.generate(key, request)
        assertTrue(bodyQuota is LlmResult.QuotaExceeded)
        assertEquals(400, (bodyQuota as LlmResult.QuotaExceeded).httpCode)

        val forbidden = client.generate(key, request)
        assertTrue(forbidden is LlmResult.InvalidKey)
        val invalidResult = forbidden as LlmResult.InvalidKey
        assertEquals(403, invalidResult.httpCode)
        assertFalse(invalidResult.detail.contains(secretKey))
    }

    @Test
    fun geminiTtsParsesBase64PcmAndMaps404ToUnsupported() = runTest {
        val rawPcm = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val encodedPcm = Base64.getEncoder().encodeToString(rawPcm)

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
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":404,"message":"Preview model not found"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(404)
                .setBody("""{"error":{"code":404,"message":"GA TTS model not found"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"only text"}]}}]}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"code":429,"message":"Quota exceeded"}}"""),
        )

        val client = GeminiClient(
            httpClient = httpClient,
            vertexBaseUrl = baseUrl(),
            aiStudioBaseUrl = baseUrl(),
        )

        val aiStudioKey = geminiKey("AIzaSyntheticTtsKey", ProviderType.GEMINI_AI_STUDIO)
        val okResult = client.synthesizeSpeech(
            key = aiStudioKey,
            text = "Chào anh nha!",
            styleInstruction = "Nói giọng ngọt ngào dễ thương:",
            voiceName = "Aoede",
        )

        val ttsRequest = server.takeRequest()
        assertEquals(
            "/v1beta/models/gemini-2.5-flash-preview-tts:generateContent",
            ttsRequest.path,
        )
        val ttsJson = json.parseToJsonElement(ttsRequest.body.readUtf8()).jsonObject
        val genConfig = ttsJson["generationConfig"]!!.jsonObject
        assertEquals("AUDIO", genConfig["responseModalities"]!!.jsonArray[0].jsonPrimitive.content)
        val voice = genConfig["speechConfig"]!!.jsonObject["voiceConfig"]!!
            .jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content
        assertEquals("Aoede", voice)

        assertTrue(okResult is TtsResult.Success)
        val audio = (okResult as TtsResult.Success).audio
        assertArrayEquals(rawPcm, audio.pcmBytes)
        assertEquals(24_000, audio.sampleRateHz)
        assertEquals("audio/L16;codec=pcm;rate=24000", audio.mimeType)

        val vertexKey = geminiKey("AQ.synthetic-vertex-tts", ProviderType.GEMINI_VERTEX_EXPRESS)
        val notFound = client.synthesizeSpeech(vertexKey, "Test", "Cute")
        assertTrue(notFound is TtsResult.Unsupported)
        val vertexTtsRequest = server.takeRequest()
        assertEquals(
            "/v1/publishers/google/models/gemini-2.5-flash-preview-tts:generateContent",
            vertexTtsRequest.path,
        )
        val vertexGaRetryRequest = server.takeRequest()
        assertEquals(
            "/v1/publishers/google/models/gemini-2.5-flash-tts:generateContent",
            vertexGaRetryRequest.path,
        )

        val missingAudio = client.synthesizeSpeech(aiStudioKey, "Test", "Cute")
        assertTrue(missingAudio is TtsResult.Unsupported)

        val quota = client.synthesizeSpeech(aiStudioKey, "Test", "Cute")
        assertTrue(quota is TtsResult.QuotaExceeded)
    }

    @Test
    fun openAiAndClaudeParseRepliesToolsAndRateLimits() = runTest {
        // 1. OpenAI chat + tool_calls
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
                            "content": "Dạ em đặt báo thức 7 giờ sáng rồi ạ!",
                            "tool_calls": [
                              {
                                "id": "call_1",
                                "type": "function",
                                "function": {
                                  "name": "set_alarm",
                                  "arguments": "{\"hour\":7,\"minute\":0,\"label\":\"Dậy đi làm\"}"
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
        // 2. OpenAI insufficient_quota
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"message":"You exceeded your current quota","type":"insufficient_quota"}}"""),
        )
        // 3. OpenAI invalid key
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key provided"}}"""),
        )
        // 4. OpenAI TTS PCM
        val expectedOpenAiPcm = byteArrayOf(10, 20, 30, 40)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(expectedOpenAiPcm)),
        )
        // 5. OpenAI Whisper transcription
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"Na Na ơi mở đèn pin giùm anh"}"""),
        )

        val openAi = OpenAiClient(httpClient = httpClient, baseUrl = baseUrl())
        val oKey = openAiKey()

        val oaiReply = openAi.generate(
            key = oKey,
            request = LlmRequest(
                systemPrompt = "Em là Na Na.",
                history = sampleHistory(),
                userText = "Đặt báo thức 7 giờ dậy đi làm",
            ),
        )
        val oaiReq = server.takeRequest()
        assertEquals("/v1/chat/completions", oaiReq.path)
        assertEquals("Bearer sk-synthetic-openai-key", oaiReq.getHeader("Authorization"))
        assertTrue(oaiReply is LlmResult.Success)
        val oaiSuccess = oaiReply as LlmResult.Success
        assertEquals("Dạ em đặt báo thức 7 giờ sáng rồi ạ!", oaiSuccess.replyText)
        assertEquals(
            listOf(DeviceCommand.SetAlarm(hour = 7, minute = 0, label = "Dậy đi làm")),
            oaiSuccess.toolCalls,
        )

        assertTrue(openAi.generate(oKey, LlmRequest("s", emptyList(), "hi")) is LlmResult.QuotaExceeded)
        server.takeRequest()
        assertTrue(openAi.generate(oKey, LlmRequest("s", emptyList(), "hi")) is LlmResult.InvalidKey)
        server.takeRequest()

        val oaiTts = openAi.synthesizeSpeech(oKey, "Xin chào anh")
        val oaiTtsReq = server.takeRequest()
        assertEquals("/v1/audio/speech", oaiTtsReq.path)
        val ttsBody = json.parseToJsonElement(oaiTtsReq.body.readUtf8()).jsonObject
        assertEquals("pcm", ttsBody["response_format"]!!.jsonPrimitive.content)
        assertEquals("nova", ttsBody["voice"]!!.jsonPrimitive.content)
        assertEquals("tts-1", ttsBody["model"]!!.jsonPrimitive.content)
        assertTrue(oaiTts is TtsResult.Success)
        assertArrayEquals(expectedOpenAiPcm, (oaiTts as TtsResult.Success).audio.pcmBytes)
        assertEquals(24_000, oaiTts.audio.sampleRateHz)

        val transcript = openAi.transcribeAudio(oKey, byteArrayOf(0x52, 0x49, 0x46, 0x46))
        val whisperReq = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", whisperReq.path)
        val multipartText = whisperReq.body.readUtf8()
        assertTrue(multipartText.contains("whisper-1"))
        assertTrue(multipartText.contains("name=\"language\""))
        assertTrue(multipartText.contains("vi"))
        assertEquals("Na Na ơi mở đèn pin giùm anh", transcript)

        // 6. Claude messages + tool_use
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "msg_1",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        { "type": "text", "text": "Dạ em bật đèn pin liền nè!" },
                        {
                          "type": "tool_use",
                          "id": "toolu_1",
                          "name": "toggle_flashlight",
                          "input": { "enabled": true }
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
        )
        // 7. Claude 429 & 529 & 403
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"type":"rate_limit_error"}}"""))
        server.enqueue(MockResponse().setResponseCode(529).setBody("""{"error":{"type":"overloaded_error"}}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"type":"permission_error","message":"Bad key sk-ant-synthetic-claude-key"}}"""))

        val claude = ClaudeClient(httpClient = httpClient, baseUrl = baseUrl())
        val cKey = claudeKey()

        val claudeReply = claude.generate(
            key = cKey,
            request = LlmRequest(
                systemPrompt = "Em là Na Na.",
                history = sampleHistory(),
                userText = "Bật đèn pin lên",
            ),
        )
        val claudeReq = server.takeRequest()
        assertEquals("/v1/messages", claudeReq.path)
        assertEquals("sk-ant-synthetic-claude-key", claudeReq.getHeader("x-api-key"))
        assertEquals("2023-06-01", claudeReq.getHeader("anthropic-version"))
        assertTrue(claudeReply is LlmResult.Success)
        val claudeSuccess = claudeReply as LlmResult.Success
        assertEquals("Dạ em bật đèn pin liền nè!", claudeSuccess.replyText)
        assertEquals(listOf(DeviceCommand.ToggleFlashlight(enabled = true)), claudeSuccess.toolCalls)

        val claude429 = claude.generate(cKey, LlmRequest("s", emptyList(), "hi"))
        assertTrue(claude429 is LlmResult.QuotaExceeded)
        assertEquals(429, (claude429 as LlmResult.QuotaExceeded).httpCode)

        val claude529 = claude.generate(cKey, LlmRequest("s", emptyList(), "hi"))
        assertTrue(claude529 is LlmResult.QuotaExceeded)
        assertEquals(529, (claude529 as LlmResult.QuotaExceeded).httpCode)

        val claude403 = claude.generate(cKey, LlmRequest("s", emptyList(), "hi"))
        assertTrue(claude403 is LlmResult.InvalidKey)
        val invalidClaude = claude403 as LlmResult.InvalidKey
        assertEquals(403, invalidClaude.httpCode)
        assertFalse(invalidClaude.detail.contains(cKey.apiKey))

        val requestsBeforeReject = server.requestCount
        val audioOnlyWithHistory = claude.generate(
            key = cKey,
            request = LlmRequest(
                systemPrompt = "s",
                history = sampleHistory(),
                userText = null,
                userAudio = AudioAttachment(bytes = byteArrayOf(1, 2, 3), mimeType = "audio/wav"),
            ),
        )
        assertTrue(audioOnlyWithHistory is LlmResult.TransientError)
        assertEquals(requestsBeforeReject, server.requestCount)
    }

    @Test
    fun toolSchemaParserParsesAllElevenDeviceCommands() {
        assertEquals(
            DeviceCommand.OpenYouTubeSearch("lofi", autoPlay = true),
            ToolSchemaParser.parseCommand(
                "open_youtube_search",
                buildJsonObject { put("query", JsonPrimitive("lofi")) },
            ),
        )
        assertEquals(
            DeviceCommand.AnswerIncomingCall,
            ToolSchemaParser.parseCommand("answer_incoming_call", buildJsonObject {}),
        )
        assertEquals(
            DeviceCommand.EndCall,
            ToolSchemaParser.parseCommand("end_call", buildJsonObject {}),
        )
        assertEquals(
            DeviceCommand.ToggleSpeaker(true),
            ToolSchemaParser.parseCommand(
                "toggle_speaker",
                buildJsonObject { put("enabled", JsonPrimitive(true)) },
            ),
        )
        assertEquals(
            DeviceCommand.MakePhoneCall("Mẹ"),
            ToolSchemaParser.parseCommand(
                "make_phone_call",
                buildJsonObject { put("target", JsonPrimitive("Mẹ")) },
            ),
        )
        assertEquals(
            DeviceCommand.OpenApp("Zalo"),
            ToolSchemaParser.parseCommand(
                "open_app",
                buildJsonObject { put("app_name", JsonPrimitive("Zalo")) },
            ),
        )
        assertEquals(
            DeviceCommand.SetAlarm(6, 30, ""),
            ToolSchemaParser.parseCommand(
                "set_alarm",
                buildJsonObject {
                    put("hour", JsonPrimitive(6))
                    put("minute", JsonPrimitive(30))
                },
            ),
        )
        assertEquals(
            DeviceCommand.SetTimer(300, "Luộc trứng"),
            ToolSchemaParser.parseCommand(
                "set_timer",
                buildJsonObject {
                    put("seconds", JsonPrimitive(300))
                    put("label", JsonPrimitive("Luộc trứng"))
                },
            ),
        )
        assertEquals(
            DeviceCommand.AdjustVolume(stream = "music", direction = "up", percent = null),
            ToolSchemaParser.parseCommand("adjust_volume", buildJsonObject {}),
        )
        assertEquals(
            DeviceCommand.ToggleFlashlight(false),
            ToolSchemaParser.parseCommand(
                "toggle_flashlight",
                buildJsonObject { put("enabled", JsonPrimitive(false)) },
            ),
        )
        assertEquals(
            DeviceCommand.MakeZaloCall("Anh Tuấn"),
            ToolSchemaParser.parseCommand(
                "call_zalo",
                buildJsonObject { put("target", JsonPrimitive("Anh Tuấn")) },
            ),
        )
        assertEquals(
            DeviceCommand.SilentWebSearch("giá vàng hôm nay thế giới và Mi Hồng"),
            ToolSchemaParser.parseCommand(
                "search_live_info",
                buildJsonObject { put("query", JsonPrimitive("giá vàng hôm nay thế giới và Mi Hồng")) },
            ),
        )
        assertEquals(
            DeviceCommand.WebSearch("thời tiết Hà Nội"),
            ToolSchemaParser.parseCommand(
                "web_search",
                buildJsonObject { put("query", JsonPrimitive("thời tiết Hà Nội")) },
            ),
        )
        assertNull(ToolSchemaParser.parseCommand("unknown_tool", buildJsonObject {}))
    }
}
