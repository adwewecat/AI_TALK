# Project Progress

> **Rotation Rule:** Prepend new entries to the top of **Recent Milestones**. Keep at most **10 entries** here; move older entries (#11+) to `ARCHIVE.md`.

## Recent Milestones

- **[2026-11-21] Built & verified complete Na Na (`AI_TALK`) Android app (`com.adwewecat.nana`)**
  - **Done:** Completed all 10 TDD implementation tasks across `:core` (domain models, multi-key failover + `"Na Na → Em gái"` quota state machine, Gemini `AQ.` Vertex Express + `AIza` AI Studio / OpenAI / Claude clients, decoupled TTS router, Vietnamese fast command parser + VAD + WAV encoder, Wi-Fi-only Google Drive resumable sync/restore/retention) and `:app` (Room SQLite, Android Keystore vault, AppAuth PKCE OAuth, WorkManager `NetworkType.UNMETERED` + live `NetworkCallback` Wi-Fi cancellation, Bluetooth-safe `"Na Na"` wake-word service, 3-tier SIM + Zalo/Messenger/Telegram call control without `ROLE_DIALER`, YouTube auto-play Accessibility service, OriginOS `Record/Call` watcher + in-app fallback, 4-tab dark-first Jetpack Compose UI, GitHub Actions CI, and `SETUP_ANDROID.md`). Passed independent per-task reviews, whole-branch review, and final fix wave (`70/70` unit tests passing, `app-debug.apk` built cleanly).
  - **Issues Fixed:** Fixed Robolectric single-threaded Looper test deadlock (`@Test(timeout)` + `Dispatchers.Default`), Android 10+ null-caps `onAvailable` cancellation guard, PTT state recovery from interrupted `THINKING`/`SPEAKING` turns, production auto-memory extraction + `(category, key)` dedup + `NaN` confidence guard, one-shot tired-prompt PCM pre-caching, shared `GLOBAL_SYNC_MUTEX` across background/manual Drive sync, `isTtsOnly` key probe, and Robolectric-only plaintext prefs fallback.
  - **Next:** Install `app-debug.apk` (or grab from GitHub Actions artifact) onto iQOO Z10 Turbo Pro following `SETUP_ANDROID.md`, add API keys inside the encrypted Settings vault, and verify live OriginOS 6 permissions (`Record/Call`, Notification Access, Accessibility, Autostart).

- **[2026-11-21] Workflow initialized & repo connected**
  - **Done:** Connected workspace to `https://github.com/adwewecat/AI_TALK.git` (`main`); integrated `Skill_Workflow_A.I` (42 skills in `.agents/skills/` covering `obra/superpowers` + manual `mattpocock/skills`); verified global MCP config (`playwright`, `private-journal`, `context7`, preserving `rea`); created `.gitignore`, `GEMINI.md` (< 300 lines), and `PROGRESS.md` without overwriting existing `README.md` / `LICENSE`; removed temp clone folder.
  - **Issues Fixed:** Fixed UTF-8 mojibake in `GEMINI.md` written by Windows PowerShell 5.1.
  - **Next:** Brainstorm and finalize the design specification for the "Na Na" Android & iOS AI voice assistant & companion app before scaffolding code.
