package com.adwewecat.nana.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Contract for streaming synthesized PCM playback and caching offline tired-state prompts.
 */
interface AudioPlaybackEngine {
    suspend fun playPcm(pcmBytes: ByteArray, sampleRateHz: Int)

    fun getCachedTiredPrompt(): ByteArray?

    fun cacheTiredPrompt(pcmBytes: ByteArray)
}

/**
 * [AudioTrack] PCM player (defaulting to 24kHz 16-bit mono) that also caches the local
 * tired consent prompt (`tired_consent_prompt.pcm`) in `filesDir`.
 */
class AndroidAudioPlayer(
    context: Context,
) : AudioPlaybackEngine {

    private val appContext = context.applicationContext
    private val cachedPromptFile: File
        get() = File(appContext.filesDir, TIRED_PROMPT_CACHE_FILE)

    override suspend fun playPcm(pcmBytes: ByteArray, sampleRateHz: Int) {
        if (pcmBytes.isEmpty() || sampleRateHz <= 0) {
            return
        }

        withContext(Dispatchers.IO) {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val safeMinBuffer = if (minBuffer > 0) minBuffer else DEFAULT_MIN_BUFFER_BYTES
            val trackBufferSize = maxOf(safeMinBuffer, pcmBytes.size.coerceAtMost(MAX_STREAM_BUFFER_BYTES))

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(trackBufferSize)
                .build()

            if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                audioTrack.release()
                return@withContext
            }

            try {
                audioTrack.play()
                var offset = 0
                while (offset < pcmBytes.size && currentCoroutineContext().isActive) {
                    val chunkSize = minOf(WRITE_CHUNK_BYTES, pcmBytes.size - offset)
                    val written = audioTrack.write(pcmBytes, offset, chunkSize, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) {
                        break
                    }
                    offset += written
                }

                // Wait for the AudioTrack hardware buffer to finish draining before stop()/release(),
                // otherwise short replies (<= buffer size) get cut off before a single syllable plays.
                val totalFrames = offset / BYTES_PER_MONO_16BIT_FRAME
                if (totalFrames > 0 && currentCoroutineContext().isActive) {
                    val expectedDurationMs = (totalFrames * 1_000L) / sampleRateHz
                    val deadlineMs = System.currentTimeMillis() + expectedDurationMs + DRAIN_GRACE_MS
                    while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadlineMs) {
                        val playedFrames = runCatching { audioTrack.playbackHeadPosition }.getOrDefault(totalFrames)
                        if (playedFrames >= totalFrames) {
                            break
                        }
                        delay(DRAIN_POLL_INTERVAL_MS)
                    }
                }
            } finally {
                runCatching {
                    if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack.stop()
                    }
                }
                runCatching { audioTrack.release() }
            }
        }
    }

    override fun getCachedTiredPrompt(): ByteArray? {
        val file = cachedPromptFile
        if (!file.exists() || file.length() <= 0L) {
            return null
        }
        return try {
            file.readBytes().takeIf { it.isNotEmpty() }
        } catch (_: IOException) {
            null
        }
    }

    override fun cacheTiredPrompt(pcmBytes: ByteArray) {
        if (pcmBytes.isEmpty()) {
            return
        }
        try {
            val target = cachedPromptFile
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "$TIRED_PROMPT_CACHE_FILE.tmp")
            temp.writeBytes(pcmBytes)
            if (!temp.renameTo(target)) {
                target.writeBytes(pcmBytes)
                temp.delete()
            }
        } catch (_: IOException) {
            // Cache write failures should not crash voice playback.
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ = 24_000
        const val TIRED_PROMPT_CACHE_FILE = "tired_consent_prompt.pcm"
        private const val DEFAULT_MIN_BUFFER_BYTES = 4_096
        private const val MAX_STREAM_BUFFER_BYTES = 16_384
        private const val WRITE_CHUNK_BYTES = 4_096
        private const val BYTES_PER_MONO_16BIT_FRAME = 2
        private const val DRAIN_POLL_INTERVAL_MS = 35L
        private const val DRAIN_GRACE_MS = 450L
    }
}
