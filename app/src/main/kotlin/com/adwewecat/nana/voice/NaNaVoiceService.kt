package com.adwewecat.nana.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.adwewecat.nana.R
import com.adwewecat.nana.core.ai.AiChatRouter
import com.adwewecat.nana.core.ai.ClaudeClient
import com.adwewecat.nana.core.ai.GeminiClient
import com.adwewecat.nana.core.ai.OpenAiClient
import com.adwewecat.nana.core.ai.TtsRouter
import com.adwewecat.nana.core.ai.VietnameseWebTtsClient
import com.adwewecat.nana.core.model.ActionExecutionResult
import com.adwewecat.nana.core.model.DeviceActionExecutor
import com.adwewecat.nana.core.model.DeviceCommand
import com.adwewecat.nana.core.quota.KeyPoolManager
import com.adwewecat.nana.core.quota.NaNaQuotaStateMachine
import com.adwewecat.nana.core.voice.EnergyVad
import com.adwewecat.nana.core.voice.FastCommandParser
import com.adwewecat.nana.core.voice.PcmWavEncoder
import com.adwewecat.nana.core.voice.VadEvent
import com.adwewecat.nana.data.local.AppSettings
import com.adwewecat.nana.data.local.EncryptedApiKeyVault
import com.adwewecat.nana.data.local.NaNaDatabase
import com.adwewecat.nana.data.sync.DriveSyncScheduler
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/** Runtime bundle supplied by [NaNaVoiceRuntimeProvider] (e.g. `NaNaApplication` in Task 10). */
data class NaNaVoiceRuntime(
    val orchestrator: NaNaVoiceOrchestrator,
    val settingsProvider: () -> AppSettings,
    val wakeWordEngine: WakeWordEngine? = null,
)

/** Implemented by `Application` (or tests) to inject shared voice runtime dependencies. */
fun interface NaNaVoiceRuntimeProvider {
    fun provideVoiceRuntime(): NaNaVoiceRuntime
}

/**
 * Android 15/16 compliant microphone Foreground Service hosting Na Na's continuous voice loop.
 * Requests `TYPE_BUILTIN_MIC` for capture to keep Bluetooth headsets in stereo A2DP, gates
 * optional `PARTIAL_WAKE_LOCK` behind `AppSettings.screenOffWakeLock`, and never starts from `BOOT_COMPLETED`.
 */
class NaNaVoiceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var runtime: NaNaVoiceRuntime? = null
    private var ownedFallbackTts: AndroidSystemTtsEngine? = null
    private var wakeWordEngine: WakeWordEngine? = null
    private var activeLoopJob: Job? = null
    private var uiObserverJob: Job? = null
    private var wakeLockRenewalJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val finishCaptureRequested = AtomicBoolean(false)
    private val pauseForTextChatRequested = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()
        val resolved = resolveRuntime()
        runtime = resolved
        wakeWordEngine = resolved.wakeWordEngine ?: VoskWakeWordEngine(this)
        observeUiForNotifications(resolved.orchestrator)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        if (action == ACTION_STOP) {
            stopVoiceService()
            return START_NOT_STICKY
        }

        val currentRuntime = runtime ?: resolveRuntime().also {
            runtime = it
            wakeWordEngine = it.wakeWordEngine ?: VoskWakeWordEngine(this)
            observeUiForNotifications(it.orchestrator)
        }

        if (!startForegroundWithMicType(currentRuntime.orchestrator.uiState.value.statusMessage)) {
            currentRuntime.orchestrator.reportStatus("Không thể bật dịch vụ Micro — hãy cấp quyền Micro cho Na Na.")
            stopSelf()
            return START_NOT_STICKY
        }

        _isRunning.value = true
        syncWakeLockWithSettings(currentRuntime.settingsProvider())
        currentRuntime.orchestrator.precacheWakeGreetingIfNeeded()

        when (action) {
            ACTION_PUSH_TO_TALK -> triggerPushToTalk(currentRuntime)
            else -> {
                if (activeLoopJob?.isActive != true) {
                    currentRuntime.orchestrator.markServiceReady()
                    ensurePassiveWakeWord(currentRuntime)
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep foreground wake-word service alive when user swipes the UI card out of Recents.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        _isRunning.value = false
        finishCaptureRequested.set(false)
        pauseForTextChatRequested.set(true)
        activeLoopJob?.cancel()
        uiObserverJob?.cancel()
        wakeWordEngine?.stop()
        releaseWakeLock()
        runtime?.orchestrator?.markServiceStopped()
        ownedFallbackTts?.shutdown()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopVoiceService() {
        _isRunning.value = false
        activeLoopJob?.cancel()
        wakeWordEngine?.stop()
        releaseWakeLock()
        runtime?.orchestrator?.markServiceStopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundWithMicType(statusText: String): Boolean {
        if (!BuiltInMicAudioInput.hasRecordAudioPermission(this)) {
            return false
        }
        val notification = buildNotification(statusText)
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }

    private fun observeUiForNotifications(orchestrator: NaNaVoiceOrchestrator) {
        uiObserverJob?.cancel()
        uiObserverJob = serviceScope.launch {
            orchestrator.uiState.collectLatest { snapshot ->
                if (_isRunning.value) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.notify(NOTIFICATION_ID, buildNotification(snapshot.statusMessage))
                }
            }
        }
    }

    private fun ensurePassiveWakeWord(currentRuntime: NaNaVoiceRuntime) {
        if (!_isRunning.value) return
        val settings = currentRuntime.settingsProvider()
        syncWakeLockWithSettings(settings)
        if (!settings.wakeWordEnabled) {
            wakeWordEngine?.stop()
            return
        }
        if (currentRuntime.orchestrator.uiState.value.loopState != VoiceLoopState.IDLE_WAKE) {
            return
        }
        val engine = wakeWordEngine ?: return
        if (!engine.isModelAvailable() && VoskModelInstaller.hasBundledAssets(this)) {
            serviceScope.launch {
                val installed = VoskModelInstaller.ensureInstalled(this@NaNaVoiceService)
                if (installed && _isRunning.value) {
                    ensurePassiveWakeWord(currentRuntime)
                }
            }
            return
        }
        engine.start(
            onWakeDetected = {
                serviceScope.launch {
                    if (_isRunning.value) {
                        triggerWakeAndConversation(currentRuntime)
                    }
                }
            },
            onMicLevel = { level ->
                currentRuntime.orchestrator.updateMicLevel(level)
            },
        )
    }

    private fun triggerPushToTalk(currentRuntime: NaNaVoiceRuntime) {
        val currentLoopState = currentRuntime.orchestrator.uiState.value.loopState
        // If already capturing speech, tapping Mic/Orb again finishes capture immediately and sends it!
        if (activeLoopJob?.isActive == true &&
            (currentLoopState == VoiceLoopState.LISTENING || currentLoopState == VoiceLoopState.AWAITING_CONSENT)
        ) {
            finishCaptureRequested.set(true)
            return
        }

        wakeWordEngine?.stop()
        finishCaptureRequested.set(false)
        pauseForTextChatRequested.set(false)
        val previousJob = activeLoopJob
        activeLoopJob = serviceScope.launch {
            previousJob?.cancelAndJoin()
            val state = currentRuntime.orchestrator.uiState.value.loopState
            if (state != VoiceLoopState.LISTENING && state != VoiceLoopState.AWAITING_CONSENT) {
                if (state != VoiceLoopState.IDLE_WAKE && state != VoiceLoopState.STOPPED) {
                    currentRuntime.orchestrator.stopSession()
                }
                currentRuntime.orchestrator.onWakeWordDetected()
            }
            playListeningBeep()
            runActiveTurnLoop(currentRuntime)
        }
    }

    private fun triggerWakeAndConversation(currentRuntime: NaNaVoiceRuntime) {
        if (activeLoopJob?.isActive == true) {
            return
        }
        wakeWordEngine?.stop()
        finishCaptureRequested.set(false)
        pauseForTextChatRequested.set(false)
        activeLoopJob = serviceScope.launch {
            currentRuntime.orchestrator.onWakeWordDetected()
            // Speak an immediate, pre-cached "Dạ em nghe!" acknowledgement when woken hands-free
            // so the user hears Na Na answer right away even with the screen off or in another app.
            if (currentRuntime.orchestrator.uiState.value.loopState == VoiceLoopState.LISTENING) {
                currentRuntime.orchestrator.playInstantWakeGreeting()
            }
            runActiveTurnLoop(currentRuntime)
        }
    }

    private fun pauseCaptureForTextChatInternal() {
        pauseForTextChatRequested.set(true)
        finishCaptureRequested.set(false)
        wakeWordEngine?.stop()
        activeLoopJob?.cancel()
        activeLoopJob = null
    }

    private fun notifyWakeModelReadyInternal() {
        val currentRuntime = runtime ?: return
        if (_isRunning.value && activeLoopJob?.isActive != true) {
            ensurePassiveWakeWord(currentRuntime)
        }
    }

    private fun playListeningBeep() {
        if ("robolectric".equals(Build.FINGERPRINT, ignoreCase = true)) return
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 65)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 75)
            serviceScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(180L)
                runCatching { tone.release() }
            }
        }
    }

    private suspend fun runActiveTurnLoop(currentRuntime: NaNaVoiceRuntime) {
        val orchestrator = currentRuntime.orchestrator
        while (serviceScope.isActive && _isRunning.value) {
            val snapshot = orchestrator.uiState.value
            val loopState = snapshot.loopState
            if (loopState != VoiceLoopState.LISTENING && loopState != VoiceLoopState.AWAITING_CONSENT) {
                break
            }

            finishCaptureRequested.set(false)
            pauseForTextChatRequested.set(false)
            val settings = currentRuntime.settingsProvider()
            val timeoutMs = settings.silenceTimeoutSec.coerceIn(5, 60) * 1_000L
            val captureResult = captureSingleUtterancePcm(
                maxWaitForSpeechMs = timeoutMs,
                onMicLevel = { orchestrator.updateMicLevel(it) },
            )

            if (!serviceScope.isActive || !_isRunning.value || pauseForTextChatRequested.get()) {
                break
            }

            if (captureResult.micOpenFailed) {
                orchestrator.stopSession()
                orchestrator.reportStatus("Không mở được micro — hãy kiểm tra quyền Micro cho Na Na.")
                break
            }

            val capturedPcm = captureResult.pcmBytes
            if (capturedPcm == null || capturedPcm.isEmpty()) {
                if (loopState == VoiceLoopState.AWAITING_CONSENT) {
                    orchestrator.onConsentTimeout()
                } else {
                    orchestrator.stopSession()
                }
                break
            }

            val wavBytes = PcmWavEncoder.encodeMono16Bit(capturedPcm, BuiltInMicAudioInput.SAMPLE_RATE_HZ)
            val offlineTranscript = withContext(Dispatchers.Default) {
                when (loopState) {
                    VoiceLoopState.AWAITING_CONSENT ->
                        VoskWakeWordEngine.recognizeConsentFromPcm(this@NaNaVoiceService, capturedPcm)

                    VoiceLoopState.LISTENING ->
                        VoskWakeWordEngine.recognizeLocalCommandFromPcm(this@NaNaVoiceService, capturedPcm)
                            ?.takeIf { FastCommandParser.parseLocalCommand(it) != null }

                    else -> null
                }
            }
            orchestrator.onUserUtterance(transcript = offlineTranscript, wavBytes = wavBytes)
        }

        if (_isRunning.value && orchestrator.uiState.value.loopState == VoiceLoopState.IDLE_WAKE) {
            ensurePassiveWakeWord(currentRuntime)
        }
    }

    private data class CaptureResult(
        val pcmBytes: ByteArray?,
        val micOpenFailed: Boolean = false,
    )

    private suspend fun captureSingleUtterancePcm(
        maxWaitForSpeechMs: Long,
        onMicLevel: (Float) -> Unit,
    ): CaptureResult = withContext(Dispatchers.IO) {
        val audioRecord = BuiltInMicAudioInput.openBuiltInMicAudioRecord(this@NaNaVoiceService)
            ?: return@withContext CaptureResult(pcmBytes = null, micOpenFailed = true)

        try {
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return@withContext CaptureResult(pcmBytes = null, micOpenFailed = true)
            }

            val vad = EnergyVad(
                speechRmsThreshold = 180.0,
                silenceDurationMs = 1_500L,
                maxUtteranceMs = 20_000L,
            )
            val pcmBuffer = ByteArrayOutputStream()
            val fallbackBuffer = ByteArrayOutputStream()
            val frame = ShortArray(CAPTURE_FRAME_SAMPLES)
            var speechStarted = false
            var idleElapsedMs = 0L
            var preRollBytes: ByteArray? = null

            while (isActive && !pauseForTextChatRequested.get()) {
                val readCount = audioRecord.read(frame, 0, frame.size)
                if (readCount <= 0) {
                    if (finishCaptureRequested.get()) break
                    continue
                }

                val rms = vad.computeRms(frame, readCount)
                onMicLevel(BuiltInMicAudioInput.computeNormalizedMicLevel(rms))

                val frameDurationMs = (readCount * 1_000L) / BuiltInMicAudioInput.SAMPLE_RATE_HZ
                val chunkBytes = BuiltInMicAudioInput.shortsToLittleEndianPcmBytes(frame, readCount)
                if (fallbackBuffer.size() < MAX_FALLBACK_PCM_BYTES) {
                    fallbackBuffer.write(chunkBytes)
                }

                when (vad.feedFrame(if (readCount == frame.size) frame else frame.copyOf(readCount), frameDurationMs)) {
                    VadEvent.IDLE -> {
                        if (!speechStarted) {
                            preRollBytes = chunkBytes
                            idleElapsedMs += frameDurationMs
                            if (idleElapsedMs >= maxWaitForSpeechMs) {
                                return@withContext CaptureResult(pcmBytes = null)
                            }
                        }
                    }

                    VadEvent.SPEECH_STARTED -> {
                        speechStarted = true
                        preRollBytes?.let { pcmBuffer.write(it) }
                        preRollBytes = null
                        pcmBuffer.write(chunkBytes)
                    }

                    VadEvent.SPEECH_ACTIVE -> {
                        pcmBuffer.write(chunkBytes)
                    }

                    VadEvent.UTTERANCE_COMPLETE -> {
                        pcmBuffer.write(chunkBytes)
                        return@withContext CaptureResult(pcmBytes = pcmBuffer.toByteArray())
                    }
                }

                if (finishCaptureRequested.get()) {
                    break
                }
            }

            if (pauseForTextChatRequested.get()) {
                return@withContext CaptureResult(pcmBytes = null)
            }
            val manualBytes = when {
                speechStarted && pcmBuffer.size() > 0 -> pcmBuffer.toByteArray()
                finishCaptureRequested.get() && fallbackBuffer.size() >= MIN_MANUAL_SEND_PCM_BYTES ->
                    fallbackBuffer.toByteArray()
                else -> null
            }
            CaptureResult(pcmBytes = manualBytes)
        } finally {
            onMicLevel(0f)
            runCatching {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop()
                }
                audioRecord.release()
            }
        }
    }

    private fun syncWakeLockWithSettings(settings: AppSettings) {
        if (settings.screenOffWakeLock) {
            acquireOrRenewWakeLock()
            if (wakeLockRenewalJob?.isActive != true) {
                wakeLockRenewalJob = serviceScope.launch {
                    while (isActive && _isRunning.value) {
                        kotlinx.coroutines.delay(WAKE_LOCK_RENEW_INTERVAL_MS)
                        if (runtime?.settingsProvider?.invoke()?.screenOffWakeLock == true) {
                            acquireOrRenewWakeLock()
                        } else {
                            releaseWakeLock()
                            break
                        }
                    }
                }
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun acquireOrRenewWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val lock = wakeLock ?: pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG,
        ).apply {
            setReferenceCounted(false)
        }.also { wakeLock = it }
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = null
        runCatching {
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                }
            }
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_voice_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_voice_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val talkPendingIntent = PendingIntent.getService(
            this,
            REQUEST_PUSH_TO_TALK,
            Intent(this, NaNaVoiceService::class.java).setAction(ACTION_PUSH_TO_TALK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, NaNaVoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .apply {
                if (contentPendingIntent != null) {
                    setContentIntent(contentPendingIntent)
                }
            }
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                getString(R.string.voice_action_talk),
                talkPendingIntent,
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.voice_action_stop),
                stopPendingIntent,
            )
            .build()
    }

    private fun resolveRuntime(): NaNaVoiceRuntime {
        val provider = applicationContext as? NaNaVoiceRuntimeProvider
        if (provider != null) {
            return provider.provideVoiceRuntime()
        }
        return createDefaultRuntime()
    }

    private fun createDefaultRuntime(): NaNaVoiceRuntime {
        val appContext = applicationContext
        val vault = runCatching { EncryptedApiKeyVault.create(appContext) }.getOrNull()
        val settingsProvider: () -> AppSettings = { vault?.loadSettings() ?: AppSettings() }
        val keyPool = KeyPoolManager(vault?.loadKeys().orEmpty())
        val stateMachine = NaNaQuotaStateMachine(keyPool)
        val httpClient = OkHttpClient()
        val geminiClient = GeminiClient(httpClient)
        val openAiClient = OpenAiClient(httpClient)
        val claudeClient = ClaudeClient(httpClient)
        val systemTts = AndroidSystemTtsEngine(appContext).also { ownedFallbackTts = it }
        val localTts = VietnameseFallbackTts(
            context = appContext,
            webTtsClient = VietnameseWebTtsClient(httpClient),
            systemTts = systemTts,
        )
        val db = NaNaDatabase.getInstance(appContext)
        val scheduler = DriveSyncScheduler(appContext)
        val fallbackExecutor = object : DeviceActionExecutor {
            override suspend fun execute(command: DeviceCommand): ActionExecutionResult =
                ActionExecutionResult(
                    succeeded = false,
                    spokenFeedback = "Trình điều khiển thiết bị chưa được kết nối.",
                )
        }

        val orchestrator = NaNaVoiceOrchestrator(
            stateMachine = stateMachine,
            aiChatRouter = AiChatRouter(keyPool, stateMachine, geminiClient, openAiClient, claudeClient),
            ttsRouter = TtsRouter(keyPool, geminiClient, openAiClient, localTts),
            localFallbackTts = localTts,
            actionExecutor = fallbackExecutor,
            audioPlayer = AndroidAudioPlayer(appContext),
            messageDao = db.messageDao(),
            memoryFactDao = db.memoryFactDao(),
            syncTrigger = SyncTrigger { scheduler.enqueueSync() },
            settingsProvider = settingsProvider,
            offlineConsentTranscriber = { audioBytes ->
                val rawPcm = if (
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
                VoskWakeWordEngine.recognizeConsentFromPcm(appContext, rawPcm)
            },
            backgroundScope = serviceScope,
        )
        return NaNaVoiceRuntime(
            orchestrator = orchestrator,
            settingsProvider = settingsProvider,
            wakeWordEngine = VoskWakeWordEngine(appContext),
        )
    }

    companion object {
        const val ACTION_START = "com.adwewecat.nana.voice.action.START"
        const val ACTION_STOP = "com.adwewecat.nana.voice.action.STOP"
        const val ACTION_PUSH_TO_TALK = "com.adwewecat.nana.voice.action.PUSH_TO_TALK"

        private const val CHANNEL_ID = "nana_voice_channel"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_OPEN_APP = 10
        private const val REQUEST_PUSH_TO_TALK = 11
        private const val REQUEST_STOP = 12
        private const val CAPTURE_FRAME_SAMPLES = 1_600
        private const val MAX_FALLBACK_PCM_BYTES = 16_000 * 2 * 15
        private const val MIN_MANUAL_SEND_PCM_BYTES = 16_000 * 2 / 4
        private const val WAKE_LOCK_TAG = "NaNa:VoiceWakeLock"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1_000L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 20 * 60 * 1_000L

        @Volatile
        private var activeInstance: NaNaVoiceService? = null

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        /** Pauses active microphone capture so typed chat replies speak without audio focus conflict. */
        fun pauseActiveCapture() {
            activeInstance?.pauseCaptureForTextChatInternal()
        }

        /** Restarts passive wake-word listening after offline Vosk assets finish extracting. */
        fun notifyWakeModelReady() {
            activeInstance?.notifyWakeModelReadyInternal()
        }
    }
}
