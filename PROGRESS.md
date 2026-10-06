# Progress

Append-only log. Newest entry at the bottom; never edit past entries.

## 2026-10-06: Project setup

- Reviewed the PRD draft and changed two locked decisions: the app runs entirely on the Android phone
  (no Node companion, no PWA) and is built natively in Kotlin + Jetpack Compose.
- Login moves to an in-app WebView that reads Instagram's cookies; pasting `sessionid` stays as a fallback.
- Added `.gitignore` (Android/Gradle plus session and secret patterns), a project `CLAUDE.md`, and these docs.
- Found the GitHub repo is public; flagged to the owner.

## 2026-10-06: Design approved and spec written

- Brainstormed the design section by section with the owner and wrote it up in `docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`.
- New decisions: video streams on demand into a `pk`-keyed LRU cache; unsaves and collection moves are mirrored
  only by a manual Full sync; sync is manual-only through WorkManager; the session is checked per sync, not
  per app start.
- Safety model: one strictly sequential Pacer (4–12 s gaps, breaks, 300 per run, 600 per rolling 24 h), zero
  retries on challenges, escalating cooldowns on rate limits, and deletion only after a complete walk.
- An adapter spike on the owner's phone (M3) confirms endpoints, headers and `saved_collection_ids` before
  the real parsers are written.

## 2026-10-06: M0–M2 plan written

- 18-task TDD plan for M0 (skeleton, secret guard), M1 (full app on the fake backend) and M2 (WebView login,
  paced session validation); M3–M6 get their own plans after the M3 spike.
- Spec amended to match: `SessionProbe` split and `reportsSavedCollectionIds` on the adapter contract,
  progress columns on `sync_run`, one numbering scheme for quick and full walks, a separate fake-backend
  Pacer, and SDK level 36.

## 2026-10-06: M0 skeleton

- Two-module Gradle build (AGP 9.4.1 built-in Kotlin, Kotlin 2.4.20, Gradle 9.8.0); `./gradlew check` green.
- Backup and device transfer disabled, with a test that checks every domain is excluded.
- Pre-commit secret guard with a self-test script.

## 2026-10-06: M1 mock app

- Fake backend through the real sync engine and Pacer: 2,000 items across 8 collections, quick and full syncs, resume after process death.
- Home, grid, viewer (reused ExoPlayer, remembered mute), search (debounced FTS with filters) and the Sync screen.
- Device walkthrough passed on the Android emulator (Medium Phone, API 37, debug build); deviations and what is left to the owner:
  - The fake backend's fast pacing finishes a run in 4 to 8 s, so the "Syncing saved reels" notification never showed (Android holds foreground-service notifications back about 10 s). Fixed by asking for immediate display.
  - `adb shell am kill` does nothing while a sync runs (the foreground service keeps the process alive). A real `kill -9` and a force-stop mid-run both ended with WorkManager re-running the worker about 30 s later and finishing the run on its own, so "Interrupted, tap Sync to resume" did not appear. That banner (and Resume after it) was checked by planting a RUNNING row with no live work.
  - The kill also showed a real gap: the page being thumbnailed when the process died never got its thumbnails (20 items showed "Not available on Instagram" until the next Full sync). Fixed: a resumed scope retries the thumbnails of items the run already saw without one.
  - Sync screen shows the 24 h budget only inside the run counters, so right after Delete library it is hidden until the next run; it does keep counting (checked on the next run).
  - Debug-build scrolling on the emulator: 37 % janky frames (median 25 ms, 95th percentile 44 ms). Judged on the M6 release build.
  - Left to the owner: whether the clip is audible (the player was started and unmuted, then muted at the audio-track level, but no ears were involved) and how smooth scrolling feels on the phone.
