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

## 2026-10-07: M2 session, code complete

- WebView login screen, session controls on the Sync screen (state, Check now, Log out, masked Paste sessionid), one paced validation per new session; cookies shared with OkHttp; debug builds allow WebView remote debugging.
- Unit tests cover the validation-once logic, the paste outcomes (only Valid is accepted; a rejection keeps the current login and never shows an account handle), the https-only WebView client and the Sync-to-Login navigation.
- Not yet confirmed on a device: the login itself. Confirmed currentUser endpoint and X-IG-App-ID: pending owner check.
- M2 stays open in `TODO.md` until the owner runs the on-phone checklist (login, relaunch with no request, Check now, logout, optional paste).

## 2026-10-07: M0-M2 final-review fixes

- Sync screen: Log out is now offered in every state but LoggedOut (Valid, Expired, Challenge), and Challenge also offers Paste sessionid; the buttons wrap instead of truncating. Check now is coalesced like a paste (a double tap is one request). Until the stored session state has been read the section shows "Checking session..." and no buttons, so an early tap can't open Log in for a challenged session.
- Login screen: after any Challenge result it stops validating by itself for the rest of its life (Instagram may re-issue the sessionid mid-checkpoint) and shows the manual "Finished verifying on Instagram? / Check again" control. New `LoginPurpose.RELOGIN` for "Log in again": like LOGIN, but the stale session already in the jar is not re-checked. Both only remove requests; no pacing number, budget or concurrency changed.
- Settings: a corrupt DataStore file is replaced by a 1 h cooldown plus a rate limit stamped now (ruling R54), not by empty preferences, so a corruption can't silently end an active cooldown.
- `:app` no longer spells out Instagram URLs, the login host allowlist or cookie attributes: they moved to `:instagram` (`WebEndpoints`, `WebSessionCookies`), strings byte-identical.
- Sync screen: "Requests this run" is now "Requests (all attempts)" without a per-run denominator, which a resumed run could exceed.
- `TODO.md` ticked and given M3/M4/M5 gates and two owner checks for the M2 phone checklist; `ARCHITECTURE.md` brought up to date (settings keys, RELOGIN, where the Instagram strings live).

## 2026-10-07: M3–M6 code complete (real sync, Adapter lab, video, release build)

Every line was written and tested without any agent contacting Instagram (MockWebServer and fakes only; the emulator stayed in Mock mode or logged out).

- **Real adapter.** `WebInstagramClient` with `WebParsers`, `MediaLinks` and `WebEndpoints` for the four candidate endpoints (shapes from instaloader and instagrapi, pinned by synthetic fixtures), a no-redirect API client with a session guard, and a separate cookieless CDN client that makes one request per download.
- **Adapter lab (M3).** A pure-JVM core (one request per call, a redacted shape, a scrubbed copy) and a debug-only screen under Sync → Developer. Spec 6.4's "a redacted shape dump is saved for the Adapter lab" on a sync shape change is not built: the lab reproduces the call instead.
- **Real sync (M4).** `Backend.Real` on the one Conservative pacer; a debug-only Mock mode (default on) with separate libraries (`reels.db` fake, `library.db` real) and a shared request log; the engine's delete-safety gates and the session gates from the M0–M2 final review; truncated rather than clamped gaps, and the gap seeded from the persisted request log.
- **Video (M5).** Links renewed on the interactive lane only when expired and only under a valid session, a `pk`-keyed 512 MB cache, one refresh after a 403/410, next-item prefetch, and the viewer on a `TextureView`.
- **Release (M6).** Signing from `keystore.properties` with a debug-key fallback, `androidx.profileinstaller` plus a hand-written baseline profile, and an R8 build smoke-tested on the emulator.
- **Docs.** `README.md` rewritten as one guide (setup, first real sync, the Adapter lab, the on-phone checklist, release build, troubleshooting); `ARCHITECTURE.md` restructured to the current state; `TODO.md` ticked, with the owner's steps listed.
- **Safety fixes the reviews found,** each a real defect in a first version:
  - The parser silently skipped an item with a missing or mistyped `media`, which would have let a Full sync delete it; a page without a real `more_available` boolean is now a shape change, never "last page".
  - The lab treated a 429 whose body could not be read as a network error, so the cooldown would not start; such answers are now classified from their headers.
  - A future-dated row in the request log could stall the pacer for days; the restart gap is now seeded from the log and clamped to now.
  - The CDN client silently re-sent after a 503 `Retry-After: 0` or a 421, a second request the pacer never counted; it now follows no redirects, retries nothing, strips the header, and turns a 421 into a failure.
  - The reconcile guard's denominator counted the feed's own new items, so a foreign feed of 100 new items over a 100-item library would have wiped it; it now counts only items that existed before the run.
  - The session guard read the old session from the jar after the connection was up, so a logout during the handshake could write it back; it now compares the cookie the request actually sent. Logout now always forgets the session, even if cancelling the run fails.
  - A Mock mode restart could have replayed the other library's queued run, and the 24 h request log would have been orphaned in `reels.db`; work now carries its library, the switch cancels queued work first, and the log is copied once.
  - The video path could send a link request, a prefetch included, with no valid or a challenged session; it now sends nothing unless the session is valid, checks again inside the pacer's gate, and plays a fully cached video with no request.
  - The release build crashed on launch under R8 (an enum navigation argument); a keep rule fixes it, exception names are kept so `lastError` stays readable, the pre-commit guard now refuses signing files, and the README's keytool step was corrected to one password.
- **Left for the owner** (all on the phone, in the order of README sections 5, 7, 8 and 9): run the M2 checklist and confirm `X-IG-App-ID`; turn Mock mode off, log in and run the Adapter lab, then hand back the seven spike answers (and flip `SAVED_COLLECTION_IDS_CONFIRMED` only if Q2 says so); the first real Sync, then the first real Full sync, checking that each collection feed ends correctly; video playback and offline cache; and `installRelease`, with a real keystore if wanted, plus the release-build checks. Also decide the repo's visibility.

## 2026-10-07: M3–M6 final-review fixes

- A running sync now checks the session inside the pacer's gate before every request (its first `currentUser` included): a challenge, an expiry or a replaced session stored by any other lane stops it before its next request, and an Android re-run under a challenged session sends nothing. Only removes requests.
- The Full sync guard counts as "the library" only items first seen before the last finished sync, so items left by a refused, paused or discarded run can't make a large removal look small; and a library remembers its Instagram account, so a run under another account stops before writing anything (Delete library to switch).
- Video: the player no longer retries a refused link by itself (the renew-once path handles 403/410); Mock mode has its own video cache. The Adapter lab re-checks the session inside the gate.
- Docs: Android may re-run queued sync work by itself (paced, budgeted, session-gated); a 403 on one reel's link renewal reads as an expired session (on-phone check); two new known limits in `TODO.md`.

## 2026-10-07: On-device smoke tests

- Added an Android instrumented suite (`app/src/androidTest/`) that drives the debug app through its real UI on the emulator, in Mock mode: launch, Sync (logged-out session, Developer section, a fake Sync to the end), grid, viewer, video playback and Search, with a numbered screenshot per step. It skips on a physical phone and fails unless Mock mode is on, both before the Activity launches; it taps nothing that logs in or reaches Instagram. No pacing or request-rate change.
- Production change: one test tag (`video-surface`) on the viewer page that has the player.
- Build: androidx.test runner and rules 1.7.0 and Espresso 3.7.0 (Compose's 3.5.0 crashes on API 37); `gradle.properties` keeps the app installed after `connectedDebugAndroidTest` so a run never deletes a device's app data.
