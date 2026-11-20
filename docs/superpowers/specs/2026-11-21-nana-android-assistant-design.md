# Na Na (`AI_TALK`) — Android Voice Assistant & AI Companion Design

- **Date:** 2026-11-21
- **Status:** Ready for user approval (passed independent builder review)
- **Target Platform:** Android only — optimized for **iQOO Z10 Turbo Pro** (`PD2453_A`, **OriginOS 6 / Android 16**, Vietnamese UI)
- **Application ID:** `com.adwewecat.nana` *(engineering call — fixed permanently so APK updates preserve Room DB, Keystore, and Google Drive `drive.file` access)*
- **Repository:** `https://github.com/adwewecat/AI_TALK.git`

---

## 1. Intent & Why

The user wants a personal daily Android assistant and emotional companion named **Na Na** that unites two capabilities existing assistants do not deliver together:

1. **Hands-free device control** activated by saying `"Na Na"` — opening YouTube and playing/searching requested music or videos, answering incoming calls, recording phone calls, and running extensible daily phone commands.
2. **A warm, human-like companion** with a cute, sweet, expressive anime/kawaii Vietnamese female voice and devoted girlfriend-like personality that remembers the user's preferences, habits, and life history permanently across reinstalls.

Key personal reliability requirements:
- **Multi-key & multi-LLM failover with in-character persona handoff** so API quota limits never break immersion (`Gemini Pro Key #1` -> `Gemini Pro Key #2` -> optional `Claude` / `ChatGPT` -> Free tier **"Em gái của Na Na"**).
- **Zero cellular data waste & zero data loss:** Call recordings and conversation history are stored locally first and synced to Google Drive **strictly over Wi-Fi** (automatically pausing whenever offline or on 4G/5G).

---

## 2. Goals, Non-Goals & Anti-Goals

### Goals
- Reliable hands-free `"Na Na"` wake-word activation running on OriginOS 6 (Android 16) without degrading Bluetooth music playback.
- Continuous two-way Vietnamese voice conversations after wake word without repeating `"Na Na"` before every sentence.
- Cute, expressive Vietnamese female voice for Na Na (Pro tier) and a cheerful little-sister fallback ("Em gái") for Free tier, decoupled from chat provider failures and safe on China ROM devices lacking Google Speech Services.
- Automatic multi-key failover supporting Vertex AI Express (`AQ.` prefix), Gemini Developer API (`AIza` prefix), OpenAI ChatGPT, Anthropic Claude, and Free fallback.
- Exact voice handoff when all paid chat keys are exhausted: Na Na announces she is tired/out of quota; on subsequent `"Na Na"` calls while still in cooldown she asks *"Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?"* and switches to **Em gái** immediately upon hearing `"ok"` (or equivalent Vietnamese affirmatives).
- Hands-free device control: YouTube voice search + auto-play, answering incoming calls (both SIM calls and VoIP calls like Zalo/Messenger/Telegram without breaking OriginOS's native dialer), call recording (OriginOS native 2-way watcher + in-app fallback), plus extensible tool-calling commands (open apps, dial contacts, alarms/timers, volume/flashlight, web search).
- Google Sign-In + offline-first Room storage + **strictly Wi-Fi-only** sync of conversation history, structured long-term memory, and call recordings to the user's personal Google Drive, with seamless restore after app reinstall.

### Non-Goals
- No iOS or web app version (user explicitly scoped to Android only).
- No custom backend server or cloud database; everything runs directly on the device using the user's personal API keys and personal Google Drive.
- No replacing the Vivo/iQOO system dialer (`ROLE_DIALER` / `InCallService`), because replacing the stock dialer breaks OriginOS's native 2-way SIM call recording.
- No rooting, Magisk modules, or Play Store distribution/monetization infrastructure.

### Anti-Goals (Failure even if technically working)
- **Leaking API keys, OAuth secrets, or signing keystores** into source code, logs, or GitHub commits.
- **Syncing over 4G/5G cellular data** or losing local messages/call recordings when Wi-Fi drops mid-upload.
- **Showing raw HTTP/API error codes** (like `429 RESOURCE_EXHAUSTED`) during voice chat instead of staying in-character as Na Na / Em gái.
- **Falsely triggering `"Em mệt rồi"`** when a TTS endpoint fails while the primary Gemini Pro chat key is still healthy.
- **Breaking OriginOS's built-in 2-way call recording** or degrading Bluetooth A2DP music to mono call audio while passively listening for `"Na Na"`.
- **Silent failure on China ROM** when `com.google.android.tts` (Google Speech Services) is not installed.

---

## 3. Constraints & Target Environment

- **Primary Device:** iQOO Z10 Turbo Pro (`PD2453_A`), OriginOS 6 (Android 16, API 36), China ROM with Vietnamese locale, dual 4G SIM, Wi-Fi, and Bluetooth headset usage.
- **Android 16 Call Answering vs. Recording Constraint:** Taking `RoleManager.ROLE_DIALER` (`InCallService`) replaces the stock Vivo/iQOO dialer and disables OriginOS's crystal-clear native 2-way call recording (`Record/Call`). Therefore, **preserving OriginOS's native dialer strictly takes priority**: call answering/ending uses `NotificationListenerService` (notification Answer/Decline `PendingIntent`) + `TelecomManager` + `AccessibilityService` fallback, which preserves native 2-way recording AND works across SIM + Zalo/Messenger/Telegram calls.
- **Android 16 Background Mic & Reboot Constraint:** Android 15/16 prohibits starting `foregroundServiceType="microphone"` directly from `BOOT_COMPLETED`. Therefore, the reliable baseline is starting/re-arming listening with 1 tap via the app, Quick Settings Tile, or notification after reboot, with optional `PARTIAL_WAKE_LOCK` (screen-off listening toggle, default OFF) and optional `VoiceInteractionService` (`ROLE_ASSISTANT`) registration for deeper system integration.
- **OriginOS China ROM Speech Constraint:** China ROMs default to Vivo/Baidu Chinese speech engines rather than `com.google.android.tts`. Conversational Vietnamese STT MUST NOT depend exclusively on Android `SpeechRecognizer`, and Vietnamese TTS MUST work even without Google TTS installed.
- **Gemini Key Format Constraint:** The user's Gemini Pro keys start with `AQ.` (**Vertex AI Express Mode**), which uses `POST https://aiplatform.googleapis.com/v1/publishers/google/models/{MODEL_ID}:generateContent` with header `x-goog-api-key: <KEY>`. Standard Google AI Studio keys (`AIza...`) use `https://generativelanguage.googleapis.com/v1beta/models/{MODEL_ID}:generateContent`. Note also that Vertex AI Express keys may restrict preview audio-output TTS (`gemini-2.5-flash-preview-tts`), so TTS routing and health MUST be decoupled from chat routing.
- **OAuth & Persistent Signing Constraint:** AppAuth-Android requires `appAuthRedirectScheme` statically in `AndroidManifest.xml` bound to `applicationId` (`com.adwewecat.nana`) and signing SHA-1. `GOOGLE_OAUTH_CLIENT_ID` is injected at build time from gitignored `local.properties` / GitHub Actions Secrets (with a safe fallback scheme `com.adwewecat.nana.oauth` so builds/tests always succeed before OAuth setup). Signing uses a persistent gitignored keystore (and optional GitHub Secrets keystore) so APK upgrades never wipe Room DB or Keystore.
- **Security Constraint:** Never commit real API keys, OAuth secrets, or keystores to Git. All API keys are entered on-device into Android Keystore-backed `EncryptedSharedPreferences`.
- **Development Workstation:** Windows with OpenJDK 17 (`17.0.21`), Node.js `v24.22.1`, Python `3.12.10`. Android SDK Command-Line Tools will be installed locally for real Gradle builds & JVM unit tests, alongside a GitHub Actions workflow producing downloadable APKs.
- **Content Safety Boundary:** Na Na's companion persona is strictly wholesome, affectionate, playful, and adult-safe (cute anime/kawaii Vietnamese companion tone).

---

## 4. Approaches Considered

### Approach 1: Native Android (Kotlin + Jetpack Compose) — On-Device, Serverless *(Selected)*
- **Design:** Pure Kotlin Android app with Jetpack Compose UI, Foreground Service for `"Na Na"` wake word, `NotificationListenerService` + `TelecomManager` + `AccessibilityService` for calls & YouTube auto-play, OriginOS folder watcher + MediaRecorder fallback for call recordings, Room SQLite, WorkManager (`NetworkType.UNMETERED`) for Google Drive sync, and direct HTTP clients for Gemini/Claude/ChatGPT.
- **Trade-offs:** Android-only (matches requirement), lowest RAM/battery footprint for surviving OriginOS 6 background limits, direct access to Android 16 system APIs without JS bridge bugs, zero server hosting cost, maximum privacy.

### Approach 2: React Native + Native Kotlin Modules
- **Design:** TypeScript UI with custom Kotlin native modules for every Android system feature.
- **Trade-offs:** Enables future iOS UI reuse, but doubles architecture complexity and RAM usage for an Android-only project while still requiring Kotlin for all core features.

### Approach 3: Native Android + Personal Backend Proxy Server
- **Design:** Android client handles capture and OS actions while a self-hosted Node.js/Python server handles key failover, memory extraction, and TTS.
- **Trade-offs:** Keeps API keys off-device and eases remote prompt tuning, but adds 24/7 hosting cost/maintenance, conversational latency, and a single point of failure.

**Decision:** The user selected **Approach 1** (Native Android Kotlin + Jetpack Compose, serverless on-device).

---

## 5. System Architecture & Functional Design

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          Jetpack Compose UI                              │
│  [Companion Home]  [Call Recordings]  [Memory & History]  [Settings]     │
└───────────────┬───────────────────┬──────────────────┬───────────────────┘
                │                   │                  │
┌───────────────▼───────────────────▼──────────────────▼───────────────────┐
│                    NaNaOrchestrator (State Machine)                      │
│  Audio Loop: IDLE_WAKE -> LISTENING -> THINKING -> SPEAKING -> FOLLOWUP  │
│  Chat Quota: ACTIVE_NANA | NANA_TIRED_AWAITING_CONSENT | LITTLE_SISTER   │
└──┬─────────────┬──────────────────┬──────────────────┬───────────────────┘
   │             │                  │                  │
┌──▼──────────┐ ┌▼───────────────┐ ┌▼───────────────┐ ┌▼──────────────────┐
│ Voice Layer │ │ AI Chat Router │ │ Device Actions │ │ Local Store & Sync│
│ - WakeWord  │ │ - Gemini AQ.   │ │ - YouTube +    │ │ - Room SQLite     │
│   (VAD +    │ │ - Gemini AIza  │ │   Accessibility│ │ - Keystore Vault  │
│   Vosk,     │ │ - Claude       │ │ - Notif +      │ │ - OriginOS Call   │
│   Phone Mic)│ │ - ChatGPT      │ │   Telecom Call │ │   Watcher + Local │
│ - Audio STT │ │ - Free Tier    │ │   (SIM + VoIP) │ │   Temp Staging    │
│   (Gemini   │ │ - Memory Extr. │ │ - Apps/Alarms/ │ │ - WorkManager     │
│   inline +  │ ├────────────────┤ │   Vol/Flash    │ │   Wi-Fi-Only Drive│
│   fallbacks)│ │ Decoupled TTS  │ └────────────────┘ │   + Re-Auth Guard │
│             │ │ - Gemini TTS   │                    └───────────────────┘
│             │ │ - OpenAI TTS   │
│             │ │ - Edge/Local   │
└─────────────┘ └────────────────┘
```

### 5.1 Wake Word, Audio Routing & Continuous Voice Loop
- **Foreground Service (`NaNaVoiceService`):** Declared with `foregroundServiceType="microphone"` and persistent low-priority status notification (`Na Na đang lắng nghe` / `Chế độ Em gái`).
- **Bluetooth-Safe Mic Routing** *(engineering call)*: Passive wake-word listening explicitly requests the phone's built-in microphone (`AudioRecord.setPreferredDevice(TYPE_BUILTIN_MIC)`) so connected Bluetooth headsets remain in high-quality stereo A2DP playback without switching to mono HFP call mode.
- **Offline Wake Word (`WakeWordEngine`):**
  - *Engineering call:* Energy-VAD gate + Vosk offline recognizer constrained strictly to wake grammar `["na na", "nana", "na nà", "na ná", "[unk]"]`. Zero API cost while idle, 100% offline, works on OriginOS China ROM.
  - Fallback triggers: In-app **Push-to-Talk**, **Quick Settings Tile** (1-tap re-arm after reboot), **Notification Quick Action**, and optional `VoiceInteractionService` (`ROLE_ASSISTANT`) registration for system gesture/power-button activation.
  - **Screen-Off Listening Toggle** *(engineering call, default OFF)*: Optional `PARTIAL_WAKE_LOCK` setting when user explicitly wants screen-off wake-word detection (with clear battery note in Settings).
- **Continuous Turn-Taking Loop:**
  - Triggering `"Na Na"` plays a soft chime and runs `LISTEN -> STT -> AI/ACTION -> TTS -> FOLLOWUP_LISTEN` continuously until user says a stop phrase (`"tạm biệt"`, `"nghỉ nhé"`, `"thôi nha"`, `"im đi"`) or after configurable silence timeout (default `15 seconds`).
- **China-ROM-Safe Speech-to-Text (`SttEngine`)** *(engineering call)*:
  - **Primary (Online):** Record VAD-trimmed user speech (`16kHz PCM -> WAV/M4A`) and send audio directly to Gemini multimodal (`inlineData`) — zero dependency on `com.google.android.tts`, superior Vietnamese accuracy & emotion awareness! (When active chat provider is Claude or ChatGPT, uses OpenAI Whisper if OpenAI key exists, or Android `SpeechRecognizer` if available.)
  - **Fast Local / Offline Command Recognizer:** Vosk offline Vietnamese recognizer handles short critical commands (`"ok"`, `"ô kê"`, `"ừ"`, `"bắt máy"`, `"nghe máy"`, `"cúp máy"`, `"dừng lại"`, `"tạm biệt"`) instantaneously even with zero internet or missing Google Speech Services.

### 5.2 Multi-Key Chat Failover & "Na Na / Em gái" State Machine
- **Encrypted Key Vault (`ApiKeyVault`):** Stores encrypted entries in Android Keystore-backed `EncryptedSharedPreferences`:
  1. `Gemini Pro #1` (auto-routes `AQ.` -> Vertex AI Express; `AIza` -> AI Studio)
  2. `Gemini Pro #2` (backup)
  3. Optional `Claude` (`sk-ant-...`) and `ChatGPT` (`sk-...`) keys
  4. `Free Key` (Gemini Free `AIza` or configurable free OpenAI-compatible key) powering **Em gái của Na Na**
  5. Optional dedicated `TTS Key` slot (e.g., AI Studio `AIza` / OpenAI key / custom neural TTS) — decoupled from chat keys!
- **Chat-Only Failover Policy:**
  - Only **chat-completion** failures (`HTTP 429`, `RESOURCE_EXHAUSTED`, quota errors, `401`/`403` invalid key) mark a paid chat key as `COOLDOWN` (default 60 minutes, resettable anytime via Settings) or `INVALID`. **TTS failures NEVER bench a healthy chat key.**
  - On request in `ACTIVE_NANA`, router tries healthy paid chat keys strictly in priority order (`Gemini Pro #1` -> `Gemini Pro #2` -> optional `Claude` -> optional `ChatGPT`). Switching Key #1 -> Key #2 is completely silent and seamless.
  - When **all** configured paid chat keys are exhausted/in cooldown:
    1. State transitions to `NANA_TIRED`.
    2. Na Na speaks in-character: *"Em đã hết lưu lượng rồi anh ơi, em chuyển qua trạng thái bình thường đây"* or *"Em mệt rồi, em nghỉ ngơi xíu nhé..."*.
  - Next time user calls `"Na Na"` while all paid chat keys remain in cooldown:
    1. Handled locally in `NANA_TIRED_AWAITING_CONSENT` without wasting a paid API call.
    2. Na Na plays/speaks: **"Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?"**
    3. App listens for `8 seconds` (using local fast recognizer + online fallback):
       - **Affirmative** (`"ok"`, `"ô kê"`, `"ừ"`, `"ừm"`, `"có"`, `"được"`, `"đồng ý"`): switches immediately to `ACTIVE_LITTLE_SISTER`, greets in Em gái's persona, and routes chat to the **Free Key** (with full access to shared Drive memory/history).
       - **Negative or timeout:** says a soft goodbye and returns to `IDLE_WAKE`.
  - **Recovery:** Once any paid chat key's cooldown expires (or user adds/resets a paid key), the next wake session automatically restores `ACTIVE_NANA`.

### 5.3 Persona & Decoupled Cute Female Voice Pipeline
- **Personas:**
  - **Na Na (`ACTIVE_NANA`):** Sweet, playful, devoted Vietnamese female anime/kawaii companion (addresses user as `"anh"`, self as `"em"` / `"Na Na"`). Speaks naturally in conversational Vietnamese sentences (no markdown bullets/symbols when speaking aloud).
  - **Em gái (`ACTIVE_LITTLE_SISTER`):** Cheerful, affectionate younger sister filling in while chị Na Na rests; reads the exact same `memory_facts` and conversation history so continuity is unbroken.
- **Decoupled TTS Priority Cascade (`TtsEngine`)** *(engineering call)*:
  1. **Gemini Neural TTS** (`gemini-2.5-flash-preview-tts`, `responseModalities: ["AUDIO"]`, `prebuiltVoiceConfig.voiceName = "Aoede"` — selectable among `Aoede`, `Leda`, `Kore` + Vietnamese cute anime style prompt, 24kHz PCM via `AudioTrack`): tries dedicated `AIza` TTS key first (if set), then healthy Gemini chat keys (`AIza` or `AQ.`). If an `AQ.` key returns `400`/`404` unsupported modality, marks only TTS capability unsupported for that key and falls through cleanly without touching chat health!
  2. **OpenAI Neural TTS** (`gpt-4o-mini-tts` / `tts-1`, voice `nova`/`shimmer`) if an OpenAI key is configured.
  3. **Vietnam-Ready Fallback TTS (Em gái & Offline/No-Cloud Mode):** Supports configurable free neural Vietnamese voice (e.g., downloadable on-device Piper/Sherpa-ONNX Vietnamese female INT8 ~20 MB voice pack OR Android `TextToSpeech` `vi-VN` tuned to `pitch = 1.28f`, `rate = 1.08f` when Google Speech Services is present).
  4. **Cached Transition Clip:** When cloud neural TTS succeeds on first run, caches Na Na's *"Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?"* prompt to local app storage so it plays instantaneously with Na Na's real cute voice even when paid keys are exhausted!

### 5.4 Device & Phone Control (Tool Calling + Local Fast Actions)
Structured tool calls from Gemini / ChatGPT / Claude, paired with an instant offline Vietnamese command parser for time-critical actions (`"bắt máy"`, `"cúp máy"`, `"ok"`):

| Action | Mechanism on Android 16 (iQOO OriginOS 6) | Required Permission |
|---|---|---|
| **Open YouTube & play/search music/video** | Launch YouTube search Intent (`com.google.android.youtube`) + optional `NaNaAccessibilityService` taps first non-ad video result when `auto_play = true` | Accessibility Service (optional for auto-play) |
| **Answer / decline incoming call (SIM + Zalo / Messenger / Telegram)** | **3-tier non-default-dialer chain** *(preserves OriginOS native call recording)*: (1) `NaNaNotificationListener` invokes incoming-call notification's Answer/Decline `PendingIntent` (works for SIM + Zalo/Messenger/Telegram); (2) `TelecomManager.acceptRingingCall()` / `endCall()` for SIM; (3) `NaNaAccessibilityService` taps Answer/Decline button. Optional caller announcement aloud before answering. | `BIND_NOTIFICATION_LISTENER_SERVICE`, `READ_PHONE_STATE`, `ANSWER_PHONE_CALLS`, `READ_CONTACTS` |
| **Speakerphone toggle** | `AudioManager` communication device routing | `MODIFY_AUDIO_SETTINGS` |
| **Make phone call** | Lookup contact by name -> `ACTION_CALL` | `CALL_PHONE`, `READ_CONTACTS` |
| **Open any installed app** | Fuzzy-match Vietnamese/English app label via `PackageManager` | `QUERY_ALL_PACKAGES` |
| **Alarms & Timers** | `AlarmClock.ACTION_SET_ALARM` / `ACTION_SET_TIMER` | `SET_ALARM` |
| **Volume / Flashlight / Web Search** | `AudioManager`, `CameraManager.setTorchMode`, browser Intent | Standard |

- Missing permissions never crash: Na Na explains sweetly what permission is needed and opens the exact Settings screen.

### 5.5 Call Recording (Local Temp Staging -> Wi-Fi Drive Sync)
- **Primary Strategy — OriginOS Native 2-Way Recording Watcher (`OriginOsCallRecordingWatcher`):**
  - User grants folder access once via Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) targeting OriginOS's native recording directory (guided default: `Internal Storage > Record > Call`, with hints for `Recordings/Call recordings` and `PhoneRecord`).
  - Triggered after call end (`CALL_STATE_OFFHOOK -> CALL_STATE_IDLE` after 4-second settle delay) and on periodic scan: detects new `.m4a` / `.aac` / `.mp3` / `.amr` / `.wav` files, copies them to app-private staging storage (`files/call_recordings_staging/`), and creates a `CallRecordingEntity` (`syncStatus = PENDING`).
- **Secondary Strategy — In-App Fallback Recorder (`InAppCallRecorder`):**
  - Optional Settings toggle for VoIP calls (Zalo/Messenger) or unrecorded SIM calls: records via `MediaRecorder` (`AudioSource.VOICE_RECOGNITION` / `MIC` + optional speakerphone toggle) into `files/call_recordings_staging/`. UI transparently explains Android 16's 2-way limitation for non-system recorders.
- **Verified Retention & Cleanup** *(engineering call, user-configurable)*:
  - Temporary staged files stay safely on device until Google Drive upload is **verified** (valid `driveFileId` + byte size match).
  - Default: delete app's staged temp copy 7 days after verified Drive sync (NEVER deleting OriginOS's original `Record/Call` file unless user explicitly enables it), with option "Giữ mãi bản tạm trên máy".
- **Optional AI Call Summary** *(default OFF)*: When enabled and a paid key is healthy, sends completed call audio to Gemini multimodal to add a brief summary note to Long-Term Memory.

### 5.6 Google Sign-In, Long-Term Memory & Strict Wi-Fi-Only Google Drive Sync
- **Authentication (OriginOS-Friendly & Reinstall-Safe):**
  - Google OAuth 2.0 via **AppAuth-Android (Chrome Custom Tabs PKCE)** — works cleanly on OriginOS China ROM without fragile Play Services dialogs.
  - Scopes: `openid`, `email`, `profile`, `https://www.googleapis.com/auth/drive.file` (restricts access strictly to Na Na's own Drive folder).
  - **Testing-Mode 7-Day Refresh Expiry Guard** *(engineering call)*: If Google returns `invalid_grant`, app NEVER deletes local pending messages/recordings — it pauses Drive sync cleanly and displays a 1-tap *"Đăng nhập lại Google Drive"* banner.
- **Local Offline-First Storage (Room SQLite):**
  - `messages`: `id`, `sessionId`, `role`, `persona` (`NANA` / `LITTLE_SISTER`), `content`, `createdAt`, `synced`.
  - `memory_facts`: `id`, `category` (`PREFERENCE`, `HABIT`, `RELATIONSHIP`, `SCHEDULE`, `EMOTION`, `FACT`), `key`, `value`, `confidence`, `updatedAt`, `synced`.
  - `call_recordings`: `id`, `phoneNumber`, `contactName`, `source` (`ORIGINOS_NATIVE` / `IN_APP`), `startedAt`, `durationSec`, `sourceUri`, `stagedPath`, `fileSizeBytes`, `mimeType`, `driveFileId`, `syncStatus` (`PENDING`, `UPLOADING`, `SYNCED`, `FAILED`), `aiSummary`.
  - `sync_state`: last Wi-Fi sync timestamp, Drive folder IDs, local manifest revision.
- **Long-Term Memory Extraction:**
  - After a conversation session ends (or every N turns), extracts/merges structured `memory_facts` (preferences, habits, favorite songs/foods, routines, emotional context) and injects compact relevant memory into every future system prompt.
- **Google Drive Layout (`AI_TALK_NaNa/`):**
  - `AI_TALK_NaNa/manifest.json` — sync index for reinstall restore.
  - `AI_TALK_NaNa/profile/memory_facts.json` — structured user profile & preferences.
  - `AI_TALK_NaNa/history/messages_YYYY-MM.jsonl` — monthly conversation logs.
  - `AI_TALK_NaNa/call_recordings/<YYYYMMDD_HHmmss>_<contact>.m4a` — uploaded via Google Drive **resumable upload protocol** so partial Wi-Fi drops resume without restarting from byte 0.
- **Strict Wi-Fi-Only Enforcement (Defense in Depth):**
  1. `WorkManager` constraint: `NetworkType.UNMETERED`.
  2. `WifiGuard` pre-flight & per-chunk verification: requires `TRANSPORT_WIFI` + `NET_CAPABILITY_INTERNET` + `NET_CAPABILITY_NOT_METERED`. Aborts immediately on cellular or metered hotspot and returns `Result.retry()`.
  3. Live `ConnectivityManager.NetworkCallback`: immediately cancels active Drive transfer coroutines the instant Wi-Fi disconnects; preserves all local items as `PENDING` until Wi-Fi returns.
- **Uninstall / Reinstall Restore:**
  - After reinstalling and signing in with the same Google account (and same OAuth Client ID): detects `AI_TALK_NaNa/manifest.json` on Drive; waits for Wi-Fi (strict default) and restores `memory_facts.json`, recent `messages_*.jsonl`, and call recording catalog into Room so Na Na immediately remembers everything about the user.

### 5.7 UI Screens (Jetpack Compose Material 3 — Dark-First Theme)
1. **Companion Home:** Dark cozy theme matching user's iQOO UI; animated Na Na / Em gái voice orb reacting to audio level; live state badge (`Đang nghe "Na Na"`, `Na Na`, `Em gái — Free`, `Mất mạng — Lưu tạm`); scrollable live transcript; Push-to-Talk button; Wi-Fi sync pill.
2. **Call Recordings:** Chronological recordings list with inline playback, status chip (`Lưu tạm trên máy` / `Đang đồng bộ Wi-Fi` / `Đã lên Drive`), expandable optional AI summary, and Wi-Fi-only manual sync trigger.
3. **Memory & History:** Searchable chat history + editable list of learned `memory_facts` (view, edit, or delete anything Na Na remembers).
4. **Settings & OriginOS 6 Setup:**
   - **API Key Vault:** Gemini Pro #1, Gemini Pro #2, Claude, ChatGPT, Free Key, optional dedicated TTS Key — masked, encrypted, "Kiểm tra key" button, live chat vs TTS health badges, manual cooldown reset.
   - **Persona & Voice:** Voice preset selector, Em gái handoff toggle, silence timeout, screen-off listening toggle (default OFF).
   - **Google Drive & Sync:** Google Sign-In status + 1-tap re-auth banner, last Wi-Fi sync time, pending queue counter, OriginOS `Record/Call` folder picker, local temp retention policy, optional AI call summary toggle.
   - **OriginOS 6 Permission & Survival Checklist:** One-tap shortcuts for Microphone, Notification Access (SIM + Zalo/Messenger call answering), Phone/Contacts, Accessibility Service (YouTube auto-play), Ignore Battery Optimizations, and Vivo/iQOO Autostart / High Background Power settings.

---

## 6. Decisions Left to the Builder

The implementation planner & builders may decide the following without re-asking the user:
- Exact Kotlin, AGP, Jetpack Compose BOM, Room, WorkManager, OkHttp, Kotlinx Serialization, Vosk, and AppAuth dependency versions (verified via Context7 during implementation).
- Gradle module structure (clean multi-module `:core:domain`, `:core:data`, `:app` or well-structured `:app` packages that allow pure-JVM TDD unit tests without an Android emulator).
- Specific editable default model IDs (e.g., `gemini-2.5-pro`, `gemini-2.5-flash`, `claude-sonnet-4-5`, `gpt-4o-mini` — all editable in Settings so newer Gemini 3.x/4.x models can be typed in anytime without rebuilding).
- Exact Compose animations, dark-theme color palette, and typography tokens.
- Exact GitHub Actions workflow YAML to compile, run unit tests, and publish downloadable APK artifacts on push.
