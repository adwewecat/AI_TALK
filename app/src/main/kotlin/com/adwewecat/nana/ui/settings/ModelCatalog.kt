package com.adwewecat.nana.ui.settings

import com.adwewecat.nana.core.model.ProviderType

/** Quick-pick model option shown in the API key editor dialog. */
data class ModelOption(
    val modelId: String,
    val label: String,
)

/**
 * Curated official model IDs for Gemini (4.x Neon/Carbon/Argon/Hydrogen, 3.8, 3.6, 3.1 Pro, 2.5),
 * Claude, and ChatGPT, while still allowing arbitrary custom model IDs in Settings.
 */
object ModelCatalog {

    private val GEMINI_PRO_MODELS = listOf(
        ModelOption("gemini-3.5-flash", "3.5 Flash · Khuyên dùng"),
        ModelOption("gemini-3.5-flash-lite", "3.5 Flash Lite · Siêu nhanh"),
        ModelOption("gemini-3.8-flash", "3.8 Flash"),
        ModelOption("gemini-3.6-flash", "3.6 Flash"),
        ModelOption("gemini-3-flash-preview", "3 Flash · Xem trước"),
        ModelOption("gemini-3.7-flash", "3.7 Flash"),
        ModelOption("gemini-3.1-flash-lite", "3.1 Flash Lite"),
        ModelOption("gemini-4-neon", "4 Neon"),
        ModelOption("gemini-4-carbon", "4 Carbon"),
        ModelOption("gemini-4-argon", "4 Argon"),
        ModelOption("gemini-4-hydrogen", "4 Hydrogen"),
        ModelOption("gemini-3.1-pro-preview", "3.1 Pro"),
        ModelOption("gemini-2.5-flash", "2.5 Flash · Cũ"),
    )

    private val GEMINI_FREE_MODELS = listOf(
        ModelOption("gemini-3.5-flash-lite", "3.5 Flash Lite · Siêu nhanh"),
        ModelOption("gemini-3-flash-preview", "3 Flash"),
        ModelOption("gemini-3.6-flash", "3.6 Flash"),
        ModelOption("gemini-3.8-flash", "3.8 Flash"),
        ModelOption("gemini-2.5-flash-lite", "2.5 Flash Lite"),
    )

    private val GEMINI_TTS_MODELS = listOf(
        ModelOption("gemini-3.8-flash-tts", "3.8 Flash TTS"),
        ModelOption("gemini-3.8-flash-lite-tts", "3.8 Flash Lite TTS"),
        ModelOption("gemini-2.5-flash-preview-tts", "2.5 Preview TTS"),
        ModelOption("gemini-2.5-flash-tts", "2.5 Flash TTS"),
    )

    private val CLAUDE_MODELS = listOf(
        ModelOption("claude-sonnet-4-5", "Claude Sonnet 4.5"),
        ModelOption("claude-opus-4-1", "Claude Opus 4.1"),
        ModelOption("claude-3-7-sonnet-latest", "Claude 3.7 Sonnet"),
    )

    private val OPENAI_MODELS = listOf(
        ModelOption("gpt-4o-mini", "GPT-4o mini"),
        ModelOption("gpt-4o", "GPT-4o"),
        ModelOption("o4-mini", "o4-mini"),
    )

    private val OPENAI_TTS_MODELS = listOf(
        ModelOption("gpt-4o-mini-tts", "GPT-4o mini TTS"),
        ModelOption("tts-1", "TTS-1"),
    )

    fun defaultModelFor(provider: ProviderType, isTtsOnly: Boolean = false): String {
        if (isTtsOnly) {
            return when (provider) {
                ProviderType.OPENAI, ProviderType.FREE_OPENAI_COMPAT -> "gpt-4o-mini-tts"
                else -> "gemini-3.8-flash-tts"
            }
        }
        return when (provider) {
            ProviderType.GEMINI_VERTEX_EXPRESS,
            ProviderType.GEMINI_AI_STUDIO,
            -> "gemini-3.5-flash"

            ProviderType.FREE_GEMINI -> "gemini-3.5-flash-lite"
            ProviderType.CLAUDE -> "claude-sonnet-4-5"
            ProviderType.OPENAI,
            ProviderType.FREE_OPENAI_COMPAT,
            -> "gpt-4o-mini"
        }
    }

    fun suggestedModelsFor(provider: ProviderType, isTtsOnly: Boolean): List<ModelOption> {
        if (isTtsOnly) {
            return when (provider) {
                ProviderType.OPENAI, ProviderType.FREE_OPENAI_COMPAT -> OPENAI_TTS_MODELS
                ProviderType.CLAUDE -> emptyList()
                else -> GEMINI_TTS_MODELS
            }
        }
        return when (provider) {
            ProviderType.GEMINI_VERTEX_EXPRESS,
            ProviderType.GEMINI_AI_STUDIO,
            -> GEMINI_PRO_MODELS

            ProviderType.FREE_GEMINI -> GEMINI_FREE_MODELS
            ProviderType.CLAUDE -> CLAUDE_MODELS
            ProviderType.OPENAI,
            ProviderType.FREE_OPENAI_COMPAT,
            -> OPENAI_MODELS
        }
    }
}
