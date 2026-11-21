package com.adwewecat.nana.core.ai

import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.DeviceCommand
import com.adwewecat.nana.core.model.KeyStatus
import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.ProviderType
import com.adwewecat.nana.core.model.QuotaState
import com.adwewecat.nana.core.quota.KeyPoolManager
import com.adwewecat.nana.core.quota.NaNaQuotaStateMachine

/** Normalized chat response produced by [AiChatRouter]. */
data class RoutedChatReply(
    val replyText: String,
    val persona: PersonaMode,
    val toolCalls: List<DeviceCommand>,
    val quotaExhaustedNotice: Boolean = false,
    val usedKeyId: String? = null,
)

/**
 * Routes conversational turns across configured paid and free-tier LLM keys,
 * handling silent failover, Whisper audio transcription fallback, and quota state transitions.
 */
class AiChatRouter(
    private val keyPool: KeyPoolManager,
    private val stateMachine: NaNaQuotaStateMachine,
    private val geminiClient: GeminiClient,
    private val openAiClient: OpenAiClient,
    private val claudeClient: ClaudeClient,
) {

    /**
     * Executes one user turn using the active persona and key tier dictated by [stateMachine],
     * rotating across healthy keys on quota exhaustion or invalid credentials.
     */
    suspend fun sendTurn(
        history: List<ChatMessage>,
        memories: List<MemoryFact>,
        userText: String?,
        userAudio: AudioAttachment? = null,
        enableTools: Boolean = true,
        transitionOnExhausted: Boolean = true,
    ): RoutedChatReply {
        val isLittleSister = stateMachine.state == QuotaState.ACTIVE_LITTLE_SISTER
        val persona = if (isLittleSister) PersonaMode.LITTLE_SISTER else PersonaMode.NANA
        val systemPrompt = PersonaPromptBuilder.buildSystemPrompt(persona, memories)

        val attemptedKeyIds = mutableSetOf<String>()
        var cachedTranscript: String? = null
        var transcriptAttempted = false

        while (true) {
            val key = nextUntriedChatKey(isFreeTier = isLittleSister, attemptedKeyIds = attemptedKeyIds)
                ?: break
            attemptedKeyIds.add(key.id)

            val request = buildProviderRequest(
                key = key,
                systemPrompt = systemPrompt,
                history = history,
                userText = userText,
                userAudio = userAudio,
                enableTools = enableTools,
                resolveTranscript = {
                    if (!transcriptAttempted && userAudio != null) {
                        transcriptAttempted = true
                        cachedTranscript = transcribeWithAvailableOpenAiKey(key, userAudio.bytes)
                    }
                    cachedTranscript
                },
            ) ?: continue

            when (val result = dispatchGenerate(key, request)) {
                is LlmResult.Success -> {
                    return RoutedChatReply(
                        replyText = result.replyText,
                        persona = persona,
                        toolCalls = result.toolCalls,
                        quotaExhaustedNotice = false,
                        usedKeyId = key.id,
                    )
                }

                is LlmResult.QuotaExceeded -> keyPool.markChatQuotaExhausted(key.id)
                is LlmResult.InvalidKey -> keyPool.markChatInvalid(key.id)
                is LlmResult.TransientError -> Unit
            }
        }

        if (isLittleSister) {
            return RoutedChatReply(
                replyText = LITTLE_SISTER_OFFLINE_REPLY,
                persona = PersonaMode.LITTLE_SISTER,
                toolCalls = emptyList(),
                quotaExhaustedNotice = false,
                usedKeyId = null,
            )
        }

        if (!transitionOnExhausted) {
            return RoutedChatReply(
                replyText = "",
                persona = PersonaMode.NANA,
                toolCalls = emptyList(),
                quotaExhaustedNotice = true,
                usedKeyId = null,
            )
        }

        val notice = stateMachine.onAllPaidChatKeysExhausted()
        return RoutedChatReply(
            replyText = notice,
            persona = PersonaMode.NANA,
            toolCalls = emptyList(),
            quotaExhaustedNotice = true,
            usedKeyId = null,
        )
    }

    private fun nextUntriedChatKey(isFreeTier: Boolean, attemptedKeyIds: Set<String>): ApiKeyEntry? {
        val primary = if (isFreeTier) keyPool.nextHealthyFreeChatKey() else keyPool.nextHealthyPaidChatKey()
        if (primary != null && primary.id !in attemptedKeyIds) {
            return primary
        }
        return keyPool.snapshot().firstOrNull { entry ->
            entry.enabled &&
                entry.isFreeTier == isFreeTier &&
                !entry.isTtsOnly &&
                entry.status == KeyStatus.HEALTHY &&
                entry.apiKey.isNotBlank() &&
                entry.id !in attemptedKeyIds
        }
    }

    private suspend inline fun buildProviderRequest(
        key: ApiKeyEntry,
        systemPrompt: String,
        history: List<ChatMessage>,
        userText: String?,
        userAudio: AudioAttachment?,
        enableTools: Boolean,
        resolveTranscript: () -> String?,
    ): LlmRequest? {
        if (key.provider in GEMINI_PROVIDERS) {
            return LlmRequest(
                systemPrompt = systemPrompt,
                history = history,
                userText = userText,
                userAudio = userAudio,
                enableTools = enableTools,
            )
        }

        val effectiveText = when {
            !userText.isNullOrBlank() -> userText
            userAudio != null -> resolveTranscript()
            else -> null
        }
        // Skip non-Gemini providers when an audio-only turn has no usable transcript so Gemini can still try.
        if (effectiveText.isNullOrBlank()) {
            return null
        }

        return LlmRequest(
            systemPrompt = systemPrompt,
            history = history,
            userText = effectiveText,
            userAudio = null,
            enableTools = enableTools,
        )
    }

    private suspend fun transcribeWithAvailableOpenAiKey(
        currentKey: ApiKeyEntry,
        wavBytes: ByteArray,
    ): String? {
        val whisperKey = if (currentKey.provider == ProviderType.OPENAI) {
            currentKey
        } else {
            keyPool.snapshot().firstOrNull { entry ->
                entry.enabled &&
                    entry.provider == ProviderType.OPENAI &&
                    entry.status == KeyStatus.HEALTHY &&
                    entry.apiKey.isNotBlank()
            }
        } ?: return null

        return openAiClient.transcribeAudio(whisperKey, wavBytes)
    }

    private suspend fun dispatchGenerate(key: ApiKeyEntry, request: LlmRequest): LlmResult =
        when (key.provider) {
            ProviderType.GEMINI_VERTEX_EXPRESS,
            ProviderType.GEMINI_AI_STUDIO,
            ProviderType.FREE_GEMINI,
            -> geminiClient.generate(key, request)

            ProviderType.OPENAI,
            ProviderType.FREE_OPENAI_COMPAT,
            -> openAiClient.generate(key, request)

            ProviderType.CLAUDE -> claudeClient.generate(key, request)
        }

    private companion object {
        val GEMINI_PROVIDERS = setOf(
            ProviderType.GEMINI_VERTEX_EXPRESS,
            ProviderType.GEMINI_AI_STUDIO,
            ProviderType.FREE_GEMINI,
        )

        const val LITTLE_SISTER_OFFLINE_REPLY =
            "Dạ mạng chỗ em đang hơi chập chờn một xíu nên em chưa trả lời được, anh thử gọi lại em sau nha!"
    }
}
