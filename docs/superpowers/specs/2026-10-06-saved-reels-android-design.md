# Saved Reels for Android: design spec

- **Date:** 2026-10-06
- **Status:** approved in brainstorming, awaiting written-spec review
- **Supersedes:** the PRD draft "Personal Saved Reels viewer with Instagram session sync" (PWA + Node companion)

## 1. Goal

A personal, single-user Android app that logs into a throwaway Instagram account, syncs its saved reels and
posts together with the collections they are organised into, and shows them Pinterest-style (real thumbnails,
smooth scrolling, grouped by collection) so saved reels can be rewatched. Never published or distributed.

## 2. Context and constraints

- The app uses Instagram's private web endpoints through the account's own logged-in session. This is against
  Instagram's Terms of Service. The owner accepts that and runs it only against a throwaway account.
- There is no official API for saved items or collections. No OAuth or Graph API.
- Everything runs on the phone. Session, library and media never leave the device: no cloud, no telemetry,
  no third parties, no Google backup.
- Account safety is a first-class constraint: human-like pacing, strict request budgets, resumable sync, and a
  hard stop with zero automatic retries on any challenge.
- Endpoints and response shapes change without notice, so all Instagram-specific code sits behind one adapter.

## 3. Decisions

| # | Decision | Choice | Change from PRD |
|---|---|---|---|
| D1 | Where it runs | Entirely on the Android phone: login, sync, storage, playback | Was a PWA plus a Node companion on a computer, over `localhost` |
| D2 | Stack | Native Kotlin + Jetpack Compose | Was React + Vite + Tailwind and Node |
| D3 | Auth | In-app WebView login to instagram.com; cookies read from `CookieManager`. Pasted `sessionid` as a fallback. | Pasting `sessionid` was primary; username/password + 2FA handling is dropped (Instagram's own page handles it) |
| D4 | Video | Streamed on demand, with an expired link refreshed per item; size-capped LRU cache | Was optional bulk download during sync |
| D5 | Unsaves and moves | Mirrored by a manual **Full sync**; a quick **Sync** only adds | New |
| D6 | Sync trigger | Manual only (two buttons), executed by WorkManager | New |
| D7 | Session check | At the start of every sync and after login, not on every app start | PRD validated on startup; this saves a request per app open |
| D8 | Item identity | Instagram media `pk`, with `code` unique | PRD keyed on permalink |
| D9 | Save order | Feed position (`sortKey`), since the saved feed is not known to expose a save timestamp | PRD had `savedAt` |

## 4. Architecture

### 4.1 Modules

- **`:instagram`**: pure Kotlin/JVM library (OkHttp, kotlinx.serialization, coroutines), no Android dependency.
  The only code that knows Instagram exists: endpoints, headers, pagination cursors, JSON-to-model mapping,
  error classification, media-type mapping, thumbnail candidate choice, and CDN-link expiry parsing.
  It also contains `FakeInstagramClient`, a deterministic fixture-backed implementation.
- **`:app`**: Android application. Compose UI, Room database, sync engine (WorkManager), Pacer, media
  storage and video cache, login WebView, settings. Dependencies are wired by hand in an `AppContainer` owned
  by the `Application` (no Hilt). WorkManager uses on-demand initialisation with a custom `WorkerFactory`.

Package: `io.github.yuriimurha.reels`.

### 4.2 Adapter interface

```kotlin
interface SessionProbe {                     // split out so login (M2) ships before the rest (M3)
    suspend fun currentUser(): Account
}

interface InstagramClient : SessionProbe {
    val reportsSavedCollectionIds: Boolean   // strategy A vs B (section 7.2), known from the spike
    suspend fun collections(cursor: String?): Page<RemoteCollection>
    suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> // null = All Saved
    suspend fun mediaInfo(mediaPk: String): RemoteMedia
}

data class Page<T>(val items: List<T>, val nextCursor: String?) // nextCursor == null means last page

data class RemoteMedia(
    val pk: String, val code: String, val type: MediaType, // REEL, VIDEO, IMAGE, CAROUSEL
    val author: String, val caption: String?, val takenAt: Instant,
    val width: Int, val height: Int, val carouselCount: Int?,
    val thumbnailUrl: String, val videoUrl: String?, val videoUrlExpiresAt: Instant?,
    val savedCollectionIds: List<String>?, // null when the response does not carry it
)
```

Every failure is an `InstagramException` subtype: `LoginRequired`, `ChallengeRequired(challengeUrl)`,
`RateLimited`, `Transient`, `ShapeChanged(fieldPath)`. Section 6.4 defines the mapping.

### 4.3 Data flow

1. The login WebView stores Instagram's cookies in Android's `CookieManager`.
2. A `CookieJar` bridge gives OkHttp those same cookies and writes every `Set-Cookie` back into `CookieManager`.
3. The sync engine calls `InstagramClient` only through the **Pacer** (section 7.3).
4. Results go into Room, and thumbnails go into app-private files.
5. The UI observes Room through `Flow`/Paging and never calls Instagram. The single exception is the viewer
   asking `MediaRepository` for a fresh video link, which also goes through the Pacer.

Media bytes (thumbnails and video) are plain HTTP downloads of URLs the adapter returned, done by `:app`'s
`MediaFetcher`. Interpreting those URLs (choosing a candidate, reading the expiry) stays in the adapter.

### 4.4 Privacy

- `android:allowBackup="false"`, and `dataExtractionRules` excluding every domain from cloud backup and
  device transfer.
- The session exists only in `CookieManager`. There is no separate session file, and a pasted `sessionid` is
  written into `CookieManager`.
- HTTP logging only in debug builds, at header level, with `Cookie`, `Set-Cookie` and `X-CSRFToken` redacted.
  Response bodies are never logged. Challenge URLs are never logged.
- No analytics, no crash reporter, no network calls to anything other than Instagram and its CDN.

### 4.5 Backends and mock mode

`AppContainer` builds exactly one `Backend`:

```kotlin
sealed interface Backend {
    val client: InstagramClient
    val pacing: PacingPolicy
    class Real(override val client: InstagramClient) : Backend { override val pacing = PacingPolicy.Conservative }
    class Fake(override val client: FakeInstagramClient) : Backend { override val pacing = PacingPolicy.Fast }
}
```

The fast pacing policy can only exist next to the fake client; there is no setting that changes pacing.
Mock mode is a debug-only toggle that selects `Backend.Fake` and a fake `MediaFetcher`.

The fake backend generates a deterministic library (fixed seed): about 2,000 items across 8 collections,
with a mix of reels, videos, images and carousels, some items in several collections, some in none, and a few
"unavailable" items. Thumbnails are placeholder images drawn at runtime (gradient, index, type), so no
third-party images live in the repo. One short synthetic clip, generated once with ffmpeg's `testsrc` and
committed, serves every fake video. Scripted variants of the fake (challenge after N calls, rate limit, shape
change, transient failures) drive the tests.

## 5. Data model

### 5.1 Tables (Room)

**`media`**

| Column | Type | Notes |
|---|---|---|
| `pk` | TEXT PK | Instagram media id |
| `code` | TEXT UNIQUE | Shortcode; permalink is `https://www.instagram.com/reel/{code}/` for reels, `/p/{code}/` otherwise |
| `type` | TEXT | REEL, VIDEO, IMAGE, CAROUSEL |
| `author` | TEXT | Owner handle |
| `caption` | TEXT? | |
| `takenAt` | INTEGER | Epoch millis of the post |
| `width`, `height` | INTEGER | Cover dimensions, used for tile aspect ratio |
| `carouselCount` | INTEGER? | Carousels only |
| `thumbPath` | TEXT? | Local file; null renders a placeholder |
| `thumbUrl` | TEXT | Last remote thumbnail URL |
| `videoUrl` | TEXT? | Last known video link |
| `videoUrlExpiresAt` | INTEGER? | Parsed by the adapter |
| `collectionNames` | TEXT | Denormalised, space-joined, for search |
| `firstSeenAt`, `lastSeenAt` | INTEGER | |
| `removedAt` | INTEGER? | Set when unsaved; hidden from every view |

**`collection`**: `id` TEXT PK, `name`, `coverPk` TEXT?, `position` INTEGER, `removedAt` INTEGER?.
The pseudo-collection `__all__` ("All Saved") is a real row.

**`collection_media`**: (`collectionId`, `mediaPk`) PK, `sortKey` INTEGER, `lastSeenRunId` INTEGER.
Index on (`collectionId`, `sortKey` DESC).

**`media_fts`**: FTS4 with `contentEntity = media`, over `caption`, `author`, `collectionNames`.

**`sync_run`**: `id`, `mode` (QUICK/FULL), `status` (RUNNING, PAUSED, DONE, CANCELLED, STOPPED_CHALLENGE,
STOPPED_LOGIN, STOPPED_RATE_LIMIT, STOPPED_SHAPE), `startedAt`, `finishedAt?`, `phase`, `collectionsDone`,
`collectionsTotal`, `requestsUsed`, `newItems`,
`seenItems`, `thumbsCached`, `failures`, `lastError?` (already redacted text). A run that ends unfinished
(PAUSED or any STOPPED_*) is *resumable*; CANCELLED means a resumable run was discarded.

**`api_request`**: `at` INTEGER, one row per API request, pruned after 24 h. Backs the rolling budget.

**`sync_cursor`**: (`runId`, `scope`) PK, where scope is a collection id or `__all__`; `nextCursor?`,
`walkBase`, `walkIndex`, `done`.

### 5.2 Ordering

`sortKey` is a Long; views sort `sortKey DESC`. `WALK_SPAN = 1_000_000` (larger than any collection).

- **Full walk of a scope:** at walk start, `walkBase = max(sortKey in scope) + WALK_SPAN`. The item at walk
  index `i` (0 = newest) gets `sortKey = walkBase - i` and `lastSeenRunId = run`. Walked items therefore sit
  above unwalked ones in correct order while the walk is in progress, and unwalked items keep their previous
  relative order. `walkBase` and `walkIndex` are persisted in `sync_cursor`, so a resumed walk continues
  with the same numbering.
- **Quick sync of a scope:** uses the same `walkBase`/`walkIndex` numbering, but assigns `walkBase - i` only
  to items the scope doesn't have yet; known items keep their keys. New items therefore sit above the current
  top in feed order, even when they span several pages. Order drift from re-saves is corrected by the next
  full sync.

### 5.3 Derived views

- **All Saved:** memberships of `__all__`.
- **Uncategorized:** non-removed media in `__all__` with no membership in any non-removed real collection.
- **Collection counts:** `COUNT(*)` of non-removed memberships per collection.

### 5.4 Files and settings

- **Thumbnails:** `filesDir/thumbs/{pk}.jpg`, app-private and never evicted by the OS. The adapter picks the
  candidate closest to 720 px wide (the smallest one that is at least 720 px, otherwise the largest).
  About 100 KB each, so roughly 300 MB per 3,000 items.
- **Video cache:** Media3 `SimpleCache` in `cacheDir/video` with a least-recently-used evictor. Default cap
  2 GB, adjustable in settings.
- **Settings (DataStore):** video cache cap, mute preference, mock mode (debug only), the logged-in handle,
  and the cooldown state (section 7.4), which must survive process death.
- **Migrations:** Room auto-migrations with the exported schema committed under `app/schemas/`. A
  destructive migration is never allowed, because wiping the library costs a full re-sync.

## 6. Instagram adapter

### 6.1 Client identity

- `User-Agent` is the WebView's own (`WebSettings.getDefaultUserAgent`), passed into the adapter at
  construction.
- Headers mirror what the mobile website sends (e.g. `X-IG-App-ID`, `X-Requested-With`, `X-CSRFToken` from
  the `csrftoken` cookie). Exact names and values are pinned in the spike and kept as constants in
  `:instagram`.
- Cookies come only from the `CookieManager`-backed jar. CDN requests carry no Instagram cookies; normal
  cookie domain scoping handles this.

### 6.2 Endpoints (candidates, confirmed in the spike)

All under `https://www.instagram.com/api/v1/`, taken from open-source clients (instaloader, instagrapi):

| Call | Candidate endpoint |
|---|---|
| `currentUser` | `accounts/current_user/` or the profile endpoint for `ds_user_id` |
| `collections` | `collections/list/` |
| `savedMedia(null, …)` | `feed/saved/posts/` |
| `savedMedia(id, …)` | `feed/collection/{id}/posts/` |
| `mediaInfo` | `media/{pk}/info/` |

### 6.3 Spike (milestone M3, on the owner's phone)

A debug-only "Adapter lab" screen calls each candidate once (through the Pacer) and shows the response
**shape**: keys, types and array lengths, with every value redacted. It writes scrubbed copies, with
synthetic ids, handles, captions and URLs, to app-private storage so they can be exported as test fixtures.
Debug builds enable `WebView.setWebContentsDebuggingEnabled(true)`, so the requests the real mobile site
makes can be observed through `chrome://inspect` as a cross-check.

The spike must answer:

1. The working endpoint and headers for each call, including the mobile-web `X-IG-App-ID`.
2. Whether saved items carry `saved_collection_ids` (decides section 7.2, strategy A or B).
3. Whether any save timestamp exists (if one does, D9 is revisited).
4. Page size and the cursor field (`next_max_id` / `more_available`).
5. Media-type mapping (`media_type` 1/2/8, `product_type == "clips"` for reels).
6. Error payload formats for login, challenge and rate-limit responses.
7. The CDN expiry parameter (expected: hex Unix time in `oe`).

### 6.4 Error classification

| Signal | Exception | Engine behaviour |
|---|---|---|
| `checkpoint_required`, `challenge_required`, or a redirect to `/challenge/` | `ChallengeRequired` | Hard stop, **zero automatic retries**. Run status STOPPED_CHALLENGE. Sync is blocked until a successful `currentUser()` after the owner resolves the challenge in the WebView. |
| `login_required`, a 401/403, or a redirect to `/accounts/login` | `LoginRequired` | Hard stop. Session marked expired. Run status STOPPED_LOGIN. |
| HTTP 429, `feedback_required`, or "please wait a few minutes" | `RateLimited` | Run stops (STOPPED_RATE_LIMIT) and a cooldown starts (section 7.4). |
| A 5xx, a timeout, or a connection error | `Transient` | Back off 30 s, 1 min, 2 min, 4 min (each ±20 % jitter), then the run is PAUSED. |
| A required field is missing or has the wrong type | `ShapeChanged(fieldPath)` | Run stops (STOPPED_SHAPE). The banner says "adapter needs repair: {fieldPath}". A redacted shape dump is saved for the Adapter lab. |

Per-item problems (an unavailable or private item, a thumbnail 404) are not exceptions. The item is still
stored, with a null `thumbPath` and its failure counted, and sync continues. JSON parsing ignores unknown
keys; only fields the app needs are required.

## 7. Sync engine

### 7.1 Runner

- `SyncWorker` (a `CoroutineWorker`) enqueued as **unique work** `sync`, so two runs never overlap. It runs as
  a foreground worker with a progress notification.
- Both buttons go through the same rule: if a resumable run exists (PAUSED or STOPPED_*), the button resumes
  it from its cursors once its blocker has cleared (session valid, cooldown over, adapter fixed). A new run
  starts only when none is resumable. "Discard paused run" sets it to CANCELLED, which is safe because
  nothing is deleted before a walk completes.
- Cancel stops after the current request. Cursors are kept, and the run becomes PAUSED.

### 7.2 Run steps

1. `currentUser()`, which validates the session (D7).
2. Fetch the full collection list (usually 1–2 requests). Upsert names, covers and positions. If the list
   fetch completed, collections missing from it get `removedAt`, and a returning collection has it cleared.
3. Walk the scopes in order: `__all__` first, then real collections by position.
   - **Strategy A** (spike finds `saved_collection_ids`): walk only `__all__`. Whenever an item is seen, in
     either mode, its real-collection memberships are set to exactly its `savedCollectionIds` and use the
     item's `__all__` `sortKey`.
   - **Strategy B** (the field is absent): walk `__all__` and then every real collection separately.
   - **QUICK:** stop a scope after the first page that contains an item the scope already has.
     **FULL:** walk every scope to `nextCursor == null`.
4. For each page: upsert media (refreshing mutable fields: caption, thumbnail URL, video link; clearing
   `removedAt` if the item was re-saved), assign `sortKey` (section 5.2), set `lastSeenRunId`, queue
   thumbnails for missing files, and persist `sync_cursor` in the same transaction.
5. **Reconcile (FULL only, per scope, only when that scope's walk reached the end in this run):**
   - `__all__`: every saved item is in All Saved, so media whose `__all__` membership was not seen in this
     run is unsaved. It gets `removedAt`; all its memberships, its thumbnail file and its cached video are
     deleted.
   - A real collection (strategy B): its memberships with `lastSeenRunId != run` are deleted. The item stays
     in All Saved.
   - A scope whose walk did not complete reconciles nothing.
6. Recompute `collectionNames` for touched media, write the final counters, and set DONE.

### 7.3 Pacer

One instance per process for real Instagram traffic. The fake backend has its own, with in-memory budgets,
so fake syncs never consume the real budget or trigger the real cooldown. API requests go strictly one at a
time.

| Rule | `Conservative` | `Fast` (fake only) |
|---|---|---|
| Gap between API calls | Random 4–12 s, median ~6 s (log-normal) | 20–60 ms |
| Breaks | 60–180 s every 15–30 requests | none |
| Per-run budget | 300 requests, then the run is PAUSED ("budget reached") | 300 |
| Rolling 24 h budget | 600 requests, counted from `api_request` | 600 |
| Interactive lane (viewer link refresh) | Minimum 2 s gap, priority over sync, counts toward the 24 h budget | same |
| CDN lane (thumbnails) | 2 concurrent, 0.2–0.8 s jitter, not budgeted | 4 concurrent, no jitter |

Budgets are identical in both policies so the fake exercises them. All values are code constants, so
raising them always takes an explicit commit (see `CLAUDE.md`). Estimated first full sync with ~20 items per
page: 3,000 items is 150–250 requests, roughly 25–45 minutes; 10,000 items spans two runs.

### 7.4 Cooldowns

- After a `RateLimited`: 1 h cooldown. A second `RateLimited` within 24 h: 24 h cooldown.
- While a cooldown is active, both buttons are disabled and show a countdown. Interactive link refreshes are
  refused too, so uncached videos show their thumbnail.
- Cooldown state lives in DataStore, so killing the app does not reset it.

## 8. Video playback

1. When the viewer settles on a video item: if `videoUrl` is present and `videoUrlExpiresAt` is more than
   10 minutes away, play it. Otherwise call `mediaInfo(pk)` on the interactive lane, update the row, and play.
2. If playback fails with HTTP 403/410: refresh once and retry once. If that fails, show the placeholder.
3. `CacheDataSource` over `SimpleCache` uses a **custom cache key = media `pk`**, so a refreshed URL still
   hits the cached bytes.
4. The next item's link is refreshed ahead of time (one request, only if it is expired), so swiping
   doesn't stall.
5. Offline, or when the 24 h budget is spent or a cooldown is active: cached videos play; uncached ones
   show the large thumbnail with a short message and **Open on Instagram**.

One `ExoPlayer` instance is reused across pager pages. Playback loops, sound is on by default, tapping
pauses, and the mute toggle is remembered.

## 9. Screens

Dark-only Material 3, edge to edge, Navigation Compose with type-safe routes.

1. **Home:** a two-column grid of collection cards (cover, name, count). All Saved and Uncategorized come
   first, then collections by Instagram position. The cover is `coverPk`, falling back to the newest item.
   The top bar has search and a sync status chip ("synced 2 h ago" / spinner / warning dot) that opens Sync.
2. **Collection grid:** `LazyVerticalStaggeredGrid`, two columns, `sortKey DESC`. Each tile uses its real
   aspect ratio and shows the local thumbnail, a type badge (▶ for reels, "1/N" for carousels), the author
   and a one-line caption. Paging 3 from Room with page size 60, stable keys (`pk`), and Coil decoding at
   tile size.
3. **Viewer:** `VerticalPager` over the same paged list, starting at the tapped item. Video behaviour as in
   section 8. Images are shown large. Carousels show only the cover with a "1/N" badge. The overlay has the
   author, the caption (tap to expand), collection chips, and **Open on Instagram** (`ACTION_VIEW` on the
   permalink, which opens the Instagram app when installed). Unavailable items get a placeholder. Going
   back returns to the grid, scrolled to the current item.
4. **Search:** one field, debounced by 200 ms, querying `media_fts`. Filter chips for Reels/Posts and for
   collection. Results use the same grid and open in the viewer.
5. **Sync and account:**
   - **Session:** the last known state ("@handle", "expired" or "logged out"; shown without a request, per
     D7), with **Log in** (opens the Login screen),
     **Log out** (clears instagram.com cookies, keeps the library), and *Paste sessionid* in an overflow menu.
   - **Actions:** **Sync**, **Full sync**, and **Discard paused run** when one exists.
   - **Live progress:** phase, collections done/total, new and seen items, thumbnails cached, failures, and
     requests used against both budgets.
   - **History:** last sync time and last full sync time.
   - **Banners:** challenge (with a button that opens the challenge in the WebView), session expired,
     cooldown countdown, and "adapter needs repair."
   - **Settings:** video cache cap, clear video cache, **Delete library** (confirmed; wipes the database and
     files, keeps the session), and mock mode (debug builds only).
6. **Login:** a full-screen WebView at `https://www.instagram.com/accounts/login/`. When both the `sessionid`
   and `ds_user_id` cookies are present, the app calls `currentUser()`, stores the handle, and closes the
   screen. The same screen opens challenge URLs.
7. **Adapter lab** (debug only): see section 6.3.

**Paste sessionid fallback:** the value is written into `CookieManager` as a secure, HTTP-only
`.instagram.com` cookie, then validated with `currentUser()`. If `csrftoken` is missing, the WebView loads
the instagram.com home page once to obtain it.

## 10. Testing

Run locally with `./gradlew check`. Every safety guard has a test that fails when the guard is removed.

- **`:instagram` (JVM):**
  - Parser golden tests: scrubbed fixture in, expected models out.
  - Error classification against OkHttp `MockWebServer`, one case per row of section 6.4, redirects included.
  - Thumbnail candidate choice and CDN expiry parsing.
  - Cookie round trip: cookies are sent, and `Set-Cookie` updates reach the jar.
  - A fixture guard that fails if any fixture contains `sessionid`, `csrftoken`, `Cookie:` or a real
    `scontent`/`fbcdn` host.
- **Pacer (`kotlinx-coroutines-test`, virtual time):** gap bounds, breaks, per-run and 24 h budgets, the
  interactive lane's priority, cooldown escalation, and cooldown persistence.
- **Sync engine (Robolectric, in-memory Room, scripted fake):**
  - Quick sync stops at the first known item.
  - New items land above the top in feed order.
  - **An interrupted full walk deletes nothing.**
  - A completed walk removes exactly the unseen memberships.
  - Resuming after process death continues from the cursor with the same `walkBase`.
  - A challenge mid-run stops with no further requests and keeps the cursors.
  - Strategy A and strategy B produce the same memberships for the same fixture.
  - The Uncategorized computation is correct.
- **DAO:** paging order, counts, FTS search across caption, author and collection name.
- **UI (Robolectric Compose):** Home renders the pseudo-collections first; tapping a tile opens the viewer
  at that item. Visual checks on the emulator (`adb exec-out screencap`) and on the phone.

## 11. Tooling and workflow

- Gradle Kotlin DSL with a version catalog (`gradle/libs.versions.toml`), latest stable AGP, Kotlin 2.x with
  KSP for Room, and the Compose BOM. `compileSdk`/`targetSdk` 36 (API 37 exists, but Robolectric support for it is unconfirmed; bump when it
  is); `minSdk 29`.
- `./gradlew installDebug` installs the debug build (mock mode available).
- `./gradlew installRelease` installs an R8-minified release build with a baseline profile, signed with a
  local keystore described in `keystore.properties` (gitignored). Smoothness is judged on this build.
- Devices: the owner's phone over USB or wireless debugging; the emulator for visual checks only. The
  account is only ever logged in on the phone.
- Secret guard: `.githooks/pre-commit` blocks staged `.har`, session and cookie files and any staged line
  containing `sessionid=` or a `Cookie:` header. Enabled per clone with `git config core.hooksPath .githooks`.
- Docs: `TODO.md` (plan), `PROGRESS.md` (append-only), `ARCHITECTURE.md` (current state), updated with the
  code. `README.md` is written at M6.

## 12. Milestones

| # | Milestone | Done when |
|---|---|---|
| M0 | Skeleton | Both modules build, `./gradlew check` passes, the pre-commit guard blocks a planted secret |
| M1 | Mock app | Home, grid, viewer and search work on the fake library; a fake sync runs through the real `SyncWorker` and Pacer |
| M2 | Session | WebView login, cookie bridge, `currentUser()`, paste fallback, and session states on the Sync screen |
| M3 | Adapter spike | All seven spike questions answered; scrubbed fixtures committed; real parsers pass their tests |
| M4 | Real sync | Quick and full sync, resume, budgets, cooldowns, the challenge hard stop, and thumbnails work on the test account |
| M5 | Video | On-demand playback, link refresh, `pk`-keyed cache |
| M6 | Polish | Release signing, baseline profile, README |

## 13. Success criteria

1. After a fresh install, the owner logs in and a full sync of the test account completes within budget
   without a challenge.
2. The library browses smoothly and offline on a release build, at thousands of items.
3. Reels play on demand, and rewatching a cached reel needs no network.
4. A re-sync fetches only new items.
5. An interrupted sync resumes without losing or deleting anything.
6. No session value appears in logs, the repo, or backups.

## 14. Risks

| Risk | Mitigation |
|---|---|
| Endpoints or response shapes change | One adapter, a visible `ShapeChanged` error with the field path, fixtures for a quick repair |
| The test account gets flagged | Conservative pacing, budgets, cooldowns, zero retries on challenges |
| Accounts get linked through the device: the owner's main Instagram account likely uses the same phone and home IP | Pacing is the main defence; never log the main account into this app. A separate phone or network removes the risk (the owner's call). |
| Instagram blocks or degrades the WebView login | Paste `sessionid` from a mobile browser on the same phone, so the IP stays the same |
| Storage growth | ~720 px thumbnails; video cache cap |
| The repo is public | `.gitignore`, the pre-commit guard, and the fixture guard test; repo visibility is the owner's decision |

## 15. Out of scope

Swiping through every carousel image, scheduled or background-triggered sync, multiple accounts, changing
saves from the app (unsave, move), downloading videos beyond the cache, stories, comments and likes,
distribution through any store, and any cloud component.
