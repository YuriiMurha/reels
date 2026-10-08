# TODO

## Now

- [x] Finish the design: video strategy, unsave semantics, sync triggers, screens, error handling
- [x] Write the design spec: [`docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`](docs/superpowers/specs/2026-10-06-saved-reels-android-design.md)
- [x] Owner reviews the written spec
- [x] Write the M0–M2 implementation plan: [`docs/superpowers/plans/2026-10-06-saved-reels-m0-m2.md`](docs/superpowers/plans/2026-10-06-saved-reels-m0-m2.md)
- [x] Owner reviews the plan and picks the execution method (subagent-driven)
- [x] Write the M3–M6 implementation plan: [`docs/superpowers/plans/2026-10-07-saved-reels-m3-m6.md`](docs/superpowers/plans/2026-10-07-saved-reels-m3-m6.md)
- [x] Build M3–M6 in code, tested without any agent contacting Instagram (everything that talks to Instagram has only met fakes and a local test server)
- [x] On-device UI smoke suite on the emulator in Mock mode (`./gradlew connectedDebugAndroidTest`; skips on a real phone; README "Automated smoke tests")
- [x] Move the API calls into a hidden instagram.com WebView page after the OkHttp client's first two requests got HTTP 429: [`docs/superpowers/specs/2026-10-08-webview-transport-design.md`](docs/superpowers/specs/2026-10-08-webview-transport-design.md), [`docs/superpowers/plans/2026-10-08-webview-transport.md`](docs/superpowers/plans/2026-10-08-webview-transport.md); tested with fakes, local servers and the emulator only
- [ ] Owner: the on-phone checks below, in this order: Mock mode off (README section 5 step 2), then the box "After an update that changes how the app talks to Instagram" in README section 5 (wait out any cooldown; one request: Who am I if Logged in, otherwise the login check (R102); paste the `InstagramHttp` lines), then the rest of section 5 (the Adapter lab of section 7 once, then the first Sync), then the checklist in section 8

## Owner actions

All of these need your phone. [`README.md`](README.md) says how.

- [x] Install Android Studio (bundles the JDK, Android SDK and emulator)
- [x] Decide the integration flow: PRs on GitHub (PR #1)
- [ ] Decide repo visibility (currently public on GitHub)
- [ ] First, after the WebView transport update: the hidden page loads and one request answers 200: Who am I if Logged in, otherwise the login check (R102) (README section 5, "After an update that changes how the app talks to Instagram"). Paste the `InstagramHttp` lines into a Claude session before anything else
- [ ] Run the M2 checklist: log in on the phone (README section 8, "M2: session")
  - [x] In `chrome://inspect`, see whether the WebView sends `X-Requested-With: io.github.yuriimurha.reels`: answered on the emulator. It is sent on every request the WebView makes itself (page, images, frames) and cannot be turned off on this WebView (R100: `androidx.webkit` 1.17.1's allow-list setter is a deprecated no-op and unsupported on WebView 145). The API `fetch` carries only the script's own `XMLHttpRequest`. A known marker, accepted
  - [ ] Note whether `sessionid` changes during a checkpoint flow
  - [ ] Note whether Instagram re-issues `sessionid` on ordinary API responses (the app's own requests): if it does, Check now (or a login-screen check) while a sync runs ends the epoch and the run stops with STOPPED_LOGIN. Safe, but spurious
- [x] Confirm `X-IG-App-ID`: the mobile site sends `1217981644879628`, and `WebHeaders.APP_ID` is now that value (the old desktop-web `936619743392459` is gone); `X-ASBD-ID` `359341` is sent too
- [ ] Run the Adapter lab once (README section 7) and hand back the seven spike answers (README section 8, "M3")
- [ ] Flip `SAVED_COLLECTION_IDS_CONFIRMED` to `true` (in `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebInstagramClient.kt`) only if spike Q2 says saved items carry `saved_collection_ids`
- [ ] The first real Sync, then the first real Full sync (README section 5): a Full sync only after a normal Sync has succeeded
- [ ] Video playback on the phone (README section 8, "M5: video")
- [ ] `installRelease` with a real keystore (README section 9), and the release-build checks (README section 8, "M6: release build")

## Milestones (spec section 12)

- [x] M0 Skeleton: both modules build, `./gradlew check` passes, the pre-commit guard blocks a planted secret
- [x] M1 Mock app: home, grid, viewer, search on the fake library; fake sync through the real worker and Pacer
- [ ] M2 Session (code done, on-phone check pending): WebView login, cookie bridge, `currentUser()`, paste fallback, session states
  - [x] Code: every API call runs as a same-origin `fetch()` in a hidden instagram.com page (`WebViewTransport`, `AndroidWebPage`, `ig_fetch.js`); the login check is `api/v1/accounts/edit/web_form_data/` (`form_data.username`, the pk from the `ds_user_id` cookie); the page is destroyed before the session changes and closed after 5 idle minutes; the OkHttp API client, `SessionGuard` and the cookie bridge are off the API path (`ARCHITECTURE.md`, Transport)
  - [ ] On the phone: the hidden page loads and one request answers 200: Who am I if Logged in, otherwise the login check (R102) (README section 5, "After an update that changes how the app talks to Instagram")
- [ ] M3 Adapter spike (code done, on-phone check pending): seven questions answered, scrubbed fixtures, real parsers
  - [x] Code: the Adapter lab, with a pure-JVM core (`instagram/.../lab/`: one paced request per candidate endpoint, a redacted shape, a scrubbed copy) and a debug-only screen (Sync → Developer → Adapter lab)
  - [x] Code: the real parsers, endpoint builders and `WebInstagramClient`, pinned by synthetic fixtures until the spike confirms the shapes
  - [x] Gate: the pre-commit hook catches JSON/Netscape cookie dumps, `csrftoken` and `++` diff lines before scrubbed fixtures are committed (with the spec-10 fixture guard test)
  - [ ] Gate: offset-cursor stability (the spike's page-size and cursor answer, Q4, decides whether it matters)
  - [x] Gate (M3/M4, strategy A): limit `deleteRealMembershipsExcept` to `collectionId IN (:known)`; an empty `collections()` list while live collections exist means `STOPPED_SHAPE`
  - [ ] On the phone: the seven questions answered, the scrubbed files read and committed as fixtures, the parsers re-checked against them
  - [ ] Spike note: a feed that says `more_available: false` and also sends a `next_max_id`. Today the parser ends the walk and ignores the cursor; decide after the spike whether that should be a shape change
  - [ ] Known gap (not blocking): spec 6.4's "a redacted shape dump is saved for the Adapter lab" on a sync `ShapeChanged` isn't implemented; the lab reproduces the call instead
- [ ] M4 Real sync (code done, on-phone check pending): quick and full, resume, budgets, cooldowns, challenge hard stop, thumbnails
  - [x] Code: `Backend.Real` (`WebInstagramClient`, `HttpMediaFetcher`, the one Conservative pacer), Mock mode (debug builds, default on) with separate libraries (`reels.db` fake, `library.db` real), the cookieless CDN client that makes one request per download
  - [x] Gate: truncated-resampling gaps instead of clamping (today about 18 % of gaps are exactly 4,000 ms)
  - [x] Gate: seed `lastRequestEndedAt` from `RequestLog`
  - [x] Gate: `Backend.Real` reuses `AppContainer.instagramPacer`, with a guard test, and the Sync screen shows that Pacer
  - [x] Gate: a proportional reconcile guard (P7/R71: a FULL All Saved reconcile that would remove at least 20 items and more than half of the items that existed before the run is refused as `STOPPED_SHAPE`)
  - [x] Gate: engine signals carry the session epoch
  - [x] Gate: logout stops a running sync
  - [x] Gate: a "session OK" signal from a sync's `currentUser`
  - [x] Gate: CDN `RateLimited`/timeout handling inside the thumbnail try (a 429 stops thumbnail fetches for the rest of the run; a download timeout is one failed thumbnail)
  - [x] Gate: 421 coalesced-connection re-send. API client: one host per client (P6, pinned in Task 3). CDN client (many hosts, so P6 doesn't cover it): `HttpClientFactory.createCdn` turns a 421 into an IOException, strips a 503 `Retry-After`, follows no redirects and does no connection retries, so every download is one request (pinned by MockWebServer tests)
  - [x] Gate: redact request lines if a challenge URL is ever requested through OkHttp (moot: redirects are never followed, the API client only requests `/api/v1/` paths on one host (P6), and challenge URLs only ever open in the WebView, never through OkHttp)
  - [ ] On the phone: the first real Sync, a Full sync, the budget and cooldown display, and the challenge hard stop if one ever happens (README section 8, "M4: real sync")
  - [ ] Check on the phone (R72): strategy B's per-collection FULL reconcile has no proportion guard (it removes memberships only; the next good Full sync rebuilds them, and a legitimately emptied collection must still mirror), so confirm that each collection feed ends correctly (`more_available` false on the last page, not a truncated feed)
- [ ] M5 Video (code done, on-phone check pending): on-demand playback, link refresh, `pk`-keyed cache
  - [x] Gate: `mediaInfo` needs a not-found outcome (P8: `RemoteMedia?`, null for 400/404 or an empty `items`; the viewer shows "This item is no longer available on Instagram" and deletes nothing)
  - [x] Code: links renewed on the interactive lane only when expired and only under a valid session, pk-keyed `SimpleCache` (512 MB, LRU), a fully cached video plays with no request, one refresh after a 403/410, next-item prefetch after the visible item, `collectLatest` on the settled page, eviction on unsave and on Delete library, the viewer on a `TextureView`
  - [ ] Check on the phone: a real reel plays, loops and replays from the cache offline (airplane mode); an expired link is renewed once; a 403/410 is recovered or ends in "Can't play this video"; Open on Instagram shows under an unavailable video; the `TextureView` swipes smoothly (README section 8, "M5: video")
- [ ] M6 Polish (code done, on-phone check pending): release signing, baseline profile, README
  - [x] Code: release signing from `keystore.properties` (debug key when it is absent), `androidx.profileinstaller` 1.4.1 with a hand-written wildcard `baseline-prof.txt`, an R8 release build that launches (keep rules for the `@Serializable` enum route argument and for exception class names), smoke-tested on the emulator logged out and, with a temporary local edit, on the fake backend
  - [x] README: the finished guide, from setup through the first real sync and the on-phone checklist to the release build and troubleshooting
  - [ ] Check on the phone: create the key and `keystore.properties` (README section 9) and `installRelease`; log in, run a real Sync, and open a grid and a viewer on the release build (the emulator smoke covered sync, grid, viewer, Media3, mute and search under R8 on the fake backend, but nothing that talks to Instagram: the real client, the CDN fetcher and the WebView login; if one of those crashes, it is a missing keep rule: `adb -d logcat -t 300` and `mapping.txt`); judge scrolling there too (the M1 debug build had 37 % janky frames)

## Later (no phone needed)

- [ ] Resolve the hidden page's load on `DOMContentLoaded` instead of the window's `load` (R108), so a cold-cache slow link does not run into the 30 s load bound. It needs a script injected at document start; R104 already keeps a slow load from costing pages
- [ ] Remove the OkHttp API client from `:instagram` (R101): `OkHttpTransport`, `HttpClientFactory.create`, `CookieStoreJar`, `SessionGuard`, `ErrorReplyLogger` and `WebHeaders.interceptor` are used only by JVM tests now (production has `WebViewTransport` and the CDN's `createCdn`). It is not a clean deletion yet: port the MockWebServer suites that build their client with them (`WebInstagramClientTest`, `WebSessionProbeTest`, `AdapterLabTest`, `WebJsonTest`, `OkHttpTransportTest`) to a fake `InstagramTransport`, then delete the client with its own tests

## Known limits (not blocking)

From the reviews of M3–M6; none has been reproduced on a device.

- After a Full sync is refused by the proportional guard (`STOPPED_SHAPE`), or paused, the items its earlier pages added stay in All Saved. Fixed in the final review: the guard's "before the run" now ends at the last finished (Done) run (R83), so after **Discard paused run** those items no longer count towards M; and a library remembers its Instagram account, so a run under another account stops right after its session check and writes nothing (R84), which also closes Discard, then Sync, then Full sync with another account's feed. Left by design: a same-account feed that a Sync (QUICK) run has finished with counts as library from then on.
- A WebView login as another account is now seen by every run's gate as soon as the jar's `ds_user_id` differs (no check needed), but not atomically: the one request that is already past the gate when the cookies change can go out under the new account, and R84's account check only guards a run's first `currentUser`, so a page of the other account's feed could be written into this library. Only a sync running while the owner logs in as someone else can meet it.
- A library synced before the account check existed (or after a settings-file corruption) has no stored account, so its next run adopts whichever account is logged in.
- The hidden page's own traffic is not paced: its home-page load and the site's background requests while the page exists are what any visit to instagram.com sends, and the Pacer does not count them (only the one `fetch` per API call). They are bounded: at most 3 page loads per user action (counted again after an idle close), and the page closes 5 minutes after the last call. Chromium may also re-send a GET on a dropped connection, as it would for the website itself (spec 4, accepted); the app neither sees nor counts that. Not yet seen on a phone.
