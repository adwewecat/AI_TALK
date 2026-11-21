package com.adwewecat.nana.di

import android.content.Context
import android.net.Uri
import android.os.Build
import com.adwewecat.nana.actions.AndroidDeviceActionExecutor
import com.adwewecat.nana.actions.CallRecordingIngestionCoordinator
import com.adwewecat.nana.actions.CallStateMonitor
import com.adwewecat.nana.actions.InAppCallRecorder
import com.adwewecat.nana.actions.OriginOsCallRecordingWatcher
import com.adwewecat.nana.actions.RoomRecordingStore
import com.adwewecat.nana.core.ai.AiChatRouter
import com.adwewecat.nana.core.ai.AudioAttachment
import com.adwewecat.nana.core.ai.ClaudeClient
import com.adwewecat.nana.core.ai.GeminiClient
import com.adwewecat.nana.core.ai.LlmRequest
import com.adwewecat.nana.core.ai.LlmResult
import com.adwewecat.nana.core.ai.OpenAiClient
import com.adwewecat.nana.core.ai.PersonaPromptBuilder
import com.adwewecat.nana.core.ai.TtsRouter
import com.adwewecat.nana.core.ai.TtsResult
import com.adwewecat.nana.core.model.ApiKeyEntry
import com.adwewecat.nana.core.model.CallRecordingRecord
import com.adwewecat.nana.core.model.KeyStatus
import com.adwewecat.nana.core.model.MemoryCategory
import com.adwewecat.nana.core.model.MemoryFact
import com.adwewecat.nana.core.model.PersonaMode
import com.adwewecat.nana.core.model.ProviderType
import com.adwewecat.nana.core.model.QuotaState
import com.adwewecat.nana.core.model.RecordingSource
import com.adwewecat.nana.core.model.detectGeminiProvider
import com.adwewecat.nana.core.quota.KeyPoolManager
import com.adwewecat.nana.core.quota.NaNaQuotaStateMachine
import com.adwewecat.nana.core.sync.DriveRestoreCoordinator
import com.adwewecat.nana.core.sync.GoogleDriveClient
import com.adwewecat.nana.core.sync.SyncOutcome
import com.adwewecat.nana.core.sync.WifiOnlySyncCoordinator
import com.adwewecat.nana.data.auth.GoogleDriveAuthManager
import com.adwewecat.nana.data.local.AppSettings
import com.adwewecat.nana.data.local.EncryptedApiKeyVault
import com.adwewecat.nana.data.local.NaNaDatabase
import com.adwewecat.nana.data.local.RoomSyncStorageGateway
import com.adwewecat.nana.data.local.toEntity
import com.adwewecat.nana.data.sync.AndroidWifiNetworkChecker
import com.adwewecat.nana.data.sync.DriveSyncDependencies
import com.adwewecat.nana.data.sync.DriveSyncScheduler
import com.adwewecat.nana.data.sync.DriveSyncWorker
import com.adwewecat.nana.voice.AndroidAudioPlayer
import com.adwewecat.nana.voice.AndroidSystemTtsEngine
import com.adwewecat.nana.voice.NaNaVoiceOrchestrator
import com.adwewecat.nana.voice.NaNaVoiceRuntime
import com.adwewecat.nana.voice.NaNaVoiceService
import com.adwewecat.nana.voice.SyncTrigger
import com.adwewecat.nana.voice.VoiceLoopState
import com.adwewecat.nana.voice.VoskWakeWordEngine
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Manual DI container wiring `:core` routers/coordinators and `:app` Android services
 * around a single shared [NaNaDatabase], [EncryptedApiKeyVault], and [KeyPoolManager].
 */
class AppContainer internal constructor(
    context: Context,
    val database: NaNaDatabase,
    val vault: EncryptedApiKeyVault,
    val httpClient: OkHttpClient = OkHttpClient(),
    val geminiClient: GeminiClient = GeminiClient(httpClient),
    val openAiClient: OpenAiClient = OpenAiClient(httpClient),
    val claudeClient: ClaudeClient = ClaudeClient(httpClient),
    val driveClient: GoogleDriveClient = GoogleDriveClient(httpClient),
    val authManager: GoogleDriveAuthManager = GoogleDriveAuthManager(context.applicationContext, vault),
    val syncMutex: Mutex = DriveSyncWorker.GLOBAL_SYNC_MUTEX,
) {
    constructor(context: Context) : this(
        context = context.applicationContext,
        database = NaNaDatabase.getInstance(context.applicationContext),
        vault = createVaultSafely(context.applicationContext),
    )

    private val appContext: Context = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _keysFlow = MutableStateFlow(vault.loadKeys().sortedBy { it.priority })
    val keysFlow: StateFlow<List<ApiKeyEntry>> = _keysFlow.asStateFlow()

    private val _settingsFlow = MutableStateFlow(vault.loadSettings())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _lastSyncOutcome = MutableStateFlow<SyncOutcome?>(null)
    val lastSyncOutcome: StateFlow<SyncOutcome?> = _lastSyncOutcome.asStateFlow()

    val keyPool: KeyPoolManager = KeyPoolManager(_keysFlow.value)
    val quotaStateMachine: NaNaQuotaStateMachine = NaNaQuotaStateMachine(keyPool)

    val aiChatRouter: AiChatRouter = AiChatRouter(
        keyPool = keyPool,
        stateMachine = quotaStateMachine,
        geminiClient = geminiClient,
        openAiClient = openAiClient,
        claudeClient = claudeClient,
    )

    val localFallbackTts: AndroidSystemTtsEngine = AndroidSystemTtsEngine(appContext)
    val ttsRouter: TtsRouter = TtsRouter(
        keyPool = keyPool,
        geminiClient = geminiClient,
        openAiClient = openAiClient,
        localFallback = localFallbackTts,
    )
    val audioPlayer: AndroidAudioPlayer = AndroidAudioPlayer(appContext)

    val wifiChecker: AndroidWifiNetworkChecker = AndroidWifiNetworkChecker(appContext)
    val syncScheduler: DriveSyncScheduler = DriveSyncScheduler(appContext)
    val syncStorageGateway: RoomSyncStorageGateway = RoomSyncStorageGateway(database)

    val deviceActionExecutor: AndroidDeviceActionExecutor = AndroidDeviceActionExecutor(appContext)

    private val recordingStore = RoomRecordingStore(database.callRecordingDao())
    val syncTrigger: SyncTrigger = SyncTrigger { syncScheduler.enqueueSync() }

    private val originOsIngestionCoordinator = CallRecordingIngestionCoordinator(
        store = recordingStore,
        syncTrigger = syncTrigger,
        recordingSource = RecordingSource.ORIGINOS_NATIVE,
    )
    private val inAppIngestionCoordinator = CallRecordingIngestionCoordinator(
        store = recordingStore,
        syncTrigger = syncTrigger,
        recordingSource = RecordingSource.IN_APP,
    )

    val callRecordingWatcher: OriginOsCallRecordingWatcher = OriginOsCallRecordingWatcher(
        context = appContext,
        coordinator = originOsIngestionCoordinator,
    )
    val inAppCallRecorder: InAppCallRecorder = InAppCallRecorder(appContext)
    val callStateMonitor: CallStateMonitor = CallStateMonitor(
        context = appContext,
        scope = appScope,
        settingsProvider = { _settingsFlow.value },
        recordingWatcher = callRecordingWatcher,
        inAppCallRecorder = inAppCallRecorder,
        inAppIngestionCoordinator = inAppIngestionCoordinator,
    )

    val wakeWordEngine: VoskWakeWordEngine = VoskWakeWordEngine(appContext)

    val voiceOrchestrator: NaNaVoiceOrchestrator = NaNaVoiceOrchestrator(
        stateMachine = quotaStateMachine,
        aiChatRouter = aiChatRouter,
        ttsRouter = ttsRouter,
        localFallbackTts = localFallbackTts,
        actionExecutor = deviceActionExecutor,
        audioPlayer = audioPlayer,
        messageDao = database.messageDao(),
        memoryFactDao = database.memoryFactDao(),
        syncTrigger = syncTrigger,
        settingsProvider = { _settingsFlow.value },
        offlineConsentTranscriber = { audioBytes ->
            val rawPcm = stripWavHeaderIfPresent(audioBytes)
            VoskWakeWordEngine.recognizeConsentFromPcm(appContext, rawPcm)
        },
        backgroundScope = appScope,
    )

    /** Supplies shared Drive sync dependencies to [com.adwewecat.nana.data.sync.DriveSyncWorker]. */
    fun provideDriveSyncDependencies(): DriveSyncDependencies = DriveSyncDependencies(
        database = database,
        vault = vault,
        authManager = authManager,
        wifiChecker = wifiChecker,
        driveClient = driveClient,
        syncMutex = syncMutex,
    )

    /** Supplies shared voice dependencies to [com.adwewecat.nana.voice.NaNaVoiceService]. */
    fun provideVoiceRuntime(): NaNaVoiceRuntime = NaNaVoiceRuntime(
        orchestrator = voiceOrchestrator,
        settingsProvider = { _settingsFlow.value },
        wakeWordEngine = wakeWordEngine,
    )

    /** Adds or updates an [ApiKeyEntry] in encrypted storage and refreshes [keyPool]. */
    fun saveApiKey(entry: ApiKeyEntry) {
        val normalized = normalizeApiKeyEntry(entry)
        val current = _keysFlow.value.toMutableList()
        val existingIdx = current.indexOfFirst { it.id == normalized.id }
        if (existingIdx >= 0) {
            current[existingIdx] = normalized
        } else {
            current.add(normalized)
        }
        val sorted = current.sortedBy { it.priority }
        vault.saveKeys(sorted)
        keyPool.replaceKeys(sorted)
        _keysFlow.value = keyPool.snapshot()
        syncIdleQuotaUiIfRecovered()
    }

    /** Deletes an [ApiKeyEntry] by [id] from encrypted storage and [keyPool]. */
    fun deleteApiKey(id: String) {
        val updated = _keysFlow.value.filterNot { it.id == id }
        vault.saveKeys(updated)
        keyPool.replaceKeys(updated)
        _keysFlow.value = keyPool.snapshot()
    }

    /** Syncs UI key status badges with the latest [keyPool] cooldown/health snapshot. */
    fun refreshKeyPoolSnapshot() {
        _keysFlow.value = keyPool.snapshot()
    }

    /** Clears all key cooldowns and restores Na Na out of tired mode when healthy paid keys exist. */
    fun reviveNaNaNow() {
        keyPool.resetAllCooldowns()
        val updated = keyPool.snapshot()
        vault.saveKeys(updated)
        _keysFlow.value = updated
        quotaStateMachine.onSessionEnded()

        if (voiceOrchestrator.uiState.value.loopState == VoiceLoopState.STOPPED) {
            voiceOrchestrator.markServiceStopped()
        } else {
            voiceOrchestrator.stopSession()
        }
    }

    /** Applies [transform] to [AppSettings] and persists the result to [vault]. */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settingsFlow.value)
        _settingsFlow.value = updated
        vault.saveSettings(updated)
    }

    /** Performs a lightweight live validation call for [entry] without exposing the raw key. */
    suspend fun testApiKey(entry: ApiKeyEntry): String {
        val normalized = normalizeApiKeyEntry(entry)
        if (normalized.apiKey.isBlank()) {
            return "Chưa nhập API key."
        }
        if (normalized.isTtsOnly || normalized.modelId.contains("tts", ignoreCase = true)) {
            return testTtsApiKey(normalized)
        }
        val probeRequest = LlmRequest(
            systemPrompt = "Trả lời ngắn gọn 1 từ: OK.",
            history = emptyList(),
            userText = "Ping",
            userAudio = null,
            enableTools = false,
        )
        val result = when (normalized.provider) {
            ProviderType.GEMINI_VERTEX_EXPRESS,
            ProviderType.GEMINI_AI_STUDIO,
            ProviderType.FREE_GEMINI,
            -> geminiClient.generate(normalized, probeRequest)

            ProviderType.OPENAI,
            ProviderType.FREE_OPENAI_COMPAT,
            -> openAiClient.generate(normalized, probeRequest)

            ProviderType.CLAUDE -> claudeClient.generate(normalized, probeRequest)
        }
        return when (result) {
            is LlmResult.Success -> "Hợp lệ (${providerDisplayName(normalized.provider)} · ${normalized.modelId})"
            is LlmResult.QuotaExceeded -> "Hết quota / giới hạn tốc độ (HTTP ${result.httpCode})"
            is LlmResult.InvalidKey -> "Key không hợp lệ hoặc sai quyền (HTTP ${result.httpCode})"
            is LlmResult.TransientError -> "Lỗi kết nối: ${result.detail}"
        }
    }

    private suspend fun testTtsApiKey(normalized: ApiKeyEntry): String {
        val ttsModel = normalized.modelId.ifBlank { DEFAULT_GEMINI_TTS_MODEL }
        val result = when (normalized.provider) {
            ProviderType.GEMINI_VERTEX_EXPRESS,
            ProviderType.GEMINI_AI_STUDIO,
            ProviderType.FREE_GEMINI,
            -> geminiClient.synthesizeSpeech(
                key = normalized,
                text = "Xin chào anh",
                styleInstruction = PersonaPromptBuilder.buildTtsStyleInstruction(PersonaMode.NANA),
                voiceName = _settingsFlow.value.preferredGeminiVoice,
                ttsModelId = ttsModel,
            )

            ProviderType.OPENAI,
            ProviderType.FREE_OPENAI_COMPAT,
            -> openAiClient.synthesizeSpeech(
                key = normalized,
                text = "Xin chào anh",
            )

            ProviderType.CLAUDE -> return "Claude không hỗ trợ endpoint TTS."
        }
        val displayedModel = if (normalized.provider in GEMINI_PROVIDERS) ttsModel else normalized.modelId.ifBlank { "tts-1" }
        return when (result) {
            is TtsResult.Success -> "Hợp lệ (TTS · ${providerDisplayName(normalized.provider)} · $displayedModel)"
            is TtsResult.QuotaExceeded -> "Hết quota / giới hạn tốc độ TTS: ${result.detail}"
            is TtsResult.Unsupported -> "Không hỗ trợ TTS: ${result.detail}"
            is TtsResult.Error -> "Lỗi kiểm tra TTS: ${result.detail}"
        }
    }

    /** Sends a typed chat turn through [voiceOrchestrator] and refreshes key pool status. */
    suspend fun sendTextChat(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        voiceOrchestrator.onUserUtterance(transcript = trimmed, wavBytes = null)
        if (!NaNaVoiceService.isRunning.value) {
            voiceOrchestrator.stopSession()
        }
        refreshKeyPoolSnapshot()
    }

    /** Scans the configured OriginOS `Record/Call` SAF tree for newly finalized call recordings. */
    suspend fun scanOriginOsRecordings(): Int {
        val folderUri = _settingsFlow.value.originOsRecordingFolderUri?.takeIf { it.isNotBlank() }
            ?: return 0
        return callRecordingWatcher.scanAndIngest(Uri.parse(folderUri))
    }

    /** Triggers an immediate Drive backup strictly over unmetered Wi-Fi. */
    suspend fun runManualWifiSync(): SyncOutcome = syncMutex.withLock {
        if (!wifiChecker.isUnmeteredWifiConnected()) {
            _lastSyncOutcome.value = SyncOutcome.PAUSED_NO_WIFI
            return@withLock SyncOutcome.PAUSED_NO_WIFI
        }
        _isSyncing.value = true
        try {
            val accessToken = authManager.getFreshAccessToken()
            if (accessToken.isNullOrBlank()) {
                _lastSyncOutcome.value = SyncOutcome.NEEDS_REAUTH
                return@withLock SyncOutcome.NEEDS_REAUTH
            }
            syncStorageGateway.resetStaleUploadingRecordings()
            val coordinator = WifiOnlySyncCoordinator(
                wifiChecker = wifiChecker,
                driveClient = driveClient,
                storage = syncStorageGateway,
            )
            val outcome = coordinator.syncNow(accessToken)
            if (outcome == SyncOutcome.NEEDS_REAUTH) {
                authManager.markNeedsReauth()
            }
            _lastSyncOutcome.value = outcome
            outcome
        } finally {
            _isSyncing.value = false
        }
    }

    /** Restores backed-up memories and chat history from Google Drive (`AI_TALK_NaNa/`). */
    suspend fun runRestore(allowMetered: Boolean = false): SyncOutcome = syncMutex.withLock {
        if (!allowMetered && !wifiChecker.isUnmeteredWifiConnected()) {
            _lastSyncOutcome.value = SyncOutcome.PAUSED_NO_WIFI
            return@withLock SyncOutcome.PAUSED_NO_WIFI
        }
        _isSyncing.value = true
        try {
            val accessToken = authManager.getFreshAccessToken()
            if (accessToken.isNullOrBlank()) {
                _lastSyncOutcome.value = SyncOutcome.NEEDS_REAUTH
                return@withLock SyncOutcome.NEEDS_REAUTH
            }
            val coordinator = DriveRestoreCoordinator(
                wifiChecker = wifiChecker,
                driveClient = driveClient,
                storage = syncStorageGateway,
            )
            val outcome = coordinator.restoreIfAvailable(
                accessToken = accessToken,
                allowMeteredOverride = allowMetered,
            )
            if (outcome == SyncOutcome.NEEDS_REAUTH) {
                authManager.markNeedsReauth()
            }
            _lastSyncOutcome.value = outcome
            outcome
        } finally {
            _isSyncing.value = false
        }
    }

    /** Generates and caches a short Vietnamese AI summary for a staged call recording. */
    suspend fun summarizeCallRecording(record: CallRecordingRecord): String? {
        val bytes = readRecordingBytesForSummary(record) ?: return null
        val prompt = LlmRequest(
            systemPrompt = "Bạn là trợ lý tóm tắt cuộc gọi. Trả lời bằng tiếng Việt súc tích, rõ ý chính.",
            history = emptyList(),
            userText = "Hãy nghe đoạn ghi âm cuộc gọi này và tóm tắt ngắn gọn nội dung chính trong tối đa 4 câu.",
            userAudio = AudioAttachment(bytes = bytes, mimeType = record.mimeType.ifBlank { "audio/mp4" }),
            enableTools = false,
        )
        val candidateKeys = keyPool.snapshot()
            .filter { entry ->
                entry.enabled &&
                    entry.status == KeyStatus.HEALTHY &&
                    !entry.isTtsOnly &&
                    entry.apiKey.isNotBlank() &&
                    entry.provider in GEMINI_PROVIDERS
            }
            .sortedWith(compareBy<ApiKeyEntry> { it.isFreeTier }.thenBy { it.priority })

        for (candidateKey in candidateKeys) {
            when (val result = geminiClient.generate(candidateKey, prompt)) {
                is LlmResult.Success -> {
                    val summary = result.replyText.trim().takeIf { it.isNotEmpty() }
                    if (summary != null) {
                        database.callRecordingDao().updateAiSummary(record.id, summary)
                        refreshKeyPoolSnapshot()
                        return summary
                    }
                }
                is LlmResult.QuotaExceeded -> {
                    keyPool.markChatQuotaExhausted(candidateKey.id)
                    refreshKeyPoolSnapshot()
                }
                is LlmResult.InvalidKey -> {
                    keyPool.markChatInvalid(candidateKey.id)
                    refreshKeyPoolSnapshot()
                }
                is LlmResult.TransientError -> Unit
            }
        }
        refreshKeyPoolSnapshot()
        return null
    }

    /** Adds a manual user [MemoryFact] and schedules Wi-Fi sync. */
    suspend fun addManualMemoryFact(category: MemoryCategory, key: String, value: String) {
        val cleanKey = key.trim()
        val cleanValue = value.trim()
        if (cleanKey.isEmpty() || cleanValue.isEmpty()) return
        val fact = MemoryFact(
            id = "mem_manual_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}",
            category = category,
            key = cleanKey,
            value = cleanValue,
            confidence = 1.0f,
            updatedAtMs = System.currentTimeMillis(),
            synced = false,
        )
        database.memoryFactDao().upsert(fact.toEntity())
        syncTrigger.enqueueWifiSync()
    }

    /** Updates an existing [MemoryFact] locally and marks it pending Wi-Fi sync. */
    suspend fun updateMemoryFact(fact: MemoryFact) {
        database.memoryFactDao().upsert(
            fact.copy(
                updatedAtMs = System.currentTimeMillis(),
                synced = false,
            ).toEntity(),
        )
        syncTrigger.enqueueWifiSync()
    }

    /** Deletes a [MemoryFact] by [id] from local storage. */
    suspend fun deleteMemoryFact(id: String) {
        database.memoryFactDao().deleteById(id)
    }

    private suspend fun readRecordingBytesForSummary(record: CallRecordingRecord): ByteArray? =
        withContext(Dispatchers.IO) {
            if (record.fileSizeBytes > MAX_SUMMARY_AUDIO_BYTES) {
                return@withContext null
            }
            val stagedFile = record.stagedPath?.takeIf { it.isNotBlank() }?.let(::File)
            if (stagedFile != null && stagedFile.exists() && stagedFile.length() in 1L..MAX_SUMMARY_AUDIO_BYTES) {
                return@withContext runCatching { stagedFile.readBytes() }.getOrNull()
            }
            val uri = record.sourceUri.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?: return@withContext null
            runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    bytes.takeIf { it.isNotEmpty() && it.size <= MAX_SUMMARY_AUDIO_BYTES }
                }
            }.getOrNull()
        }

    private fun normalizeApiKeyEntry(entry: ApiKeyEntry): ApiKeyEntry {
        val trimmedKey = entry.apiKey.trim()
        val resolvedProvider = when (entry.provider) {
            ProviderType.GEMINI_VERTEX_EXPRESS,
            ProviderType.GEMINI_AI_STUDIO,
            -> detectGeminiProvider(trimmedKey)

            else -> entry.provider
        }
        val resolvedFreeTier = when (resolvedProvider) {
            ProviderType.FREE_GEMINI,
            ProviderType.FREE_OPENAI_COMPAT,
            -> true

            else -> entry.isFreeTier
        }
        return entry.copy(
            label = entry.label.trim().ifEmpty { providerDisplayName(resolvedProvider) },
            provider = resolvedProvider,
            apiKey = trimmedKey,
            modelId = entry.modelId.trim(),
            isFreeTier = resolvedFreeTier,
        )
    }

    private fun syncIdleQuotaUiIfRecovered() {
        if (quotaStateMachine.state == QuotaState.ACTIVE_NANA) return
        if (!keyPool.hasAnyHealthyPaidChatKey()) return
        val loopState = voiceOrchestrator.uiState.value.loopState
        if (loopState == VoiceLoopState.IDLE_WAKE || loopState == VoiceLoopState.STOPPED) {
            quotaStateMachine.onSessionEnded()
            if (loopState == VoiceLoopState.STOPPED) {
                voiceOrchestrator.markServiceStopped()
            } else {
                voiceOrchestrator.stopSession()
            }
        }
    }

    private fun stripWavHeaderIfPresent(audioBytes: ByteArray): ByteArray =
        if (
            audioBytes.size > 44 &&
            audioBytes[0] == 'R'.code.toByte() &&
            audioBytes[1] == 'I'.code.toByte() &&
            audioBytes[2] == 'F'.code.toByte() &&
            audioBytes[3] == 'F'.code.toByte()
        ) {
            audioBytes.copyOfRange(44, audioBytes.size)
        } else {
            audioBytes
        }

    companion object {
        private const val MAX_SUMMARY_AUDIO_BYTES = 12L * 1024L * 1024L
        private const val DEFAULT_GEMINI_TTS_MODEL = "gemini-2.5-flash-preview-tts"
        private const val FALLBACK_VAULT_PREFS = "nana_robolectric_vault"
        private val GEMINI_PROVIDERS = setOf(
            ProviderType.GEMINI_VERTEX_EXPRESS,
            ProviderType.GEMINI_AI_STUDIO,
            ProviderType.FREE_GEMINI,
        )

        fun providerDisplayName(provider: ProviderType): String = when (provider) {
            ProviderType.GEMINI_VERTEX_EXPRESS -> "Gemini Vertex (AQ.)"
            ProviderType.GEMINI_AI_STUDIO -> "Gemini AI Studio"
            ProviderType.CLAUDE -> "Claude"
            ProviderType.OPENAI -> "ChatGPT (OpenAI)"
            ProviderType.FREE_GEMINI -> "Gemini Free"
            ProviderType.FREE_OPENAI_COMPAT -> "OpenAI-Compat Free"
        }

        internal fun createVaultSafely(
            appContext: Context,
            fingerprint: String = Build.FINGERPRINT,
        ): EncryptedApiKeyVault {
            if ("robolectric".equals(fingerprint, ignoreCase = true)) {
                return EncryptedApiKeyVault(
                    appContext.getSharedPreferences(FALLBACK_VAULT_PREFS, Context.MODE_PRIVATE),
                )
            }
            return EncryptedApiKeyVault.create(appContext)
        }
    }
}
