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

## 2026-10-08: Hardening batch (the known limits that need no phone)

Six small commits on `claude/hardening`; each started with a failing test. Every test used fakes: nothing contacted Instagram, and no pacing number, budget or concurrency changed (H1 and H4 only remove or end work, H2 only reads).

- **H1, a new login starts a new session epoch.** `SessionRepository` remembers the session fingerprint behind the current epoch. `validate()` ends the epoch when the jar holds another session, before its request (a sleeping run resumes while the check waits for the Pacer) and again after it (a login that lands mid-flight discards the answer, like a logout), always before it stores. A check of the same session leaves the epoch alone, so Check now never stops a running sync. Paste, its rollback and logout record their own session; nothing reads the jar at construction (the first `epoch()` or `validate()` does). The known limit is removed from `TODO.md`.
- **H2, Mock mode shows the real Pacer.** With the fake backend the Session section adds one line, "Instagram requests in 24 h: X / 600" or "Instagram requests paused: N min left (cooldown)", read from `instagramPacer` with `status()` only. Nothing is added with the real backend. The known limit is removed; README's Mock mode section says what the line is.
- **H3, `*.p12` and `*.pfx`.** The pre-commit hook refuses them (case-insensitively, force-added too) and `.gitignore` lists them; `scripts/test-secret-guard.sh` has the new cases.
- **H4, no crash on a storage failure.** Logout and Delete library catch non-cancellation exceptions and say so on the screen's message line, with no exception text. Delete library still removes thumbnails and cached videos when only the account record could not be cleared.
- **H5, one cookie parser.** `SessionGuard` and `CookieStore.cookieValue` both call `cookieValueIn(header, name)`, behaviour unchanged, pinned by a table of edge cases and source pins.
- **H6, the lab's digit-run check.** It strips the whole sentence punctuation set first, so `31:00:00:00:00` and `(31)(00)(00)` are hidden; the KDoc says "one word of letters or underscores, any case".

## 2026-10-08: Hardening batch, fix round 1 (review findings)

- **The gate also checks the account (H1 gap).** The epoch ended only in `validate()`, so a WebView login as another account before the next login-screen poll (or after the owner had left that screen) let a running sync send its next page with the other account's cookies. The epoch's record now holds the jar's `ds_user_id` next to the sessionid fingerprint, and `runSession`, the lock-free in-gate check, says NOT_USABLE when the jar's account is not the recorded one (a re-issued sessionid of the same account is fine). The Adapter lab and the video resolver ask the same check from inside their gates. One request can still be past the gate when the cookies change; written down in `TODO.md` and `ARCHITECTURE.md`.
- **`epoch()` never throws.** It reads the jar, and `CookieManager` throws without a WebView provider; it is called outside every try. It now records nothing and retries on failure, and the engine reads it inside its try (a run ends PAUSED "Unexpected error" instead of staying RUNNING).
- **Delete library messages** moved under the Delete library button (a Storage message line) and say exactly what was left: the account record, only cached files ("Library deleted; some cached files couldn't be removed"), or, only when the delete itself failed, "Couldn't delete the library; try again". `ThumbnailStore.deleteAll()` reports a file it could not remove.
- Tests: a first-validate test that really reaches the "nothing recorded yet" branch; a dot-ends-each-group case for the lab's digit rule; the any-case one-word rule pinned for every visible-value key. `TODO.md` gets an on-phone check for whether Instagram re-issues `sessionid` on ordinary responses; docs no longer claim the login screen validates every new session.

## 2026-10-08: review test gaps closed

- Tests now pin that the Adapter lab and the video resolver check the session they started under (not the one current when the pacer gate opens), that Delete library keeps removing every other thumbnail when one cannot be removed, and that the storage message clears when a new Delete library starts. Each test was shown to fail under the mutation it guards against. No app code changed.

## 2026-10-08: debug builds log a redacted summary of Instagram error replies

- The app's first real request (the login check) was classified as a rate limit and armed a 1 h cooldown, but the debug log shows headers only and the phone's log buffer had rotated, so what Instagram said was lost. Debug builds now log one line per non-2xx reply (`ErrorReplyLogger` in `:instagram`, a network interceptor registered only when a logger is passed): `status`, `message`, `error_type`, `require_login`, `spam`, `lock` and the sorted top-level key names, each value through the Adapter lab's `isVisibleString` (else `<redacted len N>`) and each key through `isSafeName` (else counted). A body that is not a JSON object logs its content type and size only. No URL, cookie, id, handle or raw body; 2xx untouched; release builds unchanged.
- It amends spec 4.4's "response bodies are never logged" for debug builds only, with these allowlisted fields (noted in `ARCHITECTURE.md`). The peek is at most 16 KiB and does not consume the body; it un-gzips the peek itself because a network interceptor sees the wire bytes (a test fails without that). No request is added: no pacing, budget or concurrency change.
- `README.md` troubleshooting: after a login check or Check now ends in a cooldown, run `adb -d logcat -s InstagramHttp` before retrying and paste the `reply:` line.

## 2026-10-08: API calls move into a hidden instagram.com WebView page (Tasks 1 to 5)

- **Why.** On the owner's phone the app's first two Instagram API requests (the login check, `GET /api/v1/users/<id>/info/`, sent by OkHttp) were answered HTTP 429 with an empty HTML body in 0.4 s: a refusal, not a quota. A capture of the mobile website from the same WebView showed OkHttp differing in almost every header the site uses (the desktop app id, no `x-asbd-id`, no Sec-Fetch headers or client hints, no claim header) and in its TLS and HTTP/2 fingerprint, which copied headers can't fix. So the calls now run in the browser that already holds the session. Spec and plan: `docs/superpowers/specs/2026-10-08-webview-transport-design.md` (with an "Amendments during implementation" section) and `docs/superpowers/plans/2026-10-08-webview-transport.md`.
- **Task 1, a transport seam in `:instagram`.** `InstagramTransport` makes one GET per call and answers a `RawReply`; `classifyReply` is the one rule for every transport (a redirect is `ChallengeRequired(null)`, a null body is `Transient` on a 2xx or 5xx and uses the header-only rules on a 3xx or 4xx). The client, the session probe and the lab take a transport. The login check moves to `api/v1/accounts/edit/web_form_data/` (`form_data.username`, the pk from the `ds_user_id` cookie), `WebHeaders.APP_ID` becomes the mobile web id `1217981644879628`, `X-ASBD-ID` `359341` is added, and `ErrorReplySummary` takes over the error-reply line so any transport can write it. `OkHttpTransport` stays, for JVM tests.
- **Task 2, the page.** `WebViewTransport` (logic, over a `WebPage`), `AndroidWebPage` (the hidden WebView) and `ig_fetch.js`. One page, loaded once from the home URL; one same-origin `fetch` per call, matched to its reply by id; the load and each call bounded at 30 s. Where the page is gets checked before every call: a landing on login or a challenge is remembered until `reset()`, another site is `Transient`, a home page answered 429 is `RateLimited` (the cooldown arms with no API request made), a dead renderer fails the call at once. The page reports back through `addWebMessageListener` for the Instagram origin only, from the main frame, as a string. `addJavascriptInterface` is used nowhere (a source pin). The login screen's WebView also survives a renderer crash now.
- **Task 3, wiring.** `AppContainer` builds the one transport, lazily, for the real client, the probe and the lab; the OkHttp API client is gone from `:app`'s `src/main` (only the CDN client remains; the client's classes still live in `:instagram`'s `src/main`, used only by tests). Logout, a paste and its rollback, Delete library, and a check that starts on a stored state that is not Valid destroy the page first; a sync run, a check and a lab tap each start a new user action for the page cap (3 pages per action). Mock mode never builds the transport (source pins, and a test that starts a fake run). Source pins keep `OkHttpTransport`, `HttpClientFactory.create(` and `instagramHttp` out of production and make `WebViewTransport` the only `InstagramTransport`.
- **The idle close.** The hidden page is a live single-page app, and its own background requests would run for as long as the process lives. It is now closed after 5 minutes without a call, which a sync run normally never reaches (the Pacer's breaks and backoffs are shorter). It gives back the page cap. The cost is one more home-page load per active period.
- **Task 4, the emulator.** `AndroidWebPageTest` runs the real page, the real script and the transport against local MockWebServers on `127.0.0.1` (it skips on a physical phone; the smoke suite now also fails if Mock mode ever built the transport). Findings: the platform accepts `http://127.0.0.1:<port>` as the bridge's origin and allows loopback cleartext with no config on API 37 (`CleartextGuardTest` pins that no release source set permits cleartext); a frame of another origin gets no `window.igBridge` at all, while a same-origin subframe does and is stopped by the main-frame check; the home navigation carries `X-Requested-With: io.github.yuriimurha.reels` and cannot be stopped on this WebView (`androidx.webkit` 1.17.1's allow-list setter is a deprecated no-op, unsupported on WebView 145), so it stays (R100), while the API `fetch` carries only the script's own `XMLHttpRequest`.
- **Task 5, docs.** README: a box "After an update that changes how the app talks to Instagram" under the first real sync (wait out any cooldown, one Who am I, paste the `InstagramHttp` lines, only then the rest), the M2 expected log line `GET api/v1/accounts/edit/web_form_data/ -> 200`, and the new log format. `ARCHITECTURE.md`: a Transport section and every stale OkHttp, `instagramHttp` and `users/{id}/info` mention fixed. `TODO.md`: the app id (`1217981644879628`) and the `X-Requested-With` question ticked, the phone check added. Nothing was deleted: `SessionGuard`, the cookie bridge and the OkHttp API client are used only by the JVM tests, which build their MockWebServer client with them, so removing them is a separate clean-up (`TODO.md`, "Later").
- **Pacing and safety.** No rate, budget or concurrency changed: every API call is still one `fetch` through the one Conservative Pacer and its in-gate session check. What is new is unpaced traffic the Pacer does not count, the home-page load and the site's background requests while the page exists (what any visit to instagram.com sends), bounded by the page cap and the idle close and described in `ARCHITECTURE.md`. No agent contacted Instagram: every test used fakes, MockWebServer or a local server, and the emulator stayed in Mock mode or logged out.
- **Next, the owner's phone.** The hidden page has never met Instagram. Wait out any cooldown, then follow the README box: one Who am I, and paste the `InstagramHttp` lines.

## 2026-10-08: WebView transport, final review fixes

- **A cancelled caller (R104, R104a, R105).** A caller cancelled while the hidden page loads (a swipe in the viewer) no longer drops the page: the load finishes under its own 30 s bound and its outcome is applied, so swipes during a cold load cost one page instead of using up the cap of 3 and failing every link refresh after them. The caller always ends with its own cancellation; a 429 home page found for it is remembered once and answers the next call `RateLimited`, so the Pacer still arms the cooldown. A call cancelled while it waits for its reply aborts its fetch in the page (`window.__igAbort`), as OkHttp cancelled a call, so no two of the app's API requests are open at once.
- **The page closes when the session needs the owner (R106).** Storing an Expired or a Challenge, and opening the login screen to log in again or to verify, closes the hidden page at once (it keeps the transport's verdicts) instead of leaving the site running for up to 5 minutes beside the login. ARCHITECTURE now says which paths destroy the page before the app changes the cookies.
- **A sync run re-checks the session when a request returns (R107)**, before it writes the answer, so a paste or a login as another account during a request can never put that session's page or account into the library.
- **Tests and pins.** The hidden page's `WebViewClient` is a testable `PageClient`; new emulator tests (local servers only) for a 429 home page, a reset during a load and during a call, a reply that never comes, an aborted call, the stored claim header, a page that moved itself and a dropped connection; source pins for the transport's wiring and the page's default origin (now pinned to `WebEndpoints.HOME_URL`); the script pin checks every field it posts. The landing rule moved into `:instagram` (`WebEndpoints.landingOf`). A same-origin frame can speak through `parent.igBridge`: the trust boundary is the instagram.com origin, which docs and spec now say (R109).
- **Docs.** README's rollout order is now: Mock mode off, then the box (instead of step 3, since the box covers the login), then the lab and Sync. A correction to the entry above: its "one Who am I" is one request, Who am I if Sync says "Logged in as", otherwise the login check (R102). Resolving the page load on `DOMContentLoaded` is parked in `TODO.md` (R108).
- **Pacing.** No rate, budget or concurrency changed. A cancelled call now ends its request at once, a cancelled caller's load is finished rather than repeated (fewer home-page loads), and a session that needs the owner costs one more home-page load on the next call. No agent contacted Instagram; the emulator ran local servers only.

## 2026-10-09: collection names through the website's own query, self-repairing (Tasks 1 to 6)

- **Why (the phone findings).** The first phone tests of the WebView transport (2026-10-08/09, a throwaway account with 10 saved
  posts in 3 collections) went: the login check 200; All Saved (`api/v1/feed/saved/posts/`) 200, with `saved_collection_ids` on
  every item (spike Q2: yes); the collections call (`api/v1/collections/list/`) **404**, the site's own "page not available", so
  a sync stopped at "Listing collections". Captures of the website itself (the debug WebView, values never read) showed its
  mobile Saved tab has no collections at all, while its desktop one lists them with one GraphQL query,
  `PolarisProfileSavedTabContentQuery` (`POST /api/graphql`, a `doc_id` that Instagram changes whenever it deploys the site).
  The same query sent once from the mobile hidden page, with the page's own tokens and the mobile app id, was answered 200 with
  the same edges: All posts and the 3 collections, with their names. The owner wants the names, and wants the app to keep them
  working with no computer.
- **The design.** Spec `docs/superpowers/specs/2026-10-09-collection-names-design.md` (with an "Amendments during implementation"
  section) and plan `docs/superpowers/plans/2026-10-09-collection-names.md`:
  - the names come from that query, sent by the same hidden page (`InstagramTransport.graphql`, an allow-list in `:instagram`,
    `window.__igGraphQl`, the page's tokens read and used only in the page);
  - when Instagram rejects the stored `doc_id`, a second, separate hidden page in desktop mode (Chrome-on-Android's "Desktop
    site" identity, a 1440 x 900 CSS-pixel window, `ig_watch.js`) views the owner's own Saved page and watches the site's own
    request for that one query; the app takes the new id from it, at most once per 24 h and inside the Pacer's gate;
  - when that fails the sync goes on with the last names, collections no name covers become "Collection N", and Sync says
    "Couldn't refresh collection names";
  - `SAVED_COLLECTION_IDS_CONFIRMED` is `true` (strategy A): a sync walks All Saved once and reads each item's collections from
    the item;
  - a Developer action, **Forget collections query id**, makes the next names query stale, so the owner can watch one real repair.
- **The tasks.**
  - **Task 1** (a phone spike for the three facts the parser assumes) did not run: the phone was not connected. STALE (how a
    rejected id is answered), CURSOR (the page-2 variable, `after`) and AUTO (the automatic collections have non-digit ids) are the
    plan's stated defaults, marked "assumed" in the code, and the phone rollout verifies them.
  - **Task 2**, the GraphQL transport: one allow-listed POST from the hidden page, tokens read and used in the page only, a
    digits-only doc id, the form and header names as `:instagram` constants that the script pins use.
  - **Task 3**, the repair page: `AndroidRepairPage`, `DesktopSite`, `ig_watch.js`, refused on a WebView that cannot do desktop
    mode.
  - **Task 4**, the parser, the stale rule, the doc-id store and the client; `WebEndpoints.collections` and the 404 call are gone.
  - **Task 5**, the sync: `QueryRepairer` with its 24 h limit, the fallback, the placeholders, the names-stale notice and
    Forget.
  - **Task 6**, the docs and the phone rollout steps: `ARCHITECTURE.md` (a "Collection names" section and every stale
    collections mention), the repo `CLAUDE.md` hard rule on what the page scripts may repeat, `README.md` (a rollout box, the
    lab and troubleshooting), `TODO.md`, and the amendments in both specs.
- **What the reviews found in first versions** (each fixed, with a test shown to fail without it):
  - the token pins did not stop a token being logged or beaconed from the page script;
  - the repair page said "Mobile" for its form factor and had no bitness, and laid out as 548 CSS pixels, not a desktop window;
  - `ig_watch.js` missed the site's real request shapes and its XHR path could throw;
  - a rate limit reported inside a GraphQL reply's `errors` (or a reply that had `data.viewer`) was classified as a stale id, which
    would have triggered repairs during a rate limit;
  - nothing stopped a page-2 cursor that never advanced (up to 300 identical requests per run).
- **Pacing and safety.**
  - A normal sync sends fewer requests: the per-collection feeds are gone, and the names query is one request per 12 collections.
  - A 2xx execution error with `data.viewer` is `Transient` and follows the existing retry, up to 5 requests.
  - A repair is one desktop page view of the owner's Saved page (the site's own requests, unpaced like the home-page load), at most
    once per 24 h; it costs one run-budget unit and one request-log entry even when the limit refuses it. Each tap of Forget
    re-arms one repair outside the limit. No rate, budget, gap or concurrency was raised.
  - No agent contacted Instagram: every test used fakes, MockWebServer or the emulator's local servers, and the emulator stayed in
    Mock mode.
- **Next, the owner's phone.** Wait out any cooldown, then follow the README box "After the collection names update": one Sync,
  the names on the grid, **Forget collections query id**, one more Sync, and paste the `InstagramHttp` lines. `TODO.md` lists
  what that settles.

## 2026-10-09: collection names, final review fixes

A final review in four lenses (security, concurrency, tests by mutation, plan/docs/pacing) and a refute pass; rulings R14 to R21.
Fixed in four commits and documented in a fifth:

- **The repair** builds no Instagram URL in `:app` any more: `WebEndpoints.savedPage(handle)` owns the Saved page's URL and the
  handle rule (D-C1). A site reply of another shape is a failed repair, so the sync keeps the last names (R14). The log says
  `repair: learned new id` only once the client has parsed the reply and kept the id (D-I2), else
  `repair: failed (reply stale|reply transient|shape …)`. A last attempt dated in the future is moved back to now, so a clock set
  back never stalls repairs longer than a day; a page whose `destroy()` throws no longer hides how the watch ended (C-M4); a
  collection a good listing had marked removed comes back under its old name, not as a new "Collection N". A 2xx names reply
  without `data.viewer` is stale with or without errors (R17).
- **Forget collections query id** no longer stores a made-up id (D-I4, R18, R21): it arms a persisted one-shot forced repair; the
  next sync sends no names query (`collections query forced`) and repairs once; the flag is spent only once the Pacer grants the
  attempt, is read by the real backend only, and the button is off while a run is going. A page without tokens is
  `QueryNotSent`: never retried, the page kept, the last names (C-M2, R20).
- **The hidden pages' console** stays out of the system log: both pages install one chrome client that handles console messages
  and cancels or denies dialogs and permission requests (S-M1, R16). The emulator shows a plain WebView's console reaching logcat
  and the hidden pages' never doing so.
- **Tests** close the mutation review's gaps (where the page script's form and headers may go, a failing notice store, the built-in
  id, placeholder numbers and places, a blank id, names kept with their white space) and drop or rename three tests that checked
  only their own helpers. In-band GraphQL errors read `error_type` too, and Delete library clears the names notice (C-M5).
- **Pacing.** No rate, budget, gap or concurrency was raised. Forget's sync sends one request fewer; a page without tokens costs one
  run-budget unit and no request instead of up to five units and three home-page loads; R17 can start a repair (at most one per
  24 h) where a run used to stop. The site is loaded at most 3 home loads per user action, plus at most one repair page view per
  24 h or per Forget; during a repair the idle mobile page and the desktop repair page are both live for at most 45 s, with no
  API call overlapping (R19).
- **What the phone rollout settles.** AUTO and a real repair. STALE stays assumed (Forget no longer sends a wrong id), and CURSOR
  stays unverified until the account has more than 12 collection edges, the automatic ones included.
- No agent contacted Instagram: fakes, MockWebServer and the emulator's local servers only, the emulator in Mock mode.

## 2026-10-09: a stale reply says what it was; a repaired reply that stops the run says why

Two follow-ups the final review of the collection-names change parked (PR #8), and what their own review changed:

- **R22, the stale rule kept, its log made clearer.** The parked item proposed that a 2xx names reply that is no GraphQL reply
  at all (an error envelope of the site's such as `{"error":1675030,...}`, a bare `{"status":"fail"}`) should stop being a
  stale query. The review of that change showed the cost: if that envelope is how Instagram answers an outdated id, every
  sync would stop with "Adapter needs repair" and never repair, which a phone without a computer can't get out of. So R17
  stays (such a reply is stale: one repair a day at most, else the last names), and the stale query now says what it was
  instead: the log reads `collections query stale (not graphql)` or `(http <code>)`, so "Instagram changed the id" can be
  told from "Instagram answered something else". The reply's other error text (a plain-string `errors` entry, an `errors` that is a string or a
  single object, an envelope's `errorSummary`/`errorDescription`) is now read for the rate-limit, login and challenge
  markers too, so a throttle sent that way never starts a repair.
- **The missing log line.** A repaired reply that itself reports a rate limit, a logout or a challenge (in its GraphQL
  `errors`) stopped the run correctly, but the debug log ended at `repair: start`. The client now hands the engine only its
  reply's failures (`repairCollections(onReplyFailure)`; never the repair page's own landings, which the repairer logs), and
  the engine logs `repair: failed (reply rate limit|reply login|reply challenge)` (and the earlier `reply stale|reply
  transient|shape ...`) from that one place.
- Pacing: unchanged (no new request, no retry; a throttle written as a plain string no longer starts a repair).
- Tests: `./gradlew check` 1285 (new: an envelope's own text keeping its meaning, the stale detail for envelopes and non-JSON pages, plain-string error entries, the
  reply-failure callback's contract both ways, the log line on a stopping repaired reply, an envelope that repairs).

## 2026-10-11: the phone rollout of the WebView transport and the collection names

The first real runs against Instagram, on the owner's phone (throwaway account, 10 saved posts in 3 collections), with the
build of main `b411e01` (PRs #7, #8, #9):

- **2026-10-08 (PR #7's build):** the hidden page loaded and the login check answered `200`; the saved feed answered `200`;
  the old `api/v1/collections/list/` answered `404`, which led to PR #8.
- **First real Sync (2026-10-11 17:45):** DONE with 3 requests (session check, the GraphQL names query, the saved feed),
  10 new items, 10 thumbnails, no failure, no repair (the built-in doc id was current). Three collections under their real
  names, holding 4, 4 and 2 posts, All Saved 10; no "All posts" card and no placeholder. This confirms fact AUTO and that the
  names query's `collection_id` matches the items' `saved_collection_ids` (strategy A records the memberships).
- **Forget collections query id, then one Sync (17:53):** `collections query forced`, `repair: start`, `repair: learned new
  id` (about 1.2 s), then the saved feed; DONE with 3 requests, the names intact, no "Couldn't refresh collection names".
  The learned id equals the built-in one: the repair page read it from the real desktop Saved page's own request, so the
  Chrome-on-Android "Desktop site" identity gets the desktop page and `ig_watch.js` catches the site's query.
- **One more Sync (17:56):** one `GRAPHQL PolarisProfileSavedTabContentQuery -> 200` with the learned id, no repair.
- **First Full sync (18:00):** DONE with 3 requests, 10 seen, nothing removed, every membership kept.
- **Video (18:03):** a reel played, looped and replayed offline from the cache in airplane mode. A reel Instagram no longer
  has did not play; its Instagram page confirmed it is gone. Neither needed an API request (the stored links were fresh).
- Still open: STALE (seen only when Instagram really changes the id), CURSOR (needs more than 12 collection edges), an
  expired video link renewed and a 403/410 recovered, and the release-build checks.
- Practical note: the phone's logcat main buffer is 256 KB and rotates within minutes, so the `InstagramHttp` lines of a run
  must be read at once or captured live (`adb logcat -s InstagramHttp > file`).

## 2026-10-12: viewer UX: carousels, icon buttons, the app icon and motion (Tasks 1 to 7)

Spec `docs/superpowers/specs/2026-10-12-viewer-ux-design.md` (its section 6 lists the rulings R1 to R22; R16 to R22 come
from the final review, in the 2026-10-13 entry below), plan
`docs/superpowers/plans/2026-10-12-viewer-ux.md`. Seven tasks; Tasks 1 to 6 were each reviewed, with a fix round where a review
asked for one:

- **Why.** The owner's first real use on the phone (2026-10-11) found four rough edges: a carousel post could not be swiped
  (the app kept only its first picture and a "1/N" badge), Mute and Open on Instagram were text buttons, navigation had no
  motion that related a post to its tile, and the app had no icon of its own.
- **Tasks 1 and 2, the data.** `WebParsers` reads each child of `carousel_media` into `RemoteCarouselItem`s from the saved feed
  the sync already downloads; an unreadable child leaves a post's items unknown, never a `ShapeChanged` (R4). Room goes to
  version 2 (`carousel_item`, `MIGRATION_1_2`, no destructive fallback); foreign keys are now enforced, so `MediaDao.upsert`
  must stay an in-place `@Upsert`. The sync replaces a page's items in that page's transaction, and an empty list keeps the
  stored ones (R2). The schema files ship as debug assets (R5). `FakeLibrary` gives Mock mode's carousels items.
- **Task 3, loading on demand.** `PacedMediaInfo` is the reel resolver's `mediaInfo` request and session gates, cut out and
  shared. `CarouselRepository` fills a post's items, downloads an image and resolves a video item, with at most one
  `mediaInfo` per `Visit`, spent by any outcome (R8). Images go through the cookieless CDN fetcher on the Pacer's CDN lane
  into `CarouselImageStore` (R1, R7); video items are cached under `{pk}_{position}` and play the bundled clip in Mock mode
  (R3), with the reels' cached-bytes table (R9). Eviction drops a post's `{pk}_*` videos (R6), each step on its own
  (`MediaEviction.eachStep`). The review found the CDN lane unpinned: fixed and pinned.
- **Task 4, the viewer.** A horizontal pager inside a carousel post, "N/M" and dots, the playable unit (post, item), one visit
  per settled post with its calls in a post-scoped coroutine, item 0 showing the stored cover, images downloading when they
  settle, no posters for video items (R10), and the next post's prefetch decided only on a post's first unit (R11). The review
  found the visit's sharing and the cross-post guards unpinned: fixed and pinned, with a seeded pager and a same-post re-settle
  that keeps its visit.
- **Task 5, icons.** Mute and Open on Instagram are icon buttons with tooltips, from Material Symbols (Apache 2.0, notice in
  `res/raw/license_material_symbols.txt`, R12); no `material-icons-extended`. The adaptive icon (`#0F6E56`, four masonry tiles
  with a play triangle) has a monochrome layer with the triangle cut out of its tile (R13).
- **Task 6, motion.** `Motion.kt`, a `SharedTransitionLayout` around the nav host, the key `media-<pk>` on the tile's picture
  and the viewer's settled page, as shared bounds with scale-to-bounds (R14); shared-axis X pages; predictive back, which
  needed the NavHost's own predictive transitions and `revealTile` (R15); nothing animates with animations off. On the
  emulator the grow, the shrink, the page slides and the back gesture were seen frame by frame; the first viewer open after
  an app start showed no grow there (a debug first composition stalls the main thread longer than the fade), and fell back to
  a plain fade, by design. The viewer's two player effects now run on `Dispatchers.Main.immediate` (a test once woke one on a
  Room thread); pinned since the final review.
- **Task 7, docs.** `ARCHITECTURE.md` has sections Carousels, Viewer, Motion and App icon, and the tests; the spec has its
  amendments; the README has a phone checklist and its text-button lines now describe the icon buttons.
- **Tests.** `./gradlew check`: 1097 app tests and 382 `:instagram` tests, lint with no errors. `connectedDebugAndroidTest` on
  the emulator in Mock mode: 54 of 54 at the last code change (the smoke suite gained a carousel swipe and a back from a far
  post). The new tests were mutation-checked: a mutant of each rule named above (the visit sharing, the CDN lane, the
  cross-post guards, the shared bounds, `revealTile`, a REPLACE upsert) fails a test, and the few survivors are equivalent in
  this version or covered by another guard. The teardown flakes seen then were fixed in the final review (below); a rare
  60 s hang in one `SyncViewModelTest` body stays open in `TODO.md`.
- **Pacing.** The sync is unchanged: it sends no new request, and its replies already carry the items. A carousel visit sends
  at most one `mediaInfo`, on the interactive lane (the fill and any renewal together), under the same session gates as a
  reel's link renewal. Carousel images download on the existing CDN lane, which can only lower a rate. No rate, budget or
  concurrency was raised, and item swipes never start a prefetch.
- **Safety.** No agent contacted Instagram, logged in, or touched the owner's phone: fakes, local data and the emulator in
  Mock mode only. Fixtures use synthetic ids and `cdn.example.invalid`-style hosts.
- **Next, the owner's phone.** README section 8, "The viewer update": install over the app, swipe a carousel, play a video item,
  reopen it offline, the icon buttons, the launcher icon and its themed layer, and the back swipe from a post and between pages.
  On the release build the first open after a cold start should grow from its tile; a debug build on a slow phone may only fade.

## 2026-10-13: viewer UX: the final review's fixes (R16 to R22)

A five-lens final review of the whole update (correctness, pacing with the adapter boundary and secrets, data and the
migration, tests by mutation, docs), then refuters for its findings, then one fix round. Spec section 6 records R16 to R22.

- **Fixes.**
  - **C1, a list moving under the viewer.** A sync that saved or removed a post above the viewed carousel reset it to item 1,
    stopped its video item and, for one frame, handed the player the post that slid in (the pager re-keys a moved post only in
    its next measure). The viewer now reports a settle only where the list and the pager's layout agree; a placeholder row
    always settles.
  - **R16.** A carousel video item the player cannot reach says "Offline: this item isn't cached yet" unless all its bytes are
    cached, as an unviewed image does.
  - **R20.** A carousel whose items change shape (a slide deleted, an image swapped for a video) drops its downloaded item
    images and cached item videos, which are keyed by position: the sync after its page commits, a renewal after its store.
    The same items with renewed links keep everything. A reorder that keeps every position's type is a known gap (TODO).
  - **D3.** No image file is written, and no request sent, for a post a sync has marked removed.
  - **R21.** `versionCode` 2 follows the Room schema, with a guard test, so a build from before the update is refused
    (`INSTALL_FAILED_VERSION_DOWNGRADE`) instead of failing at every start; the README says never to uninstall to get past it.
  - **Smaller.** The schema assets merge after Room writes them (D4); a tile taller than the grid aligns by its top on return;
    the manifest's back flag says API 33 and the glyphs' notice is kept (lint warnings 18 to 16).
- **Test gaps closed.** A held drag reports no settle; the bright dot, the black placeholder and Fit for item pictures; R2 on
  the repository's renewal and fill; the log lines carry no link, host or pk; every v1 table keeps its rows; the shared-axis
  direction forward and back and the nav host's use of every transition; Search's return; an activity naming its own icon;
  each glyph's own outline; anchored session-gate pins; no stroke on the launcher tiles; the licence's terms; the player
  called on the main thread only; an unset animator scale; any registration of the lab's route; the prefetch when a post's
  first item was swiped past before it resolved.
- **Flakes.** "Dispatchers.Main is used concurrently with setting it" (`SyncViewModelTest`, `ViewerViewModelTest`): a
  ViewModel's cancelled (or never ended) coroutine still resumed on Main from the IO, Room or DataStore thread it waited on
  while Main was reset. The tests now end their ViewModels and wait for them, then close the database, then reset Main.
  "Pool closed" (`SyncScreenRealPacerNoteTest`, and the same order in `NavigationBackTest` and `ViewerScreenCarouselTest`): the
  database closed while the screen was still composed; it now closes after the composition is disposed. A rarer 60 s hang in
  one `SyncViewModelTest` body, seen once before and once in this round's first full `check`, is another mechanism; it stays
  open in `TODO.md`.
- **Tests.** `./gradlew check --no-build-cache --rerun-tasks`: 1137 app tests in 92 classes and 382 `:instagram` tests,
  0 failures, lint with no errors and 16 warnings (one from this update, `ObsoleteSdkInt`). `connectedDebugAndroidTest` on the
  emulator in Mock mode: 54 of 54. Every fix and pin was mutation-checked in a scratch export: 49 mutants, all killed.
- **Pacing.** Nothing raised. R20's drop deletes local files and cache keys only (a dropped image downloads again only when
  viewed), the removed-post guard can only send fewer requests, and the settle gate keeps a transient post from ever starting
  a fill or a resolve.
- **Safety.** No agent contacted Instagram, logged in, or touched the owner's phone: fakes, local data and the emulator in Mock
  mode only.
- **Next, the owner's phone.** Unchanged: README section 8, "The viewer update", now including the offline video item (step 4)
  and the install rule of step 1.

## 2026-10-13: pages push instead of the shared axis (R24)

Spec `docs/superpowers/specs/2026-10-12-viewer-ux-design.md`, section 6, R24 (amends §3.6).

- **Why.** The owner tried the viewer update on the phone (gesture navigation): the post-to-tile grow and shrink and the
  gestures are fine, but not the shared-axis X between pages (a 10 % slide with a 300 ms fade). The ask: "just slide, or
  whatever is used in the real Instagram".
- **The push.** `Motion.kt`'s page transitions (`pageEnter`, `pageExit`, `pagePopEnter`, `pagePopExit`, renamed from
  `sharedAxis*`): forward, the new page slides in from the end edge, the whole width, over the page left, which drifts a
  third of the width towards the start; Back is the exact reverse; 300 ms with `FastOutSlowInEasing`; nothing fades. The back
  gesture seeks the same slides with linear timing (`gesture = true`), so the page moves with the finger; letting go finishes
  the slide at that constant pace, a cancel eases it back. The viewer's fade, the shared bounds (R14), the page under the
  viewer (R15) and no motion at animator scale 0 are unchanged.
- **Z-order.** No code needed: Navigation 2.10's `NavHost` (read from its bytecode) gives the arriving page the z-index of
  the page left plus one on a navigate, and minus one on a pop and under the gesture, so the moving page is always on top.
- **Tests.** `NavigationBackTest`'s page pins now assert the push forward and back (direction, the whole width and a third,
  over within 24 frames, slowing into its end) and the gesture's linear follow on both pages (positions read unclipped, from
  `positionInRoot`, as a full-width slide puts them off screen); a new native-graphics test reads the screen's pixels frame by
  frame to pin the moving page on top and neither page faded, forward, back and under the gesture. `MotionTest` pins the
  renamed factories, the gesture's two, and the nav host's wiring. `./gradlew check --no-build-cache`: 1138 app tests in 92
  classes and 382 `:instagram` tests, 0 failures, lint with no errors and 16 warnings (unchanged). `connectedDebugAndroidTest`
  on the emulator in Mock mode: 54 of 54. Mutation-checked in a scratch export: 17 mutants (each slide's distance or
  direction, a fade on each slide and on the gesture's alone, an eased gesture, a linear push, a 450 ms push, motion at scale
  0, the nav host's gesture flag, the page coming back drawn on top), all killed.
- **Emulator, Mock mode.** Saved to Search and back, measured from screencaps (the animator scale set to 10 for the Back
  and forward frames, since a screencap takes 0.7 s; 1 for the gesture, which the finger drives). Search came in from the
  right edge and left the same way, Saved drifting by a third at the same progress in every frame; Saved's tiles were cut
  exactly at Search's edge in both directions (Search on top); colours matched the pages at rest (no fade). A 6 s swipe from
  the left edge moved Search with the finger, its edge 50 to 110 px of 1080 behind the fingertip (the screencaps' timing is
  coarse), and Saved's offset matched the same progress to within a pixel. The scales were restored and the screenshots deleted.
  The emulator then hung (after six days up, its userspace stopped answering mid-run) and was cold-booted; a "System UI isn't
  responding" dialog after the boot took the Back key from `SmokeTest` on `main` and on this branch alike until dismissed.
- **Pacing.** Untouched: UI only.
- **Safety.** No agent contacted Instagram or touched the owner's phone: the emulator in Mock mode only.
- **Next, the owner's phone.** README section 8, "The viewer update", step 8, rewritten for the push.

## 2026-10-13: the 60 s hang in `SyncViewModelTest`, found and fixed in the app

- **Cause.** Not the Sync screen's 1 s ticker, but DataStore 1.2.1. A flow that starts collecting while a write lands can miss
  that write for good. Its first read, made without the file lock, takes the version the write has already taken but reads
  the file from before the write's rename. The write's own update, under that same version, is then dropped as not newer
  (`readDataOrHandleCorruption` tags an unlocked read with the version it read before its `tryLock`; androidx-main now uses
  the cached version). The flow keeps the old value until the next write of any key. `theNamesNoticeFollowsTheStoredFlag`
  starts the names flag's flow and writes the flag at once, so now and then the notice never came and `first { }` ran into
  `runTest`'s 60 s.
- **Evidence.** The test body repeated in one JVM (a scratch class, never committed), with a thread that dumps the state
  after 8 s: 5 hangs in 500, and 14 in 500 with ten CPU burners, each with the write finished, a fresh read of the file
  `true` and the screen without the notice. With each write held 300 ms between its version and its rename and the flag's
  flow started 100 ms in: 20 hangs in 20; with the flow started after the write instead: 0 in 20.
- **Fix, in the app.** `SettingsStore` goes through `TurnTakingDataStore`, a mutex held from a flow's start to its first
  value and around each write, taken and given back on an I/O thread only. The first version held it on the caller's thread
  and deadlocked `SyncScreenCollectionNamesTest` in the full `check`: its main thread blocks in `runBlocking` on a read
  while its ViewModel's write, launched from that thread, holds the mutex and must resume there to give it back. In the app,
  the race could leave a screen on an old setting: a Sync screen opened just as a sync wrote the names flag or the session
  state kept the old value until the next settings write, or until it was reopened after 5 s.
- **After.** On the final version: 0 hangs in 3,000 runs of the test body with ten CPU burners, 0 in 50 with the widened
  window, and `SyncViewModelTest` with `SettingsStoreTest` 20 times in a row with six burners (the first version also: 0 in
  6,000, and 25 class runs). `SettingsStoreTest` gains three tests (a flow that starts while a write lands sees it; a read,
  and a write, that block the other's thread are not stuck); they kill all four mutants of the fence (no read fence, no
  write fence, no I/O for the read, no I/O for the write). `aForgetThatCannotBeWrittenIsSaidOnTheScreen` now waits for its
  message in real time: its failing write now hops to I/O like every settings write, so it no longer fails inside
  `advanceUntilIdle`.
- **Tests.** `./gradlew check --no-build-cache`: 1140 app tests in 92 classes and 382 `:instagram` tests, 0 failures, lint
  with no errors and 16 warnings (none new).
- **Pacing.** Nothing changes: no request is added, and no rate or concurrency of requests is touched.

## 2026-10-13: the settings fence, the review's fixes

A two-lens review of the fence (concurrency, and tests by mutation: 19 mutants over the 16 classes that use `SettingsStore`)
confirmed the root cause (upstream: b/431787506, androidx commit 1ef22b8, not in 1.2.1) and found six Minor gaps, all fixed:

- **M1, a cancelled write.** A write whose caller was cancelled while DataStore was still writing gave the fence back at once,
  so a flow starting then could still miss it. It now waits for DataStore first (a no-op update queues behind the write).
- **M2, the whole first read is fenced.** A new test pauses a flow's first read inside its version read and starts a write
  then; a fence that only waited for a write already in flight passed every earlier test.
- **M3, re-entry.** A transform that reads or writes the store would wait for ever for its own write's fence; it now fails at
  once with an `IllegalStateException` (a marker in the write's context). No transform does it today.
- **M4, when the wrapper can go.** No DataStore release note names the fix, so the TODO's trigger is the race tests passing on
  the raw store with a stable release.
- **M5 and M6.** A first read that fails, or whose collector is cancelled, gives the fence back, and the read's error reaches its
  caller (two tests). The session's and the mute's flows are raced like the names flag's, so a flow that reads the raw store
  fails a test.
- **Mutation.** In a scratch export, 14 mutants against `SettingsStoreTest` (23 tests): the review's survivors (a barrier-only
  fence, no `finally` unlock, swallowed read errors, the session flow on the raw store), one per fix above (no wait for a
  cancelled write, no re-entry check on read or write, no context marker, the mute or names flow on the raw store) and the
  first round's four (no read fence, no write fence, no I/O for the read, no I/O for the write). All 14 killed, each by its
  own test.
- **Tests.** `./gradlew check --no-build-cache`: 1146 app tests in 92 classes and 382 `:instagram` tests, 0 failures, lint
  with no errors and 16 warnings (none new).
- **Pacing.** Nothing changes: no request is added, and no rate or concurrency of requests is touched.

## 2026-10-13: the Sync screen tests end their ViewModel before the database closes

- **The failure.** On this branch merged with main, a full `check --rerun-tasks` failed 2 of 1147:
  `SyncScreenCollectionNamesTest.noNoticeWhileTheNamesAreFresh` and
  `SyncScreenRealPacerNoteTest.inMockModeTheRealPacersCountIsShownUnderTheSessionStatus`, both with "connection pool has been
  closed" from a `SyncDao` read (`lastFullSyncAt`, `lastSyncAt`), caused at the test's own `db.close()`.
- **Cause.** The five Sync screen test classes never ended their `SyncViewModel`, so its Room flows outlived the test. Disposing
  the screen does not end them: `WhileSubscribed` keeps them for 5 s, and a query already under way still runs. A query in
  flight when the test closed the database then met the closed pool. (One that starts after the close is harmless: in a probe
  it simply never ran.) These tests pass in about 30 ms, so the window is the time a first Room read takes; the settings
  fence's extra I/O hop in the screen's other first reads most likely shifted that timing in the full suite (not proven).
- **Fix, tests only.** `endViewModelsOnMainLooper` (`testutil/ViewModels.kt`), the screen-test form of `endViewModels`, cancels
  the ViewModels' scopes and idles Robolectric's main looper until every coroutine has finished. A cancelled coroutine that
  waits on a query finishes only after it returns, and one whose query has not started never starts it. The five classes
  (`SyncScreenCollectionNamesTest`, `SyncScreenLoginTest`, `SyncScreenRealPacerNoteTest`, `SyncScreenStorageMessageTest`,
  `DeveloperSectionTest`) now end their ViewModel, then close the database, then the settings file. The app is not affected:
  its database is a process singleton and is never closed.
- **Evidence.** In isolation the failure did not show: 20 forced runs of each failing class with eight CPU burners, all green.
  In a scratch export where every test query spends 300 ms in SQLite and the teardown idles the looper for 1 s after the
  close: 11 of 32 tests failed in one run with the old teardown (seven caused at the test's `db.close()`), 0 of 96 in three
  runs with the new one. After the fix, in the worktree: 20 forced runs of each of the five classes with eight burners, 100
  class runs, all green.
- **Tests.** `./gradlew check --no-build-cache --rerun-tasks`: 47 of 47 tasks run, 1147 app tests in 92 classes and 382
  `:instagram` tests, 0 failures, lint with no errors and 16 warnings (none new).
- **Pacing.** Nothing changes: test code only.

## 2026-10-13: an open carousel fetches its other pictures ahead (R23, R25)

Spec `docs/superpowers/specs/2026-10-12-viewer-ux-design.md` §6, R23 (it amends R10's "no prefetch of items") and R25, the
controller's ruling on the branch's review.

- **Why.** On the phone, swiping through a carousel showed a black placeholder on every item: an item's picture downloaded
  only once the item settled, after the CDN lane's 200–800 ms jitter. The owner approved fetching the next item's picture
  ahead, then, having tried the viewer on the phone again, asked to load the whole carousel.
- **What.** `CarouselRepository.prefetchImage` downloads one item's picture from its stored link through the same fetcher, CDN
  lane and store as the on-demand `image`; it never fills. A per-item lock in the `Visit` makes an item's own load wait for
  its download ahead instead of fetching it twice. The viewer's carousel visit runs one queue in the post's scope: once the
  items are known, every image item but the cover, one download at a time, the items after the settled one in order, then
  those before it, the nearest first. An item settle reorders what is left without restarting it or cancelling the download
  that is out; leaving the post cancels it. Video items are not fetched ahead. A failure ahead never shows; its item loads as
  before when it settles.
- **The branch's review (pacing and tests lenses), and its fixes.**
  - **P1.** A CDN 429 ahead did not stop the queue, which asked for every other item after it. Now the 429, offline and a
    post gone stop the queue for the visit (`ImageAhead.Stop`). There is no shared CDN back-off to follow: the sync's is per
    run.
  - **P2.** A download ahead still out when a renewal reshaped the post (R20's drop) wrote the old shape's slide after the
    drop, and every later visit showed it. A download, ahead or on demand, now reads the item's stored link again before it
    writes, writes nothing for a link replaced meanwhile, and loads the item as stored now, once.
  - **P3, ruled as R25.** Refused links were asked again on every visit, and twice for an item the owner then swiped to, and
    loading ahead did nothing for a carousel older than its links. Now a refused link ahead (or none) may spend the visit's one
    renewal, by the same rule and lane as an item's own load, and the queue goes on with the fresh links; a renewal spent
    already, or failed, stops it. A link the CDN refused is never asked again in the visit.
  - **P4.** ARCHITECTURE's pacing section no longer says flatly that no rate is raised.
  - **F1 to F6, the tests lens's gaps:** the once-a-visit guard on an item's own load, an on-demand load that waited for a
    prefetch that stored nothing, the earlier items nearest first, a post deleted while its picture downloaded ahead, another
    post's item settle loading nothing, and Mock mode on the fake backend's own CDN lane. Each has a test now.
- **CURSOR held on the phone.** Run 6 of 2026-10-11, a Sync (QUICK), ended DONE and stored 41 of the owner's collections with
  no "Collection N" placeholder; the names query asks for 12 a page, so every page from the second on was asked for with the
  page before's `page_info.end_cursor` as `after`. Ticked in `TODO.md`; STALE stays assumed.
- **Tests.** `./gradlew check --no-build-cache`: 1169 app tests in 92 classes and 382 `:instagram` tests, 0 failures, lint
  with no errors and 16 warnings (none new). `connectedDebugAndroidTest` on the emulator in Mock mode: 54 of 54. Earlier runs
  of the same code failed only in `SmokeTest`'s Back presses and screenshots, while another session's suite ran on the same
  emulator, and later while its system server kept restarting under the host's load, with a system "Application Not
  Responding" dialog over the app; after a reboot, on a quiet emulator, the suite passed. Mutation-checked in scratch exports:
  the first round's 31 mutants (29 killed; 2 equivalent, the queue's own skip of item 0 and of video items, which
  `prefetchImage`'s guards repeat), then the fix round's 44 (P1, P2, R25, F1 to F6, and R23's re-anchored on the new code),
  all killed; one of them (pages taken twice) only after a test was added for a page the renewal turns into a video. Changed
  tests: six `ViewerViewModelTest` tests that expected an exact list of downloads now allow for the queue's, and each still
  fails without the rule it pinned; two that pinned the opposite of R25 were replaced by tests of the ruling;
  `anExpiredImageLinkIsRenewedOncePerVisit` no longer expects a refused link to be asked for twice.
- **Pacing.** Opening a carousel downloads up to its other image items ahead, one at a time on the CDN lane; the lane's
  concurrency (2) and jitter (0.2–0.8 s) are unchanged, and one download ahead at a time leaves its second slot for the item
  on screen. The queue stops for the visit on the CDN's first 429 or when offline. A carousel whose stored links are old may
  spend its visit's one `mediaInfo` as it opens (R25), even if the owner never swipes: a request such a visit did not send
  before. The bound stays at most one a visit, the fill, the queue's and the items' own renewals together. A refused link is
  never asked again in a visit; one that met a 429 or offline may be asked again by its item's own load.
- **Safety.** No agent contacted Instagram, logged in, or touched the owner's phone: fakes, local data and the emulator in Mock
  mode only.
- **Next, the owner's phone.** README section 8, "The viewer update", step 2: open a carousel, wait a second or two, and the
  next item's picture should be there as it slides in; a carousel with old links may log its one `GET api/v1/media/<n>/info/`
  as it opens.

## 2026-10-13: a carousel's video items start sooner (R26, R27)

Spec `docs/superpowers/specs/2026-10-12-viewer-ux-design.md` §6, R26 (it reverses R10's "video items have no poster") and R27,
the controller's ruling on R26's review. This entry is the branch's own, amended with its review's fixes before the merge (R18).

- **Why.** On the phone (2026-10-13) a carousel's pictures were there as they slid in (R23), but its video items were still
  slow: a video item began to stream only when it settled, and the screen stayed black until the first frame, because R10
  gave video items no poster. The app sent no Instagram request during that test (the links were fresh from a Full sync), so
  the delay was the CDN stream and the start buffer.
- **What.**
  - **The start buffer.** The viewer's one player gets `viewerLoadControl()`: start once 1 s is buffered, go on after a stall
    once 2 s is, every other value Media3's default. Finding: Media3 1.11.1's own streaming defaults are already 1 s and 2 s
    (the 2.5 s start buffer of the brief is older ExoPlayer's), so this pins them against a library update and changes no
    playback today.
  - **Posters.** A video item's `imageUrl` (its preview frame) is fetched by R23's queue in the pictures' order
    (`CarouselRepository.prefetchPoster`), into the same `CarouselImageStore` file `{pk}_{position}` (a video item has no other
    picture there), with R20, D3, P2, R25 and P1 as for an image; an item's own load fetches it when it settles first, and a
    poster that does not come says nothing. It is the shutter of the item's own `ContentFrame`, so it shows until the player
    renders that item's first frame and hides; each item's page has its own frame, so no poster covers another item's video.
    A failed video keeps its poster under the message.
  - **Video starts.** Once no picture is left, the same queue writes the first 1 MiB of each video item but the settled one
    into the video cache under `{pk}_{position}` (`CarouselRepository.preloadVideo`, `CacheVideoStarts` with Media3's
    `CacheWriter` and a bounded request), through the player's own cookieless stack, one at a time inside `pacer.cdn { }`. Only
    a link the item would play as it is: a stale one is skipped, never renewed, and no `mediaInfo` is sent for a start. A
    cached start fetches nothing; a refused link is skipped and not asked again in the visit by a start, and a 403 or 410 ahead
    sends the item's own play to the visit's renewal (R27); the CDN's 429, offline or a failing cache stops the queue; leaving
    the post cancels the write and leaves the cache consistent; the item the owner settles on stops its write first (300 ms
    at most), so the player never reads past its lock; a start written across a link change or an unsave is dropped again,
    finished or cancelled. Mock mode writes none (its clip is in the app).
  - `CacheWriter` asks again, unbounded (the whole video), whenever its bounded request fails, so the write's upstream allows
    one try and refuses an unbounded request outright (`OneTryUpstream`), and the write's stack does not ignore a cache error.
- **The review's fixes (two adversarial reviews, pacing and tests lenses).**
  - **I1 (Important).** A failing cache (a full disk, or a cache that failed to start) turned each start into an unbounded
    download of the whole video that cached nothing and answered "written": the player's `FLAG_IGNORE_CACHE_ON_ERROR` let
    `CacheWriter`'s fallback stream past the cache, and the one-try rule only remembered a failed network open. Now the write's
    upstream refuses any unbounded request, its stack keeps cache errors, and a cache error is `FAILED`, which stops the
    queue. Pinned with the reviewer's two failing-cache probes.
  - **R27 (the controller's ruling, M1).** Only a 403 or 410 ahead sends the item's own play to the visit's renewal, as the
    player's own error path would; any other 4xx is passed and leaves the play to the player (R19: a video's 404 renews
    nothing), so a pre-load never changes what a play costs. "No new Instagram request" in the docs is now "no request the
    visit would not already allow, and none from a pre-load itself".
  - **M2.** A write cancelled part way skipped the link re-check, so an old slide's partial start could stay under a reshaped
    position: it is now checked as a finished one is.
  - **M4.** A video item with no preview frame spent the visit's `mediaInfo` on every visit for a poster that does not exist,
    and its stop ended the starts: now it is passed with no renewal, and its own load asks nothing.
  - **M5.** The settled item waited for its write until `CacheWriter`'s next read, up to the connection's 8 s timeouts. Now a
    cancel closes the write's request from a thread of its own (a wait for the CDN's answer ends at once), and the viewer
    waits 300 ms at most whatever the network does. On the JVM a body read stalled inside the platform's stream outlasts the
    close (it waits on the stream's lock); the bound covers that case.
  - **M3 (docs).** Starts share the 512 MiB video cache with watched videos and can push the oldest out: recorded as a cost.
  - **The tests lens.** Pinned: the write off the caller's thread (T-I1), the one-try rule in the start stack only, the
    player's own retries intact (T-I2), one request for a timeout and a body cut short and never an unbounded one after a
    drop (T-I3), and the Minors: the whole start checked as cached, R26's log lines, the earlier starts nearest first, one
    surface mid-swipe, a video item 0's cover as its shutter, the minimum buffer, Mock mode's user agent, and R16 with only a
    start cached.
- **Tests.** `./gradlew check --no-build-cache --rerun-tasks`: 1243 app tests in 94 classes and 382 `:instagram` tests, 0 failures, lint with no errors and 16 warnings (none new). `connectedDebugAndroidTest` on the emulator in Mock mode: 54 of 54. New: `VideoStartsTest` (the writer over MockWebServer and a real cache, failing-cache decorators included) and `ViewerLoadControlTest`, and R26/R27 tests in `CarouselRepositoryTest`, `ViewerViewModelTest`, `CarouselPagerTest` (a player that raises the first-frame event), `ViewerScreenCarouselTest` (end to end in Mock mode) and `VideoWiringGuardTest`. Mutation-checked in scratch exports of the branch: R26's 53 mutants, then the fix round's 34 (I1, R27, M2, M4, M5, T-I1 to T-I3, the Minors, and the one-try rule re-anchored), 87 in all, every one killed. Left out as equivalent or unpinnable: the writer's `ensureActive` after a cancelled write (a cancelled caller's answer is never read), the start's own type check (an image item has no video link, so it is skipped anyway), and the write stack's `setFlags(0)` behind the unbounded refusal (with the refusal in place, a cache error ends the write either way; a source guard pins it). One finding of the tests lens is met differently: T-I3's "one request per write" holds for timeouts and bodies cut short, but a connection dropped before its answer may be resent once, bounded, by the platform's HTTP stack (the JVM's does; it is below the data source, the player's too), so that test pins "never an unbounded request" instead.
- **Changed tests.** R10's "no poster" was in seven `ViewerViewModelTest` tests: `ahead` (the pages whose picture the queue
  fetches) now counts the video items' posters, so `openingACarouselFetchesItsOtherPicturesAheadOneAtATimeInOrder`,
  `imagesDownloadOnDemandOnceAVisitAndItemZeroUsesTheCover` and `aSpentRenewalStopsThePicturesFetchedAhead` expect the posters
  where they expected none, three more take their lists from `ahead`, and `aPageTheRenewalMadeAVideoIsPassedOnceAndTheQueueGoesOn`
  waits for the image pages only (`imagesAhead`), as before. Each still fails without the rule it pinned.
- **Pacing.** No request to Instagram that the visit would not already allow, none from a pre-load itself, and no new
  request type: still at most one `mediaInfo` a visit, every path together (a refused poster may spend it by R25's rule; a
  start never asks; a 403 or 410 ahead only moves the renewal the item's play would have asked for anyway, R27). The posters
  and the starts are extra CDN downloads on the existing CDN lane, one at a time, with its concurrency (2), jitter
  (0.2–0.8 s) and rate unchanged; the player's stream stays off the lane; a start is one bounded request, never the whole
  video. The cost: about 1 MB of data per video item of every carousel opened, even for items never watched, and one small
  poster image each; and the starts share the 512 MiB video cache with watched videos, pushing the oldest out over time.
- **Safety.** No agent contacted Instagram, logged in, or touched the owner's phone: fakes, MockWebServer, local data and the
  emulator in Mock mode only.
- **Next, the owner's phone.** README section 8, "The viewer update", step 3: open a carousel with a video item, wait a few
  seconds, swipe to it: its preview picture until it starts, never black, and a quick start.

## 2026-10-14: video starts, the re-review's follow-ups (N-M1, N-M2)

Spec `docs/superpowers/specs/2026-10-12-viewer-ux-design.md` §6, after R26's "Smaller changes": R26's fix round was merged
with its scoped re-review's two Minors, one docs Minor and two nits open. This fixes them.

- **N-M1, a late write of a replaced link.** With M5's 300 ms cap, a start write that outlasts it commits what it wrote when
  it stops, after the player has started reading the item's key past the write's lock. When the item's link was replaced
  while it was written (a reshape, a sync, the renewal), those are another video's bytes, and M2's check that drops them came
  only after a few more hops: the re-review's probe had a player read 309,248 bytes of the old video in the middle of the new
  one. Now `CarouselRepository.video` hands out the item's source only once a start of it from another link than the one it
  plays has stopped and been checked, however long that takes (a write cancelled during its handshake can take the connect
  and then the answer's headers, each with its 8 s timeout: about 16 s; the item shows its poster meanwhile). The repository
  keeps the write out until then (`startsOut`, by `{pk}_{position}`), not the visit: the branch's review found that a visit
  the owner leaves does not wait for its write, so coming back to the post while the write was stopping skipped the hold, and
  its cross-visit probe read 309,248 old bytes again. While a start of a key is out, no other start of it is written. A start
  of the very link played is the same video, so the cap still holds for it. The check of a finished write no longer gives
  way to a cancel landing as it runs (it would have left those bytes unchecked). Pinned through the repository with the real
  writer, a real cache and a stall the abort cannot cut, in the same visit and across visits: the play is held, the old bytes
  are gone when it is handed out, and the player, built with the app's flags, reads none of them; leaving the post ends a
  held play at once.
- **N-M2, a cancel during the handshake.** `OneTryUpstream` wrapped `DefaultDataSource`, whose close forgets the source it
  opened, and an open the abort came during closed its connection only if the abort had not tried already. A cancel that
  landed during the TCP or TLS handshake therefore closed nothing, and the connection stayed open with its body. Now
  `OneTryUpstream` wraps the `DefaultHttpDataSource` itself, and an aborted open always closes its connection once it returns:
  in the re-review's SOCKS-hold probe the client now hangs up as the answer comes. What cannot be done on this stack: the
  platform's connect blocks and cannot be cut from another thread (the JVM's `disconnect()` before the connection exists does
  nothing, and `DefaultHttpDataSource` has the connection only once `connect()` returns, then sends the request at once), so
  the one bounded request still goes out after a cancel that lands in the handshake, and the write still stops only once the
  handshake is done and the answer's headers have come (each within the connection's 8 s timeouts). The new test pins the
  hang-up, one bounded request at most, nothing cached and a stop with the handshake, not the timeouts.
- **D1, a correction to the R26 entry above.** It says the settled item stops its write first "(300 ms at most), so the player
  never reads past its lock". That is not what the code guarantees: a write that has not stopped within 300 ms finishes on its
  own while the player reads past its lock from the network (M5), the same video's bytes at worst twice, and, before N-M1,
  another video's when the link had changed. The spec's R26 paragraph and ARCHITECTURE's Viewer section now say so, with
  N-M1's exception.
- **N2.** ARCHITECTURE said the platform's HTTP stack may resend a request dropped before its answer "once": that is the
  JVM's (the tests'); Android's, built on OkHttp, may resend it once for each address of the CDN's host it has left to try.
  Each resend is the same bounded `Range`, as for the player.
- **N3.** `aStartThatIsSlowToStopHoldsThePlayerUpForAMomentOnly` now checks the cap (the player waits at least about 300 ms
  and under 1 s, where it allowed anything below 1.5 s and so a 1 s cap) and the order its message states (the next start
  begins only once the cancelled write has stopped), from the fake's ordered log.
- **Tests.** `./gradlew check --no-build-cache --rerun-tasks`: 1251 app tests in 94 classes and 382 `:instagram` tests, 0
  failures, lint with no errors and the same 16 warnings. New: one `VideoStartsTest` (N-M2, a SOCKS5 proxy on loopback that holds
  its handshake), six `CarouselRepositoryTest` (N-M1's hold in one visit and across visits, the review's cross-visit probe
  among them, its other side for the same link, no second write of a key while one is out and a write again once it has gone,
  a held play ending with the visit while a reel or an image item is never held, and the check a cancel cannot skip) and one
  `ViewerViewModelTest` (N-M1 through the viewer); the slow-stop test tightened (N3). Mutation-checked in scratch exports: 23
  mutants of the first round, 21 killed, 2 surviving as equivalent (X45, an upstream registered after the abort, as before;
  and a start left in the map after it is done, which the cross-visit round made killable), then 12 of the cross-visit round,
  all killed (among them the first round's per-visit hold, whose new visit's player read 309,248 old bytes). Two more runs took
  the slow-stop test's other checks out to show its new order check fails on its own. The re-review's probes on the fix: the
  SOCKS hold now hangs up as the answer comes (1 request, sent after the cancel, cancel and join 1,854 ms for a 2 s handshake);
  the rest read as before.
- **Pacing.** Nothing raised: no new request, rate, budget or concurrency. Still one write in flight on the CDN lane, still at
  most one `mediaInfo` a visit and none from a start. N-M1 only delays handing out an item's source until a write already out
  has stopped, and a new visit's start of a key whose write is still out is skipped, which only removes a request; N-M2 only
  closes a connection sooner.
- **Safety.** Nothing contacted Instagram or touched the owner's phone: fakes, MockWebServer, a SOCKS proxy on loopback and
  local data only.

## 2026-10-14: the viewer update on the owner's phone

The owner checked the viewer update on their phone (Pixel 10, gesture navigation, debug builds) on 2026-10-13 and 2026-10-14,
with the request log captured live (`adb logcat -s InstagramHttp`) and the library read through `run-as` (counts only):

- **The upgrade.** `versionCode` 1 to 2 installed over the existing library: Room went from version 1 to 2, the foreign-key
  check came back empty, and Saved kept its collections.
- **Carousels.** The first open of an 8-item carousel synced before the update sent one `GET api/v1/media/<n>/info/` and
  logged `carousel fill: fresh (8 items)`. A Full sync then (73 requests, all 200, 26 new posts, 3 removed) stored the items
  of all 145 live carousels, 790 in all, each matching its post's count; after it, carousel visits sent no `GET api/...` line.
- **Offline (R16).** After a Sync, in airplane mode, the viewed items showed and an unviewed picture said "Offline: this item
  isn't cached yet". A video item never watched through is not checked yet.
- **Feedback that changed the code.** Swiping a carousel showed a placeholder on every item, so an open carousel now fetches
  its other pictures ahead (R23, R25, #14); the shared-axis page fade was replaced by a push (R24, #12); and video items
  were still slow, so they now get a poster and their first megabyte ahead (R26, R27, #15, #16). The owner confirmed each on
  the phone: "carousels load fast now", "the transition is good", "carousel videos start fast now".
- **Icons and motion.** The icon buttons and their tooltips, the launcher icon and its themed layer, the grow and shrink
  between a tile and its post, and the back gesture all work.
- **The log.** With the items stored and their links fresh, the last two checks (the pictures ahead, then the video starts)
  logged no Instagram API request at all: only CDN downloads, which are not logged.
- **CURSOR.** The 2026-10-11 Sync stored 41 named collections with no placeholder, at 12 names a page, so the names query's
  cursor was used and works (recorded in #14).
- **Still open** (TODO): an expired carousel link renewed once, a video item offline, the first open after a cold start on
  the release build, the release build itself, the Adapter lab answers, STALE, an expired reel link and a 403/410 recovered.

## 2026-10-14: a README for visitors; the owner's manual moves to `docs/GUIDE.md`

The repo is public, and its README was the owner's 1,145-line operating manual (setup on a Mac, phone checklists, rollout
boxes, troubleshooting), with a "What works today" table that still said nothing had met Instagram. Someone who finds the
repo could not tell quickly what it is, whether to use it, or how to try it.

- **The manual moved** (`git mv`) to `docs/GUIDE.md`, its content kept, with the same section numbers and headings, so
  "section N" and every anchor still lead to the same place. Its intro now says it is the detailed guide for running the
  app on a real phone; its links are fixed for the new folder; "What works today" now gives the status as of this date,
  from `TODO.md` and the entries above (the phone checks of 2026-10-08, 2026-10-11 and 2026-10-13/14, and what is still
  open); and the two rollout boxes and the viewer update's checklist each start with a one-line status.
- **The new `README.md`** (about 250 lines) is for visitors: what the app is and does not do; a warning (Instagram's
  Terms of Service, a throwaway account only, no store and no support, everything on the phone, the real pacing numbers
  from `PacingPolicy.Conservative` and `Cooldowns`); the features; how it works, with a small diagram; Mock mode on an
  emulator and the test commands; the requirements (Android Studio's JDK, API 37 to compile, minSdk 29, targetSdk 36,
  `JAVA_HOME` and `ANDROID_HOME` with placeholder paths); a short summary of real use that links to the guide; the
  project layout; a map of the docs; the rules for forks; the license.
- **Screenshots.** Three from the emulator smoke suite's own run in Mock mode (generated data only): the Saved screen,
  a grid and a carousel in the viewer, downscaled to 450 × 1000 with their metadata chunks removed, in `docs/images/`.
- **References.** `TODO.md`, `ARCHITECTURE.md`, `gradle.properties`, `app/build.gradle.kts`, two test comments
  (`CarouselRepositoryTest`, `SyncEngineTest`) and the viewer update spec's R21 now point at `docs/GUIDE.md`; the four
  plans that edited the README carry a note where it went (their steps are left as they were run). No test or guard reads
  `README.md`: the secret guard's own test only writes one into its throwaway repository. Past entries here are unchanged.
- **Checked.** `./gradlew assembleDebug`, `:instagram:test :app:testDebugUnitTest`, `connectedDebugAndroidTest` on the
  emulator (54 tests, none failed or skipped), `scripts/test-secret-guard.sh` and `./gradlew check`; every relative link
  and anchor in the edited docs resolves.
- **Pacing.** Nothing changed: docs and comments only. **Safety.** Nothing contacted Instagram, and the phone was not
  touched.

## 2026-10-14: two phone facts recorded late, and the status brought up to date

Read from the phone's library (the `sync_run` table, counts only) and the owner's own reports, for `ARCHITECTURE.md`'s new
status section:

- **The 2026-10-11 evening Sync** (run 6, QUICK, DONE): 72 requests, 1,393 new items, 1,392 thumbnails cached, one failure
  (a thumbnail). The account's library had grown since the first Sync that afternoon (3 requests, 10 items).
- **The 2026-10-13 Full sync** (run 7, DONE): 73 requests, 1,416 items seen, 26 new, 3 removed.
- **A cooldown on Sync** (2026-10-11): Sync showed a cooldown with its minutes left (1,440), armed by a 429 an earlier build had
  received; the owner cleared it by hand (a throwaway account). The budget display and the
  challenge stop are still not reported.
- `ARCHITECTURE.md`'s Status section now lists what the phone confirmed and what is still open; `CLAUDE.md` names the
  README and `docs/GUIDE.md` among the docs to keep current.

## 2026-10-14: a carousel child's own id (R28), and the N-M1 hold's two test pins

Spec `docs/superpowers/specs/2026-10-12-viewer-ux-design.md` §6: R28 closes R20's known gap, and the two test pins N-M1's
last review asked for are in. Both were open in `TODO.md`.

- **R20's gap.** A carousel's downloaded pictures, posters and video starts are keyed by `{pk}_{position}`, and R20 dropped
  them only when a new item list changed the count or a position's type. A poster who reordered slides of the same type
  left the moved positions showing (or starting) each other's old media until the post was unsaved or Delete library ran.
- **R28, the parse.** `:instagram` reads each `carousel_media` child's own id into `RemoteCarouselItem.childPk`: its `pk` (a
  number or a string of digits), or, only when the `pk` is missing or JSON null, its `id`, plain digits or Instagram's
  `{pk}_{owner pk}` form. A `pk` that is there but unreadable is not replaced by the `id`. A child pk that is missing or
  unreadable is null: no shape change, and the post's items stay (R4 is for the type). The saved-page fixture's children
  already carried synthetic pks; the fake library's children now get distinct synthetic ones.
- **R28, the store.** Room version 3: `MIGRATION_2_3` only adds the nullable `carousel_item.childPk TEXT`, so every row
  survives with an unknown child pk; `3.json` is exported (a debug asset, merged after Room writes it, D4); `versionCode` is
  3 (R21's guard requires it), so a build from before this is refused on a phone that has it.
- **R28, the rule.** R20 now also drops a post's cached item media when a position's child pk changed while both the
  stored and the new one are known. Null on either side means unknown and never drops: the rows the migration leaves get
  their child pks from the next sync that walks their page, or the next renewal, with nothing dropped. Both replace sites
  apply it after their commit, as before; the same children with renewed links keep everything, so viewed items still show
  offline. Links are still never compared. What is left: a reorder made while a post's child pks are unknown is not seen.
- **The N-M1 pins (no code change, only a comment).** A start write that throws (not a cancel) still takes its key out of
  the repository's starts out, so the key is neither skipped by later starts nor holding the item's play; and the hold
  waits only on the item's own `{pk}_{position}` start, never on another item's of the post or the same position's of
  another post. TODO's note that the hold's "about 16 s" counts one connect and one headers wait, and that each redirect
  the CDN answers with can add as much again, is now in ARCHITECTURE (Viewer).
- **Tests.** `./gradlew check --no-build-cache --rerun-tasks`: 1263 app tests in 94 classes and 386 `:instagram` tests, 0
  failures, lint with no errors and the same 16 warnings; `connectedDebugAndroidTest` on the emulator in Mock mode, installed
  over its versionCode 2 build: 54 tests, 0 failures. New: three `WebParsersTest` (a child's own pk, the `id` without a `pk`,
  a missing or unreadable one null with the post's items kept), one `FakeLibraryTest`, two `CarouselItemDaoTest` (the column,
  and the rule case by case), three `ReelsDatabaseMigrationTest` (2 to 3, 1 to 3 through both, and a version-2 file of either
  library through `build`), two `SyncEngineTest` and three `CarouselRepositoryTest` for R28 (two slides of one type swapped
  drop; a child pk unknown on either side drops nothing; at a renewal, the same children with new links keep everything; the
  sync's test of renewed links now also asserts the child pks are known), and two `CarouselRepositoryTest` for the pins.
  Mutation-checked in a scratch export: 26 mutants, all killed. The parser (7: no child pk, no `id` fallback, an unchecked
  `pk`, an unreadable `pk` falling to the `id`, the `{pk}_{owner pk}` form dropped or loosened, a missing child pk making the
  items null), the fake (2), the entity mapping, the rule (4: R20's old rule, null counted as a change, either null guard
  alone), each replace site ignoring the child pk (2), the migration (3: not registered, `NOT NULL`, a no-op), `versionCode`
  2, the starts out's removal (3: out of the `finally`, skipped on a throw only, removal and completion both out) and the
  hold (3: on any entry, by position across posts, by post across items). The "throw only" and "any entry" mutants pass every
  other `CarouselRepositoryTest`: only the new pins kill them.
- **The review (2026-10-14), and the merge of main.** `origin/main` (#18, the README for visitors and `docs/GUIDE.md`) was
  merged in: the owner's manual edits of this entry now live in `docs/GUIDE.md` (Carousels, the viewer update's step 1 and a
  new step 9, the `INSTALL_FAILED_VERSION_DOWNGRADE` entry), and ARCHITECTURE keeps main's Status with one "Still open"
  bullet for R28. Two reviews then found:
  - **Important (data lens): Coil's memory cache kept the old pictures.** Coil 3.6.3 keys a local file by its path alone,
    so after R20 or R28 dropped a post's pictures and the new slides downloaded under the same `{pk}_{position}.jpg`, the
    viewer drew the old pictures from memory until the process died (the reviewer's Robolectric probe saw `MEMORY_CACHE`
    and the old picture). `ReelsApp` is now Coil's `SingletonImageLoader.Factory`, and its loader (`reelsImageLoader`, Coil's
    defaults, still no network fetcher) keys a local file by its path, last-modified time and size (`LocalFileKeyer`):
    every store renames a new file into place, so a rewrite is another key, and a missing file fails to load rather than
    coming from memory. Thumbnails follow the same rule.
  - **Tests lens: 8 survivors (4 Minor, 4 Nit), no defect.** Each now has a test: a reorder of video slides drops (F1); a
    throwing write releases a play it already holds (F2); the sync's drop runs outside the page's transaction (F3) and a
    renewal's only once the new items are stored (F4); a start under `{pk}_20` never holds item 2's play (F5); an `id` of
    `{pk}_` is unreadable (F6); the fake children draw nothing from the library's random source (F7, a digest of the
    seed-42 library) and their pks are `{pk}{position in two digits}` (F8).
  - **Docs.** R28's `id` fallback is described as the code has it (a missing or null `pk`), and R28's open phone check
    (a real carousel's children carrying a readable `pk` or `id`, read in the Adapter lab's Media info shape) is in TODO,
    ARCHITECTURE and the guide's step 9.
  - **Tests and mutants of the review round.** `./gradlew check --no-build-cache --rerun-tasks`: 1276 app tests in 95
    classes and 388 `:instagram` tests, 0 failures, lint with no errors and the same 16 warnings; `connectedDebugAndroidTest`
    on the emulator in Mock mode, through the app's own loader: 54 tests, 0 failures (the grow and shrink and the grid's
    thumbnails among them). New: eight `ImageLoadingTest` (through the loader `ReelsApp` gives Coil, with real PNGs: a
    rewrite at the same path loads the new picture, told by its time when the size is the same and by its size when the time
    is; a dropped picture is a failed load; a thumbnail likewise; an unchanged one still comes from memory; a link loads
    nothing; `ReelsApp` is Coil's factory), and the tests lens's eight pins (one `CarouselItemDaoTest`, three
    `CarouselRepositoryTest`, one `SyncEngineTest`, two `FakeLibraryTest` and one more case in a `WebParsersTest`).
    Mutation-checked in a new scratch export: 15 mutants, all killed. The loader (7: no keyer, the default loader from
    `ReelsApp`, no size, no time, the path alone, a keyer that never answers, and Coil's own last-modified flag in its place,
    which the same-time rewrite kills), and the tests lens's eight survivors re-applied (RX2, AX1, AX3, SX2, SX3, PX4, FX1,
    FX2), each killed by its new test only. A check, not a mutant: with `FakeLibrary.kt` as it was before R28 (main at
    6edb4be), the new digest test passes, so the digest is the library from before the children had pks.
- **Pacing.** Nothing new is sent and nothing is raised. The child pk is a field of the replies the sync and a visit's one
  `mediaInfo` already download; R28's drop is the same local deletion as R20's, and what it drops downloads again only as
  before (a view, or the open post's queue ahead), never from the sync. The test pins change no code path. The image
  loader's key adds a file stat per picture load, on the device only, as Coil's own last-modified key would.
- **Safety.** Nothing contacted Instagram or touched the owner's phone: fakes, synthetic fixtures and local data only, and
  the emulator in Mock mode.

## 2026-10-15: another first slide takes the post's cover with it (R29)

Spec `docs/superpowers/specs/2026-10-12-viewer-ux-design.md` §6: R29 closes the known gap R28's review found, which was
open in `TODO.md`.

- **The gap.** Item 0 and the grid tile show the post's stored cover (`{pk}.jpg`), the first slide's picture, which a sync
  writes only while the post has none, and R20 and R28 dropped only the slides' own files (`{pk}_{position}.jpg`) and the
  item video starts. After a poster deleted, moved or swapped slide 0, item 0 and the tile kept the old cover until the post
  was unsaved or Delete library ran.
- **The rule.** `firstSlideChanged` (next to `itemShapeChanged`): the type at position 0 changed, or its child pk did while
  known on both sides (R28's rule at position 0 only), or there is no position 0. It is only ever true when R20's drop
  fires, and the cover goes only then; a change further on, and renewed links, keep the cover.
- **At the sync.** Once the page is committed, the post's `thumbPath` is cleared and its `{pk}.jpg` deleted (`dropCovers`,
  not cancellable: a Cancel between the commit and the drop would leave the old cover for good, since the next sync stores
  the same items), and the post joins that page's own thumbnail step, which fetches the new cover from the post's cover
  link, as for a new post. If that fetch fails, the post has no cover and the next sync that reaches it, or a resume,
  fetches it.
- **At a renewal.** The same drop once the renewal's items are stored (the repository clears the path; the container's
  `coverEviction` deletes the file), and nothing downloads the cover: the tile shows its placeholder until the next sync
  that reaches the post. The visit learns it (`Visit.coverDropped`), and `Visit.coverShows` is now the one rule for item 0:
  on item 0 its own picture loads at once, elsewhere when it settles or R23's queue reaches it (the queue, `prefetchImage`
  and `prefetchPoster` take item 0 whenever there is no cover).
- **One reading of the plan.** It said the next sync downloads the new cover. At the sync site the page that dropped it
  fetches it in the same run (its existing thumbnail step), because a Sync walks only the first page and an older post would
  otherwise show a blank tile until the next Full sync; the next sync is the fallback when that fetch fails. At the renewal
  site it is the next sync that reaches the post, as planned.
- **Tests.** `./gradlew check --no-build-cache --rerun-tasks`: 1291 app tests in 95 classes and 388 `:instagram` tests, 0
  failures, lint with no errors and the same 16 warnings; `connectedDebugAndroidTest` on the emulator in Mock mode: 54
  tests, 0 failures. New (15): one `CarouselItemDaoTest` (the rule case by case), five `SyncEngineTest` (another first slide drops the cover after the commit and the page fetches the new one once;
  a new cover the CDN refused comes with the next sync that reaches the post; a change further on, renewed links and a page
  that rolls back keep the cover), five `CarouselRepositoryTest` (a renewal drops the cover's path and file once the items
  are stored and the visit knows, item 0 then loads its own picture on demand and ahead, the next sync writes the cover
  again; a change further on and the same slides with new links keep it), two `ViewerViewModelTest` (on item 0 its own
  picture at once; elsewhere the queue fetches it in its turn, last), one `ImageLoadingTest` (a dropped cover is a failed
  load until the new one loads anew) and one `BackendWiringGuardTest` (the container's cover drop). Mutation-checked in a
  scratch export: 26 mutants, all killed: the sync site (7: no drop, the thumbnail step leaving it, never a new cover, the
  path or the file kept, every reshape dropping, the file deleted inside the page's transaction), the rule (5: no child pk,
  null as a change, no type, R20's rule, no position 0 as no change), the renewal site (9: no drop, the path or the file
  kept, the visit not told, the drop before the store, every reshape dropping, item 0 never fetched ahead, the cover read
  from the row alone, the container's wiring), the viewer (4: item 0 by the row alone, nothing following the drop, item 0
  loaded at once wherever the owner is, the queue never taking item 0) and the grid tile (1: Coil keying a file by its path
  alone). "Item 0 at once wherever the owner is" survived the first run: the queue test read the order as soon as item 0
  showed, not once the queue was done; it now waits for every picture.
- **Pacing.** No new API request; the drop is local. A dropped cover is downloaded again only by the sync's existing
  thumbnail step on the CDN lane (that page's, or the next sync's that reaches the post): one CDN download per changed
  carousel, as for a new post. In the viewer, while a post has no cover, item 0's own picture is one download on the same
  lane, by its own load or the queue, like any other slide's, and it is kept. No lane's concurrency, jitter or budget
  changes, and no schema change (`versionCode` stays 3).
- **Safety.** Nothing contacted Instagram or touched the owner's phone: fakes, synthetic data and local files only, and the
  emulator in Mock mode.
- **The review (2026-10-15).** An adversarial review (pacing, correctness, tests by mutation) found 1 Important, 6 Minor
  and 3 Nits, and two statements above held only in part: the sync's drop was not cancellable for a Cancel in flight (the
  page rolled back) but a process death after the commit kept the old cover, and "one CDN download per changed carousel"
  holds at the sync only. Fixed:
  - **I-1 (Important), and R20's own gap.** At a renewal the cover's drop ran behind R20's drop and the replace, which a visit
    ending (the owner swiping on) could cancel, Room's replace even after committing; the old cover, and the slides' old
    media (R20, a gap older than R29), then stayed for good. `store` now runs the replace and both drops in one
    `withContext(NonCancellable)` that starts at the replace. The sync's page and its housekeeping (a reconcile's evictions,
    R20's drop, the cover's file) are one such block too, from the transaction on: a Cancel during a page lets it finish,
    and the run stops at its next step.
  - **M-4.** The sync clears the cover's path in the page's own transaction, so a process death after the commit leaves a
    post with no cover, which the resume fetches; a path that cannot be cleared now fails the page like any of its writes
    (the review's probe of a failing path step at the sync no longer applies, and a test pins the page's rollback instead).
    The file still goes only after the commit. At a renewal the path is still cleared right after the replace's own
    transaction: a process death in that instant keeps the old cover (documented).
  - **M-1.** A grid tile handed the drop and the new cover in one recomposition (the app in the background while the sync
    ran) kept drawing the old bitmap: the path was the same before and after, so Coil was never asked again. `Thumbnail`
    now takes a `version`, the cover link for the grid tile and item 0's cover, and asks again when it changes; an
    unchanged file (a link only signed again) still comes from memory.
  - **M-2.** Item 0 was black for the rest of a visit after a sync dropped the open post's cover and could not fetch the new
    one. The screen now reports a carousel's row whose cover path changed in place (`ViewerViewModel.onRowChanged`, no
    settle), and item 0 loads its own picture at once when the owner is on it.
  - **M-3.** Not changed, documented: a change found at a renewal downloads the first slide's picture twice (item 0's own,
    then the cover by the next sync that reaches the post), in R29 and ARCHITECTURE's pacing statement.
  - **M-5, M-6 and the Nits.** The review's probes are tests, and the docs say what the code does (N-1's order no longer
    matters; N-2's race, a renewal's file deletion landing between a sync's new cover and its path, stays negligible).
  - **Tests and mutants.** `./gradlew check --no-build-cache --rerun-tasks`: 1309 app tests in 96 classes and 388
    `:instagram` tests, 0 failures, lint with no errors and the same 16 warnings; `connectedDebugAndroidTest` on the
    emulator in Mock mode: 54 tests, 0 failures. New (18): six `CarouselRepositoryTest`, the review's five probes and one
    more (a visit ending
    right after the store, with the replace returning as Room's does to a cancelled caller, drops the slides' media and the
    cover; one ending while R20's drop or the cover's runs still drops the cover; each of the cover's two steps on its own),
    five `SyncEngineTest` (a process death right after the commit still leads to the new cover through the resume; a path
    that cannot be cleared fails the page and keeps the old cover whole; a Cancel inside the page's transaction finishes it
    with its housekeeping; one during the housekeeping still leads to the new cover; two carousels of one page), three
    `ViewerViewModelTest` (a lost cover on item 0 loads its own picture at once, elsewhere when it settles, another post's
    row changes nothing), one `ViewerScreenCarouselTest` (the real screen: a row whose cover went, on item 0, shows item
    0's own picture with no settle) and three `CoverRewriteTest` (the review's compose probe, through the app's loader: the
    grid tile and item 0's cover show a cover rewritten under the same name in one recomposition, and a cover whose link
    only changed still shows); the review's probes P-F, P-G and P-I are among the `SyncEngineTest` ones. Mutation-checked in
    a new scratch export: 23 mutants, all killed: the renewal (6: the store cancellable, the block starting after the
    replace, the cover's drop outside it, one try for both cover steps, the visit told only after the file or only after the
    path), the sync (7: the path after the commit, alone or with the file, the page cancellable, only the housekeeping not
    cancellable, only the first cover file, no cover file, the file deleted inside the transaction), the tile (4: no key, a
    key without the version, no version from the grid tile or from item 0's cover) and item 0 after a sync's drop (6: the
    visit keeping its row, nothing loading, item 0 loading wherever the owner is, any post's row, no row reported by the
    screen, a row reported once per post). The review's own survivors S1 (the sync's inner `NonCancellable`) and R1 (the
    renewal's) are gone with the code: each site has one block now, and S2 (the step order) no longer exists.

## 2026-10-15: a Cancel during a page no longer sets the run row RUNNING again

Closes the `TODO.md` line left by R29's review: a Cancel that lands during a sync page.

- **The bug.** Since R29 a page runs, from its transaction on, in a block that a Cancel cannot interrupt. The Cancel button
  writes the run row PAUSED "Cancelled" (`SyncController.cancel`), the page then commits, and its progress write, inside
  the transaction, wrote the engine's own copy of the row whole: RUNNING, and the error cleared. A few milliseconds later the
  run's cleanup ended it as PAUSED "Cancelled" again. In between Sync and the Saved chip read "Syncing…" and a Delete
  library tap was ignored. The re-review reproduced it with its probe RR-2.
- **The fix, one level up from the TODO's wording.** The TODO suggested leaving the status out of the page's progress write
  once a Cancel has been seen. The engine cannot see the Cancel (it is the controller's write, and inside the block the job
  is not cancelled), and every progress write carried the same stale copy, so no progress write writes the status now:
  `Progress.update` and `phase` go through the new `SyncDao.updateProgress(SyncRunProgress)`, a partial update of the phase
  and the counts (`SyncRunProgress`, no table, no schema change), and only `save` (the run's start, RUNNING) and `finish` (its
  end) write the row whole. The counts and the page's cursor still commit with the page, so a Resume starts at the next page
  and counts on. A run with no Cancel reads RUNNING throughout, including one started on a row left PAUSED. No request is
  added: nothing about pacing, rates or concurrency changes.
- **Safety.** Nothing contacted Instagram or touched the owner's phone or the emulator: fakes, an in-memory database and
  local files only.
- **Tests and mutants.** `./gradlew check --no-build-cache --rerun-tasks`: 1315 app tests in 97 classes and 388
  `:instagram` tests, 0 failures, lint with no errors and the same 16 warnings. New (6): four in `SyncEngineTest` and two in
  `SyncRunProgressTest`.
  - The re-review's RR-2 as a test (the controller's pause, then a Cancel inside the page's transaction: the row reads PAUSED
    "Cancelled" with the page's 20 items already counted while the housekeeping runs, a SQLite trigger records no write that
    makes the paused row RUNNING, and the run ends PAUSED "Cancelled"; it failed before the fix with `RUNNING/null`).
  - A Resume after that Cancel asks for the page after the first (never the first again), counts on to 50 and fetches the
    cover the page dropped.
  - A run with no Cancel, started on a PAUSED row, reads RUNNING at every request, housekeeping and thumbnail.
  - Every column the progress write carries (the phase, collections done and total, the requests, the new and seen items,
    the thumbnails stored and failed) is right while a run goes on: a strategy B run reads the row at every saved-feed
    request, over three scopes, and compares each column with what the test counted itself (the scope, the fake's call log,
    the pages it returned, the fetcher's answers). It has to be a read in the middle: the run's last write, `finish`, puts the
    engine's whole copy in, so a progress write that got a column wrong leaves no trace in the finished row.
  - `SyncRunProgressTest`: `SyncRunProgress`'s fields are `SyncRunEntity`'s minus `mode`, `status`, `startedAt`,
    `finishedAt` and `lastError` (a counter added to the entity and forgotten there fails by itself), and `progress()` copies
    each of them unchanged, from values of their own.
  - **Mutants, in two scratch exports (deleted).** First, 8, all killed: the bug back for every progress write, for the
    page's own write only, a progress write only while the stored row is RUNNING, no cursor written with the page, a resume
    that ignores its cursor, a progress write that writes the seen count as 0, a start that writes no status, an end that
    writes no status. That round let two survive (the end's whole-row write puts a lost count in anyway), so the Cancel test
    reads the counts right after the commit. Then a review found that only `seenItems` was read mid-run: a mapping that wrote
    the phase and six of the seven counts as ""/0 survived all 1312 tests. Now 11, all killed: that one, each of the 8 columns
    (the phase and the seven counts) dropped alone, `newItems` and `seenItems` written into each other, and a counter
    forgotten in `SyncRunProgress`. The mid-run test kills every mapping mutant on its own, the mapping test as well, and
    the field test kills the forgotten counter.
