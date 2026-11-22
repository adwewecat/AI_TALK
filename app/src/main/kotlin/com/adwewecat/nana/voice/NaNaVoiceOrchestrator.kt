package com.adwewecat.nana.voice

import com.adwewecat.nana.core.ai.AiChatRouter
import com.adwewecat.nana.core.ai.AudioAttachment
import com.adwewecat.nana.core.ai.LocalFallbackTts
import com.adwewecat.nana.core.ai.MemoryExtractor
import com.adwewecat.nana.core.ai.SpokenOutput
import com.adwewecat.nana.core.ai.TtsRouter
import com.adwewecat.nana.core.model.ChatMessage
import com.adwewecat.nana.core.model.DeviceActionExecutor
import com.adwewecat.nana.core.model.DeviceCommand
import com.adwewecat.nana.core.model.MemoryCategory
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.QuotaState
import com.adwewecat.nana.core.quota.NaNaQuotaStateMachine
import com.adwewecat.nana.core.voice.FastCommandParser
import com.adwewecat.nana.data.local.AppSettings
import com.adwewecat.nana.data.local.MemoryFactDao
import com.adwewecat.nana.data.local.MessageDao
import com.adwewecat.nana.data.local.toDomain
import com.adwewecat.nana.data.local.toEntity
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Voice loop lifecycle states exposed to the UI and foreground service. */
enum class VoiceLoopState {
    STOPPED,
    IDLE_WAKE,
    LISTENING,
    THINKING,
    SPEAKING,
    AWAITING_CONSENT,
}

/** Observable UI state snapshot for Na Na's voice companion pipeline. */
data class VoiceUiSnapshot(
    val loopState: VoiceLoopState = VoiceLoopState.IDLE_WAKE,
    val quotaState: QuotaState = QuotaState.ACTIVE_NANA,
    val activePersona: PersonaMode = PersonaMode.NANA,
    val micLevel: Float = 0f,
    val statusMessage: String = "Na Na sẵn sàng",
)

/** Triggers deferred Wi-Fi-only Google Drive synchronization after local data mutations. */
fun interface SyncTrigger {
    fun enqueueWifiSync()
}

/**
 * Coordinates Na Na's end-to-end voice turn lifecycle:
 * wake-word gating, tired-state consent handoff, offline fast commands,
 * multi-provider LLM chat + tool calls, decoupled TTS / AudioTrack playback,
 * and offline-first Room persistence + Wi-Fi sync enqueueing.
 */
class NaNaVoiceOrchestrator(
    private val stateMachine: NaNaQuotaStateMachine,
    private val aiChatRouter: AiChatRouter,
    private val ttsRouter: TtsRouter,
    private val localFallbackTts: LocalFallbackTts,
    private val actionExecutor: DeviceActionExecutor,
    private val audioPlayer: AudioPlaybackEngine,
    private val messageDao: MessageDao,
    private val memoryFactDao: MemoryFactDao,
    private val syncTrigger: SyncTrigger,
    private val settingsProvider: () -> AppSettings = { AppSettings() },
    private val memoryExtractor: MemoryExtractor = MemoryExtractor(),
    private val offlineConsentTranscriber: (ByteArray) -> String? = { null },
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val backgroundScope: CoroutineScope? = null,
) {

    private val turnMutex = Mutex()
    private val memoryExtractionMutex = Mutex()
    private val tiredPromptPrecacheAttempted = AtomicBoolean(false)
    private val _uiState = MutableStateFlow(
        VoiceUiSnapshot(
            loopState = VoiceLoopState.IDLE_WAKE,
            quotaState = stateMachine.state,
            activePersona = stateMachine.activePersona,
        ),
    )

    @Volatile
    private var currentSessionId: String? = null

    @Volatile
    private var currentSessionMessageCount: Int = 0

    val uiState: StateFlow<VoiceUiSnapshot> = _uiState.asStateFlow()

    /** Handles wake-word activation, entering listening or playing the tired consent prompt. */
    suspend fun onWakeWordDetected() = turnMutex.withLock {
        val wakeResult = stateMachine.onWakeWord()
        if (!wakeResult.awaitConsent) {
            ensureSessionId()
            _uiState.update {
                it.copy(
                    loopState = VoiceLoopState.LISTENING,
                    quotaState = wakeResult.state,
                    activePersona = wakeResult.persona,
                    statusMessage = listeningMessageFor(wakeResult.persona),
                )
            }
            return@withLock
        }

        if (!settingsProvider().emGaiHandoffEnabled) {
            val declined = stateMachine.onConsentTimeout()
            val declineText = declined.spokenPrompt ?: NaNaQuotaStateMachine.DECLINED_GOODBYE
            _uiState.update {
                it.copy(
                    loopState = VoiceLoopState.SPEAKING,
                    quotaState = declined.state,
                    activePersona = declined.persona,
                    statusMessage = declineText,
                )
            }
            localFallbackTts.speak(declineText, PersonaMode.NANA)
            currentSessionId = null
            _uiState.update {
                it.copy(
                    loopState = VoiceLoopState.IDLE_WAKE,
                    quotaState = declined.state,
                    activePersona = declined.persona,
                    micLevel = 0f,
                    statusMessage = "Na Na đang nghỉ ngơi...",
                )
            }
            return@withLock
        }

        val consentPrompt = wakeResult.spokenPrompt ?: NaNaQuotaStateMachine.TIRED_CONSENT_PROMPT
        _uiState.update {
            it.copy(
                loopState = VoiceLoopState.SPEAKING,
                quotaState = wakeResult.state,
                activePersona = wakeResult.persona,
                statusMessage = consentPrompt,
            )
        }
        speakTiredPromptWithoutPaidApi(consentPrompt)

        _uiState.update {
            it.copy(
                loopState = VoiceLoopState.AWAITING_CONSENT,
                quotaState = wakeResult.state,
                activePersona = wakeResult.persona,
                statusMessage = consentPrompt,
            )
        }
    }

    /** Processes one user speech/text utterance within consent or active conversation mode. */
    suspend fun onUserUtterance(transcript: String?, wavBytes: ByteArray?) = turnMutex.withLock {
        if (stateMachine.state == QuotaState.NANA_TIRED_AWAITING_CONSENT ||
            _uiState.value.loopState == VoiceLoopState.AWAITING_CONSENT
        ) {
            handleConsentUtterance(transcript, wavBytes)
            return@withLock
        }

        val trimmedTranscript = transcript?.trim()?.takeIf { it.isNotEmpty() }
        val hasAudio = wavBytes != null && wavBytes.isNotEmpty()
        if (trimmedTranscript == null && !hasAudio) {
            return@withLock
        }

        if (trimmedTranscript != null) {
            val localCommand = FastCommandParser.parseLocalCommand(trimmedTranscript)
            if (localCommand != null) {
                handleFastCommand(localCommand)
                return@withLock
            }
        }

        handleLlmConversationTurn(trimmedTranscript, wavBytes)
    }

    /** Handles silence timeout while awaiting consent for Na Na's little sister. */
    suspend fun onConsentTimeout() = turnMutex.withLock {
        val result = stateMachine.onConsentTimeout()
        val goodbye = result.spokenPrompt ?: NaNaQuotaStateMachine.DECLINED_GOODBYE
        _uiState.update {
            it.copy(
                loopState = VoiceLoopState.SPEAKING,
                quotaState = result.state,
                activePersona = result.persona,
                statusMessage = goodbye,
            )
        }
        localFallbackTts.speak(goodbye, PersonaMode.NANA)
        currentSessionId = null
        _uiState.update {
            it.copy(
                loopState = VoiceLoopState.IDLE_WAKE,
                quotaState = result.state,
                activePersona = result.persona,
                micLevel = 0f,
                statusMessage = "Na Na đang nghỉ ngơi...",
            )
        }
    }

    /** Ends the active conversation session and returns to passive wake-word listening. */
    fun stopSession() {
        applyStopSession(loopState = VoiceLoopState.IDLE_WAKE)
    }

    /** Marks the voice pipeline as stopped when the foreground service shuts down. */
    fun markServiceStopped() {
        applyStopSession(loopState = VoiceLoopState.STOPPED)
    }

    /** Marks the voice pipeline ready for passive wake-word listening when the service starts. */
    fun markServiceReady() {
        _uiState.update {
            it.copy(
                loopState = VoiceLoopState.IDLE_WAKE,
                quotaState = stateMachine.state,
                activePersona = stateMachine.activePersona,
                micLevel = 0f,
                statusMessage = idleStatusMessage(stateMachine.state),
            )
        }
    }

    /** Updates the user-visible status line (e.g., mic hardware warnings) without altering loop state. */
    fun reportStatus(message: String) {
        if (message.isBlank()) return
        _uiState.update { it.copy(statusMessage = message) }
    }

    /** Replays an existing assistant reply aloud via [ttsRouter] without writing to chat history. */
    suspend fun speakMessageAloud(text: String, persona: PersonaMode) = turnMutex.withLock {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return@withLock
        val previous = _uiState.value
        _uiState.update {
            it.copy(
                loopState = VoiceLoopState.SPEAKING,
                activePersona = persona,
                statusMessage = cleaned,
            )
        }
        try {
            speakViaTtsRouter(cleaned, persona)
        } finally {
            _uiState.update {
                it.copy(
                    loopState = if (previous.loopState == VoiceLoopState.SPEAKING) VoiceLoopState.IDLE_WAKE else previous.loopState,
                    activePersona = stateMachine.activePersona,
                    statusMessage = idleStatusMessage(stateMachine.state),
                )
            }
        }
    }

    /** Updates normalized microphone input amplitude (`0f..1f`) for UI visualization. */
    fun updateMicLevel(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        _uiState.update { snapshot ->
            if (snapshot.micLevel == clamped) snapshot else snapshot.copy(micLevel = clamped)
        }
    }

    /**
     * Runs [MemoryExtractor] over recent messages and persists newly discovered [MemoryFact]s
     * when explicitly invoked outside real-time utterance latency.
     */
    suspend fun extractMemoriesFromRecentSession(recentLimit: Int = 10) {
        if (stateMachine.state == QuotaState.NANA_TIRED ||
            stateMachine.state == QuotaState.NANA_TIRED_AWAITING_CONSENT
        ) {
            return
        }
        if (stateMachine.state == QuotaState.ACTIVE_NANA && !stateMachine.hasHealthyPaidChatKey()) {
            return
        }

        memoryExtractionMutex.withLock {
            try {
                val recent = messageDao.getAll().takeLast(recentLimit).map { it.toDomain() }
                if (recent.isEmpty()) return@withLock

                val prompt = memoryExtractor.buildExtractionPrompt(recent)
                val existingMemories = memoryFactDao.getAll().map { it.toDomain() }
                val reply = aiChatRouter.sendTurn(
                    history = emptyList(),
                    memories = existingMemories,
                    userText = prompt,
                    userAudio = null,
                    enableTools = false,
                    transitionOnExhausted = false,
                )
                val extracted = memoryExtractor.parseExtractedFacts(reply.replyText, nowMs = clockMs())
                if (extracted.isNotEmpty()) {
                    val existingByPair = existingMemories.associateByTo(mutableMapOf()) {
                        normalizedMemoryKey(it.category, it.key)
                    }
                    extracted.forEach { fact ->
                        val pair = normalizedMemoryKey(fact.category, fact.key)
                        val existing = existingByPair[pair]
                        val merged = if (existing != null) fact.copy(id = existing.id) else fact
                        existingByPair[pair] = merged
                        memoryFactDao.upsert(merged.toEntity())
                    }
                    syncTrigger.enqueueWifiSync()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Background memory extraction must never disrupt active voice sessions or surface errors.
            }
        }
    }

    private fun normalizedMemoryKey(category: MemoryCategory, key: String): Pair<MemoryCategory, String> {
        val normalized = FastCommandParser.normalizeVietnamese(key).ifEmpty {
            key.trim().lowercase(Locale.ROOT)
        }
        return category to normalized
    }

    private suspend fun handleConsentUtterance(transcript: String?, wavBytes: ByteArray?) {
        val resolved = transcript?.trim()?.takeIf { it.isNotEmpty() }
            ?: wavBytes?.let(offlineConsentTranscriber)?.trim()?.takeIf { it.isNotEmpty() }
            ?: ""

        val result = stateMachine.onConsentUtterance(resolved)
        val prompt = result.spokenPrompt

        when (result.state) {
            QuotaState.ACTIVE_LITTLE_SISTER -> {
                val greeting = prompt ?: NaNaQuotaStateMachine.LITTLE_SISTER_GREETING
                ensureSessionId()
                _uiState.update {
                    it.copy(
                        loopState = VoiceLoopState.SPEAKING,
                        quotaState = result.state,
                        activePersona = result.persona,
                        statusMessage = greeting,
                    )
                }
                localFallbackTts.speak(greeting, PersonaMode.LITTLE_SISTER)
                _uiState.update {
                    it.copy(
                        loopState = VoiceLoopState.LISTENING,
                        quotaState = result.state,
                        activePersona = result.persona,
                        statusMessage = listeningMessageFor(result.persona),
                    )
                }
            }

            QuotaState.NANA_TIRED_AWAITING_CONSENT -> {
                val retryPrompt = prompt ?: NaNaQuotaStateMachine.TIRED_CONSENT_PROMPT
                _uiState.update {
                    it.copy(
                        loopState = VoiceLoopState.SPEAKING,
                        quotaState = result.state,
                        activePersona = result.persona,
                        statusMessage = retryPrompt,
                    )
                }
                speakTiredPromptWithoutPaidApi(retryPrompt)
                _uiState.update {
                    it.copy(
                        loopState = VoiceLoopState.AWAITING_CONSENT,
                        quotaState = result.state,
                        activePersona = result.persona,
                        statusMessage = retryPrompt,
                    )
                }
            }

            else -> {
                val goodbye = prompt ?: NaNaQuotaStateMachine.DECLINED_GOODBYE
                _uiState.update {
                    it.copy(
                        loopState = VoiceLoopState.SPEAKING,
                        quotaState = result.state,
                        activePersona = result.persona,
                        statusMessage = goodbye,
                    )
                }
                localFallbackTts.speak(goodbye, PersonaMode.NANA)
                currentSessionId = null
                _uiState.update {
                    it.copy(
                        loopState = VoiceLoopState.IDLE_WAKE,
                        quotaState = result.state,
                        activePersona = result.persona,
                        micLevel = 0f,
                        statusMessage = idleStatusMessage(result.state),
                    )
                }
            }
        }
    }

    private suspend fun handleFastCommand(command: DeviceCommand) {
        val persona = stateMachine.activePersona
        if (command is DeviceCommand.StopSession) {
            val farewell = "Dạ tạm biệt anh nha!"
            _uiState.update {
                it.copy(
                    loopState = VoiceLoopState.SPEAKING,
                    quotaState = stateMachine.state,
                    activePersona = persona,
                    statusMessage = farewell,
                )
            }
            localFallbackTts.speak(farewell, persona)
            applyStopSession(loopState = VoiceLoopState.IDLE_WAKE)
            return
        }

        _uiState.update {
            it.copy(
                loopState = VoiceLoopState.THINKING,
                quotaState = stateMachine.state,
                activePersona = persona,
                statusMessage = "Đang thực hiện...",
            )
        }
        val result = actionExecutor.execute(command)
        val feedback = result.spokenFeedback.trim()
        if (feedback.isNotEmpty()) {
            _uiState.update {
                it.copy(
                    loopState = VoiceLoopState.SPEAKING,
                    quotaState = stateMachine.state,
                    activePersona = persona,
                    statusMessage = feedback,
                )
            }
            localFallbackTts.speak(feedback, persona)
        }

        _uiState.update {
            it.copy(
                loopState = VoiceLoopState.LISTENING,
                quotaState = stateMachine.state,
                activePersona = stateMachine.activePersona,
                statusMessage = listeningMessageFor(stateMachine.activePersona),
            )
        }
    }

    private suspend fun handleLlmConversationTurn(
        trimmedTranscript: String?,
        wavBytes: ByteArray?,
    ) {
        val sessionId = ensureSessionId()
        val thinkingPersona = stateMachine.activePersona
        _uiState.update {
            it.copy(
                loopState = VoiceLoopState.THINKING,
                quotaState = stateMachine.state,
                activePersona = thinkingPersona,
                statusMessage = if (thinkingPersona == PersonaMode.LITTLE_SISTER) {
                    "Em gái Na Na đang suy nghĩ..."
                } else {
                    "Na Na đang suy nghĩ..."
                },
            )
        }

        val history = messageDao.getAll().takeLast(MAX_HISTORY_MESSAGES).map { it.toDomain() }
        val memories = memoryFactDao.getAll().map { it.toDomain() }
        val audioAttachment = wavBytes?.takeIf { it.isNotEmpty() }?.let {
            AudioAttachment(bytes = it, mimeType = "audio/wav")
        }

        val reply = aiChatRouter.sendTurn(
            history = history,
            memories = memories,
            userText = trimmedTranscript,
            userAudio = audioAttachment,
        )

        var shouldStopSession = false
        var lastActionFeedback: String? = null
        for (command in reply.toolCalls) {
            if (command is DeviceCommand.StopSession) {
                shouldStopSession = true
            } else {
                val actionResult = actionExecutor.execute(command)
                if (actionResult.spokenFeedback.isNotBlank()) {
                    lastActionFeedback = actionResult.spokenFeedback
                }
            }
        }

        val userContent = trimmedTranscript ?: "[Tin nhắn thoại]"
        val userTimeMs = clockMs()
        messageDao.upsert(
            ChatMessage(
                id = idGenerator(),
                sessionId = sessionId,
                role = "user",
                persona = reply.persona,
                content = userContent,
                createdAtMs = userTimeMs,
                synced = false,
            ).toEntity(),
        )
        currentSessionMessageCount++

        val assistantContent = reply.replyText.ifBlank { lastActionFeedback.orEmpty() }
        if (assistantContent.isNotBlank()) {
            val assistantTimeMs = clockMs().coerceAtLeast(userTimeMs + 1L)
            messageDao.upsert(
                ChatMessage(
                    id = idGenerator(),
                    sessionId = sessionId,
                    role = "assistant",
                    persona = reply.persona,
                    content = assistantContent,
                    createdAtMs = assistantTimeMs,
                    synced = false,
                ).toEntity(),
            )
            currentSessionMessageCount++
        }
        syncTrigger.enqueueWifiSync()

        if (assistantContent.isNotBlank()) {
            _uiState.update {
                it.copy(
                    loopState = VoiceLoopState.SPEAKING,
                    quotaState = stateMachine.state,
                    activePersona = reply.persona,
                    statusMessage = assistantContent,
                )
            }
            if (reply.quotaExhaustedNotice) {
                localFallbackTts.speak(assistantContent, PersonaMode.NANA)
            } else {
                speakViaTtsRouter(assistantContent, reply.persona)
            }
        }

        if (reply.quotaExhaustedNotice || shouldStopSession) {
            applyStopSession(loopState = VoiceLoopState.IDLE_WAKE)
        } else {
            _uiState.update {
                it.copy(
                    loopState = VoiceLoopState.LISTENING,
                    quotaState = stateMachine.state,
                    activePersona = stateMachine.activePersona,
                    statusMessage = listeningMessageFor(stateMachine.activePersona),
                )
            }
        }
    }

    private suspend fun speakTiredPromptWithoutPaidApi(prompt: String) {
        val cachedPcm = audioPlayer.getCachedTiredPrompt()
        if (cachedPcm != null && cachedPcm.isNotEmpty()) {
            audioPlayer.playPcm(cachedPcm, AndroidAudioPlayer.DEFAULT_SAMPLE_RATE_HZ)
        } else {
            localFallbackTts.speak(prompt, PersonaMode.NANA)
        }
    }

    private suspend fun speakViaTtsRouter(text: String, persona: PersonaMode) {
        when (val spoken = ttsRouter.synthesizeOrSpeak(text, persona, settingsProvider().preferredGeminiVoice)) {
            is SpokenOutput.CloudPcm -> {
                audioPlayer.playPcm(spoken.audio.pcmBytes, spoken.audio.sampleRateHz)
                if (backgroundScope != null &&
                    persona == PersonaMode.NANA &&
                    audioPlayer.getCachedTiredPrompt() == null &&
                    tiredPromptPrecacheAttempted.compareAndSet(false, true)
                ) {
                    backgroundScope.launch {
                        try {
                            val audio = ttsRouter.synthesizeCloudOnly(
                                text = NaNaQuotaStateMachine.TIRED_CONSENT_PROMPT,
                                persona = PersonaMode.NANA,
                                preferredGeminiVoice = settingsProvider().preferredGeminiVoice,
                            )
                            if (audio != null && audio.pcmBytes.isNotEmpty()) {
                                audioPlayer.cacheTiredPrompt(audio.pcmBytes)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Throwable) {
                            // Best-effort offline prompt cache should never crash or surface errors.
                        }
                    }
                }
            }

            SpokenOutput.SpokenByLocalFallback,
            SpokenOutput.Failed,
            -> Unit
        }
    }

    private fun applyStopSession(loopState: VoiceLoopState) {
        val sessionMessageCount = currentSessionMessageCount
        currentSessionId = null
        currentSessionMessageCount = 0
        stateMachine.onSessionEnded()
        val newQuotaState = stateMachine.state
        val newPersona = stateMachine.activePersona
        _uiState.update {
            it.copy(
                loopState = loopState,
                quotaState = newQuotaState,
                activePersona = newPersona,
                micLevel = 0f,
                statusMessage = if (loopState == VoiceLoopState.STOPPED) {
                    "Na Na đang tạm dừng"
                } else {
                    idleStatusMessage(newQuotaState)
                },
            )
        }
        if (sessionMessageCount >= 2 && stateMachine.hasHealthyPaidChatKey()) {
            backgroundScope?.launch {
                extractMemoriesFromRecentSession()
            }
        }
    }

    private fun ensureSessionId(): String =
        currentSessionId ?: "session-${clockMs()}-${idGenerator().take(8)}".also {
            currentSessionId = it
            currentSessionMessageCount = 0
        }

    private fun listeningMessageFor(persona: PersonaMode): String =
        if (persona == PersonaMode.LITTLE_SISTER) {
            "Em gái Na Na đang nghe anh..."
        } else {
            "Na Na đang nghe anh..."
        }

    private fun idleStatusMessage(quotaState: QuotaState): String =
        if (quotaState == QuotaState.NANA_TIRED || quotaState == QuotaState.NANA_TIRED_AWAITING_CONSENT) {
            "Na Na đang nghỉ ngơi..."
        } else {
            "Na Na sẵn sàng"
        }

    private companion object {
        const val MAX_HISTORY_MESSAGES = 20
    }
}
