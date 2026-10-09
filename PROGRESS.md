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
