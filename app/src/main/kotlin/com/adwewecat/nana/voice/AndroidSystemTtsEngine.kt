package com.adwewecat.nana.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.adwewecat.nana.core.ai.LocalFallbackTts
import com.adwewecat.nana.core.model.PersonaMode
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local Android [TextToSpeech] fallback configured for Vietnamese (`vi-VN`) with
 * brighter female persona pitch settings (`1.28f` for Na Na, `1.34f` for Little Sister).
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
    private var initDeferred: CompletableDeferred<Boolean>? = null

    override suspend fun speak(text: String, persona: PersonaMode): Boolean {
        if (text.isBlank()) {
            return false
        }

        return speakMutex.withLock {
            val engine = ensureInitialized() ?: return@withLock false
            val pitch = when (persona) {
                PersonaMode.NANA -> NANA_PITCH
                PersonaMode.LITTLE_SISTER -> LITTLE_SISTER_PITCH
            }
            engine.setPitch(pitch)
            engine.setSpeechRate(SPEECH_RATE)

            val utteranceId = UUID.randomUUID().toString()
            val completion = CompletableDeferred<Boolean>()
            pendingUtterances[utteranceId] = completion

            val enqueueStatus = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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

        val deferred = CompletableDeferred<Boolean>()
        initDeferred = deferred
        val created = TextToSpeech(appContext) { status ->
            val ok = status == TextToSpeech.SUCCESS
            isInitialized = ok
            deferred.complete(ok)
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

        val viLocale = Locale.forLanguageTag("vi-VN")
        val langResult = created.setLanguage(viLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            created.setLanguage(Locale("vi"))
        }
        return created
    }

    companion object {
        const val NANA_PITCH = 1.28f
        const val LITTLE_SISTER_PITCH = 1.34f
        const val SPEECH_RATE = 1.08f
        private const val INIT_TIMEOUT_MS = 5_000L
        private const val MAX_SPEAK_TIMEOUT_MS = 30_000L
    }
}
