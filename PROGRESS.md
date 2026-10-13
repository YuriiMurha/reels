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
