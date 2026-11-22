package com.adwewecat.nana.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.adwewecat.nana.core.ai.LocalFallbackTts
import com.adwewecat.nana.core.ai.VietnameseWebTtsClient
import com.adwewecat.nana.core.model.PersonaMode
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
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
class VietnameseFallbackTtsTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test(timeout = 15_000)
    fun systemTtsLanguageValidatorRejectsMissingOrUnsupportedVietnamese() {
        assertFalse(AndroidSystemTtsEngine.isLanguageSupportedResult(TextToSpeech.LANG_NOT_SUPPORTED))
        assertFalse(AndroidSystemTtsEngine.isLanguageSupportedResult(TextToSpeech.LANG_MISSING_DATA))
        assertTrue(AndroidSystemTtsEngine.isLanguageSupportedResult(TextToSpeech.LANG_AVAILABLE))
        assertTrue(AndroidSystemTtsEngine.isLanguageSupportedResult(TextToSpeech.LANG_COUNTRY_AVAILABLE))
        assertTrue(AndroidSystemTtsEngine.isLanguageSupportedResult(TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE))
    }

    @Test(timeout = 15_000)
    fun engineSelectionPrefersGoogleTtsOverVivoChineseEngine() {
        val preferred = AndroidSystemTtsEngine.choosePreferredEnginePackage(
            installedEnginePackages = listOf("com.vivo.tts", "com.iflytek.speechsuite", "com.google.android.tts"),
        )
        assertEquals("com.google.android.tts", preferred)

        val fallback = AndroidSystemTtsEngine.choosePreferredEnginePackage(
            installedEnginePackages = listOf("com.vivo.tts"),
        )
        assertEquals(null, fallback)
    }

    @Test(timeout = 15_000)
    fun vietnameseFallbackTtsPlaysWebMp3WithCutePitchWithoutTouchingBrokenSystemTts() = runBlocking {
        val fakeMp3 = byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x01, 0x02)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "audio/mpeg")
                .setBody(Buffer().write(fakeMp3)),
        )

        val playedChunks = mutableListOf<Triple<Int, Float, Float>>()
        var systemFallbackCalls = 0

        val webClient = VietnameseWebTtsClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        val fallback = VietnameseFallbackTts(
            context = context,
            webTtsClient = webClient,
            systemTts = object : LocalFallbackTts {
                override suspend fun speak(text: String, persona: PersonaMode): Boolean {
                    systemFallbackCalls++
                    return false
                }
            },
            mp3ChunkPlayer = { bytes, pitch, speed ->
                playedChunks += Triple(bytes.size, pitch, speed)
                true
            },
        )

        val spoken = fallback.speak("**Em chào anh yêu ơi!**", PersonaMode.NANA)
        assertTrue(spoken)
        assertEquals(1, playedChunks.size)
        assertEquals(fakeMp3.size, playedChunks.first().first)
        assertEquals(VietnameseFallbackTts.NANA_WEB_PITCH, playedChunks.first().second, 0.001f)
        assertEquals(0, systemFallbackCalls)
    }

    @Test(timeout = 15_000)
    fun vietnameseFallbackTtsFallsBackToSystemTtsOnlyWhenOfflineOrWebFails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val systemCalls = mutableListOf<Pair<String, PersonaMode>>()
        val webClient = VietnameseWebTtsClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
        val fallback = VietnameseFallbackTts(
            context = context,
            webTtsClient = webClient,
            systemTts = object : LocalFallbackTts {
                override suspend fun speak(text: String, persona: PersonaMode): Boolean {
                    systemCalls += text to persona
                    return true
                }
            },
            mp3ChunkPlayer = { _, _, _ -> false },
        )

        val spoken = fallback.speak("Dạ em gái Na Na đây anh ơi!", PersonaMode.LITTLE_SISTER)
        assertTrue(spoken)
        assertEquals(1, systemCalls.size)
        assertEquals("Dạ em gái Na Na đây anh ơi!", systemCalls.first().first)
        assertEquals(PersonaMode.LITTLE_SISTER, systemCalls.first().second)
    }

    @Test(timeout = 15_000)
    fun builtInMicLevelScalesSensitivelyForNormalVoice() {
        assertEquals(0f, BuiltInMicAudioInput.computeNormalizedMicLevel(0.0), 0.001f)
        assertTrue(
            "Normal speech RMS ~400 should register clearly on mic meter",
            BuiltInMicAudioInput.computeNormalizedMicLevel(400.0) >= 0.19f,
        )
        assertEquals(1f, BuiltInMicAudioInput.computeNormalizedMicLevel(5_000.0), 0.001f)
    }
}
