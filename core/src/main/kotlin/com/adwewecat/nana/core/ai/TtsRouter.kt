package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.ProviderType
import com.adwewecat.nana.core.quota.KeyPoolManager

/** Platform local text-to-speech fallback invoked when cloud neural TTS cannot play. */
interface LocalFallbackTts {
    suspend fun speak(text: String, persona: PersonaMode): Boolean
}

/** Result of attempting cloud TTS synthesis with local Android TTS fallback. */
sealed class SpokenOutput {
    data class CloudPcm(val audio: TtsAudio) : SpokenOutput()

    data object SpokenByLocalFallback : SpokenOutput()

    data object Failed : SpokenOutput()
}

/**
 * Decoupled TTS router that cascades through TTS-capable keys and falls back to local speech.
 * TTS failures never mark chat keys as quota-exhausted or invalid.
 */
class TtsRouter(
    private val keyPool: KeyPoolManager,
    private val geminiClient: GeminiClient,
    private val openAiClient: OpenAiClient,
    private val localFallback: LocalFallbackTts,
) {

    /**
     * Synthesizes [text] using cloud neural TTS or [localFallback] according to [persona].
     * In [PersonaMode.LITTLE_SISTER], paid chat keys are skipped in favor of free/dedicated TTS or local speech.
     */
    suspend fun synthesizeOrSpeak(
        text: String,
        persona: PersonaMode,
        preferredGeminiVoice: String = "Aoede",
    ): SpokenOutput {
        if (text.isBlank()) return SpokenOutput.Failed

        val styleInstruction = PersonaPromptBuilder.buildTtsStyleInstruction(persona)
        val candidateKeys = keyPool.nextHealthyTtsKeys().filter { key ->
            key.provider != ProviderType.CLAUDE &&
                (persona != PersonaMode.LITTLE_SISTER || key.isTtsOnly || key.isFreeTier)
        }

        for (key in candidateKeys) {
            val result = when (key.provider) {
                ProviderType.GEMINI_VERTEX_EXPRESS,
                ProviderType.GEMINI_AI_STUDIO,
                ProviderType.FREE_GEMINI,
                -> geminiClient.synthesizeSpeech(
                    key = key,
                    text = text,
                    styleInstruction = styleInstruction,
                    voiceName = preferredGeminiVoice,
                )

                ProviderType.OPENAI,
                ProviderType.FREE_OPENAI_COMPAT,
                -> openAiClient.synthesizeSpeech(
                    key = key,
                    text = text,
                )

                ProviderType.CLAUDE -> continue
            }

            when (result) {
                is TtsResult.Success -> return SpokenOutput.CloudPcm(result.audio)
                // Decoupled health: only disable TTS capability on this key; never alter chat quota/status.
                is TtsResult.Unsupported -> keyPool.markTtsUnsupported(key.id)
                is TtsResult.QuotaExceeded,
                is TtsResult.Error,
                -> Unit
            }
        }

        return if (localFallback.speak(text, persona)) {
            SpokenOutput.SpokenByLocalFallback
        } else {
            SpokenOutput.Failed
        }
    }
}
