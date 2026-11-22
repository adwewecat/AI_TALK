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
import com.adwewecat.nana.core.voice.FastCommandParser

/** Normalized chat response produced by [AiChatRouter]. */
data class RoutedChatReply(
    val replyText: String,
    val persona: PersonaMode,
    val toolCalls: List<DeviceCommand>,
    val quotaExhaustedNotice: Boolean = false,
    val usedKeyId: String? = null,
    val unclearAudio: Boolean = false,
    val inferredUserTranscript: String? = null,
)

/**
 * Routes conversational turns across configured paid and free-tier LLM keys,
 * handling silent failover, silent background web search synthesis, Whisper audio transcription
 * fallback, and quota state transitions.
 */
class AiChatRouter(
    private val keyPool: KeyPoolManager,
    private val stateMachine: NaNaQuotaStateMachine,
    private val geminiClient: GeminiClient,
    private val openAiClient: OpenAiClient,
    private val claudeClient: ClaudeClient,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val silentWebSearchClient: SilentWebSearchClient? = null,
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
        val systemPrompt = PersonaPromptBuilder.buildSystemPrompt(persona, memories, nowMs = clockMs())

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
                    if (result.transcriptHint == GeminiClient.UNCLEAR_AUDIO_HINT) {
                        return RoutedChatReply(
                            replyText = result.replyText.ifBlank {
                                "Dạ em nghe chưa rõ, anh nói lại giúp em nha?"
                            },
                            persona = persona,
                            toolCalls = emptyList(),
                            quotaExhaustedNotice = false,
                            usedKeyId = key.id,
                            unclearAudio = true,
                        )
                    }
                    val silentSearchQuery = extractSilentSearchQuery(
                        toolCalls = result.toolCalls,
                        userText = userText,
                    )
                    if (silentSearchQuery != null && silentWebSearchClient != null) {
                        val remainingTools = result.toolCalls.filterNot {
                            it is DeviceCommand.SilentWebSearch || it is DeviceCommand.WebSearch
                        }
                        val synthesized = synthesizeSilentWebAnswer(
                            key = key,
                            systemPrompt = systemPrompt,
                            history = history,
                            originalUserText = userText,
                            searchQuery = silentSearchQuery,
                        )
                        return RoutedChatReply(
                            replyText = synthesized ?: result.replyText.ifBlank {
                                "Dạ em đã tìm \"$silentSearchQuery\" nhưng hiện chưa lấy được thông tin rõ ràng, anh thử hỏi lại sau một chút nha."
                            },
                            persona = persona,
                            toolCalls = remainingTools,
                            quotaExhaustedNotice = false,
                            usedKeyId = key.id,
                            inferredUserTranscript = silentSearchQuery,
                        )
                    }
                    return RoutedChatReply(
                        replyText = result.replyText,
                        persona = persona,
                        toolCalls = result.toolCalls.filterNot { it is DeviceCommand.SilentWebSearch },
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

    private fun extractSilentSearchQuery(
        toolCalls: List<DeviceCommand>,
        userText: String?,
    ): String? {
        val explicitSilent = toolCalls.filterIsInstance<DeviceCommand.SilentWebSearch>().firstOrNull()
        if (explicitSilent != null) {
            return explicitSilent.query
        }
        val accidentalBrowserSearch = toolCalls.filterIsInstance<DeviceCommand.WebSearch>().firstOrNull()
            ?: return null
        // Guard against the LLM launching a browser when the user only asked a voice/text question
        // without explicitly requesting "mở web" / "mở trình duyệt".
        return if (userExplicitlyRequestedBrowser(userText)) {
            null
        } else {
            accidentalBrowserSearch.query
        }
    }

    private fun userExplicitlyRequestedBrowser(userText: String?): Boolean {
        val norm = FastCommandParser.normalizeVietnamese(userText.orEmpty())
        if (norm.isEmpty()) return false
        return EXPLICIT_BROWSER_PHRASES.any { phrase -> norm.contains(phrase) }
    }

    private suspend fun synthesizeSilentWebAnswer(
        key: ApiKeyEntry,
        systemPrompt: String,
        history: List<ChatMessage>,
        originalUserText: String?,
        searchQuery: String,
    ): String? {
        val client = silentWebSearchClient ?: return null
        val snippets = client.search(searchQuery)
        val formattedResults = client.formatForPrompt(searchQuery, snippets)
        val question = originalUserText?.takeIf { it.isNotBlank() } ?: searchQuery
        val synthesisPrompt = """
            Câu hỏi của anh: "$question"

            $formattedResults

            Hãy tổng hợp ngắn gọn, rõ ràng, tự nhiên bằng tiếng Việt để đọc trực tiếp cho anh nghe (không đọc URL, không dùng dấu * hay gạch đầu dòng). Nếu có số liệu giá cả hoặc ngày cập nhật thì nêu rõ.
        """.trimIndent()

        val synthesisRequest = LlmRequest(
            systemPrompt = systemPrompt,
            history = history,
            userText = synthesisPrompt,
            userAudio = null,
            enableTools = false,
        )
        return when (val followUp = dispatchGenerate(key, synthesisRequest)) {
            is LlmResult.Success -> followUp.replyText.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
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

        val EXPLICIT_BROWSER_PHRASES = listOf(
            "mo web",
            "mo trinh duyet",
            "mo trang web",
            "mo chrome",
            "mo google tren man hinh",
            "open browser",
            "open web",
        )

        const val LITTLE_SISTER_OFFLINE_REPLY =
            "Dạ mạng chỗ em đang hơi chập chờn một xíu nên em chưa trả lời được, anh thử gọi lại em sau nha!"
    }
}
