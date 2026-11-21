# Na Na (`AI_TALK`) Android Voice Assistant & Companion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the complete native Android app **Na Na (`com.adwewecat.nana`)** for iQOO Z10 Turbo Pro (OriginOS 6 / Android 16) featuring offline `"Na Na"` wake-word activation, multi-key Gemini (`AQ.` + `AIza`) / Claude / ChatGPT failover with the **"Na Na → Em gái"** voice handoff, decoupled cute female TTS, YouTube search + auto-play, 3-tier SIM + VoIP call answering, OriginOS call recording watcher + in-app fallback, and strictly Wi-Fi-only Google Drive sync & reinstall restore.

**Architecture:** Two-module Gradle Kotlin project — `:core` (pure JVM library holding domain models, failover/quota state machine, OkHttp LLM/TTS/Google Drive clients, Vietnamese command parser, WAV/VAD framing, and Wi-Fi-only sync/retention logic for fast sub-second JVM TDD) and `:app` (Android application with Jetpack Compose Material 3 dark-first UI, Room SQLite, Android Keystore `EncryptedSharedPreferences`, AppAuth PKCE OAuth, WorkManager `NetworkType.UNMETERED`, Foreground wake-word service, `NotificationListenerService`, `AccessibilityService`, and OriginOS call recording watcher).

**Tech Stack:** Kotlin `2.1.0`, Gradle `8.9`, AGP `8.7.3`, OpenJDK `17`, Jetpack Compose BOM `2024.12.01`, Material 3, Room `2.7.1` (KSP `2.1.0-1.0.29`), WorkManager `2.10.0`, OkHttp `4.12.0`, Kotlinx Serialization `1.7.3`, Coroutines `1.9.0`, AppAuth-Android `0.11.1`, AndroidX Security Crypto `1.1.0-alpha06`, Vosk Android `0.3.75`, JUnit 4 + MockWebServer + Robolectric `4.14.1`.

**Spec:** `docs/superpowers/specs/2026-11-21-nana-android-assistant-design.md`

---

## Global Constraints

- **Application ID:** Fixed permanently to `com.adwewecat.nana` (`minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`, Java/Kotlin target `17`).
- **Zero Hardcoded Secrets:** Never commit real Gemini/OpenAI/Claude API keys, Google OAuth Client IDs/secrets, or release keystores to Git. Inject `GOOGLE_OAUTH_CLIENT_ID` via gitignored `local.properties` / GitHub Secrets with fallback placeholder `com.adwewecat.nana.oauth`.
- **Vertex AI Express vs AI Studio Routing:** Keys starting with `AQ.` MUST route to `https://aiplatform.googleapis.com/v1/publishers/google/models/{model}:generateContent` with header `x-goog-api-key`. Keys starting with `AIza` route to `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` with header `x-goog-api-key`.
- **Decoupled Chat vs TTS Health:** TTS errors (`400`/`404`/`429`) MUST NEVER mark a healthy chat key as `COOLDOWN` or trigger `"Em mệt rồi"`.
- **Exact Vietnamese Copy:**
  - Quota exhausted notice: `"Em đã hết lưu lượng rồi anh ơi, em chuyển qua trạng thái bình thường đây"` or `"Em mệt rồi, em nghỉ ngơi xíu nhé..."`.
  - Subsequent wake prompt while all paid chat keys remain in cooldown: `"Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?"`.
- **Strict Wi-Fi-Only Sync:** Drive uploads/sync require `NetworkType.UNMETERED` AND runtime `TRANSPORT_WIFI` + `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_NOT_METERED`, aborting immediately if Wi-Fi drops.
- **Preserve OriginOS Native Dialer:** NEVER register `InCallService` or request `ROLE_DIALER`; keep Vivo/iQOO stock dialer intact so `Record/Call` 2-way recording continues working.
- **Bluetooth Audio Protection:** Passive wake-word recording uses `TYPE_BUILTIN_MIC` so Bluetooth A2DP stereo music is never forced into mono HFP.

## Review Focus

1. **Vertex AI Express `AQ.` key rejects `gemini-2.5-flash-preview-tts` (`400`/`404`) while chat works** → `TtsRouter` marks only TTS unsupported on that key, falls through to OpenAI/local fallback, and leaves chat key `HEALTHY` (tested in **Task 4**).
2. **Wi-Fi drops mid-resumable upload of a large call recording** → `GoogleDriveClient` / `WifiOnlySyncCoordinator` aborts before the next chunk, retains staged local file and resume cursor (`PENDING`), and resumes cleanly when Wi-Fi returns (tested in **Task 6**).
3. **Vietnamese affirmative/negative phrasing during Em gái consent** → `"ok"`, `"ô kê"`, `"ừ"`, `"ok em ơi"` switch to `ACTIVE_LITTLE_SISTER`; `"không"`, `"thôi"`, `"không ok"` decline safely back to `IDLE_WAKE` (tested in **Task 2**).
4. **OriginOS call recording file still being written (0-byte) or rescanned** → `CallRecordingIngestionCoordinator` skips 0-byte/unstable files and deduplicates by `(sourceUri, fileSizeBytes)` so recordings are never corrupted or double-uploaded (tested in **Task 9**).
5. **Reinstall restore with duplicate rows or OAuth `invalid_grant` (7-day Testing token expiry)** → `DriveRestoreCoordinator` merges idempotently by ID without duplicates, and `invalid_grant` sets `needsReauth = true` without deleting unsynced local messages or recordings (tested in **Task 6** & **Task 7**).

---

## File Map

- **Build & CI:** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `local.properties.example`, `.github/workflows/android-build.yml`, `SETUP_ANDROID.md`
- **`:core` module (`core/src/main/kotlin/com/adwewecat/nana/core/`):**
  - `model/`: `Models.kt`
  - `quota/`: `KeyPoolManager.kt`, `NaNaQuotaStateMachine.kt`, `VietnameseConsentParser.kt`
  - `ai/`: `PersonaPromptBuilder.kt`, `LlmProviderClient.kt`, `GeminiClient.kt`, `OpenAiClient.kt`, `ClaudeClient.kt`, `AiChatRouter.kt`, `TtsRouter.kt`, `MemoryExtractor.kt`
  - `voice/`: `FastCommandParser.kt`, `PcmWavEncoder.kt`, `EnergyVad.kt`
  - `sync/`: `WifiNetworkChecker.kt`, `GoogleDriveClient.kt`, `WifiOnlySyncCoordinator.kt`, `DriveRestoreCoordinator.kt`, `CallRecordingRetentionPolicy.kt`
- **`:app` module (`app/src/main/`):**
  - `AndroidManifest.xml`, `res/xml/accessibility_service_config.xml`, `res/xml/voice_interaction_service.xml`, `res/values/*`
  - `kotlin/com/adwewecat/nana/`:
    - `NaNaApplication.kt`, `di/AppContainer.kt`, `MainActivity.kt`
    - `data/local/`: `NaNaDatabase.kt`, `Entities.kt`, `Daos.kt`, `EncryptedApiKeyVault.kt`
    - `data/auth/`: `GoogleDriveAuthManager.kt`
    - `data/sync/`: `AndroidWifiNetworkChecker.kt`, `DriveSyncWorker.kt`, `DriveSyncScheduler.kt`
    - `voice/`: `WakeWordEngine.kt`, `VoskWakeWordEngine.kt`, `AndroidAudioPlayer.kt`, `AndroidSystemTtsEngine.kt`, `NaNaVoiceOrchestrator.kt`, `NaNaVoiceService.kt`, `NaNaQuickSettingsTile.kt`, `NaNaVoiceInteractionService.kt`
    - `actions/`: `NaNaNotificationListener.kt`, `NaNaAccessibilityService.kt`, `AndroidDeviceActionExecutor.kt`, `OriginOsCallRecordingWatcher.kt`, `InAppCallRecorder.kt`, `CallStateMonitor.kt`
    - `ui/`: `theme/NaNaTheme.kt`, `NaNaApp.kt`, `home/CompanionHomeScreen.kt`, `recordings/CallRecordingsScreen.kt`, `memory/MemoryHistoryScreen.kt`, `settings/SettingsScreen.kt`

---

### Task 1: Android SDK Bootstrap & Multi-Module Gradle Scaffold (`:core` + `:app`)

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `gradlew`, `gradlew.bat`, `local.properties.example`
- Create: `core/build.gradle.kts`, `core/src/main/kotlin/com/adwewecat/nana/core/model/Models.kt`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`
- Modify: `.gitignore` (ensure Android build outputs, `local.properties`, `*.jks`, `.gradle/` are ignored)
- Test: `core/src/test/kotlin/com/adwewecat/nana/core/model/ModelsSmokeTest.kt`

**Interfaces:**
- Produces in `com.adwewecat.nana.core.model`:
  - `enum class ProviderType { GEMINI_VERTEX_EXPRESS, GEMINI_AI_STUDIO, CLAUDE, OPENAI, FREE_GEMINI, FREE_OPENAI_COMPAT }`
  - `enum class KeyStatus { HEALTHY, COOLDOWN, INVALID }`
  - `enum class PersonaMode { NANA, LITTLE_SISTER }`
  - `enum class QuotaState { ACTIVE_NANA, NANA_TIRED, NANA_TIRED_AWAITING_CONSENT, ACTIVE_LITTLE_SISTER }`
  - `enum class SyncStatus { PENDING, UPLOADING, SYNCED, FAILED }`
  - `enum class RecordingSource { ORIGINOS_NATIVE, IN_APP }`
  - `enum class MemoryCategory { PREFERENCE, HABIT, RELATIONSHIP, SCHEDULE, EMOTION, FACT }`
  - `data class ApiKeyEntry(val id: String, val label: String, val provider: ProviderType, val apiKey: String, val modelId: String, val priority: Int, val isFreeTier: Boolean, val isTtsOnly: Boolean = false, val enabled: Boolean = true, val status: KeyStatus = KeyStatus.HEALTHY, val cooldownUntilMs: Long = 0L, val ttsSupported: Boolean = true)`
  - `data class ChatMessage(val id: String, val sessionId: String, val role: String, val persona: PersonaMode, val content: String, val createdAtMs: Long, val synced: Boolean = false)`
  - `data class MemoryFact(val id: String, val category: MemoryCategory, val key: String, val value: String, val confidence: Float, val updatedAtMs: Long, val synced: Boolean = false)`
  - `data class CallRecordingRecord(val id: String, val phoneNumber: String, val contactName: String, val source: RecordingSource, val startedAtMs: Long, val durationSec: Int, val sourceUri: String, val stagedPath: String?, val fileSizeBytes: Long, val mimeType: String, val driveFileId: String? = null, val resumeSessionUri: String? = null, val uploadedBytes: Long = 0L, val syncStatus: SyncStatus = SyncStatus.PENDING, val syncedAtMs: Long? = null, val aiSummary: String? = null)`
  - `sealed class DeviceCommand` (`OpenYouTubeSearch`, `AnswerIncomingCall`, `EndCall`, `ToggleSpeaker`, `MakePhoneCall`, `OpenApp`, `SetAlarm`, `SetTimer`, `AdjustVolume`, `ToggleFlashlight`, `WebSearch`, `StopSession`)
  - `fun detectGeminiProvider(apiKey: String): ProviderType` (`AQ.` -> `GEMINI_VERTEX_EXPRESS`, else `GEMINI_AI_STUDIO`)

- [ ] **Step 1: Ensure Android SDK Command-Line Tools (`platforms;android-35`, `build-tools;35.0.0`) & Gradle `8.9` wrapper exist on the workstation**
  Install Android cmdline-tools into `%LOCALAPPDATA%\Android\Sdk` (outside the git repo) if absent, configure gitignored `local.properties` pointing to it, and generate Gradle `8.9` wrapper files.

- [ ] **Step 2: Create Gradle build files, `gradle/libs.versions.toml`, `app/src/main/AndroidManifest.xml`, and `local.properties.example`**
  Set `applicationId = "com.adwewecat.nana"`, `minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`, Java/Kotlin `17`, and read optional `GOOGLE_OAUTH_CLIENT_ID` from `local.properties` / env into `manifestPlaceholders["appAuthRedirectScheme"]` (default `"com.adwewecat.nana.oauth"`) and `BuildConfig.GOOGLE_OAUTH_CLIENT_ID`. Update `.gitignore` for `.gradle/`, `local.properties`, `*.jks`, `app/build/`, `core/build/`.

- [ ] **Step 3: Write failing test `ModelsSmokeTest`**
  Assert `detectGeminiProvider("AQ.example123") == ProviderType.GEMINI_VERTEX_EXPRESS` and `detectGeminiProvider("AIzaExample123") == ProviderType.GEMINI_AI_STUDIO`, plus JSON round-trip serialization of `ChatMessage`, `MemoryFact`, and `CallRecordingRecord`.

- [ ] **Step 4: Run test to verify it fails**
  Run: `.\gradlew.bat :core:test --tests "*ModelsSmokeTest*"`
  Expected: FAIL (compile error — `Models.kt` not defined yet).

- [ ] **Step 5: Implement `core/src/main/kotlin/com/adwewecat/nana/core/model/Models.kt`**
  Implement all `@Serializable` domain models and `detectGeminiProvider(apiKey: String): ProviderType`.

- [ ] **Step 6: Verify tests pass and `:app:compileDebugKotlin` compiles**
  Run: `.\gradlew.bat :core:test :app:compileDebugKotlin`
  Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**
  `git add` scaffold files (never `local.properties`) and commit `"chore: scaffold Android Kotlin Gradle project (:core and :app) with domain models"`.

---

### Task 2: Multi-Key Pool, Quota State Machine & "Na Na → Em gái" Voice Consent Parser (`:core`)

**Files:**
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/quota/VietnameseConsentParser.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/quota/KeyPoolManager.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/quota/NaNaQuotaStateMachine.kt`
- Test: `core/src/test/kotlin/com/adwewecat/nana/core/quota/NaNaQuotaStateMachineTest.kt`

**Interfaces:**
- Consumes: `ApiKeyEntry`, `KeyStatus`, `PersonaMode`, `QuotaState`, `ProviderType` from Task 1.
- Produces:
  - `enum class ConsentDecision { AFFIRMATIVE, NEGATIVE, UNKNOWN }`
  - `object VietnameseConsentParser { fun parse(transcript: String): ConsentDecision }`
  - `class KeyPoolManager(initialKeys: List<ApiKeyEntry>, private val clockMs: () -> Long, private val defaultCooldownMs: Long = 3_600_000L)`:
    - `fun nextHealthyPaidChatKey(): ApiKeyEntry?`
    - `fun nextHealthyFreeChatKey(): ApiKeyEntry?`
    - `fun markChatQuotaExhausted(keyId: String)`
    - `fun markChatInvalid(keyId: String)`
    - `fun markTtsUnsupported(keyId: String)` (MUST NOT alter `status` or `cooldownUntilMs`)
    - `fun resetAllCooldowns()`
    - `fun hasAnyHealthyPaidChatKey(): Boolean`
    - `fun snapshot(): List<ApiKeyEntry>`
  - `data class WakeResult(val state: QuotaState, val persona: PersonaMode, val spokenPrompt: String?, val awaitConsent: Boolean)`
  - `class NaNaQuotaStateMachine(private val keyPool: KeyPoolManager)`:
    - `val state: QuotaState`
    - `val activePersona: PersonaMode`
    - `fun onWakeWord(): WakeResult`
    - `fun onAllPaidChatKeysExhausted(): String`
    - `fun onConsentUtterance(transcript: String): WakeResult`
    - `fun onConsentTimeout(): WakeResult`
    - `fun onSessionEnded()`

- [ ] **Step 1: Write failing tests in `NaNaQuotaStateMachineTest.kt`**
  Cover:
  1. `paidKeysFailoverInPriorityOrder`: Key #1 exhausted -> Key #2 returned seamlessly; cooldown expiry restores Key #1.
  2. `ttsUnsupportedDoesNotBenchChatKey`: calling `markTtsUnsupported(key1)` leaves `key1` `HEALTHY` and `hasAnyHealthyPaidChatKey() == true`.
  3. `allPaidExhaustedTriggersTiredMessageAndEmGaiConsentFlow`: after both Pro keys hit quota, `onAllPaidChatKeysExhausted()` returns exact Na Na tired line; next `onWakeWord()` returns `QuotaState.NANA_TIRED_AWAITING_CONSENT` with exact prompt `"Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?"` and `awaitConsent == true`.
  4. `sayingOkSwitchesToLittleSister`: `onConsentUtterance("ok")`, `"ô kê"`, `"ừ"`, `"ok em ơi"` transition to `QuotaState.ACTIVE_LITTLE_SISTER` / `PersonaMode.LITTLE_SISTER`.
  5. `negativeOrAmbiguousDeclinesSafely` *(Review Focus #3)*: `"không"`, `"thôi"`, `"không ok"`, `"không đâu"` return `ConsentDecision.NEGATIVE` and do NOT activate Little Sister; `onConsentTimeout()` returns to `NANA_TIRED`.
  6. `paidKeyRecoveryRestoresNaNa`: advancing fake clock past cooldown causes next `onWakeWord()` to restore `QuotaState.ACTIVE_NANA` / `PersonaMode.NANA`.

- [ ] **Step 2: Run test to verify failure**
  Run: `.\gradlew.bat :core:test --tests "*NaNaQuotaStateMachineTest*"`
  Expected: FAIL (classes missing).

- [ ] **Step 3: Implement `VietnameseConsentParser`, `KeyPoolManager`, and `NaNaQuotaStateMachine`**
  Normalize Vietnamese diacritics/casing in `VietnameseConsentParser` and evaluate negative tokens (`khong`, `thoi`, `dung`, `no`) BEFORE affirmative tokens (`ok`, `o ke`, `okay`, `u`, `um`, `co`, `duoc`, `dong y`) so `"không ok"` safely returns `NEGATIVE`.

- [ ] **Step 4: Run test to verify pass**
  Run: `.\gradlew.bat :core:test --tests "*NaNaQuotaStateMachineTest*"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  Commit `"feat(core): add multi-key failover pool and Na Na to Em gai quota state machine"`.

---

### Task 3: LLM Provider Clients (`Gemini AQ. & AIza`, `OpenAI`, `Claude`) with MockWebServer Tests (`:core`)

**Files:**
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/ai/LlmProviderClient.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/ai/GeminiClient.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/ai/OpenAiClient.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/ai/ClaudeClient.kt`
- Test: `core/src/test/kotlin/com/adwewecat/nana/core/ai/LlmProviderClientsTest.kt`

**Interfaces:**
- Consumes: `ApiKeyEntry`, `ProviderType`, `ChatMessage`, `DeviceCommand` from Task 1.
- Produces:
  - `data class AudioAttachment(val bytes: ByteArray, val mimeType: String)`
  - `data class LlmRequest(val systemPrompt: String, val history: List<ChatMessage>, val userText: String?, val userAudio: AudioAttachment? = null, val enableTools: Boolean = true)`
  - `sealed class LlmResult`:
    - `data class Success(val replyText: String, val toolCalls: List<DeviceCommand>, val transcriptHint: String? = null)`
    - `data class QuotaExceeded(val httpCode: Int, val detail: String)`
    - `data class InvalidKey(val httpCode: Int, val detail: String)`
    - `data class TransientError(val detail: String)`
  - `interface LlmProviderClient { suspend fun generate(key: ApiKeyEntry, request: LlmRequest): LlmResult }`
  - `data class TtsAudio(val pcmBytes: ByteArray, val sampleRateHz: Int, val mimeType: String)`
  - `sealed class TtsResult { data class Success(val audio: TtsAudio); data class Unsupported(val detail: String); data class QuotaExceeded(val detail: String); data class Error(val detail: String) }`
  - `GeminiClient(httpClient: OkHttpClient, vertexBaseUrl: String = "https://aiplatform.googleapis.com", aiStudioBaseUrl: String = "https://generativelanguage.googleapis.com") : LlmProviderClient` plus `suspend fun synthesizeSpeech(key: ApiKeyEntry, text: String, styleInstruction: String, voiceName: String, ttsModelId: String = "gemini-2.5-flash-preview-tts"): TtsResult`
  - `OpenAiClient(httpClient: OkHttpClient, baseUrl: String = "https://api.openai.com") : LlmProviderClient` plus `suspend fun synthesizeSpeech(key: ApiKeyEntry, text: String, voice: String = "nova", model: String = "tts-1"): TtsResult` and `suspend fun transcribeAudio(key: ApiKeyEntry, wavBytes: ByteArray): String?`
  - `ClaudeClient(httpClient: OkHttpClient, baseUrl: String = "https://api.anthropic.com") : LlmProviderClient`

- [ ] **Step 1: Write failing tests in `LlmProviderClientsTest.kt` using `MockWebServer`**
  Verify:
  1. `geminiVertexExpressUsesAiplatformEndpointAndHeader`: `AQ.` key sends POST to `/v1/publishers/google/models/{model}:generateContent` with `x-goog-api-key: AQ...`, parses text + `functionCall` (`open_youtube_search` -> `DeviceCommand.OpenYouTubeSearch`).
  2. `geminiAiStudioUsesGenerativelanguageEndpointAndInlineAudio`: `AIza` key sends POST to `/v1beta/models/{model}:generateContent` including base64 `inlineData` for `AudioAttachment`.
  3. `geminiReturnsQuotaExceededOn429AndInvalidKeyOn403`: maps HTTP 429 / `RESOURCE_EXHAUSTED` to `LlmResult.QuotaExceeded`, HTTP 401/403 to `LlmResult.InvalidKey`.
  4. `geminiTtsParsesBase64PcmAndMaps404ToUnsupported`: verifies `responseModalities: ["AUDIO"]` payload, base64 audio decode, and HTTP 400/404 -> `TtsResult.Unsupported`.
  5. `openAiAndClaudeParseRepliesToolsAndRateLimits`: verifies OpenAI `/v1/chat/completions` (`Bearer`) + tool_calls and Claude `/v1/messages` (`x-api-key`, `anthropic-version: 2023-06-01`) + `tool_use` parsing and 429 mapping.

- [ ] **Step 2: Run test to verify failure**
  Run: `.\gradlew.bat :core:test --tests "*LlmProviderClientsTest*"`
  Expected: FAIL.

- [ ] **Step 3: Implement `LlmProviderClient`, `GeminiClient`, `OpenAiClient`, and `ClaudeClient`**
  Use `OkHttpClient` and `kotlinx.serialization.json` (`ignoreUnknownKeys = true`). Expose full device tool schema (`open_youtube_search`, `answer_incoming_call`, `end_call`, `toggle_speaker`, `make_phone_call`, `open_app`, `set_alarm`, `set_timer`, `adjust_volume`, `toggle_flashlight`, `web_search`).

- [ ] **Step 4: Run test to verify pass**
  Run: `.\gradlew.bat :core:test --tests "*LlmProviderClientsTest*"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  Commit `"feat(core): implement Gemini (AQ. Vertex Express + AIza), OpenAI, and Claude clients"`.

---

### Task 4: AI Chat Router, Decoupled TTS Router, Persona Prompt Builder & Memory Extractor (`:core`)

**Files:**
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/ai/PersonaPromptBuilder.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/ai/AiChatRouter.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/ai/TtsRouter.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/ai/MemoryExtractor.kt`
- Test: `core/src/test/kotlin/com/adwewecat/nana/core/ai/AiAndTtsRouterTest.kt`

**Interfaces:**
- Consumes: Task 1 models, Task 2 `KeyPoolManager` + `NaNaQuotaStateMachine`, Task 3 clients.
- Produces:
  - `object PersonaPromptBuilder { fun buildSystemPrompt(persona: PersonaMode, memories: List<MemoryFact>): String; fun buildTtsStyleInstruction(persona: PersonaMode): String }`
  - `data class RoutedChatReply(val replyText: String, val persona: PersonaMode, val toolCalls: List<DeviceCommand>, val quotaExhaustedNotice: Boolean = false, val usedKeyId: String? = null)`
  - `class AiChatRouter(private val keyPool: KeyPoolManager, private val stateMachine: NaNaQuotaStateMachine, private val geminiClient: GeminiClient, private val openAiClient: OpenAiClient, private val claudeClient: ClaudeClient)`:
    - `suspend fun sendTurn(history: List<ChatMessage>, memories: List<MemoryFact>, userText: String?, userAudio: AudioAttachment? = null): RoutedChatReply`
  - `interface LocalFallbackTts { suspend fun speak(text: String, persona: PersonaMode): Boolean }`
  - `sealed class SpokenOutput { data class CloudPcm(val audio: TtsAudio); object SpokenByLocalFallback; object Failed }`
  - `class TtsRouter(private val keyPool: KeyPoolManager, private val geminiClient: GeminiClient, private val openAiClient: OpenAiClient, private val localFallback: LocalFallbackTts)`:
    - `suspend fun synthesizeOrSpeak(text: String, persona: PersonaMode, preferredGeminiVoice: String = "Aoede"): SpokenOutput`
  - `class MemoryExtractor(private val json: Json)` with `fun buildExtractionPrompt(recentMessages: List<ChatMessage>): String` and `fun parseExtractedFacts(rawJson: String, nowMs: Long): List<MemoryFact>`

- [ ] **Step 1: Write failing tests in `AiAndTtsRouterTest.kt`**
  Cover:
  1. `chatRouterSilentlyFailsOverFromKey1ToKey2`: Key #1 returns `QuotaExceeded` -> Key #2 succeeds seamlessly without mentioning quota.
  2. `chatRouterTransitionsToTiredWhenAllPaidKeysExhausted`: Key #1 + Key #2 both fail with 429 -> returns Na Na's in-character exhausted notice with `quotaExhaustedNotice = true` and `stateMachine.state == QuotaState.NANA_TIRED`.
  3. `chatRouterUsesFreeKeyInLittleSisterMode`: when `stateMachine` is in `ACTIVE_LITTLE_SISTER`, routes directly to free key with Em gái prompt containing shared `memories`.
  4. `ttsRouterUnsupportedAqKeyFallsBackWithoutBenchingChatKey` *(Review Focus #1)*: `AQ.` key returns `TtsResult.Unsupported` -> `TtsRouter` marks only `ttsSupported = false`, falls back to OpenAI/local TTS, and `keyPool.nextHealthyPaidChatKey()` STILL returns that `AQ.` key as `HEALTHY`!
  5. `ttsRouterSpeaksWithGeminiOrOpenAiEvenWhenClaudeAnsweredChat`: verifies decoupled TTS selection works cleanly when active chat key is Claude.
  6. `memoryExtractorParsesJsonWrappedInMarkdownFence`: parses preferences/habits into `MemoryFact` entries ignoring malformed entries safely.

- [ ] **Step 2: Run test to verify failure**
  Run: `.\gradlew.bat :core:test --tests "*AiAndTtsRouterTest*"`
  Expected: FAIL.

- [ ] **Step 3: Implement `PersonaPromptBuilder`, `AiChatRouter`, `TtsRouter`, and `MemoryExtractor`**
  Keep Na Na's system prompt natural, warm, concise Vietnamese voice-chat style (no markdown symbols read aloud); inject structured `MemoryFact` bullets sorted by recency.

- [ ] **Step 4: Run test to verify pass**
  Run: `.\gradlew.bat :core:test --tests "*AiAndTtsRouterTest*"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  Commit `"feat(core): add AI failover router, decoupled TTS router, persona prompts, and memory extractor"`.

---

### Task 5: Local Vietnamese Fast Command Parser, Energy VAD & WAV Framing (`:core`)

**Files:**
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/voice/FastCommandParser.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/voice/EnergyVad.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/voice/PcmWavEncoder.kt`
- Test: `core/src/test/kotlin/com/adwewecat/nana/core/voice/VoiceCoreTest.kt`

**Interfaces:**
- Consumes: `DeviceCommand` from Task 1.
- Produces:
  - `object FastCommandParser`:
    - `fun matchesWakeWord(transcript: String): Boolean` (matches `"na na"`, `"nana"`, `"na nà"`, `"na ná"`)
    - `fun parseLocalCommand(transcript: String): DeviceCommand?` (matches `"bắt máy"`, `"nghe máy"`, `"cúp máy"`, `"tắt máy"`, `"bật loa ngoài"`, `"tắt loa ngoài"`, `"mở youtube tìm ..."`, `"mở youtube phát ..."`, `"bật đèn pin"`, `"tắt đèn pin"`, `"tạm biệt"`, `"nghỉ nhé"`, `"dừng lại"`)
  - `class EnergyVad(val speechRmsThreshold: Double = 650.0, val silenceDurationMs: Long = 1_400L, val maxUtteranceMs: Long = 15_000L)`:
    - `fun computeRms(pcm16: ShortArray, length: Int = pcm16.size): Double`
    - `fun feedFrame(pcm16: ShortArray, frameDurationMs: Long): VadEvent` (`IDLE`, `SPEECH_STARTED`, `SPEECH_ACTIVE`, `UTTERANCE_COMPLETE`)
    - `fun reset()`
  - `object PcmWavEncoder { fun encodeMono16Bit(pcmBytes: ByteArray, sampleRateHz: Int = 16_000): ByteArray }`

- [ ] **Step 1: Write failing tests in `VoiceCoreTest.kt`**
  Assert:
  - Wake word matches `"Na Na"`, `"nana ơi"`, `"na nà"` and rejects `"xin chào"`.
  - Local command parser parses `"bắt máy đi em"` -> `AnswerIncomingCall`, `"cúp máy"` -> `EndCall`, `"mở youtube tìm nhạc chill"` -> `OpenYouTubeSearch("nhạc chill", autoPlay = true)`, `"tạm biệt na na"` -> `StopSession`, and returns `null` for ordinary heart-to-heart chat `"hôm nay anh hơi mệt"`.
  - `EnergyVad` transitions `IDLE -> SPEECH_STARTED -> SPEECH_ACTIVE -> UTTERANCE_COMPLETE` after silence threshold and caps at `maxUtteranceMs`.
  - `PcmWavEncoder` produces valid 44-byte RIFF/WAVE header with exact chunk sizes for 16kHz mono PCM.

- [ ] **Step 2: Run test to verify failure**
  Run: `.\gradlew.bat :core:test --tests "*VoiceCoreTest*"`
  Expected: FAIL.

- [ ] **Step 3: Implement `FastCommandParser`, `EnergyVad`, and `PcmWavEncoder`**

- [ ] **Step 4: Run test to verify pass**
  Run: `.\gradlew.bat :core:test --tests "*VoiceCoreTest*"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  Commit `"feat(core): add offline Vietnamese command parser, energy VAD, and PCM WAV encoder"`.

---

### Task 6: Wi-Fi-Only Google Drive Resumable Sync, Restore & Retention Engine (`:core`)

**Files:**
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/sync/WifiNetworkChecker.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/sync/GoogleDriveClient.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/sync/WifiOnlySyncCoordinator.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/sync/DriveRestoreCoordinator.kt`
- Create: `core/src/main/kotlin/com/adwewecat/nana/core/sync/CallRecordingRetentionPolicy.kt`
- Test: `core/src/test/kotlin/com/adwewecat/nana/core/sync/WifiOnlyDriveSyncTest.kt`

**Interfaces:**
- Consumes: `ChatMessage`, `MemoryFact`, `CallRecordingRecord`, `SyncStatus` from Task 1.
- Produces:
  - `interface WifiNetworkChecker { fun isUnmeteredWifiConnected(): Boolean }`
  - `class WifiDroppedException : IOException`
  - `class DriveUnauthorizedException : IOException`
  - `@Serializable data class SyncManifest(val schemaVersion: Int = 1, val updatedAtMs: Long, val memoryFileId: String?, val historyFileIds: Map<String, String>, val recordingDriveIds: Map<String, String>)`
  - `class GoogleDriveClient(httpClient: OkHttpClient, apiBaseUrl: String = "https://www.googleapis.com")`:
    - `suspend fun findOrCreateFolder(accessToken: String, name: String, parentId: String?): String`
    - `suspend fun upsertJsonFile(accessToken: String, parentId: String, fileName: String, jsonContent: String, existingFileId: String?): String`
    - `suspend fun downloadTextFile(accessToken: String, fileId: String): String`
    - `suspend fun initiateResumableUpload(accessToken: String, parentId: String, fileName: String, mimeType: String, totalBytes: Long): String`
    - `suspend fun uploadChunkedResumable(accessToken: String, sessionUri: String, file: File, startOffset: Long, chunkSizeBytes: Int = 262_144, wifiChecker: WifiNetworkChecker, onProgress: suspend (Long) -> Unit): String`
  - `interface SyncStorageGateway` (reads/writes unsynced messages, memories, recordings, manifest state, and merges restored items idempotently)
  - `enum class SyncOutcome { COMPLETED, PAUSED_NO_WIFI, NEEDS_REAUTH, RETRYABLE_ERROR }`
  - `class WifiOnlySyncCoordinator(private val wifiChecker: WifiNetworkChecker, private val driveClient: GoogleDriveClient, private val storage: SyncStorageGateway)` -> `suspend fun syncNow(accessToken: String): SyncOutcome`
  - `class DriveRestoreCoordinator(private val wifiChecker: WifiNetworkChecker, private val driveClient: GoogleDriveClient, private val storage: SyncStorageGateway)` -> `suspend fun restoreIfAvailable(accessToken: String, allowMeteredOverride: Boolean = false): SyncOutcome`
  - `class CallRecordingRetentionPolicy(val retentionDaysAfterVerifiedSync: Int = 7, val keepForever: Boolean = false)` -> `fun selectStagedFilesEligibleForCleanup(records: List<CallRecordingRecord>, nowMs: Long): List<CallRecordingRecord>`

- [ ] **Step 1: Write failing tests in `WifiOnlyDriveSyncTest.kt` using `MockWebServer`**
  Cover:
  1. `syncAbortsImmediatelyWithoutNetworkCallsWhenNotOnUnmeteredWifi`: `wifiChecker = false` -> `SyncOutcome.PAUSED_NO_WIFI`, `server.requestCount == 0`.
  2. `resumableUploadAbortsMidTransferWhenWifiDropsAndResumesCleanly` *(Review Focus #2)*: upload 600 KB file with 256 KB chunks; fake `wifiChecker` flips `false` before chunk #2 -> throws/pauses `PAUSED_NO_WIFI`, persists `uploadedBytes = 262_144` & `resumeSessionUri`, keeps `syncStatus = PENDING` and local file intact; flipping `wifiChecker = true` resumes from byte 262_144 and completes with `SYNCED` + `driveFileId`.
  3. `unauthorized401ReturnsNeedsReauthWithoutDeletingLocalData` *(Review Focus #5)*: Drive returns HTTP 401 -> `SyncOutcome.NEEDS_REAUTH`, local messages and recordings remain untouched.
  4. `restoreMergesManifestMemoriesAndMessagesIdempotently` *(Review Focus #5)*: running `restoreIfAvailable` twice with overlapping IDs never duplicates `messages` or `memory_facts`.
  5. `retentionPolicyNeverDeletesUnsyncedOrRecentRecordings`: refuses cleanup when `driveFileId == null`, `syncStatus != SYNCED`, `keepForever == true`, or `< 7 days` since `syncedAtMs`; selects only verified staged copies older than 7 days.

- [ ] **Step 2: Run test to verify failure**
  Run: `.\gradlew.bat :core:test --tests "*WifiOnlyDriveSyncTest*"`
  Expected: FAIL.

- [ ] **Step 3: Implement all 5 files in `core/src/main/kotlin/com/adwewecat/nana/core/sync/`**
  Check `wifiChecker.isUnmeteredWifiConnected()` before every HTTP call and before every 256 KB resumable chunk; map HTTP 401 to `DriveUnauthorizedException` -> `SyncOutcome.NEEDS_REAUTH`.

- [ ] **Step 4: Run test to verify pass**
  Run: `.\gradlew.bat :core:test --tests "*WifiOnlyDriveSyncTest*"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  Commit `"feat(core): implement strict Wi-Fi-only Google Drive resumable sync, restore, and retention policy"`.

---

### Task 7: Android Persistence (Room DB), Keystore API Vault, AppAuth OAuth & WorkManager Wi-Fi Sync (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/adwewecat/nana/data/local/Entities.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/data/local/Daos.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/data/local/NaNaDatabase.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/data/local/RoomSyncStorageGateway.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/data/local/EncryptedApiKeyVault.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/data/auth/GoogleDriveAuthManager.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/data/sync/AndroidWifiNetworkChecker.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/data/sync/DriveSyncWorker.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/data/sync/DriveSyncScheduler.kt`
- Test: `app/src/test/kotlin/com/adwewecat/nana/data/AndroidDataAndWifiSyncTest.kt`

**Interfaces:**
- Consumes: `:core` models + `WifiNetworkChecker`, `SyncStorageGateway`, `WifiOnlySyncCoordinator`, `DriveRestoreCoordinator`, `CallRecordingRetentionPolicy`.
- Produces:
  - `NaNaDatabase` (Room v1 with `MessageDao`, `MemoryFactDao`, `CallRecordingDao`, `SyncStateDao`)
  - `RoomSyncStorageGateway(db: NaNaDatabase) : SyncStorageGateway`
  - `EncryptedApiKeyVault(context: Context)` with `fun loadKeys(): List<ApiKeyEntry>`, `fun saveKeys(keys: List<ApiKeyEntry>)`, `fun loadSettings(): AppSettings`, `fun saveSettings(settings: AppSettings)`
  - `GoogleDriveAuthManager(context: Context)` (AppAuth PKCE for Google Drive `drive.file`, `buildAuthIntent()`, `handleAuthResponse()`, `getFreshAccessToken()`, `markNeedsReauth()`, `val authStateFlow: StateFlow<DriveAuthStatus>`)
  - `AndroidWifiNetworkChecker(context: Context) : WifiNetworkChecker` (verifies `TRANSPORT_WIFI` + `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_NOT_METERED`)
  - `DriveSyncWorker : CoroutineWorker` (registers live `ConnectivityManager.NetworkCallback` cancelling sync job immediately when Wi-Fi drops, runs `WifiOnlySyncCoordinator` + `CallRecordingRetentionPolicy` cleanup)
  - `DriveSyncScheduler(context: Context)` (`enqueueSync()` using `NetworkType.UNMETERED` + `ExistingWorkPolicy.KEEP`, `schedulePeriodicWifiSync()`)

- [ ] **Step 1: Write failing Robolectric unit tests in `AndroidDataAndWifiSyncTest.kt`**
  Verify:
  1. `roomGatewayDeduplicatesRestoredMessagesAndFacts` (in-memory Room DB via Robolectric): restoring overlapping messages & facts keeps exact count without duplicates.
  2. `androidWifiNetworkCheckerRejectsCellularAndMeteredWifi`: returns `false` on `TRANSPORT_CELLULAR`, `false` on `TRANSPORT_WIFI` without `NET_CAPABILITY_NOT_METERED`, and `true` only when `TRANSPORT_WIFI + INTERNET + NOT_METERED`.
  3. `driveSyncSchedulerEnqueuesWithStrictUnmeteredConstraint`: verifies built `OneTimeWorkRequest` has `constraints.requiredNetworkType == NetworkType.UNMETERED`.
  4. `authManagerFlagsNeedsReauthOnInvalidGrant`: verifies `invalid_grant` transitions `authStateFlow` to `needsReauth = true` without clearing local DB.

- [ ] **Step 2: Run test to verify failure**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*AndroidDataAndWifiSyncTest*"`
  Expected: FAIL.

- [ ] **Step 3: Implement Room DB, `EncryptedApiKeyVault`, `GoogleDriveAuthManager`, `AndroidWifiNetworkChecker`, `DriveSyncWorker`, and `DriveSyncScheduler`**
  Remember: `EncryptedApiKeyVault` gracefully falls back to an injectable `SharedPreferences` constructor for JVM unit tests while using `MasterKey` + `EncryptedSharedPreferences` on Android devices.

- [ ] **Step 4: Run test to verify pass**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*AndroidDataAndWifiSyncTest*"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  Commit `"feat(app): add Room storage, Keystore API vault, AppAuth OAuth, and Wi-Fi-only WorkManager sync"`.

---

### Task 8: Android Voice Pipeline — Wake-Word Service, Bluetooth-Safe Mic, AudioTrack PCM & Cached Prompts (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/adwewecat/nana/voice/WakeWordEngine.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/voice/VoskWakeWordEngine.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/voice/AndroidAudioPlayer.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/voice/AndroidSystemTtsEngine.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/voice/NaNaVoiceOrchestrator.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/voice/NaNaVoiceService.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/voice/NaNaQuickSettingsTile.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/voice/NaNaVoiceInteractionService.kt`
- Create: `app/src/main/res/xml/voice_interaction_service.xml`
- Modify: `app/src/main/AndroidManifest.xml` (declare foreground mic service, Quick Settings Tile, optional `VoiceInteractionService`, required audio/notification permissions)
- Test: `app/src/test/kotlin/com/adwewecat/nana/voice/NaNaVoiceOrchestratorTest.kt`

**Interfaces:**
- Consumes: `:core` `NaNaQuotaStateMachine`, `AiChatRouter`, `TtsRouter`, `FastCommandParser`, `EnergyVad`, `PcmWavEncoder`, `MemoryExtractor`; Task 7 `NaNaDatabase`, `DriveSyncScheduler`.
- Produces:
  - `enum class VoiceLoopState { STOPPED, IDLE_WAKE, LISTENING, THINKING, SPEAKING, AWAITING_CONSENT }`
  - `data class VoiceUiSnapshot(val loopState: VoiceLoopState, val quotaState: QuotaState, val activePersona: PersonaMode, val micLevel: Float, val statusMessage: String)`
  - `class NaNaVoiceOrchestrator(...)`:
    - `val uiState: StateFlow<VoiceUiSnapshot>`
    - `suspend fun onWakeWordDetected()`
    - `suspend fun onUserUtterance(transcript: String?, wavBytes: ByteArray?)`
    - `fun stopSession()`
  - `NaNaVoiceService : Service` (Foreground Service `microphone`, uses `AudioRecord.setPreferredDevice(TYPE_BUILTIN_MIC)` for passive wake listening to protect Bluetooth A2DP, optional `PARTIAL_WAKE_LOCK` gated by Settings toggle)
  - `AndroidAudioPlayer` (24kHz 16-bit PCM `AudioTrack` + local disk cache for Na Na's *"Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?"* audio clip)

- [ ] **Step 1: Write failing tests in `NaNaVoiceOrchestratorTest.kt`**
  Cover:
  1. `wakeWordWhileTiredPlaysExactConsentPromptAndSwitchesToEmGaiOnOk`: verifies full orchestrator flow from wake word -> `AWAITING_CONSENT` -> `"ok"` -> `ACTIVE_LITTLE_SISTER` without calling any paid API key.
  2. `localFastCommandBypassesLlmAndExecutesImmediately`: `"bắt máy"` invokes `DeviceActionExecutor.execute(AnswerIncomingCall)` directly even when offline.
  3. `conversationTurnSavesMessagesExtractsMemoryAndEnqueuesWifiSync`: persists user + assistant messages to Room, triggers `DriveSyncScheduler.enqueueSync()`, and returns to `LISTENING` for continuous turn-taking until `"tạm biệt"` transitions to `IDLE_WAKE`.

- [ ] **Step 2: Run test to verify failure**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*NaNaVoiceOrchestratorTest*"`
  Expected: FAIL.

- [ ] **Step 3: Implement Voice Layer components & update `AndroidManifest.xml`**
  Ensure `VoskWakeWordEngine` checks for locally unpacked/downloaded Vosk Vietnamese model (`files/vosk-model-vn`), constrains grammar to `["na na", "nana", "na nà", "na ná", "[unk]"]`, and degrades cleanly to Android `SpeechRecognizer` / Push-to-Talk if model isn't downloaded yet (with in-app download helper).

- [ ] **Step 4: Run test to verify pass**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*NaNaVoiceOrchestratorTest*"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  Commit `"feat(app): implement Na Na voice orchestrator, Bluetooth-safe foreground service, PCM TTS player, and quick tile"`.

---

### Task 9: Android 16 Device Control (YouTube + 3-Tier SIM/VoIP Call Answer) & OriginOS Call Recording Watcher (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/adwewecat/nana/actions/DeviceActionExecutor.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/actions/NaNaNotificationListener.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/actions/NaNaAccessibilityService.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/actions/AndroidDeviceActionExecutor.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/actions/CallRecordingIngestionCoordinator.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/actions/OriginOsCallRecordingWatcher.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/actions/InAppCallRecorder.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/actions/CallStateMonitor.kt`
- Create: `app/src/main/res/xml/accessibility_service_config.xml`
- Modify: `app/src/main/AndroidManifest.xml` (register `NaNaNotificationListener`, `NaNaAccessibilityService`, phone/contact/alarm/camera permissions; explicitly DO NOT declare `InCallService` or `ROLE_DIALER`)
- Test: `app/src/test/kotlin/com/adwewecat/nana/actions/DeviceActionsAndCallRecordingTest.kt`

**Interfaces:**
- Consumes: `:core` `DeviceCommand`, `CallRecordingRecord`, `RecordingSource`, `SyncStatus`; Task 7 `CallRecordingDao`, `DriveSyncScheduler`.
- Produces:
  - `data class ActionExecutionResult(val succeeded: Boolean, val spokenFeedback: String, val missingPermissionAction: String? = null)`
  - `interface DeviceActionExecutor { suspend fun execute(command: DeviceCommand): ActionExecutionResult }`
  - `AndroidDeviceActionExecutor : DeviceActionExecutor`:
    - YouTube search Intent (`com.google.android.youtube`) + signals `NaNaAccessibilityService.armYouTubeAutoPlay(query)` when `autoPlay = true`
    - 3-tier incoming call answer/decline: (1) `NaNaNotificationListener.triggerIncomingCallAction(answer = true/false)` (SIM + Zalo/Messenger/Telegram), (2) `TelecomManager.acceptRingingCall()` / `endCall()`, (3) `NaNaAccessibilityService.tryTapCallAction(answer)`
    - Dial contact, fuzzy open installed app, alarm/timer, volume, flashlight, web search
  - `data class DiscoveredRecording(val sourceUri: String, val fileName: String, val sizeBytes: Long, val mimeType: String, val lastModifiedMs: Long)`
  - `class CallRecordingIngestionCoordinator(...)` -> `suspend fun ingestNewRecordings(candidates: List<DiscoveredRecording>, copyToStaging: suspend (DiscoveredRecording) -> String?): Int`

- [ ] **Step 1: Write failing tests in `DeviceActionsAndCallRecordingTest.kt`**
  Cover:
  1. `ingestionSkipsZeroByteAndDeduplicatesAlreadyIndexedRecordings` *(Review Focus #4)*: passing a 0-byte recording skips it; passing an already-ingested `(sourceUri, sizeBytes)` skips it; passing a valid `.m4a` stages it once as `PENDING` and triggers Wi-Fi sync.
  2. `notificationCallActionMatcherFindsSimZaloAndMessengerAnswerButtons`: tests matching Vietnamese & English Answer/Decline notification actions (`"Trả lời"`, `"Nghe máy"`, `"Answer"`, `"Accept"`, `"Từ chối"`, `"Decline"`) across SIM dialer, `com.zing.zalo`, `com.facebook.orca`, `org.telegram.messenger`.
  3. `answerCallUsesThreeTierFallbackWithoutRequiringDefaultDialer`: if NotificationListener succeeds -> stops; if unavailable -> falls back to `TelecomManager`; if unavailable -> falls back to Accessibility; if all missing -> returns sweet Vietnamese permission guidance with Settings action.
  4. `openYouTubeSearchArmsAutoPlayAndLaunchesYouTubeIntent`: verifies Intent package `com.google.android.youtube` + query extra + accessibility auto-play arming.

- [ ] **Step 2: Run test to verify failure**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*DeviceActionsAndCallRecordingTest*"`
  Expected: FAIL.

- [ ] **Step 3: Implement all device action, notification listener, accessibility, and call recording watcher/fallback files + Manifest entries**

- [ ] **Step 4: Run test to verify pass**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*DeviceActionsAndCallRecordingTest*"`
  Expected: PASS.

- [ ] **Step 5: Commit**
  Commit `"feat(app): add YouTube auto-play, 3-tier SIM/VoIP call control, and OriginOS call recording watcher"`.

---

### Task 10: Jetpack Compose Material 3 Dark-First UI, DI Container, CI APK Workflow & Documentation (`:app`)

**Files:**
- Create: `app/src/main/kotlin/com/adwewecat/nana/di/AppContainer.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/NaNaApplication.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/MainActivity.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/ui/theme/NaNaTheme.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/ui/NaNaApp.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/ui/home/CompanionHomeScreen.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/ui/recordings/CallRecordingsScreen.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/ui/memory/MemoryHistoryScreen.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/kotlin/com/adwewecat/nana/ui/settings/OriginOsSetupHelper.kt`
- Create: `.github/workflows/android-build.yml`
- Create: `SETUP_ANDROID.md`
- Modify: `GEMINI.md`, `PROGRESS.md`
- Test: `app/src/test/kotlin/com/adwewecat/nana/ui/SettingsAndSetupHelperTest.kt`

**Interfaces:**
- Consumes: All `:core` and `:app` components from Tasks 1–9.
- Produces:
  - Complete wired `NaNaApplication` + `AppContainer` + `MainActivity` (handles AppAuth OAuth redirect intent, SAF `ACTION_OPEN_DOCUMENT_TREE` picker for OriginOS `Record/Call`, runtime permission launchers, and 4-tab Jetpack Compose UI).
  - `OriginOsSetupHelper`: builds exact deep-link Intents for Vivo/iQOO Autostart / High Background Power, Battery Optimization Exemption, Notification Listener Settings, Accessibility Settings, and App Details Settings.
  - `.github/workflows/android-build.yml`: runs `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug` on JDK 17 and uploads `app-debug.apk` artifact (injecting optional `GOOGLE_OAUTH_CLIENT_ID` from GitHub Secrets if present).
  - `SETUP_ANDROID.md`: clear Vietnamese step-by-step guide for installing APK on iQOO Z10 Turbo Pro, entering Gemini Pro `AQ.` Key #1 / #2 / Free key / Claude / ChatGPT in-app (never in code), setting up Google Cloud OAuth Client ID for Drive sync, granting OriginOS 6 Autostart + `Record/Call` folder access.

- [ ] **Step 1: Write failing tests in `SettingsAndSetupHelperTest.kt`**
  Verify:
  1. `maskApiKeyHidesMiddleCharactersSafely`: `"AQ.Ab8RN6ExampleSecretKey1234"` masks to `"AQ.Ab8...1234"` (never leaks full key in UI display or logs).
  2. `originOsSetupHelperProvidesSafeFallbackIntents`: verifies Autostart, Battery Optimization, Notification Access, and Accessibility intents are non-null and valid.
  3. `appContainerWiresRealServicesCleanly`: instantiates `AppContainer` in Robolectric and verifies `aiChatRouter`, `ttsRouter`, `quotaStateMachine`, `syncScheduler`, and `voiceOrchestrator` initialize cleanly without hardcoded API keys.

- [ ] **Step 2: Run test to verify failure**
  Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*SettingsAndSetupHelperTest*"`
  Expected: FAIL.

- [ ] **Step 3: Implement `AppContainer`, `NaNaApplication`, `MainActivity`, all 4 Jetpack Compose screens, `OriginOsSetupHelper`, GitHub Actions workflow, and `SETUP_ANDROID.md`**

- [ ] **Step 4: Run full verification suite (JVM tests + Android unit tests + Debug APK build + Android Lint)**
  Run: `.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug`
  Expected: ALL tests PASS and `app/build/outputs/apk/debug/app-debug.apk` exists!

- [ ] **Step 5: Update `GEMINI.md` (< 300 lines) & `PROGRESS.md`, verify `git status` has zero secrets, commit, and push to `https://github.com/adwewecat/AI_TALK.git`**
