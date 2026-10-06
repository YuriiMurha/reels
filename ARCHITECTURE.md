# Architecture

Current state of the app. Updated in the same commit as the code it describes.

## Status

M0 in progress. The design is in
[`docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`](docs/superpowers/specs/2026-10-06-saved-reels-android-design.md);
this file describes only what exists.

## Components

| Component | Where | What it does |
|---|---|---|
| Build | `settings.gradle.kts`, `gradle/libs.versions.toml` | Two modules, versions pinned in one catalog. AGP 9 built-in Kotlin. |
| `:instagram` | `instagram/` | Pure Kotlin/JVM. Adapter contract (`InstagramClient`, `SessionProbe`, typed `InstagramException`s), `Permalinks`, and `FakeInstagramClient` over a deterministic `FakeLibrary` with scripted failures. |
| `:app` | `app/` | Android app. Backup and device transfer are disabled (`data_extraction_rules.xml`). |
| Secret guard | `.githooks/pre-commit`, `scripts/test-secret-guard.sh` | Blocks staged HAR/session/cookie files, `sessionid` values and `Cookie:` headers. Enable per clone with `git config core.hooksPath .githooks`. |
| Database | `app/.../data/db/` | Room v1: `media` (+ FTS4 `media_fts`, unicode61), `collection` (with the `__all__` pseudo-collection), `collection_media` (`sortKey`), `sync_run`, `sync_cursor`, `api_request`. Schema exported to `app/schemas/`. `deleteLibrary()` keeps `api_request`. |
| Library | `app/.../data/library/` | `LibraryRepository` (home cards, Paging 3 per `MediaSource`), `FtsQuery` (sanitises search input into prefix terms), `MediaSource` (serialisable grid/viewer source). |
| Media files | `app/.../data/media/` | `ThumbnailStore` (`filesDir/thumbs/{pk}.jpg`, atomic writes, key validation), `MediaFetcher` contract, `FakeMediaFetcher` (placeholder JPEGs for the fake backend). |
| Pacer | `app/.../sync/pacing/` | Single gate for Instagram API calls: one at a time, 4–12 s log-normal gaps, 60–180 s breaks every 15–30, 300/run and 600/24 h budgets, an interactive lane with priority (interactive requests never wait behind a sync gap or break), a 2-wide CDN lane, rate-limit cooldowns (1 h, then 24 h). Policies: `Conservative` (real) and `Fast` (fake only). |
| Settings | `app/.../data/settings/SettingsStore.kt` | DataStore preferences: mute and the persisted cooldown. Budgets persist in `api_request` (`RoomRequestLog`). `retryTransient` backs off 30/60/120/240 s ±20 %. |

## Decided so far

| Decision | Choice | Why |
|---|---|---|
| Where it runs | Everything on the Android phone: login, sync, storage, playback. No companion service. | The phone is where reels get watched. A Mac-hosted service would need LAN HTTPS and the Mac switched on. |
| Stack | Native Kotlin + Jetpack Compose | WebView login sharing one cookie jar with the HTTP client, WorkManager background sync, Media3 playback. |
| Auth | In-app WebView login to instagram.com; the app reads cookies from Android's `CookieManager`. Pasted `sessionid` as a fallback. | No password in app code, 2FA and checkpoints handled by Instagram's own UI, one consistent device identity. |
| Instagram access | Private web endpoints, isolated in the `:instagram` JVM module | No official API exposes saved items, and the endpoints change without notice. |
| Video | Streamed on demand with per-item link refresh; LRU cache keyed by media `pk` | Gigabytes saved, and no bulk-download pattern. |
| Unsaves and moves | Mirrored only by a manual Full sync; a quick Sync only adds | Detecting removals needs a full walk, so it stays an explicit, paced action. |
| Sync trigger | Manual only, executed by WorkManager | The fewest requests; runs survive leaving the app and resume. |

## Superseded from the PRD draft

- PWA + Node companion service over `localhost` → native Android app, no companion.
- Username/password login with 2FA handling → not needed; the WebView login covers it.
- Optional bulk video download during sync → on-demand streaming with a cache.
- Session validated on every app start → validated at the start of each sync and after login.
