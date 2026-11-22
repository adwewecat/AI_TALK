# Project Instructions & Architecture

> Keep this file concise (strictly under **300 lines**). On session start, read ONLY `GEMINI.md` and `PROGRESS.md`.

## Core Architecture

- **Overview:** `AI_TALK` (**Na Na**, `com.adwewecat.nana`) — native Android voice assistant & AI companion optimized for **iQOO Z10 Turbo Pro (OriginOS 6 / Android 16)**:
  - Offline `"Na Na"` wake-word Foreground Service (`TYPE_BUILTIN_MIC` to protect Bluetooth A2DP stereo music) + continuous two-way Vietnamese voice sessions + Quick Settings Tile & optional `VoiceInteractionService`.
  - Multi-key & multi-provider AI failover (`Gemini Pro #1` -> `Gemini Pro #2` -> optional `Claude` / `ChatGPT` -> Free tier **"Em gái"**) with auto-routing for Vertex AI Express (`AQ.` -> `aiplatform.googleapis.com`) and AI Studio (`AIza` -> `generativelanguage.googleapis.com`), exact Vietnamese `"Na Na → Em gái"` handoff (`"Em vẫn đang mệt, anh có muốn tâm sự với em gái em không?"` -> `"ok"`), and decoupled Chat vs TTS health.
  - Hands-free Android 16 device control: YouTube voice search + Accessibility auto-play, 3-tier SIM + Zalo/Messenger/Telegram call answer/decline (preserving Vivo/iQOO stock dialer — never declares `InCallService` or `ROLE_DIALER`), and OriginOS `Record/Call` call-recording SAF watcher + in-app fallback.
  - Offline-first Room SQLite storage, Keystore-encrypted API key vault (`EncryptedSharedPreferences`), AppAuth PKCE Google Drive OAuth (`drive.file`), long-term companion memory extraction, and strict **Wi-Fi-only** (`NetworkType.UNMETERED` + `TRANSPORT_WIFI`/`INTERNET`/`NOT_METERED` + live `NetworkCallback` cancel) resumable Drive sync & reinstall restore.
- **Key Directories:**
  - `core/src/main/kotlin/com/adwewecat/nana/core/` — Pure JVM library (`model/`, `quota/`, `ai/`, `voice/`, `sync/`).
  - `core/src/test/kotlin/com/adwewecat/nana/core/` — Pure JVM & `MockWebServer` unit tests (52 tests).
  - `app/src/main/kotlin/com/adwewecat/nana/` — Android app (`data/`, `voice/`, `actions/`, `di/`, `ui/`, `NaNaApplication.kt`, `MainActivity.kt`).
  - `app/src/test/kotlin/com/adwewecat/nana/` — Robolectric Android unit tests (34 tests).
  - `.github/workflows/android-build.yml` — CI test & debug APK build workflow.
  - `docs/superpowers/` — Approved design spec & 10-task TDD plan; `SETUP_ANDROID.md` — iQOO setup guide.
  - `.agents/skills/` — Installed `Skill_Workflow_A.I` suite (42 skills).

## Tech Stack

- **Language / Runtime:** Kotlin `2.1.0`, OpenJDK `17`, Gradle `8.9`, AGP `8.7.3` (`minSdk = 26`, `compileSdk = 35`, `targetSdk = 35`).
- **Frameworks / Libraries:** Jetpack Compose BOM `2024.12.01` (Material 3 dark-first), Room `2.7.1` (KSP `2.1.0-1.0.29`), WorkManager `2.10.0`, OkHttp `4.12.0`, Kotlinx Serialization `1.7.3`, Coroutines `1.9.0`, AppAuth `0.11.1`, Security Crypto `1.1.0-alpha06`, Vosk Android `0.3.75`.
- **Testing:** JUnit 4, MockWebServer `4.12.0`, Robolectric `4.14.1`, Coroutines Test `1.9.0`, WorkManager Testing.
- **Database / Infra:** Local Room SQLite (`nana.db`) + Android Keystore vault + Google Drive API v3 (`AI_TALK_NaNa/`, strictly Wi-Fi-only).

## Build, Test & Lint Commands

```bash
# Run pure-JVM core tests (fast, < 5s)
.\gradlew.bat :core:test --no-daemon

# Run full unit test suite (:core + :app Robolectric, 86 tests)
.\gradlew.bat :core:test :app:testDebugUnitTest --no-daemon

# Build debug APK (outputs app/build/outputs/apk/debug/app-debug.apk)
.\gradlew.bat :app:assembleDebug --no-daemon
```

<!-- skill-workflow-ai:start -->
## Workflow & Memory Rules (Skill_Workflow_A.I)

### 1. Memory & Auto-Init (Context Budget Protection)
- **Layered Session Startup:** At the start of every session, read **ONLY `GEMINI.md` and `PROGRESS.md`**. Do NOT bulk-read `ARCHIVE.md` or other `.md` files unless a path is explicitly named in the current task or loaded as an active skill (`SKILL.md`).
- **Auto-Initialization:** Before writing or modifying code, verify all 4 root files exist: `.gitignore`, `README.md`, `GEMINI.md` (< 300 lines), `PROGRESS.md`. Create any missing file immediately; leave existing ones intact.
- **History Rotation (`PROGRESS.md`):** After each verified change or bugfix, prepend a brief entry at the top of `PROGRESS.md`. Keep **at most 10 recent entries**; move older entries (#11+) into `ARCHIVE.md`.
- **Cross-Project Memory (`private-journal` MCP):** Before debugging unfamiliar issues or making architectural decisions, query `private-journal` (`search_journal`) for reusable environment fixes and patterns from past projects. After solving non-obvious issues, save generalizable takeaways via `process_thoughts` (`technical_insights` / `world_knowledge`). Keep `.private-journal/` gitignored.

### 2. Execution Workflow & MCP Standards
- **Main Backbone (`obra/superpowers` — Auto-On):** Follow for every task: load `using-superpowers` -> `brainstorming` / `writing-plans` -> split work into independent tasks -> dispatch fresh-context subagents per task (`subagent-driven-development` / `dispatching-parallel-agents`) -> strict RED-GREEN-REFACTOR `test-driven-development` -> run `verification-before-completion` with real command output before claiming success.
- **Up-to-Date Library Docs (`context7` MCP):** *"Luôn gọi Context7 để tra cứu API trước khi viết code tích hợp thư viện ngoài"* — always call `context7` (`resolve-library-id` -> `query-docs`) for current API docs and examples before writing third-party library or SDK integration code.
- **Browser UI Verification (`playwright` MCP):** When changes touch web/frontend UI, include `playwright` MCP in `verification-before-completion`: open the localhost URL, inspect the Accessibility snapshot, test core interactions, and check browser console/runtime errors before `git commit`.
- **Manual / Milestone Tools (`mattpocock/skills` — Manual Only):** Never auto-trigger on routine tasks. Run ONLY when the user chats one of these exact trigger phrases (or invokes the matching slash command):
  - **`quy trinh moi`** *(skill `grill-with-docs` — use when initializing a project or adding a major feature)*: Act as a strict reviewer, ask one question at a time on business logic and input/output until zero ambiguity remains, then lock canonical terms in `GLOSSARY.md` and decisions in `docs/adr/`. Never auto-trigger on routine bugfixes.
  - **`nghi ngoi`** *(skill `handoff` — use at end of workday or when chat gets long)*: Summarize completed work at the top of `PROGRESS.md` (keep max 10 entries; move older entries #11+ to `ARCHIVE.md`) and write clear handoff instructions for the next session.
  - **`cau hinh`** *(skill `wizard` — use for off-code manual setup)*: Generate an interactive terminal bash script guiding the user step-by-step to configure `.env`, API keys, GitHub Secrets, or environment setup.

### 3. Clean Code & Git / GitHub Standards
- **Clean Code:** Immediately delete unused variables, functions, imports, and dead files. Comment only **WHY** (non-obvious invariants, edge cases, special error handling); never comment obvious code.
- **Git & GitHub:**
  - Never push failing, untested, or unverified code.
  - `git commit` locally only after finishing and verifying each task.
  - `git push` to GitHub right after completing a full feature, a complete bugfix, or before ending a session — always inspect `git status` and staged diff first to ensure no `.env`, secrets, keys, or credentials are included.
<!-- skill-workflow-ai:end -->
