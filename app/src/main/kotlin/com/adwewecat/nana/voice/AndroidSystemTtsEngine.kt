package com.adwewecat.nana.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.adwewecat.nana.core.ai.LocalFallbackTts
import com.adwewecat.nana.core.ai.TtsTextSanitizer
import com.adwewecat.nana.core.model.PersonaMode
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Offline Android [TextToSpeech] fallback hardened for OriginOS 6 China ROM:
 * - Explicitly prefers `com.google.android.tts` over Vivo's default Chinese/English TTS engine.
 * - Verifies `vi-VN` / `vi` support before speaking and refuses to feed Vietnamese text into
 *   non-Vietnamese engines (preventing Latin letter-by-letter gibberish).
 * - Applies brighter female pitch settings (`1.24f` for Na Na, `1.30f` for Little Sister).
 */
class AndroidSystemTtsEngine(
    context: Context,
) : LocalFallbackTts {

    private val appContext = context.applicationContext
    private val speakMutex = Mutex()
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var isInitialized: Boolean = false

    @Volatile
    private var isVietnameseSupported: Boolean = false

    @Volatile
    private var initDeferred: CompletableDeferred<Boolean>? = null

    override suspend fun speak(text: String, persona: PersonaMode): Boolean {
        val cleaned = TtsTextSanitizer.sanitizeForSpeech(text)
        if (cleaned.isBlank()) {
            return false
        }

        return speakMutex.withLock {
            val engine = ensureInitialized() ?: return@withLock false
            // Never feed Vietnamese text to a Chinese/English OEM engine that reads Latin gibberish.
            if (!isVietnameseSupported) {
                return@withLock false
            }

            val pitch = when (persona) {
                PersonaMode.NANA -> NANA_PITCH
                PersonaMode.LITTLE_SISTER -> LITTLE_SISTER_PITCH
            }
            engine.setPitch(pitch)
            engine.setSpeechRate(SPEECH_RATE)

            val utteranceId = UUID.randomUUID().toString()
            val completion = CompletableDeferred<Boolean>()
            pendingUtterances[utteranceId] = completion

            val enqueueStatus = engine.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (enqueueStatus == TextToSpeech.ERROR) {
                pendingUtterances.remove(utteranceId)
                return@withLock false
            }

            try {
                withTimeoutOrNull(MAX_SPEAK_TIMEOUT_MS) {
                    completion.await()
                } ?: false
            } finally {
                pendingUtterances.remove(utteranceId)
            }
        }
    }

    /** Releases the underlying Android [TextToSpeech] instance and completes pending waiters. */
    fun shutdown() {
        pendingUtterances.values.forEach { it.complete(false) }
        pendingUtterances.clear()
        val current = tts
        tts = null
        isInitialized = false
        isVietnameseSupported = false
        initDeferred?.complete(false)
        initDeferred = null
        runCatching {
            current?.stop()
            current?.shutdown()
        }
    }

    private suspend fun ensureInitialized(): TextToSpeech? {
        tts?.let { existing ->
            if (isInitialized) {
                return existing
            }
        }

        val preferredEngine = discoverInstalledTtsEngines(appContext)
            .let(::choosePreferredEnginePackage)

        val deferred = CompletableDeferred<Boolean>()
        initDeferred = deferred
        val listener = TextToSpeech.OnInitListener { status ->
            val ok = status == TextToSpeech.SUCCESS
            isInitialized = ok
            deferred.complete(ok)
        }
        val created = if (preferredEngine != null) {
            TextToSpeech(appContext, listener, preferredEngine)
        } else {
            TextToSpeech(appContext, listener)
        }
        created.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId != null) {
                        pendingUtterances.remove(utteranceId)?.complete(true)
                    }
                }

                @Deprecated("Deprecated in Android API", ReplaceWith("onError(utteranceId, -1)"))
                override fun onError(utteranceId: String?) {
                    if (utteranceId != null) {
                        pendingUtterances.remove(utteranceId)?.complete(false)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    if (utteranceId != null) {
                        pendingUtterances.remove(utteranceId)?.complete(false)
                    }
                }
            },
        )
        tts = created

        val ready = withTimeoutOrNull(INIT_TIMEOUT_MS) {
            deferred.await()
        } ?: false
        if (!ready) {
            shutdown()
            return null
        }

        val viVnLocale = Locale.forLanguageTag("vi-VN")
        val viLocale = Locale.forLanguageTag("vi")
        val primaryResult = created.setLanguage(viVnLocale)
        val supported = if (isLanguageSupportedResult(primaryResult)) {
            true
        } else {
            isLanguageSupportedResult(created.setLanguage(viLocale))
        }
        isVietnameseSupported = supported

        if (supported) {
            selectBestVietnameseVoice(created)?.let { voice ->
                runCatching { created.voice = voice }
            }
        }
        return created
    }

    private fun selectBestVietnameseVoice(engine: TextToSpeech): Voice? {
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val viVoices = voices.filter { voice ->
            voice.locale?.language.equals("vi", ignoreCase = true)
        }
        if (viVoices.isEmpty()) return null
        return viVoices.minWithOrNull(
            compareBy<Voice>(
                { it.isNetworkConnectionRequired },
                { !(it.name.contains("female", ignoreCase = true) || it.name.contains("vie", ignoreCase = true)) },
                { -it.quality },
            ),
        )
    }

    private fun discoverInstalledTtsEngines(context: Context): List<String> {
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        return runCatching {
            context.packageManager.queryIntentServices(intent, 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .distinct()
        }.getOrDefault(emptyList())
    }

    companion object {
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
        const val NANA_PITCH = 1.24f
        const val LITTLE_SISTER_PITCH = 1.30f
        const val SPEECH_RATE = 1.06f
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val MAX_SPEAK_TIMEOUT_MS = 30_000L

        /** Returns true only when Android TTS confirms the language pack is installed and usable. */
        fun isLanguageSupportedResult(resultCode: Int): Boolean =
            resultCode >= TextToSpeech.LANG_AVAILABLE

        /** Prefers Google Speech Services (`com.google.android.tts`) over Chinese OEM TTS engines. */
        fun choosePreferredEnginePackage(installedEnginePackages: List<String>): String? =
            installedEnginePackages.firstOrNull { it.equals(GOOGLE_TTS_PACKAGE, ignoreCase = true) }
    }
}
