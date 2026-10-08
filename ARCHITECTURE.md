# Architecture

Current state of the app. Updated in the same commit as the code it describes. The design is in
[`docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`](docs/superpowers/specs/2026-10-06-saved-reels-android-design.md);
this file describes only what exists.

## Status

The code for M0 to M6 is complete and unit-tested, with fakes and MockWebServer only. No agent has logged in to
Instagram or sent it a request, and the emulator only ever ran the debug build in Mock mode or the release build logged
out (once also on the fake backend, through a temporary local edit), plus the hidden page's own tests, which load only
local servers. What is still unconfirmed needs the owner's phone (the README's checklist, `TODO.md`):

- **The WebView transport.** The first two API requests the app made on the owner's phone, sent by OkHttp before this
  change, were answered HTTP 429. Every API call now runs inside a hidden instagram.com page ([Transport](#transport)),
  which has never met Instagram. First on the phone: the hidden page loads and one request answers 200, Who am I if
  Logged in, otherwise the login check (R102) (README section 5, "After an update that changes how the app talks to
  Instagram").
- **M2:** the WebView login itself.
- **M3:** the endpoints, headers and JSON shapes. They are candidates from instaloader and instagrapi, pinned by
  synthetic fixtures, until the Adapter lab shows the real responses. The login check's endpoint
  (`accounts/edit/web_form_data/`) and the mobile web app id (`1217981644879628`, from a capture of the mobile site) are
  as new as the transport.
- **M4:** a real Sync and Full sync, the budgets, the cooldown display and the challenge stop.
- **M5:** playing real reels, link renewal and the cache.
- **M6:** the release build's hidden-page transport, real client, CDN fetcher and WebView login under R8.

A debug build starts in Mock mode (the fake library); a release build always uses the real one.

## How it fits together

- `:instagram` (pure Kotlin/JVM) is the only place that knows Instagram: endpoints, header constants, pagination, JSON
  fields. `:app` reaches it through the `InstagramClient` contract; it builds no Instagram URL and parses no Instagram
  JSON, and the hidden page's landing rule (which paths mean "log in" or "verify") is `WebEndpoints.landingOf`. Two small
  things in `:app` do repeat Instagram's names, as named exceptions: `ig_fetch.js` (the hidden page's script: the three
  constant header values, which `WebViewTransportTest` pins to `WebHeaders`, and the names it needs to build the site's own
  request, the header names, the `csrftoken` cookie and the `www-claim-v2` storage key, pinned by the same test) and
  `AndroidWebPage`'s default origin (the one origin its message bridge accepts; `AndroidWebPageGuardTest` pins it to the
  scheme and host of `WebEndpoints.HOME_URL`, and `BackendWiringGuardTest` that the container passes no other). Outside
  comments, a grep of `app/src/main` for the domains finds only that origin.
- `AppContainer` hand-wires `:app`, one per process. It picks a backend (fake or real) once per process, and the library
  database, thumbnails, client, fetcher and video resolver follow that choice.
- Every Instagram API request, from a sync, a session check, the viewer or the lab, goes through the one `Pacer` and then
  through the one `WebViewTransport` ([Transport](#transport)): one `fetch` in a hidden instagram.com page per call.
  Thumbnails and videos do not: they use cookieless clients.
- Sync runs in a WorkManager foreground worker and writes Room; the screens read Room.

## Components

| Component | Where | What it does |
|---|---|---|
| Build | `settings.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/baseline-prof.txt` | Two modules, one version catalog. Minified release build with a baseline profile and optional own signing key. [Details](#build-and-release). |
| `:instagram` | `instagram/` | Pure Kotlin/JVM: the adapter contract, the transport seam, the real web client, parsers, error classification, the fake client. [Details](#instagram). |
| Adapter lab (core) | `instagram/.../lab/` | One request per call; a redacted shape and a scrubbed copy of the answer (spec 6.3). [Details](#adapter-lab-core). |
| `:app` | `app/` | Android app. Backup and device transfer are disabled (`data_extraction_rules.xml`). |
| Secret guard | `.githooks/pre-commit`, `scripts/test-secret-guard.sh` | Blocks staged HAR, session, cookie and signing files (`keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, `*.pfx`; compared in lower case), `sessionid` values, `Cookie:` headers, exported cookie dumps and `csrftoken` values. Enable per clone with `git config core.hooksPath .githooks`. |
| Database | `app/.../data/db/` | Room v1: `media` (+ FTS4 `media_fts`, unicode61), `collection` (with the `__all__` pseudo-collection), `collection_media` (`sortKey`), `sync_run`, `sync_cursor`, `api_request`. Schema exported to `app/schemas/`. `deleteLibrary()` keeps `api_request`. |
| Library | `app/.../data/library/` | `LibraryRepository` (home cards, Paging 3 per `MediaSource`, `deleteLibrary()` which also forgets the library's Instagram account through an injected `forgetAccount` (R84; after the rows are gone; a failure there does not stop the rest and does not escape: `deleteLibrary()` answers `LibraryDeletion.ACCOUNT_RECORD_KEPT`, or `CACHED_FILES_KEPT` when a thumbnail or the video cache could not be emptied (`ThumbnailStore.deleteAll()` throws once it has tried them all), or both, and `SyncViewModel` says so under the Delete library button) and clears thumbnails and the cached videos, through an injected `clearVideoCache`; neither failure fails the delete, the rows are already gone, and both are reported), `FtsQuery` (sanitises search input into prefix terms), `MediaSource` (serialisable grid/viewer source). |
| Media files | `app/.../data/media/` | `ThumbnailStore` (`{pk}.jpg` in `filesDir/thumbs` for the fake library or `filesDir/library-thumbs` for the real one, chosen by `AppContainer`; atomic writes, key validation), the `MediaFetcher` contract, `FakeMediaFetcher` (placeholder JPEGs) and `HttpMediaFetcher` (the real backend's CDN downloads, see [Wiring](#wiring)). |
| Video | `app/.../data/media/`, `ui/viewer/` | On-demand playback with a `pk`-keyed cache (spec 8). [Details](#video). |
| Pacer | `app/.../sync/pacing/` | The single gate for Instagram API calls. [Details](#pacer). |
| Transport | `app/.../transport/`, `app/src/main/assets/ig_fetch.js` | Sends every Instagram API call as a same-origin `fetch` inside a hidden instagram.com WebView page. [Details](#transport). |
| Settings | `app/.../data/settings/SettingsStore.kt` | DataStore preferences: `muted`; the persisted cooldown (`cooldown_until`, `last_rate_limit_at`); the session state (`session_kind`, `session_handle`, `session_challenge_url`, encoded by the session package; the session itself is only in the cookie jar); and the Instagram account each library belongs to (R84: `library_account_pk_real`, `library_account_pk_fake`, read and written through `sync/LibraryAccount.kt`'s `StoredLibraryAccount`; a corrupt file loses it, and the next run then adopts its own account). A settings file that cannot be read is replaced by `corruptionFallback` (ruling R54), not by empty preferences: `cooldown_until = now + 1 h` (the Pacer's `Cooldowns.SHORT_MS`) and `last_rate_limit_at = now`, so a corruption never silently ends an active cooldown and a rate limit in the next 24 h escalates straight to the 24 h tier; the session keys stay empty, which reads as LoggedOut until validation runs. Budgets persist in `api_request` (`RoomRequestLog`). |
| Sync engine | `app/.../sync/SyncEngine.kt` | One run: session check, collection list, scope walks, reconcile, thumbnails. [Details](#sync-engine). |
| Sync control | `app/.../sync/SyncController.kt`, `SyncWorker.kt`, `SyncScheduler.kt` | The buttons resume the latest unfinished run or start one; unique WorkManager work (`KEEP`) means never two runs; a foreground `dataSync` worker whose notification is shown at once (`FOREGROUND_SERVICE_IMMEDIATE`). WorkManager itself re-runs an interrupted worker after process death, and orphaned RUNNING rows (no live work) become PAUSED at app start (`recoverInterruptedRuns`, "Interrupted, tap Resume"). The worker refuses work queued for the other library (see [Wiring](#wiring)). |
| Wiring | `app/.../di/`, `ReelsApp.kt`, `data/media/HttpMediaFetcher.kt` | `AppContainer`, the fake and real backends, Mock mode, the CDN fetcher. The real backend, the session probe and the Adapter lab send every API call through `WebViewTransport` (a hidden instagram.com WebView page, `app/.../transport/`), built only when a request needs it; request pacing is unchanged (one Conservative Pacer, one `fetch` per call), and the site itself is loaded at most 3 times per user action (counted again after an idle close). [Details](#wiring). |
| UI shell | `app/.../ui/` | Dark Material 3 theme, type-safe Navigation Compose routes (`MediaSource` encoded into routes), `LocalAppContainer`. Home ("Saved"): collection cards (All Saved, Uncategorized, collections) and a sync status chip ("Not synced", "Syncing…", "Synced 5 min ago", or "⚠" and the stopped or paused run's `lastError`). Grid: two-column staggered Paging grid with real aspect ratios and type badges. |
| Viewer | `app/.../ui/viewer/` | Vertical pager over the grid's paged list; one reused ExoPlayer (Media3 `ContentFrame`, thumbnail as shutter), loop, remembered mute, author/caption/collection overlay, "Open on Instagram" via `Permalinks` (an `ACTION_VIEW` intent, so whichever app handles the link). Videos come from `VideoSourceResolver` (fake: a bundled synthetic clip; real: see [Video](#video)). The player draws on a `TextureView` (`ContentFrame(surfaceType = SURFACE_TYPE_TEXTURE_VIEW)`, pinned by `VideoWiringGuardTest`): with the default `SurfaceView`, a page the owner swiped away from and back to often never got its surface on the emulator (6 of 8 tries; 0 of 8 with a `TextureView`), and the clip played as sound under the thumbnail. |
| Search | `app/.../ui/search/` | 200 ms debounced FTS search over caption, author and collection names; Reels/Posts and collection chips; results in the shared grid, opening the viewer on the same `MediaSource.Search`. |
| Sync screen | `app/.../ui/sync/` | Sync / Full sync (or Resume + Discard paused run), Cancel, live run counters ("Requests (all attempts)" is cumulative over every attempt of the run and has no denominator, because the per-run budget restarts on each resume; "Requests in 24 h" shows the rolling budget), a cooldown countdown, status banners ([run outcomes](#run-outcomes)), history (last sync, last full sync), Delete library (keeps the session and the request log). With the real backend, Sync, Full sync and Resume are disabled unless the session is Valid (banner "Log in to Instagram to sync" when nothing more specific applies); Mock mode needs no session, and its session section adds one line for the real Pacer's cooldown or 24 h count (see Session). Log out cancels a running sync before it forgets the session. Debug builds add the Developer section ([Lab screen](#lab-screen)). |
| Lab screen | `app/.../ui/lab/`, `ui/sync/DeveloperSection.kt` | The debug-only front end of the Adapter lab, and the Mock mode switch. [Details](#lab-screen). |
| Session | `app/.../session/` | The session lives only in the WebView's cookie store. [Details](#session). |
| Login | `app/.../ui/login/`, `ui/sync/SessionSection.kt` | Instagram's own login page in a WebView. [Details](#login). |

## Build and release

- Two modules, versions pinned in one catalog. AGP 9 built-in Kotlin.
- **Release (P9, P10):** `isMinifyEnabled = true` (R8 with `proguard-android-optimize.txt` plus `proguard-rules.pro`).
- **Signing:** from the gitignored `keystore.properties` at the repo root (`storeFile`, relative to the repo root,
  `storePassword`, `keyAlias`, `keyPassword`).
  - A missing or blank key fails the build by name, never by value. It fails at configuration, so every Gradle task
    stops, not only the release ones.
  - The one password `keytool` asks for goes into both `storePassword` and `keyPassword`, because a PKCS12 store has no
    separate key password.
  - With no file the build logs one line at configuration saying it signs with the debug key, and does so, so
    `installRelease` still works and replaces a debug install in place. A real key is a different signature: switching
    between the two needs an uninstall.
- **Baseline profile:** `androidx.profileinstaller` 1.4.1 installs, on a sideloaded build, the profiles the Compose and
  other libraries ship plus the hand-written `baseline-prof.txt` (wildcard rules for `io.github.yuriimurha.reels.**`; AGP
  expands them against the app's classes, R8 rewrites them to the obfuscated names). There is no macrobenchmark generator
  module: a release build has no Mock mode to generate against.
- **Two keep rules** in `proguard-rules.pro`:
  - The app's `@Serializable` enums (`LoginPurpose`, a `LoginRoute` argument) are kept by name, because Navigation looks
    an enum route argument up with `Class.forName` when the graph is built, and the minified build crashed on launch
    without it.
  - Exception classes keep their names (`-keepnames` on `Throwable` subclasses): a run's `lastError` (`Unexpected error: <class>`,
    from `SyncEngine`) and the HTTP crash guard (`IOException(<class>)`) show a class `simpleName`, which R8 would otherwise
    turn into `a`.
- **Smoke tests on the emulator,** under R8:
  - Logged out, empty library: Home, Sync ("Not logged in", Sync disabled), Search and Back, no crash; `dumpsys` shows
    the app compiled `speed-profile` from the installed profile.
  - A second pass put the release build on the FAKE backend with a local, uncommitted edit (`BackendChoice.useFake`
    forced true) and exercised what an empty real library cannot reach: a fake Sync (the `SyncWorker` foreground start
    through the custom `WorkerFactory`, Room writes, FTS), Home cards and a collection grid (Coil, route decoding), the
    viewer playing the bundled clip (Media3 through `VideoCache`), the mute toggle (DataStore, kept across a cold restart),
    a swipe to the next reel, Search and Back. No `FATAL`, `ClassNotFound` or `NoSuchMethod`, so no further keep rule.
  - **Not exercised under R8** (it needs Instagram, which no agent contacts): the real client (the hidden-page transport
    with its `ig_fetch.js` asset and `androidx.webkit` message listener, and the JSON parsing in `:instagram`), the CDN
    fetcher (OkHttp) and the WebView login.
- **On-device smoke suite** (`app/src/androidTest/`, `./gradlew connectedDebugAndroidTest`, README "Automated smoke tests"):
  `SmokeTest` drives the debug app through its real UI on the emulator, in Mock mode: Home, Sync (logged-out session, the
  Developer section, a fake Sync to the end), grid, viewer, video playback and Search, with a screenshot per step in the
  app's `files/smoke/`. It never touches Instagram: `SmokeGuard`, the outermost rule, skips every test on a physical device
  (`isEmulator()`, pinned by `EmulatorDetectionTest`) and fails unless `AppContainer.usesFake`, both before the Activity is
  launched; no test taps a login, check, Instagram-link, lab or Mock mode control. Video playback is read from the viewer's
  `TextureView` (the playing page carries the `video-surface` test tag): a frame is drawn and a later one differs. Sync pacing
  is unchanged (the fake backend's own Fast policy). Deps: androidx.test runner and rules 1.7.0, Espresso 3.7.0 (the 3.5.0
  that Compose's test library pulls in crashes on API 37), Compose `ui-test-junit4`. The rule is the v1 `createAndroidComposeRule`
  on purpose: v2 runs composition coroutines on the test thread, and the viewer's ExoPlayer insists on the main thread.
  `android.injected.androidTest.leaveApksInstalledAfterRun=true` in `gradle.properties` stops Gradle from uninstalling the app
  (and its data) after the run.

## `:instagram`

Pure Kotlin/JVM.

- **Contract.** `InstagramClient` (which extends `SessionProbe`) with `reportsSavedCollectionIds`, `collections`,
  `savedMedia` and `mediaInfo`; typed `InstagramException`s (`LoginRequired`, `ChallengeRequired`, `RateLimited`,
  `Transient`, `ShapeChanged(fieldPath)`); `Permalinks`; and `FakeInstagramClient` over a deterministic `FakeLibrary`
  with scripted failures. `mediaInfo` returns `RemoteMedia?`: null means Instagram no longer has the item (P8).
- **Web layer.**
  - **The transport seam.** `InstagramTransport.get(pathAndQuery)` makes exactly one GET per call and never retries or
    follows a redirect. The path is relative to `https://www.instagram.com/` (no leading slash) and built only by
    `WebEndpoints`. It answers a `RawReply(code, contentType, body, redirected)`: `body` is null when it could not be
    read, `redirected` is true for a redirect that was not followed (a browser `fetch` hides where it led), and
    `toString()` never prints the body. `WebInstagramClient`, `WebSessionProbe` and `AdapterLab` take a transport (as a
    lambda, asked for on the first call that needs one), not an HTTP client. Production has one, `WebViewTransport`
    (see [Transport](#transport)).
  - **`classifyReply` is the one rule that turns a reply into a failure,** whatever transport got it: a redirect is
    `ChallengeRequired(null)`; a null body is `Transient` on a 2xx or 5xx and uses the header-only rules (`classifyUnreadable`)
    on a 3xx or 4xx; anything else goes through `ErrorClassifier.classify(code, null, contentType, body)`. A body that passes it must still be a JSON object for the
    parsers, or it is `ShapeChanged("$")`.
  - **Header constants.** `WebHeaders.APP_ID` is `1217981644879628` (the mobile website's id, from a capture) and
    `WebHeaders.ASBD_ID` is `359341`. The hidden page's script sends them with `x-requested-with: XMLHttpRequest`,
    `x-csrftoken` and `x-ig-www-claim` (both read inside the page); the browser adds everything else (user agent, client
    hints, Sec-Fetch, Referer, cookies).
  - **The OkHttp API client is JVM-test code now.** `OkHttpTransport` over `HttpClientFactory.create(cookies, userAgent,
    logger)` is how the MockWebServer suites of the client, the probe and the lab run. Nothing in `:app`'s `src/main`
    uses it, and `BackendWiringGuardTest` fails if something starts to. It keeps everything it had, each still pinned by
    its own tests: no redirects, no connection retries, 15 s connect and 30 s read; the cookie bridge (`CookieStoreJar`:
    OkHttp to the shared cookie store, Set-Cookie written back); `WebHeaders.interceptor` (the WebView user agent, the two
    ids, `X-Requested-With`, Accept, Referer, and `X-CSRFToken` from the cookie when it is header-safe); redacted debug
    logging (Cookie, Set-Cookie, X-CSRFToken, Location and the `ig-set-*` headers); an outermost crash guard (an unchecked
    failure leaves as an `IOException` that names only its class); `noRetryAfterOn503`; `SessionGuard`; and
    `ErrorReplyLogger`. **Why none of it was removed:** `SessionGuard`, `CookieStoreJar` and `ErrorReplyLogger` are used
    only inside `HttpClientFactory.create`, and that factory is what the MockWebServer suites build their client with
    (`WebInstagramClientTest`, `WebSessionProbeTest`, `AdapterLabTest`, `WebJsonTest`, `OkHttpTransportTest`), and what
    `HttpClientFactoryTest`, `ErrorReplyLoggerTest` and `SessionGuardTest` assert (`CookieStoreJarTest` tests the jar
    directly). Deleting them would mean rebuilding that test client and dropping those tests, which is not a clean
    deletion; a later change can port the suites to a fake transport and remove the whole client (`TODO.md`).
  - **Debug logging of error replies** (`ErrorReplySummary`, the one line for a non-2xx reply, whichever transport got it).
    `WebViewTransport` writes it after its `GET` line (see [Transport](#transport)); `ErrorReplyLogger` writes it for the
    test client, as a network interceptor placed before the header log. The line is a redacted one-line summary:
    `<-- 401 reply: status=fail message="..." error_type="..." require_login=true spam=false lock=false keys=[...]`,
    allowlisted fields only. Each string value goes through the Adapter lab's `LabRules.isVisibleString` and is written
    `<redacted len N>` if it fails; key names go through `LabRules.isSafeName` and the rest are only counted (`+N other`); a
    body that is not a JSON object logs only `non-JSON, <content-type>, <length> bytes`. No URL, cookie, id, handle or raw
    body is ever printed, and a 2xx is never touched. `ErrorReplyLogger` peeks at most 16 KiB (`Response.peekBody`), so the
    caller's body is whole, and un-gzips the peek itself (a network interceptor sees the wire bytes; OkHttp un-zips only
    after them). This deliberately amends spec 4.4's "response bodies are never logged" for debug builds only, and only
    for these fields. No request is added, so no pacing or concurrency changes.
  - `HttpClientFactory.createCdn` builds the CDN client (below).
- **`SessionGuard`** (test client only: no longer on any API path, because Chromium stores the page's cookies itself and the
  page is destroyed before the session changes, see [Transport](#transport)) is the OkHttp client's first network
  interceptor. It takes the `sessionid` the request actually carries from its `Cookie` header (OkHttp loads that header
  before it connects, so reading the jar after the handshake would miss a logout during it) and compares it with the jar's
  `sessionid` when the answer is back; if they differ it returns the response with every `Set-Cookie` header removed, just
  before OkHttp's cookie bridge would store them. This narrows the race, it does not close it. The CDN client has no jar and
  needs none. Both reads, "sent" (the request's header) and "now" (the jar through `CookieStore.cookieValue`), go through ONE
  parser, `internal fun cookieValueIn(header, name)` in `CookieStore.kt`, so the comparison can't drift: pairs split at `;`
  and trimmed, a pair matches only with the exact case-sensitive prefix `name=`, the value is everything after the first `=`,
  an empty value is null, and the first matching pair decides. `CookieValueInTest` pins those cases and, as source pins,
  that neither call site parses a header itself. (`CookieStoreJar.loadForRequest` enumerates every cookie for OkHttp and
  keeps its own, more forgiving split.)
- **The CDN client** (`HttpClientFactory.createCdn`) is cookieless (no jar at all, only the WebView user agent reduced to
  printable ASCII, so the header can't throw; 15 s connect, 30 s read) and every download is ONE request on the wire.
  `retryOnConnectionFailure(false)` alone does not give that in OkHttp 5.5: it neither stops the follow-up after a 503 with
  `Retry-After: 0` nor the re-send after a 421 on a coalesced HTTP/2 connection, and P6's one-host argument does not cover
  a client that talks to many CDN hosts under wildcard certificates. So it also follows no redirects (a 3xx is a failed
  download), strips the `Retry-After` of a 503 (`noRetryAfterOn503`, as the API client does), and a network interceptor
  turns a 421 into `IOException("CDN 421")`; the outermost `crashGuard` keeps any unchecked failure an IOException.
  MockWebServer tests pin each rule (one request, an IOException or the 3xx returned, the redirect target never
  requested), and an identity test pins that `noRetryAfterOn503` and `misdirectedIsAFailure` are NETWORK interceptors, not
  application ones: only a network interceptor sees a 503 or a 421 before OkHttp's own follow-up logic can re-send it, and
  the MockWebServer test of the 421 cannot tell the difference (its 421 is not on a coalesced connection).
- **Where Instagram strings live.** `WebEndpoints` owns every Instagram URL and host the app needs (the home and login
  pages, the API base and each endpoint, the login host allowlist and its dot-boundary matcher `isLoginPage`) and `WebSessionCookies` the
  exact Set-Cookie values and origin for a pasted session and its rollback, so `:app` spells no Instagram URL or cookie
  attribute out.
- **`ErrorClassifier`** maps one response to a typed failure, in this precedence:
  - a challenge marker (`checkpoint_required`, `challenge_required`, a `challenge` object) is `ChallengeRequired`;
  - HTTP 429, `feedback_required` or "please wait a few minutes" is `RateLimited` (it wins over `require_login`, which
    Instagram sends with it: the cooldown must not be lost); `login_required` or `require_login` is `LoginRequired`;
  - a redirect to `/accounts/login` is `LoginRequired`, any other 3xx stops like a challenge;
  - 401 and 403 are `LoginRequired`, 5xx is `Transient`, any other 4xx is `ShapeChanged("http.<code>")`;
  - an HTML page with no JSON is `LoginRequired`, a body that is not JSON `ShapeChanged("$")`, `"status": "fail"`
    `ShapeChanged("status")`.

  A challenge URL is kept only when it is https on `instagram.com` or a subdomain. A 3xx or 4xx whose body can't be read is
  classified from its headers alone (`classifyUnreadable`, called by `classifyReply` for the client, the probe and the lab
  alike): a cut 429 is still `RateLimited`, a cut 403 `LoginRequired`, a cut redirect to `/challenge/` `ChallengeRequired`,
  and a plain cut 4xx becomes `ShapeChanged("http.<code>.unreadable")`, which `mediaInfo` does not read as "not found" (the
  lost body may have held a challenge or a rate limit). The redirect rules need a `Location`, which only the OkHttp test
  transport sees. A page's `fetch(..., {redirect: 'manual'})` reports every redirect as opaque, so with the WebView
  transport every redirect is `RawReply(redirected = true)`, and `classifyReply` makes it `ChallengeRequired(null)`; only a
  page that is itself ON `/accounts/login` is `LoginRequired` (the transport checks where the page is, below).
- **`WebSessionProbe`** answers `currentUser()` via `api/v1/accounts/edit/web_form_data/`, the account edit form the website
  loads for the logged-in user, and reads `form_data.username` (a missing `form_data` or `form_data.username` is
  `ShapeChanged` with that path). The URL carries no id: the pk is the `ds_user_id` cookie, which must be digits, else no
  session, so a logged-out probe sends nothing and builds no transport. (It replaced `api/v1/users/{id}/info/`, the OkHttp
  login check that Instagram answered with 429.)
- **`WebParsers`** turn the collections, saved-posts and media-info JSON into `Page<RemoteCollection>`,
  `Page<RemoteMedia>` and `RemoteMedia?`.
  - Unknown keys are ignored. A missing required field throws `ShapeChanged` with its path (`items[1].media.user`).
  - An item with an explicit `"media": null` is skipped (R60: Instagram can no longer show it), whereas a missing or
    non-object `media` is a shape change: skipping it silently would let a FULL reconcile delete the item.
  - A page without `more_available`, or with it not a real JSON boolean (the string `"false"` included), or true but with
    no usable `next_max_id`, is a shape change, never "last page" (P5), so a bad response can't trigger a FULL reconcile.
  - Ids are read as exact digit strings, whether Instagram sends a number or a string. A media pk must be 1 to 30 plain
    digits (`3.1E18` or text is a shape change); a collection's cover pk that isn't digits just means no cover.
  - A `taken_at` outside the `Instant` range is a shape change; an out-of-range CDN `oe` just means no expiry.
  - `saved_collection_ids` with any non-string entry counts as "the response doesn't say" (null), never a shorter list.
  - Only collections whose `collection_type` is `MEDIA` are kept.
- **`MediaLinks`** picks the thumbnail (narrowest `image_versions2` candidate at least 720 px wide, else the widest; a
  carousel uses its first child's candidates) and reads the CDN link expiry from the hex `oe` parameter.
- **`WebInstagramClient`** is the real `InstagramClient`. `WebEndpoints` builds the four candidate URLs (P4:
  `collections/list/` with the three `collection_types`, `feed/saved/posts/`, `feed/collection/{id}/posts/`,
  `media/{pk}/info/`; the cursor is `max_id`), the transport fetches them (`classifyReply`, then the JSON object), `WebParsers`
  parses them, and `currentUser` is the `WebSessionProbe`.
  - It makes exactly one request per call and never retries (the caller paces every call through the Pacer, so it adds no
    request rate or concurrency). The transport is asked for lazily on the first call that needs it, so constructing the
    client sends nothing and builds no page.
  - A collection or media id goes into a URL path, so anything but 1 to 30 digits is a `ShapeChanged` before any request.
  - **P6:** every endpoint builder stays on the base host (`www.instagram.com`) and a test pins it. It was written to keep
    OkHttp from coalescing HTTP/2 connections and re-sending after a 421; with the hidden page it keeps every call
    same-origin (the transport also refuses a path that could name another host).
  - **P3:** `reportsSavedCollectionIds` defaults to `SAVED_COLLECTION_IDS_CONFIRMED = false` (strategy B: walk every
    collection, more requests but always correct) until the Adapter lab shows `saved_collection_ids` on saved items.
  - **P8:** `mediaInfo` returns null for HTTP 400 and 404 (the `ShapeChanged("http.400")` and `http.404` that
    `ErrorClassifier` gives a 4xx without a challenge, login or rate-limit marker) and for an empty `items`. A challenge,
    login or rate limit in the same 400 body still throws, and a null never deletes anything (the viewer shows "not
    available"). `mediaInfo` builds its URL before it touches the lazy transport, so an invalid pk never builds one.
- The JSON shapes are pinned by synthetic fixtures in `src/test/resources/fixtures/web/`.

## Adapter lab core

`instagram/.../lab/`: the pure-JVM core of the on-phone Adapter lab, where the owner sends one request per candidate
endpoint to learn the real response shapes (spec 6.3).

- **One request, no retry.** `AdapterLab.run(call, arg)` sends exactly ONE GET for a `LabCall` (current user,
  collections, All Saved, one collection, one media). The URL comes from the `WebEndpoints` builders (the user id from the
  `ds_user_id` cookie, digits only), so no session, a missing id or a non-digit id throws before any request and before
  the lazy transport is built. The lab adds no request path or rate: the caller (the app) must pace each `run` through the
  Pacer.
- **Any status is an answer.** `run` takes the transport's `RawReply` and classifies it with `classifyReply` (the one rule,
  see above); an HTTP error status is an answer, not an exception, and so is a redirect (its shape reads
  `(redirect, not followed)`: a page `fetch` hides where it led). A failure to get any reply, or a body that can't be read
  on a 2xx or 5xx, is a `Transient`; a body that can't be read on a 3xx or 4xx is classified from its status alone, its
  shape reads `(unreadable body)`, and it has no scrubbed copy.
- **The result** is a `LabResult`: the classification (`ok`, or the simple name of the exception), the typed error (an
  answer, not an exception), a redacted shape, a scrubbed copy and the ids that chain the next call. The raw body never
  leaves memory: it is a local that is classified, parsed, shaped and scrubbed, then dropped, and is never stored,
  logged, returned or printed (`RawResponse.toString` omits it).
- **`ShapeDump`** is for the screen: one line per key, two spaces per level; an array shows `array[N]` and expands only
  element 0; any number shows `number(N digits)` and any string `string(len N, digits|hex|base64url|text)`, except under a
  key of `VISIBLE_VALUE_KEYS` (`media_type`, `product_type`, `collection_type`, `status`, `more_available`, `num_results`,
  sizes, `carousel_media_count`, `error_type`, `message`, `feedback_title` and a few flags), where a number of at most 6 characters and a string that
  is enum-like (up to 40 letters and `_`) or a plain sentence (up to 200 ASCII characters, no word with a `.`, `_` or `@`
  inside, no run of 3 or more digits even when separated by any sentence punctuation or spaces (`3,100`, `31:00:00`,
  `(31)(00)(00)`), no two capitalised words in a row) are
  shown. A URL shows `url(host=instagram|cdn|other, params=[sorted names], oe=hex|absent|malformed)`, never the host or a
  value; a key that is data shows only its length and class.
- **`Scrubber`** writes the synthetic copy that can become a fixture in this public repo, with the same visible-value rule:
  - Ids (a `pk`/`id`/`fbid`/`*_id`/`*_ids` value, any all-digit string, each part of `<digits>_<digits>`) map to ids of
    the same digit count, consistent within one Scrubber so the saved fixtures of a lab session cross-reference, and no
    two ids share a stand-in (a length that runs out gets a longer one).
  - `username`/`full_name` become `user_<n>`, `text` `caption <n>`, `code` `C<n>` padded to its length, a URL
    `https://cdn.example.invalid/m/<n>` with its parameter names (values `x`, except a hex `oe`), a visible-key value that
    fails the rule `text <n>`, other strings `s_<n>`, timestamps `1700000000 + n * 86400`, other numbers `n`; booleans and
    null stay.
  - A key or URL parameter name that isn't schema-like (an uppercase letter, a dot or dash, a run of 5 digits, 16 or more
    characters with a digit in them, a word of the fixture guard's list) becomes `key_<n>` / `p_<n>`, and a synthetic key
    never overwrites a real one in the same object.
  - It keeps its mappings keyed by a salted SHA-256 digest, never by the raw value, so it holds no raw id, handle, caption
    or URL. Output with a word of the fixture guard's list is withheld.
- **The redaction is heuristic, not a proof:** a lowercase handle used as a key or as a URL parameter name, or a one-word
  value of letters and underscores in any case (`johndoe`, `JohnDoe`, `jane_doe`) under ANY visible-value key (`message` and
  `feedback_title` included, not only the enum-like ones such as `status`), can't be told from schema and is kept; so does
  a lowercase sentence under `message`. Read a scrubbed file before committing it.

## Transport

`app/.../transport/`, `app/src/main/assets/ig_fetch.js`, built in `di/AppContainer.kt`. Every Instagram API call is a
same-origin `fetch()` that Chromium makes inside a hidden `https://www.instagram.com/` page, so the request carries the
browser's own TLS and HTTP/2 fingerprint, headers, client hints and cookies. It replaced the OkHttp API client, whose first
two requests on the owner's phone were answered HTTP 429 (spec `2026-10-08-webview-transport-design.md`). The CDN path
(thumbnails, video) is unchanged.

- **Three parts.**
  - `WebViewTransport` is the `InstagramTransport`: when a page exists, what it may do, the time limits, the debug log.
    It is plain Kotlin over `WebPage`, so `WebViewTransportTest` runs it against a fake page.
  - `WebPage` is the seam under it: `load`, `currentUrl`, `evaluate`, `onMessage`, `onGone`, `destroy`, and `PageHttpError`
    for a document answered with an HTTP error.
  - `AndroidWebPage` is the real page: a `WebView` made from the application context and never attached to a window.
    JavaScript and DOM storage are on; `addJavascriptInterface` is used nowhere in `src/main`
    (`JavascriptInterfaceGuardTest`). Its cookies are the app-wide WebView ones, the jar the login screen fills. A
    navigation to anything but Instagram's, Facebook's or Meta's domains (`WebEndpoints.isLoginPage`) is refused
    (`shouldOverrideUrlLoading`). Everything is main-thread only: `get` and `reset` switch to the main dispatcher, so the
    sync worker's coroutine suspends until the reply.
- **One call.** `get(path)` refuses a path that does not start with a letter or a digit, or that holds a tab, CR or LF
  (so it can never name another host), then:
  1. makes the page exist: the first call (and the first after a reset or an idle close) creates it and loads
     `WebEndpoints.HOME_URL`, bounded at 30 s;
  2. checks where the page is (below);
  3. injects `ig_fetch.js` (its own guard makes that a no-op when `window.__igFetch` is still there; a navigation may
     have wiped it) and calls `window.__igFetch(<id>, <path as a JSON string>)`;
  4. waits up to 30 s for the page's message with that id.

  Exactly one `fetch` per call, so the Pacer's one gate, its gaps and its budgets mean what they meant before.
- **The page's script** (`ig_fetch.js`) is one function: `fetch('/' + path)` with `method: 'GET'`,
  `credentials: 'same-origin'`, `redirect: 'manual'` and the headers `x-ig-app-id: 1217981644879628`,
  `x-asbd-id: 359341`, `x-requested-with: XMLHttpRequest`, `x-csrftoken` (from `document.cookie`) and `x-ig-www-claim` (the
  site's own `sessionStorage` value, else `0`). It posts `{id, code, contentType, body, redirected}` back: an opaque redirect
  is posted as `redirected = true` with `code: 0`, a failed `fetch` (offline, DNS, reset) is code -1, a body that can't be read is null.
  The CSRF token and the claim never leave the page. Each fetch gets its own `AbortController`, kept by id until the fetch
  settles, and `window.__igAbort(id)` (defined once, behind the same guard as `__igFetch`) aborts it (R105); an aborted fetch
  posts code -1 for its id, which nobody waits for any more.
- **The message channel** is `WebViewCompat.addWebMessageListener`, installed before the first load as `window.igBridge`
  for the origin `https://www.instagram.com` only. A message reaches the transport only when the platform credits it to the
  MAIN frame, from exactly that origin, and only as a STRING; `AndroidWebPageGuardTest` pins the rule set and the three
  conditions (each removal, a rule widened to `*`, and a hand-over in an `else` or after the `if` fails it). The transport
  then accepts a message only from the current page, only when it parses, and only for the id it waits for. On the emulator
  a frame of another origin got no `window.igBridge` at all (the origin rule keeps it undefined there), while a frame of the
  SAME origin did: its forged reply through its own `window.igBridge` is dropped by the main-frame check, but one through
  `parent.igBridge` or `top.igBridge` is credited to the main frame and accepted (pinned by
  `aSameOriginFrameCanSpeakThroughTheParentsBridge`). **The trust boundary is therefore the instagram.com origin, not the
  main frame (R109):** the main-frame check only stops a same-origin subframe's own bridge, and any instagram.com script can
  forge a reply or replace `__igFetch`. That is accepted, because it equals trusting Instagram's replies, which the app does
  anyway; no nonce (R89). A WebView that cannot post messages (`WEB_MESSAGE_LISTENER` unsupported), or cannot be created at
  all, makes `AndroidWebPage` refuse to construct, never falling back to `addJavascriptInterface`; the call fails
  `Transient`.
- **Where the page is** is checked before EVERY call, not only after the load: the site can move itself (`pushState`, a
  client-side redirect). A path under `/accounts/login` is `LoginRequired`, under `/challenge` or `/accounts/suspended`
  `ChallengeRequired(null)` (never with a URL); the rule is `:instagram`'s `WebEndpoints.landingOf`. Another host, scheme or port, an unreadable URL, or no URL (the renderer
  is gone) is `Transient`. Login and challenge are remembered: every later call fails the same way, with no page created
  and nothing evaluated, until `reset()` (the account needs the owner, so a retry would only ask again). The other
  verdicts are not a judgement on the account: the page is dropped and the next call starts over. Nothing is evaluated on
  a page that failed the check.
- **A home page that is an HTTP error** fails the load (`onReceivedHttpError`, main frame, status 400 or more): 429 is
  `RateLimited`, so the caller's cooldown arms with no API request made; any other status is `Transient`. The page is
  dropped either way. A main-frame load error and a dead renderer fail it too.
- **Time and failure.** The load is bounded at 30 s and each call at 30 s; neither blocks a thread (a `CompletableDeferred`
  under `withTimeoutOrNull`, which leaves a caller's own deadline alone), and expiry is `Transient`. A load that fails or
  times out, a call that times out and a page that throws all drop the page: a stuck page is never reused. A network
  failure inside the page (code -1) is `Transient` and keeps it. A dead renderer (`onRenderProcessGone`) drops the page and
  fails a call in flight at once. At most one call is in flight, the load included: a second one is refused `Transient` and
  touches nothing (the Pacer already serialises real calls, so this only guards misuse).
- **A cancelled caller** (the viewer's `collectLatest` at a swipe, a sync's Cancel, a caller's own deadline) always ends
  with its own `CancellationException`, never another exception: one thrown by a cancelled coroutine fails its parent scope
  and can take the app down.
  - Cancelled while the page LOADS (R104), it does not drop the page. The load goes on under `NonCancellable`, still bounded
    at 30 s from its start and still ended at once by `reset()` or a dead renderer, and its outcome is applied as for any
    caller: a failure drops the page (and counts), a login or challenge landing is remembered, a good page is kept with its
    idle timer. Then the caller gets its cancellation. So swipes during a cold load (or the load after an idle close) cost
    one page between them; before, each dropped its page, and three swipes used up the cap and every link refresh after them
    failed "page limit" until a sync or a check. The price: a cancelled caller, and the Pacer's gate it holds, waits for the
    rest of the load, at most 30 s and normally seconds. The cap is never given back without a page (R89, R97).
  - A home page answered 429 for a caller that is gone (R104a) is remembered once: the NEXT call fails `RateLimited` at
    once, creating no page and evaluating nothing, so the Pacer still arms its cooldown there. `reset()` forgets it.
  - Cancelled while it WAITS for a reply (R105), the call keeps the page and, if that page is still the current one,
    evaluates `window.__igAbort(<id>)`, so the request ends at once, as an OkHttp call's did when cancelled. Without it the
    fetch ran on in the page while the Pacer (which marks a request ended when its caller returns) let the next call send
    its own. The abort is best effort: its failure is swallowed and the caller still gets its cancellation. The late
    message (code -1, or a reply that won the race) carries an old id and is ignored.
- **The page cap (R89, R91).** At most 3 pages are created per user action, however they end (a creation that throws
  counts too; an idle close gives the cap back): the site is not loaded over and over. Past that every call is `Transient`, with no page, until the next
  action. A user action is a sync run or a Resume (`SyncEngine.beforeRun`, set only for `Backend.Real`), a session check
  the owner asked for (`SessionRepository.validate`, inside the Pacer's gate, right before the request: Check now and the
  login screen's), or a lab tap that is really sent (`AdapterLabViewModel.beforeCall`, inside the gate). Each calls
  `allowNewAttempts()`, which does only that: it gives back the cap, and never destroys the page, never fails a call in
  flight and never forgets a login or challenge verdict (only `reset()` does). The viewer's link refresh does not call it.
- **The idle close (R93, R97).** The page is a live single-page app: while it exists, the site's own scripts keep sending
  background requests, and Android can keep the process cached for hours. So when a call ends, whatever its outcome, and a
  page exists, a timer starts on the main dispatcher; if no call begins within `IDLE_MS` (300 000 ms, 5 minutes) the page
  is closed. A call's start, `reset()`, `closePage()` and a dead renderer cancel the timer; `allowNewAttempts()` does not. 5 minutes is
  longer than the longest the Pacer leaves between two calls (a break of up to 180 s and a gap of up to 12 s, 192 s) and than
  its longest transient backoff (240 s, 288 s with its +20 % jitter), so a sync run normally keeps one page, because the
  gaps between its API calls are normally shorter. That is not a promise
  (thumbnail downloads sit between API calls too): a longer gap closes the page and the next call loads the site again.
  An idle close is the clean end of a visit, so it gives back the page cap; a page dropped by a failure never has a timer
  and still counts; a remembered login or challenge verdict is untouched. "Once per app run" in the spec's 3.2 and 4 is
  therefore "once per active period".
- **The owner-needed close (R106).** `closePage()` is the idle close made immediate, for a session that needs the owner: it
  cancels the timer, drops the page and gives back the cap (only when there is a page: never a refund without one), and
  never forgets a login or challenge verdict or a remembered 429 (the session has not changed; that is `reset()`'s). A call
  in flight on the page fails `Transient` at once, as with `reset()`. `SessionRepository` calls it (through its `closePage`
  hook, `AppContainer.closeInstagramPage()`, which only acts on a transport that exists) right after it stores an Expired or
  a Challenge: from `loginRequired` and `challengeRequired` (a sync run, the viewer, the lab) and from a `validate()` that finds
  one (Check now, the login screen). And the login screen calls it (`LoginSession.closeHiddenPage()`) when it opens with the
  RELOGIN or CHALLENGE purpose, never LOGIN or CSRF. So the site stops running for an account that was just found expired or
  challenged, instead of for up to 5 more minutes, and is not live beside the visible login that fixes it. The cost is one
  more home-page load on the next call. A failure is swallowed (cancellation excepted) and logged in a debug build as
  `transport close failed: <Class>`.
- **Traffic, and the pacing rule.** API request rates and concurrency are unchanged: one `fetch` per call through the one
  Conservative Pacer, and "Requests in 24 h" counts those calls only. A cancelled call is aborted, so request starts stay at
  least the Pacer's gap apart and no two of the app's API requests are open at once (R105). What is new is unpaced traffic the Pacer does not
  see: the home-page load and the site's own background requests while the page exists, which are what any visit to
  instagram.com sends. They are bounded by the page's life: 3 page loads per user action at most (counted again after an
  idle close), and the page closes at most 5 minutes after the last call (before the idle close, the background traffic
  would have run for as long as the process). The cost of the idle close is one more home-page load per active period (a
  run, a check or a lab session that begins after 5 idle minutes). A login-screen check runs with two instagram.com pages
  live, the visible login WebView and the hidden page, and since the stored state is not Valid then, R92 resets the hidden
  page first, so that check always comes with a fresh home-page load. One more thing the Pacer does not see: Chromium may
  re-send a GET on a dropped connection, the same way it would for the website itself (spec 4, accepted). The app makes one
  call and the Pacer counts one request, so a re-send is a request the app neither sees nor counts.
- **X-Requested-With (R100).** The WebViews keep sending their automatic `X-Requested-With: io.github.yuriimurha.reels`:
  on the emulator it went on every request the WebView made itself (the home page, images, frames, the favicon), while the
  API `fetch` carried only the script's own `x-requested-with: XMLHttpRequest` (a single value). It cannot be turned off:
  `androidx.webkit` 1.17.1's allow-list setter (`setRequestedWithHeaderOriginAllowList`) is deprecated and does nothing,
  its feature is unsupported on the emulator's WebView 145, and overriding the header on the main-frame `loadUrl` alone
  would leave the sub-resources with it and make the fingerprint inconsistent. A known marker, accepted: the login screen's
  WebView already sends it.
- **The destroy hooks.** The page belongs to one session: it may sit on a login or challenge page it will not leave, or act
  for an account that is gone. `WebViewTransport.reset()` destroys the page (failing a call in flight with `Transient`),
  forgets a login or challenge verdict and gives back the page cap. `AppContainer` keeps the transport as a `Lazy` and its
  three hooks, `resetInstagramTransport()`, `allowNewInstagramAttempts()` and `closeInstagramPage()` (R106, above), act only
  when `isInitialized()`: nothing that merely changes or asks about the session may build it. Mock mode's engine never
  touches it, and a cold logout, a cold Delete library, a stored expiry or challenge and a login screen opened to fix one
  build nothing (`InstagramTransportWiringTest`); Check now and a lab tap with a logged-in jar in Mock mode
  may build it, as they could use the network before. Building the transport builds no WebView either: the page is made
  by the first call. `reset()` runs (`beforeSessionChange`):
  - in `SessionRepository.logout`, first, before the cookies are cleared, so a page that outlives them cannot write them
    back;
  - in `pasteSessionId`, after the Pacer has agreed and before the first cookie is written, and again (under
    `NonCancellable`) before a rejected, failed or cancelled paste puts the previous cookies back, because the page that
    checked the rejected id may sit on a login page for good;
  - in `LibraryRepository.deleteLibrary`, first;
  - in `SessionRepository.validate`, INSIDE the Pacer's gate right before the request, when the stored state read there is
    not Valid (R92). The owner had to act (log in again, finish a challenge) and is asking again, so the request goes out
    on a fresh page rather than the one that said "login" or "challenge" last time. Never while the stored state is Valid
    (a page doing its job is kept), never before the Pacer has agreed to send, and inside the gate so it cannot kill
    another request's call. It is a reset before the probe, not after a new Valid is committed, because a check that begins
    on a sticky verdict can only end in it and would never get to commit a Valid.

  A failing hook never stops a logout, a rollback or a delete (cancellation excepted). In a debug build it logs
  `transport reset failed: <Class>` (the exception's simple name, never its message) through the sink below;
  `SessionRepository` and `LibraryRepository` take it as `debugLog`.
- **Logging (spec 3.4).** Debug builds log one line per API call under the tag `InstagramHttp`:
  `GET <path, digit runs of 3 or more as <n>> -> <code> (<ms> ms)`, for example `GET api/v1/accounts/edit/web_form_data/ ->
  200 (412 ms)`. `<code>` is the HTTP status, `redirect`, or what happened when there is none: `timeout`, `login page`,
  `challenge page`, `unexpected page`, `load http <status>`, `load http 429 (remembered)` (R104a), `load failed`,
  `load timeout`, `page limit`, `busy`, `no page`,
  `page destroyed`, `page error`, `network error`, `cancelled`, or `error` (the default, for a failure that is none of
  these). A non-2xx reply is followed by its `ErrorReplySummary` line
  (`<-- 429 reply: ...`, see [`:instagram`](#instagram)). Never a body in a 2xx line and never a header (the app sees none,
  so none is logged). The idle close logs `page closed (idle)`, `closePage()` `page closed (owner needed)`. A release build logs nothing: `AppContainer.debugLog` is
  null there. This replaces the OkHttp header log (`--> GET https://...` blocks with redacted cookies) for API calls, and
  amends spec 4.4.
- **`SessionGuard` and the OkHttp cookie bridge are no longer on the API path.** Chromium stores the page's cookies in the
  jar itself, and the hooks above replace the Set-Cookie guard. When the app itself changes the session's cookies (a logout,
  a paste, a paste's rollback, Delete library), `reset()` destroys the page first, so it cannot write the old ones back. A
  WebView login is the owner's, not the app's: a login screen opened to fix the session (RELOGIN, CHALLENGE) closes the page
  as it opens (R106), a first login (LOGIN, from LoggedOut) normally finds none (a logout destroyed it, and nothing sends
  without a valid session), and a login-screen check that starts from a stored state that is not Valid resets the page inside
  the Pacer's gate before its request (R92). `:app`'s `src/main` uses none of `SessionGuard`, `CookieStoreJar`,
  `OkHttpTransport` and `HttpClientFactory.create`. They still live in `:instagram`'s `src/main`, used only by JVM tests
  (see there for why they were not deleted), and `BackendWiringGuardTest` fails if `:app`'s production code starts using
  one. The CDN client has no jar and never had them.
- **Tests.** `WebViewTransportTest` (JVM, a fake page and virtual time) covers the call, the landing checks, the timeouts,
  the page cap, the idle timer, cancelled callers (R104, R104a, R105, through a real Conservative Pacer for the remembered
  429), the logging, and what the script may send and post (the header constants match `WebHeaders`; the CSRF token and the
  claim go into the request's headers only; one same-origin GET to `/` + the path, each fetch option once; every message is
  `{id, code, contentType, body, redirected}` with the reply's own content type and text or null; a self-check of
  mutants, `body: document.cookie` and a duplicate `redirect` among them, shows the pin fails each). `AndroidWebPageTest`
  exists twice: under `src/test` (Robolectric, which has no WebView provider or JavaScript) that a WebView that cannot post
  messages refuses to construct, and the page's `PageClient` (a top-level class for that reason) driven by hand: a dead
  renderer is handled (`true`) and reported, a main-frame HTTP error of 400 or more fails the load with its status, a
  subframe's does not. Under `src/androidTest` (emulator only: it skips on a physical device with the smoke suite's
  `isEmulator()` check) it runs the real page, the real `ig_fetch.js` and the transport against LOCAL MockWebServers at
  `http://127.0.0.1:<port>`, which is also the allowed origin passed in for the test. It checks that one `get` reaches the
  server exactly once with the site headers and the page's cookie, that a redirect is reported and not followed, that
  forged messages from another origin's frame and from a same-origin subframe's own bridge are dropped (and that one through
  `parent.`/`top.igBridge` gets through, R109), that the transport works from a `Dispatchers.Default` coroutine, that a
  cancelled call aborts its fetch (the page posts code -1 for it while the server still holds the reply) and the next call
  goes through, that a 429 home page is `RateLimited` with no API request, that a reset ends a load or a call at once, that
  a reply that never comes times out at the call's bound and the page is dropped, that the claim header is the site's own
  stored claim, that a page that moved itself (`pushState`) still requests the API path, and that a dropped connection is
  `Transient` at once with the page kept. Loopback cleartext needed no configuration on the API 37 emulator (the platform
  allows it for `127.0.0.1` and `localhost`), so no network security config exists; `CleartextGuardTest` pins that no
  release source set permits cleartext or names a config, that a debug-only one could permit loopback only, and that no
  release or debug source set overrides `onReceivedSslError`.
  `AndroidWebPageGuardTest` and `JavascriptInterfaceGuardTest` are source pins; `BackendWiringGuardTest` pins that
  `WebViewTransport` is the only `InstagramTransport`, that the client, the probe and the lab are built once, by the
  container, with `{ instagramTransport }`, and that `AndroidWebPage` is built once, there; `InstagramTransportWiringTest`
  (Robolectric) runs every hook on a container with no session and asks `instagramTransportCreated`; and the smoke suite's
  `requireNoTransport()` fails a Mock mode run, before and after each test, if the transport was ever built.

## Pacer

`app/.../sync/pacing/`. The single gate for Instagram API calls: one at a time.

- **Policies:** `Conservative` (real traffic) and `Fast` (the fake backend only; `FastPolicyGuardTest` keeps it out of
  `src/main` except `di/Backend.kt`).
- **Sync lane:** 4–12 s log-normal gaps, a 60–180 s break every 15–30 requests, 300 requests per run and 600 per rolling
  24 h (counted from the persisted `api_request` log). A run's budget is per engine invocation: each resume starts at 0,
  and the 24 h cap still holds.
  - `sync(run, precondition, request)` takes the same optional `precondition` as the interactive lane (R82). It runs holding
    the gate, after the gap (and any break) has been waited out and the last `ensureAllowed()` and run-budget checks passed,
    right before the break slot and the run budget are counted and the request is recorded and sent. A throw sends and logs
    nothing, uses neither the run budget nor the break slot, leaves the end of the last request where it was, frees the gate
    and reaches the caller. It can only stop requests.
- **Interactive lane** (viewer link renewals, session checks, lab calls): priority, so interactive requests never wait
  behind a sync gap or break, with a 2 s minimum gap, counted in the 24 h budget. `interactive(precondition, request)`
  runs the optional `precondition` once the gate is held, the gap waited out and the last `ensureAllowed()` passed, right
  before the request is recorded and sent (R79).
- **CDN lane:** 2 concurrent downloads with 0.2–0.8 s jitter, not budgeted.
- **Cooldowns:** after a `RateLimited` the cooldown is 1 h, or 24 h if the previous rate limit was less than 24 h ago. It
  is persisted in DataStore, so killing the app does not reset it. While it is active every API lane refuses
  (`PacerRefusal.CoolingDown`). Refusals are `CoolingDown`, `RunBudgetReached` and `DailyBudgetReached`.
- **Transient retries:** `retryTransient` waits 30, 60, 120 and 240 s (each ±20 %) and wraps each attempt in the Pacer, so
  retries count as requests, then rethrows (the run is PAUSED).
- **Gaps are truncated, not clamped:** `PacingPolicy.sampleGap` redraws the log-normal sample until it lies in 4–12 s
  (clamping put about 18 % of gaps at exactly 4 s and 6 % at exactly 12 s, a timing signature); the median moves from
  about 6.0 s to about 6.4 s, so it is slower. When another request (an interactive one) ran while a sync request waited,
  the sync request waits a fresh gap draw from that request's end (almost never exactly the 4 s minimum, never less).
- **A restart does not reset the gap.** `lastRequestEndedAt` is seeded once from the persisted request log
  (`RequestLog.latest()`, `MAX(at)` over `api_request`), so the first request of a restarted process waits out the gap
  after the last logged request (sync: that time plus a gap draw; interactive: that time plus 2 s); with an empty log the
  first request is still immediate. The log stores request start times, so after a restart the gap is measured from the
  last request's start, and the seed is clamped to now: a future-dated entry (clock set back, emulator snapshot restore)
  is treated as "now", so it can't stall the gate. The truncation, the seeding and the clamp all lower the request rate;
  none raises it.
- **What the Pacer counts.** One API call, one `fetch` ([Transport](#transport)). It does not see the hidden page's own
  traffic (the home-page load and the site's background requests while the page exists); that is bounded by the page's
  life, not paced. Nor does it see a re-send: Chromium may re-send a GET on a dropped connection, as it would for the
  website itself (spec 4, accepted).

## Sync engine

`app/.../sync/SyncEngine.kt`. One run: session check (`currentUser()`), collection list, scope walks, one transaction per
page (media, memberships, cursor), thumbnails on the CDN lane.

- **Scopes.** Strategy A walks All Saved only, using `savedCollectionIds`; strategy B (the default, P3) walks All Saved
  and then every collection. QUICK stops a scope after the first page that holds an item the scope already has (so on an
  empty library it walks everything); FULL walks to the end.
- **Reconcile.** FULL runs reconcile a scope only when its walk reached the end in that run. All Saved marks unseen items
  removed (and deletes their memberships, thumbnails and cached videos); a collection (strategy B) deletes its unseen
  memberships only (`deleteUnseen`).
- **Gates against a broken or partial answer wiping the library:**
  - A FULL walk of All Saved that ends having seen nothing over a non-empty library stops as `STOPPED_SHAPE`
    (`"empty saved feed"`) instead of reconciling.
  - An empty collection list while the library has live collections is `ShapeChanged("empty collection list")`, thrown
    after paging and before the transaction, so no collection is marked removed.
  - Strategy A rewrites an item's memberships only among the collections this run listed
    (`deleteRealMembershipsExcept(pk, keep, known)`), so a membership in a collection the run didn't list is left alone. An
    item whose `savedCollectionIds` is null keeps its memberships.
  - **P7 as amended by R71,** in `reconcile` of All Saved before anything is marked: a FULL reconcile that would remove at
    least `RECONCILE_GUARD_MIN_ITEMS` (20) items AND more than half of the All Saved members that existed BEFORE the run
    is `ShapeChanged("full sync would remove N of M items")` (M is that pre-run count; `lastError` "Adapter needs repair:
    full sync would remove 70 of 100 items"), thrown inside the page's transaction so the page and its cursor roll back and
    the run stops `STOPPED_SHAPE`.
    - "Before the run" is `CollectionDao.memberCountSeenBefore(ALL_SAVED_ID, cutoff)`: members whose media has
      `firstSeenAt < cutoff`. The denominator must not include the feed's own new items: a feed that is not this library
      (another account's, a wrong endpoint) with 100 new pks would otherwise double the count and let all 100 originals be
      marked removed.
    - The cutoff (R83) is `min(run.startedAt, lastDoneAt + 1)`, where `SyncDao.lastDoneAt()` is the latest
      `finishedAt` of a DONE run (either mode); with no DONE run it is `run.startedAt`. A refused or paused Full sync leaves
      the pages it committed behind, and Discard then made them look older than the next run's start: a foreign feed's 80
      items over a library of 40 would have let a second Full sync remove all 40. Now items no DONE run has finished with
      are not "the library" yet. The `+ 1`: a run's last page and its finish can fall in the same millisecond (the
      boundary tests fail without it). A DONE QUICK run does count what it added: a same-account feed that a QUICK run has
      accepted is library by then, which the guard can't and doesn't try to tell from a real change.
    - `startedAt` (set by `SyncController` from `System::currentTimeMillis`) and `firstSeenAt` (set by the engine's `now`,
      the same default clock) agree, and a resumed run keeps its row and so its `startedAt`: items stored by an earlier
      attempt of the SAME run were first seen after it, so they count as new in the attempt that finishes the run (pinned
      by a two-attempt test).
    - A pre-run item that was removed earlier and is saved again keeps its old `firstSeenAt` and counts as pre-run (a small
      overcount that only makes the guard laxer by the number of re-saved items). A wall clock set back BETWEEN runs counts
      old items as new (their `firstSeenAt` is after the new, earlier `startedAt`), which makes the guard stricter; set back
      MID-run, the run's own new items get a `firstSeenAt` before its `startedAt` and count as pre-run, which makes it
      laxer.
    - The cost: an owner who unsaves more than half of the library and saves new items in the same stretch, then runs Full
      sync, is refused; Delete library then Full sync is the way to mirror that.
    - Strategy B's per-collection reconcile has no such guard by design (R72): it removes memberships only, rebuilt by the
      next good FULL walk.
  - **One library, one account (R84).** Right after `currentUser()` succeeds (and `sessionOk` is signalled), the run
    compares the account's pk with the library's `LibraryAccount`: none stored means this run's account is remembered;
    the same pk goes on; another pk stops the run as STOPPED_SHAPE with `lastError` "This library belongs to another
    Instagram account. Delete library to switch.", before the collection list is requested, so nothing of that
    account's feed is written and no reconcile can count it. Delete library forgets the pk. The fake account's pk is
    constant ("1"), so the fake library is unaffected. This is what closes Discard, then Sync (QUICK), then Full sync
    under another account's session, and the mixing of two accounts' saves.
- **Thumbnails.**
  - The first `CdnRateLimited` of a run sets `Progress.cdnBlocked`, and from then on no thumbnail is requested in that
    invocation: each download checks the flag before queuing and again once it holds a CDN permit (it may have queued
    behind the one that got the 429), so only the downloads already in flight (at most the policy's CDN concurrency) can
    still happen, and every later page makes none. The download that got the 429 counts as a failed thumbnail, the skipped
    ones do not (they have no thumbnail yet, and a resume or the next Full sync fetches them); the flag is not persisted,
    so a new run tries again.
  - A `TimeoutCancellationException` out of a download is a failed thumbnail and never cancels the run (it is caught
    before the generic `CancellationException` rethrow, and re-checked with `ensureActive()` so a timeout of the run's own
    scope still propagates).
  - A thumbnail failure (network, decode or disk) is counted and the sync goes on. A page is committed before its
    thumbnails are cached, so a resumed scope first retries the thumbnails of items the run already saw without one.
  - None of this adds a request, a rate or a concurrency: the gates only stop work.
- **Eviction.** The removed pks of a reconcile are handed to a `MediaEviction` (default none), so their cached videos go
  with their thumbnails (a failing eviction is ignored: the reconcile is already committed).
- **Session signals.** `run` reads `signals.epoch()` first thing inside its try (a session layer that cannot answer ends the
  run PAUSED "Unexpected error", never RUNNING), tells the session layer `sessionOk(username, epoch)` once
  `currentUser()` has succeeded, and passes the same epoch with `loginRequired` and `challengeRequired`; a failing receiver
  changes nothing about the run.
- **Session gate (R82, spec 6.4).** Every request of a run, the first `currentUser()` included, passes
  `sessionUsable(epoch)` (the epoch the run captured at its start) as the Pacer's sync-lane `precondition`, so it is asked
  inside the gate right before sending. `RunSession.USABLE` lets the request go. `CHALLENGE` (a stored Challenge) stops the
  run as STOPPED_CHALLENGE, and `NOT_USABLE` (Expired, LoggedOut, or another epoch after a logout, a paste or a new login) as
  STOPPED_LOGIN (this includes the jar's account no longer being the one the epoch was issued for), with nothing more sent and
  no session signal (the session layer already knows, and
  `challengeRequired(null, ...)` would wipe the challenge URL it stored). This is what stops a run when the viewer, the
  lab or Check now meets a challenge or an expiry mid-run, a paste replaces the session mid-run, or WorkManager re-runs
  work by itself after a process death under a session that is no longer valid (it then sends zero requests). The
  default (the fake backend) lets every request through. It only removes requests.
  - **And again when the request returns (R107).** The gate is asked before a request is sent, and a paste, a logout or a
    login as another account can land while it is out. So `call` asks `sessionUsable(epoch)` once more when the request (with
    its transient retries) has returned, before anything of its answer is written: a page that came back under a session
    that is gone is dropped and the run stops as above. For the first `currentUser()` this also means a library is never
    given the account of a session that changed while the check was out (R84). A read, never a request.
- `SortKeys` gives newest-first keys.

### Run outcomes

Expected errors map to run statuses, an unexpected one pauses the run (its `lastError` names only the exception class),
and only cancellation propagates. The status and `lastError` are written before `run` returns. The Sync screen shows the
`lastError` as the banner for PAUSED and `STOPPED_SHAPE`, and a fixed text for the other three STOPPED statuses; the Saved
status chip shows the `lastError` for any stopped or paused run.

| What happened | Status | `lastError` | Sync screen banner |
|---|---|---|---|
| Cancel, or logout during a run | PAUSED | Cancelled | Cancelled |
| App killed mid-run, found at the next start | PAUSED | Interrupted, tap Resume | the same |
| `ChallengeRequired` (the session layer gets the challenge URL) | STOPPED_CHALLENGE | Instagram wants verification | Instagram wants verification. Resolve it before syncing again. |
| `LoginRequired` (the session layer marks it expired) | STOPPED_LOGIN | Session expired | Session expired. Log in again, then tap Resume. |
| The session gate said no before a request (R82): a stored Challenge; or Expired, LoggedOut, or a logout or paste since the run started. Nothing more is sent, no signal | STOPPED_CHALLENGE for a Challenge, else STOPPED_LOGIN | Instagram wants verification, or Session expired | as the two rows above |
| `RateLimited` (the Pacer armed the cooldown), or `PacerRefusal.CoolingDown` | STOPPED_RATE_LIMIT | Instagram is limiting requests, or Cooling down | Instagram limited requests. Tap Resume when you're ready. |
| `ShapeChanged(path)` | STOPPED_SHAPE | Adapter needs repair: `<path>` | the `lastError` |
| The session's account is not the library's (R84), before anything is written | STOPPED_SHAPE | This library belongs to another Instagram account. Delete library to switch. | the `lastError` |
| `Transient` after the four backoffs | PAUSED | Network problem, try again later | the `lastError` |
| `RunBudgetReached` (300 requests) | PAUSED | Run budget reached, tap Resume | the `lastError` |
| `DailyBudgetReached` (600 in 24 h) | PAUSED | 24-hour budget reached | the `lastError` |
| Anything else | PAUSED | Unexpected error: `<class>` | the `lastError` |

While a cooldown is active the banner is "Cooling down after a rate limit: N min left" instead, and starting is off.

## Video

On-demand playback (spec 8). Files: `data/media/VideoSourceResolver.kt`, `RealVideoSourceResolver.kt`, `VideoCache.kt`,
and `ui/viewer/` (`ViewerPlayback.kt`, `ViewerViewModel.kt`, `ViewerScreen.kt`).

- **The resolver.** `VideoSourceResolver.resolve(media, forceRefresh)` answers `VideoSource.Play(uri, cacheKey = pk)`,
  `VideoSource.Unavailable(message)`, or null for a non-video. `RealVideoSourceResolver` first re-reads the item's row
  (`mediaDao.byPks`), because the caller's paging snapshot can be older than a link a prefetch just renewed, then uses the
  stored link as it is while it has MORE than `FRESH_MARGIN_MS` (10 min) left (no request). A video that is FULLY cached
  plays from its stored link whatever that link's age, with no request (R78: the bytes are on disk).
- **Otherwise one `mediaInfo`** on the Pacer's interactive lane (the process's one Conservative pacer, so a refresh counts
  against the same 600/24 h and cooldown as sync), but only under a valid session (R76, spec 6.4):
  - The resolver is given `isSessionReady`, which `AppContainer` wires to `session.state.first() is SessionState.Valid`,
    so after a challenge, an expiry or a logout NOTHING is sent, by a settle, a forced refresh or a prefetch alike (a
    cached video still plays, anything else shows "Instagram session needs attention (Sync screen)").
  - It is asked twice: before the request queues, and again from INSIDE the Pacer's gate. The interactive lane can wait
    seconds (a request in flight holds the gate, then the 2 s gap) and a challenge can arrive meanwhile, so the Pacer runs
    the `precondition` once the gate is held and the gap waited out (R79): a throwing precondition sends nothing, logs
    nothing (no budget is used), frees the gate and reaches the caller, and the resolver turns its private "not ready"
    signal into the same answer as the early check. The new argument adds no request, rate or concurrency.
- **The renewed link** is stored with `MediaDao.setVideoLink`; one whose URL names no expiry (`oe`) is stored as fetch time
  + 1 h (`ASSUMED_LIFETIME_MS`, R78), so it cannot cost a `mediaInfo` on every settle. The sync engine still stores such a
  link as it comes, with no expiry: it costs one refresh at its first settle and is then stored with the assumed hour.
- **Failures.**
  - Not found, or an answer with no video link: "This item is no longer available on Instagram" (nothing is deleted).
  - A `PacerRefusal` (cooldown or budget), `InstagramException.Transient` or an `IOException` falls back to `Play` with
    the stored link when `VideoCache.isFullyCached(pk)` (every such check runs on an injected IO dispatcher,
    `Dispatchers.IO` by default: `SimpleCache` synchronises its reads with its file commits, so the viewer's main thread
    never waits on it), else a message ("Video can't load right now: ...", "Offline: this video isn't cached yet").
  - `LoginRequired` / `ChallengeRequired` tell the session layer (with the epoch read BEFORE the request, like the engine;
    the challenge URL is passed on to the session layer and never shown) and show "Instagram session needs attention (Sync
    screen)". `RateLimited` shows "Instagram is limiting requests" (the Pacer armed the cooldown). Anything else,
    `ShapeChanged` included, shows a fixed "Can't load this video right now", never the failure's own text.
- **`VideoCache`** wraps a Media3 `SimpleCache` (LRU, 512 MB) in `cacheDir/video` for the real library and
  `cacheDir/fake-video` in Mock mode (R85: like thumbnails, each library has its own, so Mock mode's clip cached under a
  fake pk can never answer for a real item, and Delete library in one mode never empties the other's), ONE per process
  (`AppContainer.videoCache`, lazy; a second one on the directory throws).
  - Entries are keyed by the pk (`MediaItem.setCustomCacheKey`), never the link, so a renewed link still hits the cached
    bytes. `isFullyCached` is true only when the recorded content length is known AND the whole range 0 to that length is
    on disk (`CacheDataSource` records the length when a read OPENS, so a read the player stopped after a few KB has a
    length too: tests pin that it is not fully cached).
  - `cachedDataSourceFactory` is what the player reads through: `CacheDataSource` over `DefaultDataSource` (so Mock mode's
    `android.resource` clip still opens) over `DefaultHttpDataSource` with the WebView user agent (read once, only for the
    real library) and NO cookies: nothing installs a `CookieHandler` and the video path never touches the cookie store or
    the Instagram transport (`instagramTransport`, `WebViewTransport`; pinned by `VideoWiringGuardTest`; `VideoDataSourceTest` runs the stack against MockWebServer: a new
    link under the same pk is served from disk with no second request, and no `Cookie` header is sent).
  - Redirects are followed: `DefaultHttpDataSource` has no switch to refuse them (`setAllowCrossProtocolRedirects(false)`,
    its default, only refuses an http/https change), so a CDN redirect costs one more request, cookieless, to the host it
    names. Video is not on the Pacer's CDN lane, so this is not counted anywhere.
  - `videoMediaSourceFactory` is what the viewer's ExoPlayer builds media from: a `DefaultMediaSourceFactory` over
    `cachedDataSourceFactory` with `VideoLoadErrorPolicy` (R85). Media3's default policy retries every load error up to 3
    times before the player reports it; the policy returns `C.TIME_UNSET` (no retry) when the error or its cause chain is an
    `HttpDataSource.InvalidResponseCodeException` (a 403/410 link that ran out, a 429, a 404), and defers to the default for
    anything else (a dropped connection, a timeout). A refused link therefore costs one CDN request, not four, and reaches
    `onPlayerError` at once. `VideoWiringGuardTest` pins that the viewer uses this factory and that it installs the policy.
  - **Eviction:** a FULL reconcile's removed pks go to `MediaEviction` right after their thumbnails are deleted, and Delete
    library clears the whole cache.
- **In the viewer,** settled pages are collected with `collectLatest` (`playSettledPages`), so a newer page cancels the
  older resolve, and `playIfStillSettled` re-checks that the page still shows the same pk before the player is touched.
- **403 and 410.** A `Player.Listener.onPlayerError` whose cause chain holds an `InvalidResponseCodeException` 403 or 410
  triggers ONE `resolve(forceRefresh = true)` per item per visit (`ViewerViewModel.recover`), which first cancels a
  prefetch still out for a DIFFERENT item, so the visible item's refresh does not queue behind the next item's in the
  interactive lane (R79). Cancelling does not unsend: if that prefetch's request was already out, it was sent, so a 403/410
  recovery can cost one extra request (still paced by the interactive lane's 2 s gap and counted in the 24 h budget), and
  `recover` never reuses a prefetch's request. A second error, or any other error, shows the thumbnail with "Can't play
  this video". An `Unavailable` shows its message above "Open on Instagram".
- **Prefetch.** Once the settled item's OWN resolve has returned (R77: `playSettledPages` calls `afterResolved` after it,
  so the visible item's request is never queued behind the prefetch in the interactive lane's FIFO), the NEXT item's link
  is renewed through the same resolver (so under the same session check) only if it is a video whose link has run out or
  runs out within the margin, in ONE job that a newer settle cancels, and at most once per pk.
  - A settle on the very item being prefetched does not cancel it and does not send a second request: `resolveVideo` waits
    for the request still out (a finished prefetch is not reused; the resolver sees the renewed row).
  - If that prefetch is cancelled while the settle waits (a newer prefetch replaces it, or a refresh makes way), the settle
    is not: it asks the resolver itself, at the cost of one more request; the settle's own cancellation still propagates.
- **Request rate.** Nothing here adds a lane, a rate or a concurrency: a video costs at most one interactive `mediaInfo`
  when it is opened with an expired link, plus at most one prefetch per settled page, each paced by the interactive lane's
  2 s minimum gap and counted in the 24 h budget; a fresh link, a cached video and Mock mode send nothing. The player
  retries no HTTP error status (R85), which only removes CDN requests.

## Wiring

`app/.../di/`, `ReelsApp.kt`, `data/media/HttpMediaFetcher.kt`.

- **`AppContainer`** is hand-wired and lazy, one per process. `ReelsApp` also installs a custom `WorkerFactory`
  (`ReelsWorkerFactory`), enables WebView debugging in debug builds only, and calls `recoverInterruptedRuns` at start.
- **Backends.** `Backend` is `Fake` (fake client + placeholder fetcher + its own fast Pacer with in-memory budgets) or
  `Real` (`WebInstagramClient`, `HttpMediaFetcher`, and the process's ONE Conservative `instagramPacer`). `Real` takes the
  pacer as a constructor argument and `BackendSelectionTest` pins `backend.pacer === instagramPacer`, so real traffic can
  never get a second pacer; the Sync screen shows `backend.pacer`, which for `Real` is that one (in Mock mode it is the
  fake's, so it also passes `instagramPacer` to `SyncViewModel(realPacer = ...)` and the Session section shows the real
  Pacer's state as one extra line under the status: see Session, below).
- **Mock mode.** `BackendChoice` (SharedPreferences file `backend`, key `use_fake`) decides which: release builds never use
  the fake library, debug builds default to it, and `AppContainer.usesFake` reads it ONCE per process, so the library, the
  thumbnails and the backend can't disagree within a run.
- **Video wiring.** `videoCache` (lazy, one `SimpleCache` per process, in `cacheDir/fake-video` in Mock mode and
  `cacheDir/video` otherwise; `BackendSelectionTest` runs both) and `videoResolver` (`FakeVideoSourceResolver` in
  Mock mode, else `RealVideoSourceResolver(backend.client, instagramPacer, db.mediaDao(), videoCache, session,
  isSessionReady = { session.state.first() is SessionState.Valid })`, where `session` is both the `SessionSignals`
  argument and the source of the readiness check) live here too. `syncEngine()` passes a `MediaEviction` over
  `videoCache.remove`, and `library` gets `clearVideoCache = { videoCache.clear() }`.
- **Engine signals and gate.** `syncEngine()` is an exhaustive `when (backend)`: `Fake` gets `SessionSignals.None` and a
  gate that is always `USABLE`; `Real` gets the `SessionRepository`, whose signals carry the session epoch, and
  `sessionUsable = session::runSession` (R82: Challenge if the stored state is Challenge, Usable only for a Valid state
  under the run's own epoch, with the jar still holding that epoch's account; the lab and the video resolver ask the same
  `runSession` from inside their own gates). `runSession` never takes `SessionRepository`'s lock: a paste holds that lock while it waits
  for the Pacer's gate, and the gate's holder is the one asking, so the lock would deadlock both (pinned by a test that
  runs exactly that interleaving). It reads the state before the epoch, because logout, paste and `validate`'s new-session check bump the epoch first.
- **The library's account.** `libraryAccount` is `StoredLibraryAccount(settings, SyncWorker.kindOf(usesFake))`, one per
  library (R84); the engine checks it and `library` gets `forgetAccount = { libraryAccount.forget() }`, so Delete library
  forgets the account of the library it deleted (`BackendSelectionTest` runs both modes).
- **Lazy transport and CDN client.** The Instagram transport (`instagramTransportLazy`, a `Lazy<WebViewTransport>` that the
  container's two hooks ask `isInitialized()`, see [Transport](#transport)) and `cdnHttp` (whose user agent comes from
  `WebSettings.getDefaultUserAgent`, a WebView provider load) are built by the first request that needs them, so
  constructing the container or the backend loads no WebView, and the transport's page is made only by its first call
  (tests pin this). The real client, the session probe and the Adapter lab each get `{ instagramTransport }` and are built
  only here; `AndroidWebPage` is built once, only here.
- **`HttpMediaFetcher`** downloads from the CDN with its own client (`HttpMediaFetcher.client` delegates to
  `HttpClientFactory.createCdn`, see [`:instagram`](#instagram)). It refuses a blank, unparseable or non-https URL with no
  request (null; the `requireHttps = false` parameter exists only so MockWebServer works, and a test pins that the default
  refuses http), maps 200 to the bytes, 403/404/410 to null, 429 to `CdnRateLimited` (an `IOException` subclass; not an API
  rate limit, so no cooldown, but the engine stops the CDN for the rest of the run) and any other status, a 3xx included, to
  an `IOException` that names only the code (CDN links are signed, so a URL is never in a message). It reads the body on
  OkHttp's own thread, so cancelling the coroutine cancels the call.
- **Source pins.** `BackendWiringGuardTest` keeps `Backend.Real`'s fetcher on `cdnHttp`, never on the Instagram transport (the
  page that holds the session); keeps `OkHttpTransport`, `HttpClientFactory.create(` and `instagramHttp` out of `src/main`;
  lets `WebViewTransport` be the only `InstagramTransport`; keeps the Sync screen on `container.backend.pacer` (plus
  `realPacer = if (container.backend is Backend.Fake) container.instagramPacer else null`); and keeps the Mock switch's
  `cancelSync` on `cancelAndAwait()` (the test WorkManager cannot tell it from `cancel()`). `ContainerSyncWiringTest` runs the real
  container on WorkManager's test build with a worker that waits: a queued run carries `kindOf(usesFake)` (`"fake"` in Mock
  mode, `"real"` otherwise, so a wrong boolean fails it), and the Mock switch's `cancelSync` leaves the queued work
  CANCELLED (a no-op `cancelSync` fails it). `FastPolicyGuardTest` keeps `PacingPolicy.Fast` confined to `Backend.kt` and
  `DebugGatesGuardTest` keeps the debug gates in place.
- **Restarting.** `ProcessRestart.restart(context)` relaunches `MainActivity` with `NEW_TASK | CLEAR_TASK` and exits the
  process (never called from a unit test: the restart is injected as a lambda into `MockModeSwitch`).
  - A restart must not replay the other library's run: both libraries number their runs from 1 and the work input names
    only a run id, so the work input also carries the backend kind (`"fake"`/`"real"`, `SyncWorker.kindOf`), and
    `SyncWorker` returns `Result.failure()` without touching the engine when it differs from the process's kind (or is
    missing, as in work queued by an older build).
  - `MockModeSwitch.change` first cancels the unique sync work and waits for WorkManager to record that, then stores the
    mode, and restarts only if the write succeeded (if the work can't be cancelled, nothing changes).
- **The request log across the split.** Before the real and fake libraries were separated it lived in `reels.db`, so the first time `library.db` is opened
  (`LegacyRequestLogCopy`, a Room callback; a flag in the `backend` preferences makes it once) the `api_request` rows of the
  last 24 h are copied out of `reels.db`, which keeps the 24 h budget and the restart-gap seed across the upgrade; a missing
  `reels.db` is skipped and never created, and a failed copy leaves the flag unset so it is retried at the next open.

## Lab screen

`app/.../ui/lab/`, `ui/sync/DeveloperSection.kt`. The debug-only front end of the Adapter lab core (spec 6.3), plus the
Mock mode switch.

- **Developer section.** The Sync screen shows it only when `BuildConfig.DEBUG` (below Storage).
  - Its "Adapter lab" button is enabled only while the session is Valid.
  - Its Mock mode switch is shown when the `SyncViewModel` holds a `MockModeSwitch` (the app always passes one in debug
    builds). The row reads "Mock mode (fake library)" and shows the mode this PROCESS runs in (`AppContainer.usesFake`, not
    the stored choice, which only takes effect on the next start).
  - The switch is disabled until the latest run has been read (`SyncViewModel.mockSwitchEnabled` starts false: "no run" and
    "not loaded yet" are both a null `run`; the loaded-run flow behind it and `mockSwitchEnabled` itself drop their cached
    values once nothing has collected them for the 5 s grace period, `replayExpirationMillis = 0`, so a screen that was
    stopped can't act on, or even show, a stale "loaded, no run" while a run it never saw is going) and while a run is
    RUNNING (the screen disables it and `SyncViewModel.setMockMode` ignores it too, and a second tap while a change is under
    way). A change cancels the queued sync work, stores the mode (`BackendChoice.setUseFake`, a `commit()` that returns
    whether it was written) and then restarts the process, on an I/O dispatcher.
- **Route.** `AdapterLabRoute` is registered in `ReelsNavHost` under `BuildConfig.DEBUG` too, so a release build has
  neither the entry point nor the screen.
- **Screen.** One button per `LabCall` (Who am I, Collections, All Saved (page 1), First collection (page 1), Media info
  (first saved item)), all disabled while a call is in flight or the session isn't Valid; the last two also wait for an id
  (a collection id from Collections, a media pk from All Saved). Under the buttons the latest result shows the call, the
  HTTP code, the classification and the shape in a monospace, sideways-scrolling, selectable `Text`, then the path of the
  scrubbed copy.
- **View model.** `AdapterLabViewModel` talks to the lab through the `LabRunner` interface (`AdapterLabRunner` is the thin
  pass-through over `AdapterLab`, built by `AppContainer.adapterLab`, which does not build the transport: that is built on
  the first call that reaches the network).
- **Pacing.** Each tap is one `pacer.interactive { }` on the single `AppContainer.instagramPacer`, so it is one request, with
  the interactive lane's 2 s minimum gap, the shared 600-per-24-hour budget and the persisted cooldown; a second tap while one
  is out is ignored, and nothing retries. The stored session must be Valid, checked before the tap queues and again as the
  Pacer's `precondition` from inside the gate (R85, as R79 for the viewer): a challenge or an expiry stored while the tap
  waited sends nothing, logs nothing and adds no message (the screen already says "Log in on the Sync screen to use the
  lab."). A tap that is really sent first tells the transport that a new user action begins (`beforeCall`, inside the gate:
  the page cap counts afresh, see [Transport](#transport)); a refused tap runs nothing. The lab adds owner-triggered requests
  on that existing lane and raises no rate or concurrency. A returned `RateLimited` is stored in a local first and then thrown inside the Pacer block, because the Pacer
  arms the cooldown only when the block throws it; the ViewModel catches it outside and still shows the shape. A
  `PacerRefusal` (cooldown, budget) shows its fixed message and sends no request.
- **Session.** A `ChallengeRequired` or `LoginRequired` answer calls `SessionRepository.challengeRequired(url, epoch)` or
  `loginRequired(epoch)` so Sync reflects it (the epoch is read before the request, so an answer that outlives a logout or a
  paste is ignored); the challenge URL goes only there and is never put in the screen's state.
- **Files.** The ids that chain the calls live in ViewModel fields (the state holds only whether they are known) and are
  never shown, written or logged. The ViewModel writes `filesDir/lab/<call name lowercased>.json` (`current_user`,
  `collections`, `saved_all`, `saved_collection`, `media_info`) with the call's `scrubbedJson` and nothing else
  (overwriting), and shows its path. An answer whose `scrubbedJson` is null (the body wasn't JSON) deletes that call's own
  file, so an export can't return a copy of an older answer; a tap that got no answer at all (a refusal, a network error)
  leaves the file alone.
- **Pins.** `DebugGatesGuardTest` pins the debug gates at the source level: `DeveloperSection(` in `SyncScreen.kt` and
  `composable<AdapterLabRoute>` in `ReelsNavHost.kt` each sit inside an `if (BuildConfig.DEBUG)` block, `adapterLab` is
  referenced only from `di/AppContainer.kt` and `ui/lab/`, and `AdapterLabScreen` paces with `container.instagramPacer` and
  never touches the backend (whose pacer is the fast fake one in Mock mode).
- The README's "Adapter lab" section is the owner's procedure.

## Session

`app/.../session/`.

- The session lives only in the WebView's `CookieManager` (`AndroidCookieStore`), shared with the login screen and the hidden
  page: the page's `fetch` sends and stores its cookies in that jar itself, and the app copies a cookie into no request.
  The cookie origin and the Set-Cookie strings the store writes come from `:instagram` (`WebSessionCookies`).
- `SessionRepository` validates via one paced interactive request, stores only state + handle in DataStore, accepts pasted
  sessionids (parsed locally, no request for garbage), logs out by clearing cookies.
- **A paste is transactional:** it first asks the Pacer for permission (no cookie is written during a cooldown or at the
  daily budget), and only a Valid result commits; a failed, cancelled or rejected (Expired, Challenge) paste puts the
  previous cookies back and leaves the stored state alone, so it never strands a working session.
- Validation without cookies makes no request; a result that arrives after a logout or paste is discarded; one mutex
  serialises jar and state changes. `toString()` of anything holding a sessionid or challenge URL is redacted, and the UI
  compares sessions by a SHA-256 fingerprint, never the id. `LazySessionProbe` keeps WebView out of container construction.
- A Valid validation flushes the cookie jar (Chromium commits cookies lazily, so a kill right after login must not lose the
  session); logout clears the cookies and the WebView's storage. Real traffic uses `AppContainer.instagramPacer`
  (Conservative, Room request log, DataStore cooldown).
- **Epochs.** `SessionRepository` implements `SessionSignals` (`epoch()`, `sessionOk`, `loginRequired`,
  `challengeRequired`, `runSession`). Its `@Volatile` `sessionEpoch` is bumped, under the lock, by a logout, a paste, a paste's
  rollback, and a `validate()` that finds a session other than the one the epoch was issued for (a new login in the
  WebView, possibly as another account). A run (or a lab call, or a video resolve) reads the epoch before it starts and every
  signal carries it; the repository ignores a signal whose epoch is not current, so a request that was in flight across a
  logout can neither expire nor revive the login that came after. `epoch()` never throws: `CookieManager` fails without a
  WebView provider (while it is being updated), so no caller has to guard it; the engine reads it inside its try anyway,
  so any other failure there still ends the run PAUSED.
  - The repository remembers what the current epoch was issued for (`issuedFor`, an `AtomicReference` of the sessionid's
    fingerprint and the jar's `ds_user_id`). Every bump records the jar's session once it is written. Nothing reads the jar
    at construction (that would load the WebView), so epoch 0 is recorded by the first `epoch()` call (what a run holds), or
    by the first `validate()` or `runSession()` when nobody asked; if the jar cannot be read then, nothing is recorded and
    the next call tries again.
  - `validate()` settles the epoch in both of its locked blocks and always BEFORE it stores anything: first, before the
    request (which can wait seconds for the Pacer, while a sleeping run resumes and still sees the old session's stored
    Valid), and again after it, so a login that lands while the request is out discards the answer, as a logout would.
    The lock is still not held across the request. A check of the SAME session changes nothing, so Check now never stops a
    running sync; a sessionid that Instagram itself re-issues looks like a new login to `validate()` and stops a run, which is
    the safe direction (an on-phone check in `TODO.md` asks whether Instagram does that at all). `sessionOk`, `loginRequired`
    and `challengeRequired` don't look at the jar (they can only keep a run going for the epoch it holds or end it).
  - **The account check in the gate.** The epoch only ends in `validate()`, which the login screen calls while it is
    visible, once a second at most, when it sees a new sessionid (never for the CSRF purpose, after a Challenge only on Retry,
    and not at all if the owner has left it). So `runSession(epoch)`, the gate every sync request, lab call and video
    resolve passes, also compares the jar's `ds_user_id` with the one recorded for the epoch and says `NOT_USABLE` when it
    differs (or is gone): a WebView login as another account stops work before any check has seen it, and a re-issued
    sessionid of the same account (same `ds_user_id`) does not. `runSession` is a pure read and still never takes the lock.
    Remaining window: one request can be past that check when the cookies change, and go out under the new account (the
    cookies are read a moment after the gate). R84's account check only guards a run's first `currentUser`.
  - `sessionOk` (sent after a run's successful `currentUser`) restores `Valid(handle)` over a stale Expired or Challenge
    banner; it writes nothing when the state is already `Valid` for that handle, flushes the jar before storing `Valid` (as
    `validate` does), and is ignored when the jar holds no session cookies.
- **The transport's page follows the session.** The hidden page is destroyed (`reset()`) before the app changes the jar's
  session (logout, a paste, a paste's rollback) and before a check that starts on a stored state that is not Valid;
  `validate()` also tells the transport that a new user action begins. Delete library does the same destroy. It is closed
  (`closePage()`, R106, which keeps the transport's verdicts) right after an Expired or a Challenge is stored
  (`loginRequired`, `challengeRequired`, a `validate()` that finds one) and when the login screen opens to fix the session
  (RELOGIN, CHALLENGE, through `closeHiddenPage()`), so a WebView login that fixes it never runs beside a live page. These
  hooks replace what `SessionGuard` did for OkHttp (a stale response writing the old session's cookies back). Details, and
  why they are injected and only act on a transport that exists: [Transport](#transport).
- **Gating.** `SyncViewModel(requiresSession = container.backend is Backend.Real)`; `sessionReady = !requiresSession ||
  state is Valid` (a state still loading counts as not ready, but says nothing: no banner until the state is known), and
  `syncUiState(..., sessionReady, sessionLoading)` turns Sync, Full sync and Resume off and, when no other banner applies,
  says "Log in to Instagram to sync". `SyncViewModel.start` itself returns unless `ui.value.canStart`, so nothing that
  reaches it can begin a sync the screen did not offer. Mock mode (the fake backend) needs no session.
- **The real Pacer in Mock mode.** With the fake backend the screen's own pacer (counters, cooldown banner, Sync's enabled
  state) is the fake library's, but Check now, the Adapter lab and the video resolver use `instagramPacer`, so
  `SyncViewModel(realPacer = ...)` (the container's `instagramPacer` in Mock mode, null with the real backend) ticks every
  second like the main one and feeds `realPacerNote`; `SessionSection(pacerNote = ...)` shows it as one small line directly
  under the status: "Instagram requests paused: N min left (cooldown)" while the real cooldown runs, else "Instagram requests
  in 24 h: X / 600" (`realPacerLine`, minutes rounded up like the banner). `status()` only reads the log and the cooldown:
  it makes no request, records nothing, and never gates the fake library's Sync. With the real backend nothing is added, the
  existing lines already show that Pacer. It adds no request, rate or concurrency.
- **Logout.** Log out first cancels a running sync (`controller.cancel()`), then forgets the session; the whole sequence runs
  under `NonCancellable` (the screen going away cannot drop a logout) and a failure to cancel the run is swallowed, so
  logging out always forgets the session.
- **Storage failures never crash.** An exception that escapes `viewModelScope.launch` ends the app, so the two calls that
  write settings after they have already changed something catch non-cancellation exceptions and say so, never with any
  exception text. Logout clears the cookies first and writes the stored state second: if that write fails the cookies are
  gone anyway and the session section's message line (`sessionMessage`, under the session status) says "Couldn't finish
  logging out; try again" (a second Log out finishes it). Delete library has its own line in the Storage section
  (`storageMessage`, directly under its button), and says exactly what was left once the rows are gone: "Library deleted, but
  the account record couldn't be cleared; try Delete library again" (also when cached files failed too: a second delete
  redoes both, and the next run would still be checked against the old account), "Library deleted; some cached files
  couldn't be removed" (only thumbnails or cached videos are left: the library IS deleted), or "Couldn't delete the library;
  try again" (the delete itself failed). `logout` clears the session line when it starts, and Delete library clears the
  storage line when it starts.
## Login

`app/.../ui/login/`, `ui/sync/SessionSection.kt`. Instagram's own login page in a WebView.

- **Allowed pages.** It only loads https pages on the domains in `WebEndpoints.LOGIN_DOMAINS` (`instagram.com`,
  `facebook.com`, `meta.com`, and their subdomains, dot boundary; `:app` only parses the `Uri` and asks
  `WebEndpoints.isLoginPage`); every other navigation is dropped, never handed to another app, and a start URL that isn't one
  of those falls back to the login page. The WebView has explicit MATCH_PARENT LayoutParams (without them Compose adds it as
  WRAP_CONTENT and Chromium lays the page out at zero height, so the login form never paints).
- **`LoginRoute` carries a purpose:**
  - LOGIN: a 1 s local cookie poll validates each new session once, compared by fingerprint, never the sessionid; "Check
    again" re-validates on request.
  - RELOGIN ("Log in again" in the Expired state): LOGIN, except that the fingerprint of the stale session in the jar is
    seeded as already checked, because it is known to be dead, so opening costs no request and only a session the owner logs
    in with afterwards is validated, once. Opening closes the hidden page (R106, `LoginSession.closeHiddenPage()`).
  - CHALLENGE: opened for the session Instagram already flagged. Opening costs no request, and a start page without a usable
    URL is Instagram's home, which redirects to the checkpoint; a Challenge result without a URL loads Instagram's home, and
    a page that is already showing is not reloaded. Opening closes the hidden page, as for RELOGIN.
  - CSRF (after an accepted paste): no request at all, polls for a csrftoken locally and closes when it arrives or after 30 s.
- **After a Challenge result** (LOGIN, RELOGIN or CHALLENGE) automatic validation stops for the rest of the screen's life,
  because Instagram may re-issue the sessionid during a checkpoint flow and each new fingerprint would otherwise be a paced
  but automatic `currentUser` call for a challenged account: the screen shows "Finished verifying on Instagram? Check
  again" and only that button validates, one request per tap. The screen closes by popping exactly the Login entry, so a
  late result can't also pop Sync. A failed check shows its message in the same bar (a shape change reads "Unexpected
  Instagram response at `<path>`"); a session that isn't valid yet shows "That session isn't valid yet. Finish logging in,
  then check again."
- **Sync's session section.** It shows the session state, and until the stored state has been read
  (`SyncViewModel.sessionState` starts as null) only "Checking session…" with no buttons, so an early tap can't open Log in
  for a session that is actually challenged. The controls per state (labels in a wrapping `FlowRow`): LoggedOut ("Not
  logged in") has Log in and Paste sessionid; Valid ("Logged in as @handle") has Check now and Log out; Expired ("Session
  expired (@handle)") has Log in again, Paste sessionid and Log out; Challenge ("Instagram wants verification") has Resolve
  on Instagram, Check now, Paste sessionid and Log out. Log out is offered in every state but LoggedOut because an expired
  or challenged session still has cookies on the phone.
- **Paste** is masked (password keyboard). Only Valid counts as an accepted paste; a rejection says so without naming an
  account and leaves the current login alone. A second tap during a paste, and a second Check now while one is out, is
  ignored (each is an Instagram request). Only adapter and Pacer messages reach the screen ("Unexpected Instagram response
  at `<path>`", "Cooling down after a rate limit", ...); anything else gets a generic line.
- **A dead renderer closes the screen.** The login WebView's client (`InstagramOnlyClient`) overrides
  `onRenderProcessGone`: a renderer that crashes or is killed for memory no longer takes the app down. The view is
  destroyed and the screen closed through its existing back callback. The hidden page does the same for its own renderer
  ([Transport](#transport)).
- Debug builds enable WebView remote debugging (`chrome://inspect`) under `if (BuildConfig.DEBUG)`, pinned by a source test.

## Decided so far

| Decision | Choice | Why |
|---|---|---|
| Where it runs | Everything on the Android phone: login, sync, storage, playback. No companion service. | The phone is where reels get watched. A Mac-hosted service would need LAN HTTPS and the Mac switched on. |
| Stack | Native Kotlin + Jetpack Compose | WebView login sharing one cookie jar with the API calls (which run in a WebView page), WorkManager background sync, Media3 playback. |
| Auth | In-app WebView login to instagram.com; the app reads cookies from Android's `CookieManager`. Pasted `sessionid` as a fallback. | No password in app code, 2FA and checkpoints handled by Instagram's own UI, one consistent device identity. |
| Instagram access | Private web endpoints, isolated in the `:instagram` JVM module | No official API exposes saved items, and the endpoints change without notice. |
| API transport | Every API call is a same-origin `fetch()` in a hidden instagram.com WebView page (`WebViewTransport`); the OkHttp client stays only for the cookieless CDN and as the JVM-test transport | OkHttp's first two requests got HTTP 429, and copying headers cannot copy Chromium's TLS and HTTP/2 fingerprint. The page's cookies, client hints and Sec-Fetch headers are the browser's own. |
| Video | Streamed on demand with per-item link refresh; LRU cache keyed by media `pk` | Gigabytes saved, and no bulk-download pattern. |
| Unsaves and moves | Mirrored only by a manual Full sync; a quick Sync only adds | Detecting removals needs a full walk, so it stays an explicit, paced action. |
| Sync trigger | Manual only, executed by WorkManager | The fewest requests; runs survive leaving the app and resume. |
| Mock mode (P1) | Debug builds read `use_fake` from the SharedPreferences file `backend` (default `true`), so a fresh debug install, and every emulator, starts on the fake library and never needs a login. Release builds always use the real backend. A Developer section on the Sync screen (debug builds only) flips the flag and restarts the process; it is disabled while a run is RUNNING. | An emulator must never be logged into Instagram, and the real backend should be impossible to reach by accident on a dev build. |
| Separate libraries (P2) | The fake library keeps `reels.db` and `filesDir/thumbs` (existing emulator installs keep their data); the real library is `library.db` and `filesDir/library-thumbs`. The `api_request` log behind the real 24 h budget always lives in `library.db`, even in Mock mode, so real session checks and lab calls made in Mock mode count against the same budget. | Flipping Mock mode must never mix fake items into the real library or lose the real budget. |

## Superseded from the PRD draft

- PWA + Node companion service over `localhost` → native Android app, no companion.
- Username/password login with 2FA handling → not needed; the WebView login covers it.
- Optional bulk video download during sync → on-demand streaming with a cache.
- Session validated on every app start → validated at the start of each sync and after login.
- OkHttp API client with the WebView's cookies and copied headers → same-origin `fetch()` in a hidden instagram.com page
  (after the 429, 2026-10-08).
