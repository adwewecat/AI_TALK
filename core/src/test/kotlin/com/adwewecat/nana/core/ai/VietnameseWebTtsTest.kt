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
}
