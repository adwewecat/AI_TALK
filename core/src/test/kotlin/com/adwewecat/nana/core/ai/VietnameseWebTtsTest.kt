package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.ProviderType
import java.util.Base64
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VietnameseWebTtsTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun sanitizeForSpeechStripsMarkdownLinksAndUrlsWhileKeepingVietnameseAccents() {
        val raw = "**Chào anh yêu** *hôm nay* mở [nhạc chill](https://youtube.com/watch?v=123) nhé! `ok` https://example.com #vui"
        val cleaned = TtsTextSanitizer.sanitizeForSpeech(raw)

        assertEquals("Chào anh yêu hôm nay mở nhạc chill nhé! ok vui", cleaned)
        assertFalse(cleaned.contains("*"))
        assertFalse(cleaned.contains("https://"))
    }

    @Test
    fun chunkForWebTtsSplitsLongVietnameseTextAtSentenceAndWordBoundaries() {
        val longText = "Em chào anh yêu ơi, hôm nay anh đi làm có mệt không nè? " +
            "Nếu anh thấy hơi đuối thì để em mở một bản nhạc nhẹ nhàng cho anh thư giãn nha! " +
            "Anh nhớ uống đủ nước và ăn tối đúng giờ đó, em lúc nào cũng thương và lo cho anh nhiều lắm luôn."

        val chunks = TtsTextSanitizer.chunkForWebTts(longText, maxChunkChars = 90)

        assertTrue("Expected multiple chunks for long text", chunks.size >= 3)
        assertTrue(chunks.all { it.length <= 90 && it.isNotBlank() })
        assertEquals(longText, chunks.joinToString(" "))
    }

    @Test
    fun vietnameseWebTtsClientFetchesMp3ChunksWithVietnameseLocale() = runTest {
        val fakeMp3 = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x11, 0x22)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeMp3)),
        )

        val client = VietnameseWebTtsClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )

        val chunks = client.synthesizeMp3Chunks("**Em chào anh yêu ơi!**")
        assertEquals(1, chunks.size)
        assertArrayEquals(fakeMp3, chunks.first())

        val req = server.takeRequest()
        val path = req.path.orEmpty()
        assertTrue("Expected /translate_tts path, got $path", path.startsWith("/translate_tts?"))
        assertTrue("Expected tl=vi query param, got $path", path.contains("tl=vi"))
        assertTrue("Expected client=tw-ob query param, got $path", path.contains("client=tw-ob"))
        assertFalse("Markdown should be stripped before request", path.contains("%2A"))
    }

    @Test
    fun geminiVertexExpressRetriesGaTtsModelWhenPreviewReturns404() = runTest {
        val pcmBytes = byteArrayOf(1, 2, 3, 4, 5, 6)
        val b64 = Base64.getEncoder().encodeToString(pcmBytes)

        // First call to preview model returns 404 on Vertex Express; second call to GA gemini-2.5-flash-tts succeeds.
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"Not found"}}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(
                    """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000","data":"$b64"}}]}}]}""",
                ),
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = GeminiClient(httpClient = OkHttpClient(), vertexBaseUrl = baseUrl, aiStudioBaseUrl = baseUrl)
        val aqKey = ApiKeyEntry(
            id = "aq-1",
            label = "Vertex Express",
            provider = ProviderType.GEMINI_VERTEX_EXPRESS,
            apiKey = "AQ.synthetic-vertex-key-tts-fallback",
            modelId = "gemini-2.5-flash",
            priority = 1,
            isFreeTier = false,
        )

        val result = client.synthesizeSpeech(
            key = aqKey,
            text = "Chào anh yêu!",
            styleInstruction = PersonaPromptBuilder.buildTtsStyleInstruction(com.adwewecat.nana.core.model.PersonaMode.NANA),
        )

        assertTrue("Expected TtsResult.Success after GA fallback, got $result", result is TtsResult.Success)
        assertArrayEquals(pcmBytes, (result as TtsResult.Success).audio.pcmBytes)

        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertTrue(req1.path.orEmpty().contains("gemini-2.5-flash-preview-tts:generateContent"))
        assertTrue(req2.path.orEmpty().contains("gemini-2.5-flash-tts:generateContent"))
    }

    @Test
    fun geminiVertexExpressRetriesV1Beta1On404ForNewGemini4And38Models() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"Model not in v1"}}"""))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"Chào anh yêu từ Gemini 4 Neon!"}]}}]}"""),
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = GeminiClient(httpClient = OkHttpClient(), vertexBaseUrl = baseUrl, aiStudioBaseUrl = baseUrl)
        val aqKey = ApiKeyEntry(
            id = "aq-4",
            label = "Gemini 4 Neon",
            provider = ProviderType.GEMINI_VERTEX_EXPRESS,
            apiKey = "AQ.synthetic-vertex-key-gemini4",
            modelId = "gemini-4-neon",
            priority = 1,
            isFreeTier = false,
        )

        val result = client.generate(
            key = aqKey,
            request = LlmRequest(systemPrompt = "Na Na", history = emptyList(), userText = "Xin chào", enableTools = false),
        )

        assertTrue("Expected Success after v1beta1 fallback, got $result", result is LlmResult.Success)
        assertEquals("Chào anh yêu từ Gemini 4 Neon!", (result as LlmResult.Success).replyText)

        val req1 = server.takeRequest()
        val req2 = server.takeRequest()
        assertEquals("/v1/publishers/google/models/gemini-4-neon:generateContent", req1.path)
        assertEquals("/v1beta1/publishers/google/models/gemini-4-neon:generateContent", req2.path)
    }

    @Test
    fun geminiAqKeyFallsBackToAiStudioOnVertex403DisabledAndCachesRoute() = runTest {
        // 1st call: Vertex returns 403 Agent Platform API disabled -> retries AI Studio v1beta -> succeeds.
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody(
                    """{"error":{"code":403,"message":"Agent Platform API has not been used in project 12345 before or it is disabled.","status":"PERMISSION_DENIED"}}""",
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"internal reasoning","thought":true},{"text":"Chào anh yêu!"}]}}]}""",
                ),
        )
        // 2nd call with same AQ. key must go straight to AI Studio without another Vertex 403 hop.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"text":"Em nghe nè anh!"}]}}]}"""),
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = GeminiClient(httpClient = OkHttpClient(), vertexBaseUrl = baseUrl, aiStudioBaseUrl = baseUrl)
        val aqKey = ApiKeyEntry(
            id = "aq-studio-1",
            label = "AI Studio AQ Key",
            provider = ProviderType.GEMINI_VERTEX_EXPRESS,
            apiKey = "AQ.synthetic-aistudio-key-403-fallback",
            modelId = "gemini-3.5-flash-lite",
            priority = 1,
            isFreeTier = false,
        )

        val first = client.generate(
            key = aqKey,
            request = LlmRequest(systemPrompt = "Na Na", history = emptyList(), userText = "Chào em", enableTools = false),
        )
        assertTrue("Expected Success after AI Studio fallback, got $first", first is LlmResult.Success)
        // Thought part must be excluded from spoken replyText.
        assertEquals("Chào anh yêu!", (first as LlmResult.Success).replyText)

        val second = client.generate(
            key = aqKey,
            request = LlmRequest(systemPrompt = "Na Na", history = emptyList(), userText = "Alo", enableTools = false),
        )
        assertTrue(second is LlmResult.Success)
        assertEquals("Em nghe nè anh!", (second as LlmResult.Success).replyText)

        assertEquals(3, server.requestCount)
        assertEquals("/v1/publishers/google/models/gemini-3.5-flash-lite:generateContent", server.takeRequest().path)
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", server.takeRequest().path)
        assertEquals("/v1beta/models/gemini-3.5-flash-lite:generateContent", server.takeRequest().path)
    }

    @Test
    fun geminiTtsExtractsRawPcmAndSampleRateFromWavPayloadAndFallsBackOnVertex403() = runTest {
        val rawPcm = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x0A, 0x0B)
        val wavBytes = buildTestWav(rawPcm, sampleRateHz = 24_000)
        val b64Wav = Base64.getEncoder().encodeToString(wavBytes)

        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":{"code":403,"message":"Agent Platform API has not been used in project 999"}}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/wav","data":"$b64Wav"}}]}}]}""",
                ),
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = GeminiClient(httpClient = OkHttpClient(), vertexBaseUrl = baseUrl, aiStudioBaseUrl = baseUrl)
        val aqKey = ApiKeyEntry(
            id = "aq-tts-wav",
            label = "Gemini 3.8 TTS",
            provider = ProviderType.GEMINI_VERTEX_EXPRESS,
            apiKey = "AQ.synthetic-aistudio-tts-wav",
            modelId = "gemini-3.5-flash-lite",
            priority = 1,
            isFreeTier = false,
        )

        val result = client.synthesizeSpeech(
            key = aqKey,
            text = "Chào anh nha!",
            styleInstruction = "Dễ thương",
            ttsModelId = "gemini-3.8-flash-tts",
        )

        assertTrue("Expected TtsResult.Success, got $result", result is TtsResult.Success)
        val audio = (result as TtsResult.Success).audio
        assertArrayEquals("RIFF header should be stripped to raw PCM", rawPcm, audio.pcmBytes)
        assertEquals(24_000, audio.sampleRateHz)

        assertEquals("/v1/publishers/google/models/gemini-3.8-flash-tts:generateContent", server.takeRequest().path)
        val aiStudioReq = server.takeRequest()
        assertEquals("/v1beta/models/gemini-3.8-flash-tts:generateContent", aiStudioReq.path)
        val sentBody = aiStudioReq.body.readUtf8()
        assertTrue("Expected spoken text in TTS payload", sentBody.contains("Chào anh nha!"))
        assertFalse("Style instruction must NOT be spoken verbatim in TTS payload", sentBody.contains("Dễ thương"))
    }

    @Test
    fun geminiConcatenatesSplitTextPartsNaturallyWithoutBrokenPunctuation() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"Chào anh"},{"text":", em là Na Na nè!"}]}}]}""",
                ),
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val client = GeminiClient(httpClient = OkHttpClient(), vertexBaseUrl = baseUrl, aiStudioBaseUrl = baseUrl)
        val key = ApiKeyEntry(
            id = "gem-split",
            label = "Gemini 3.5",
            provider = ProviderType.GEMINI_AI_STUDIO,
            apiKey = "AQ.synthetic-split-parts",
            modelId = "gemini-3.5-flash-lite",
            priority = 1,
            isFreeTier = false,
        )

        val res = client.generate(key, LlmRequest("Na Na", emptyList(), "Chào em", enableTools = false))
        assertTrue(res is LlmResult.Success)
        assertEquals("Chào anh, em là Na Na nè!", (res as LlmResult.Success).replyText)
    }

    @Test
    fun ttsRouterUsesGemini38TtsForGemini3ModelsAndRespectsDedicatedTtsModel() = runTest {
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val wav = buildTestWav(pcm, sampleRateHz = 24_000)
        val b64 = Base64.getEncoder().encodeToString(wav)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/wav","data":"$b64"}}]}}]}"""),
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val http = OkHttpClient()
        val geminiClient = GeminiClient(http, vertexBaseUrl = baseUrl, aiStudioBaseUrl = baseUrl)
        val keyPool = com.adwewecat.nana.core.quota.KeyPoolManager(
            initialKeys = listOf(
                ApiKeyEntry(
                    id = "gem-35",
                    label = "Gemini 3.5",
                    provider = ProviderType.GEMINI_AI_STUDIO,
                    apiKey = "AQ.synthetic-studio-35",
                    modelId = "gemini-3.5-flash-lite",
                    priority = 1,
                    isFreeTier = false,
                ),
            ),
        )
        val router = TtsRouter(
            keyPool = keyPool,
            geminiClient = geminiClient,
            openAiClient = OpenAiClient(http, baseUrl = baseUrl),
            localFallback = object : LocalFallbackTts {
                override suspend fun speak(text: String, persona: com.adwewecat.nana.core.model.PersonaMode) = true
            },
        )

        val spoken = router.synthesizeOrSpeak("Xin chào anh!", com.adwewecat.nana.core.model.PersonaMode.NANA)
        assertTrue(spoken is SpokenOutput.CloudPcm)
        assertArrayEquals(pcm, (spoken as SpokenOutput.CloudPcm).audio.pcmBytes)
        assertEquals("/v1beta/models/gemini-3.8-flash-tts:generateContent", server.takeRequest().path)
    }

    private fun buildTestWav(pcm: ByteArray, sampleRateHz: Int): ByteArray {
        val byteRate = sampleRateHz * 2
        val totalDataLen = pcm.size + 36
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        writeLeInt(header, 4, totalDataLen)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        writeLeInt(header, 16, 16)
        writeLeShort(header, 20, 1)
        writeLeShort(header, 22, 1)
        writeLeInt(header, 24, sampleRateHz)
        writeLeInt(header, 28, byteRate)
        writeLeShort(header, 32, 2)
        writeLeShort(header, 34, 16)
        "data".toByteArray().copyInto(header, 36)
        writeLeInt(header, 40, pcm.size)
        return header + pcm
    }

    private fun writeLeInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeLeShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
