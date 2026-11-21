package com.adwewecat.nana.core.voice

import kotlin.math.sqrt

/** State transitions emitted by [EnergyVad] for each processed PCM frame. */
enum class VadEvent {
    IDLE,
    SPEECH_STARTED,
    SPEECH_ACTIVE,
    UTTERANCE_COMPLETE,
}

/**
 * Simple RMS-energy Voice Activity Detector for 16-bit PCM frames with trailing-silence
 * end-of-utterance detection and a hard maximum utterance duration cap.
 */
class EnergyVad(
    val speechRmsThreshold: Double = 650.0,
    val silenceDurationMs: Long = 1_400L,
    val maxUtteranceMs: Long = 15_000L,
) {
    private var inSpeech = false
    private var utteranceElapsedMs = 0L
    private var silenceElapsedMs = 0L

    init {
        require(speechRmsThreshold >= 0.0) { "speechRmsThreshold must be non-negative" }
        require(silenceDurationMs > 0L) { "silenceDurationMs must be positive" }
        require(maxUtteranceMs > 0L) { "maxUtteranceMs must be positive" }
    }

    /** Computes root-mean-square amplitude over the first [length] samples of [pcm16]. */
    fun computeRms(pcm16: ShortArray, length: Int = pcm16.size): Double {
        val count = length.coerceIn(0, pcm16.size)
        if (count == 0) {
            return 0.0
        }
        var sumSquares = 0.0
        for (i in 0 until count) {
            val sample = pcm16[i].toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / count)
    }

    /** Advances VAD state for one audio frame of duration [frameDurationMs] milliseconds. */
    fun feedFrame(pcm16: ShortArray, frameDurationMs: Long): VadEvent {
        val dt = frameDurationMs.coerceAtLeast(0L)
        val isSpeech = computeRms(pcm16) >= speechRmsThreshold

        if (!inSpeech) {
            if (!isSpeech) {
                return VadEvent.IDLE
            }
            inSpeech = true
            utteranceElapsedMs = dt
            silenceElapsedMs = 0L
            return VadEvent.SPEECH_STARTED
        }

        utteranceElapsedMs += dt
        if (isSpeech) {
            silenceElapsedMs = 0L
        } else {
            silenceElapsedMs += dt
        }

        if (utteranceElapsedMs >= maxUtteranceMs || silenceElapsedMs >= silenceDurationMs) {
            reset()
            return VadEvent.UTTERANCE_COMPLETE
        }
        return VadEvent.SPEECH_ACTIVE
    }

    /** Resets utterance and silence timers back to idle state. */
    fun reset() {
        inSpeech = false
        utteranceElapsedMs = 0L
        silenceElapsedMs = 0L
    }
}
