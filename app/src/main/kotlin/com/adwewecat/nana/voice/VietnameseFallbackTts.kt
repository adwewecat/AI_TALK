package com.adwewecat.nana.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.adwewecat.nana.core.ai.LocalFallbackTts
import com.adwewecat.nana.core.ai.TtsTextSanitizer
import com.adwewecat.nana.core.ai.VietnameseWebTtsClient
import com.adwewecat.nana.core.model.PersonaMode
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Vietnamese-first fallback TTS built for OriginOS 6 China ROM:
 * 1. Fetches authentic native Vietnamese female MP3 chunks via [VietnameseWebTtsClient] (zero API
 *    key and zero `com.google.android.tts` required) and plays them via [MediaPlayer] with a
 *    brighter cute female pitch (`1.16f` Na Na / `1.22f` Little Sister).
 * 2. When offline or unreachable, delegates to [systemTts] ([AndroidSystemTtsEngine]), which only
 *    speaks when a genuine `vi-VN` voice engine is installed (never Latin/Chinese gibberish).
 */
class VietnameseFallbackTts(
    context: Context,
    private val webTtsClient: VietnameseWebTtsClient,
    private val systemTts: LocalFallbackTts,
    private val mp3ChunkPlayer: (suspend (mp3Bytes: ByteArray, pitch: Float, speed: Float) -> Boolean)? = null,
) : LocalFallbackTts {

    private val appContext = context.applicationContext
    private val playbackMutex = Mutex()

    override suspend fun speak(text: String, persona: PersonaMode): Boolean {
        val cleaned = TtsTextSanitizer.sanitizeForSpeech(text)
        if (cleaned.isBlank()) return false

        val mp3Chunks = try {
            webTtsClient.synthesizeMp3Chunks(cleaned)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }

        if (mp3Chunks.isNotEmpty()) {
            val pitch = when (persona) {
                PersonaMode.NANA -> NANA_WEB_PITCH
                PersonaMode.LITTLE_SISTER -> LITTLE_SISTER_WEB_PITCH
            }
            val speed = when (persona) {
                PersonaMode.NANA -> NANA_WEB_SPEED
                PersonaMode.LITTLE_SISTER -> LITTLE_SISTER_WEB_SPEED
            }
            val playedAll = playbackMutex.withLock {
                var allSucceeded = true
                for (chunk in mp3Chunks) {
                    val ok = mp3ChunkPlayer?.invoke(chunk, pitch, speed)
                        ?: playMp3ViaMediaPlayer(chunk, pitch, speed)
                    if (!ok) {
                        allSucceeded = false
                        break
                    }
                }
                allSucceeded
            }
            if (playedAll) {
                return true
            }
        }

        return systemTts.speak(cleaned, persona)
    }

    /** Releases underlying system TTS resources when owned by a service lifecycle. */
    fun shutdown() {
        (systemTts as? AndroidSystemTtsEngine)?.shutdown()
    }

    private suspend fun playMp3ViaMediaPlayer(
        mp3Bytes: ByteArray,
        pitch: Float,
        speed: Float,
    ): Boolean = withContext(Dispatchers.IO) {
        val tempFile = File(appContext.cacheDir, "nana_tts_${UUID.randomUUID()}.mp3")
        var player: MediaPlayer? = null
        try {
            tempFile.writeBytes(mp3Bytes)
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    setDataSource(tempFile.absolutePath)
                    setOnCompletionListener {
                        if (cont.isActive) cont.resume(true)
                    }
                    setOnErrorListener { _, _, _ ->
                        if (cont.isActive) cont.resume(false)
                        true
                    }
                    prepare()
                    runCatching {
                        playbackParams = PlaybackParams().setPitch(pitch).setSpeed(speed)
                    }
                    start()
                }
                player = mp
                cont.invokeOnCancellation {
                    runCatching {
                        if (mp.isPlaying) mp.stop()
                    }
                    runCatching { mp.release() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { player?.release() }
            runCatching { tempFile.delete() }
        }
    }

    companion object {
        const val NANA_WEB_PITCH = 1.16f
        const val NANA_WEB_SPEED = 1.04f
        const val LITTLE_SISTER_WEB_PITCH = 1.22f
        const val LITTLE_SISTER_WEB_SPEED = 1.06f
    }
}
