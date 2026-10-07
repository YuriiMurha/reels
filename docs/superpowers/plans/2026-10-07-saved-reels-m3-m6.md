# Saved Reels M3–M6 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the real Instagram adapter, the Adapter lab for the on-phone spike, real paced sync, on-demand video with a `pk`-keyed cache, and a release build. Every line is written and tested without any agent contacting Instagram.

**Architecture:**
- **`:instagram` gains the real client:** `WebInstagramClient`, built on `WebParsers` and `MediaLinks`, which follow the open-source clients' known response shapes. It also gains a lab package (`AdapterLab`, `ShapeDump`, `Scrubber`), so the owner can confirm the shapes on the phone and export scrubbed fixtures.
- **`:app` gains real sync:**
  - `Backend.Real` reuses the one Conservative `instagramPacer`, and it gets an `HttpMediaFetcher` for the CDN.
  - Debug builds have a Mock mode switch. Each backend keeps its own library database, and the request log is shared.
  - The M4 safety gates from the M0–M2 final review are implemented.
  - The viewer resolves video links through the Pacer's interactive lane and plays through a Media3 `SimpleCache` keyed by `pk`.

**Tech Stack:** As in M0–M2: Kotlin 2.4.20, AGP 9.4.1 (built-in Kotlin), Compose BOM 2026.09.00, Room 2.8.5, WorkManager 2.12.0, DataStore 1.2.1, Media3 1.11.1, OkHttp 5.5.0, kotlinx.serialization 1.11.0, Robolectric 4.17, and mockwebserver3. New in this plan: `androidx.profileinstaller`.

**Spec:** `docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`. Sections 4.2–4.5, 6, 7, 8, 10, 11 and 12 rows M3–M6 are the ones this plan implements.

**Predecessor:** `docs/superpowers/plans/2026-10-06-saved-reels-m0-m2.md` (done and merged in PR #1). Its rulings R1–R56 still bind.

## Global Constraints

- Package root `io.github.yuriimurha.reels`. `:instagram` code lives under `io.github.yuriimurha.reels.instagram`.
- `minSdk 29`, `compileSdk 37`, `targetSdk 36`, JVM bytecode target 17. Robolectric runs at `sdk=36` with `application = android.app.Application`.
- AGP 9 has built-in Kotlin: never apply `org.jetbrains.kotlin.android`. `:instagram` stays pure JVM.
- Nothing in `:app` builds Instagram URLs, sets Instagram headers, or parses Instagram JSON. `:app` may hold a `JsonObject` or a string only to hand it straight back to `:instagram`.
- Every Instagram API call goes through `Pacer`. Real traffic uses `PacingPolicy.Conservative` only, from the **single** `AppContainer.instagramPacer`. `PacingPolicy.Fast` is referenced in `src/main` only from `di/Backend.kt` (`FastPolicyGuardTest`).
- Conservative values, verbatim from spec 7.3:
  - gaps random 4–12 s with a median of about 6 s (log-normal);
  - a 60–180 s break every 15–30 requests;
  - 300 requests per run and 600 per rolling 24 h;
  - the interactive lane has a 2 s minimum gap and priority;
  - the CDN lane allows 2 concurrent downloads with 0.2–0.8 s jitter and is not budgeted.
  No change may raise a rate or concurrency.
- Cooldown after `RateLimited`: 1 h, or 24 h if another happened within 24 h. Persisted.
- Transient backoff: 30 s, 60 s, 120 s and 240 s, each ±20 %, then the run is PAUSED.
- Nothing is ever deleted except by a FULL run's reconcile of a scope whose walk reached the end in that same run, or by Delete library.
- Never log or commit session material or raw Instagram responses. Test cookie values are short fakes (`s1`, `42%3Aab`). Fixtures are synthetic or scrubbed: no real ids, handles, captions or CDN hosts.
- No agent ever logs into Instagram, types a credential, or sends a request to Instagram. That includes emulators: the emulator stays in Mock mode or logged out.
- No destructive Room migrations. Schemas are exported to `app/schemas/`.
- Dark-only Material 3 UI.
- Each commit updates `ARCHITECTURE.md` when it adds or changes a component. Milestone-closing tasks append to `PROGRESS.md` (append-only) and tick `TODO.md`. `README.md` is updated in the task that changes a user-visible step.
- Stage explicit paths only (never `git add -A`). Commit trailers name the authoring model, e.g. `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- Prefix every Gradle command with:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk";`

## Decisions this plan makes (owner may overturn later)

- **P1. Mock mode.**
  - Debug builds read a `useFake` flag from the SharedPreferences file `backend`. It defaults to `true`, so a fresh debug install, and every emulator, starts on the fake library.
  - Release builds always use the real backend.
  - A Developer section on the Sync screen (debug builds only) flips the flag and restarts the process.
  - The switch is disabled while a run is RUNNING.
- **P2. Separate libraries.**
  - The fake library keeps `reels.db` and `filesDir/thumbs`, so existing emulator installs keep their data.
  - The real library uses `library.db` and `filesDir/library-thumbs`.
  - The `api_request` log behind the real 24 h budget always lives in `library.db`, even in Mock mode. Real session checks and lab calls made in Mock mode therefore count against the same budget.
- **P3. Strategy B until the spike says otherwise.** `WebInstagramClient.reportsSavedCollectionIds` defaults to `SAVED_COLLECTION_IDS_CONFIRMED = false`. Walking every collection costs more requests but is always correct. Flip it only after the lab shows `saved_collection_ids` on saved items.
- **P4. Endpoints and parameters** are the spec 6.2 candidates as instagrapi sends them:
  - `collections/list/?collection_types=["ALL_MEDIA_AUTO_COLLECTION","MEDIA","AUDIO_AUTO_COLLECTION"]`, keeping only `collection_type == "MEDIA"`;
  - `feed/saved/posts/`;
  - `feed/collection/{id}/posts/`;
  - `media/{pk}/info/`.
  All take the cursor as `max_id`.
- **P5. A missing `more_available` stops the run.** If `more_available` is absent, or it is `true` with no `next_max_id`, that is `ShapeChanged`, never "last page". A false "last page" would trigger a FULL reconcile.
- **P6. Single host per HTTP client.** The API client only ever requests `www.instagram.com`. The CDN gets its own cookieless client. One host per client means no HTTP/2 connection coalescing, so OkHttp's 421 re-send can't happen (the M4 421 gate). A test pins every endpoint builder to the base host.
- **P7. Proportional reconcile guard.** A FULL All Saved reconcile that would remove at least 20 items **and** more than half of the live All Saved members is refused as `ShapeChanged`. If the owner really did unsave that much, Delete library followed by Full sync mirrors it.
- **P8. `mediaInfo` returns `RemoteMedia?`.** `null` means not found or unavailable: HTTP 400 or 404, or an empty `items`. It never deletes anything; the viewer shows "not available".
- **P9. Baseline profile.**
  - `androidx.profileinstaller` installs the profiles the Compose libraries already ship, plus a hand-written `app/src/main/baseline-prof.txt` with wildcard rules for the app's own packages.
  - There is no macrobenchmark generator module: a release build has no Mock mode to generate it against.
- **P10. Release signing.** It reads `keystore.properties` (gitignored). When that file is missing, the release build signs with the debug key, so `installRelease` still works on a personal device.

## Review Focus

1. **A saved-feed page holding an unavailable item** (no `media` object, or media with no image candidates). Expected: the item is skipped or stored without a thumbnail, and the sync continues. Pinned in Task 2 (`unavailableItemsDoNotStopThePage`).
2. **Mock mode switched with a library loaded, or while a run is going.** Expected: the switch is disabled while RUNNING, and fake and real libraries never mix. Pinned in Task 7 (`backendsUseSeparateLibrariesAndShareTheRequestLog`, `mockSwitchDisabledWhileRunning`).
3. **A Set-Cookie arriving after Log out or a paste** during an in-flight sync request. Expected: it can't bring the old session back. Pinned in Task 9 (`setCookieFromARequestThatOutlivedItsSessionIsDropped`).
4. **Thumbnail URLs expired on resume (CDN 403) and a CDN 429.** Expected: the item counts as a failure and the sync continues. After a 429, no more CDN requests go out in that run. Neither ever touches the API budget. Pinned in Task 8 (`cdnRateLimitStopsThumbnailFetchesForTheRun`) and Task 7 (`fetcherMapsStatusCodes`).
5. **Fast swipes through videos whose links expired.** Expected: at most one `mediaInfo` per settled item. A superseded resolution never plays on the wrong page. A cooldown or spent budget plays cached bytes or shows the message. Pinned in Task 10 (`resolverRefreshesOnlyExpiredLinks`, `refusalFallsBackToCacheOrMessage`, `viewerPlaysOnlyTheSettledItem`).

---

## File structure

`:instagram` (`instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/`):

| File | Responsibility |
|---|---|
| `InstagramClient.kt` (modify) | `mediaInfo` returns `RemoteMedia?` |
| `fake/FakeInstagramClient.kt` (modify) | `mediaInfo` returns null for unknown pks |
| `web/WebEndpoints.kt` (modify) | `collections`, `savedPosts`, `collectionPosts`, `mediaInfo` URL builders |
| `web/MediaLinks.kt` (new) | image candidates, thumbnail choice, CDN `oe` expiry |
| `web/WebParsers.kt` (new) | JSON → `Page<RemoteCollection>`, `Page<RemoteMedia>`, `RemoteMedia?`, with field-path `ShapeChanged` |
| `web/WebInstagramClient.kt` (new) | the real `InstagramClient` |
| `web/WebJson.kt` (modify) | `getRaw` for the lab (status + body, classified separately) |
| `web/SessionGuard.kt` (new) | network interceptor that drops Set-Cookie from responses that outlived their session |
| `web/HttpClientFactory.kt` (modify) | registers `SessionGuard` |
| `lab/ShapeDump.kt`, `lab/Scrubber.kt`, `lab/AdapterLab.kt` (new) | Adapter lab: one request per call, redacted shape, scrubbed JSON |

`:instagram` tests: `web/MediaLinksTest`, `web/WebParsersTest`, `web/WebInstagramClientTest`, `web/SessionGuardTest`, `lab/ShapeDumpTest`, `lab/ScrubberTest`, `lab/AdapterLabTest`, `FixtureGuardTest`, plus fixtures under `instagram/src/test/resources/fixtures/web/`.

`:app` (`app/src/main/kotlin/io/github/yuriimurha/reels/`):

| File | Responsibility |
|---|---|
| `di/BackendChoice.kt` (new) | Mock mode flag (SharedPreferences, debug only) |
| `di/Backend.kt` (modify) | `Backend.Real` |
| `di/AppContainer.kt` (modify) | backend selection, per-backend db/thumbs, shared request log, real client, CDN client, video cache |
| `di/ProcessRestart.kt` (new) | relaunch after a Mock mode switch |
| `data/media/HttpMediaFetcher.kt` (new) | CDN downloads; `CdnRateLimited` |
| `data/media/VideoCache.kt` (new) | Media3 `SimpleCache`, 512 MB LRU, keyed by pk |
| `data/media/VideoSourceResolver.kt` (modify) | `VideoSource`, fake resolver |
| `data/media/RealVideoSourceResolver.kt` (new) | link freshness, `mediaInfo` on the interactive lane, fallbacks |
| `data/db/*` (modify) | `liveCollectionCount`, `memberCount`, scoped `deleteRealMembershipsExcept`, `setVideoLink`, `ApiRequestDao.latest` |
| `sync/pacing/PacingPolicy.kt`, `Pacer.kt`, `RequestLog.kt`, `RoomRequestLog.kt` (modify) | truncated gaps, re-sampled interleave gaps, seeding from the log |
| `sync/SyncEngine.kt`, `sync/SessionSignals.kt` (modify) | M4 gates, epoch-carrying signals, `sessionOk` |
| `session/SessionRepository.kt` (modify) | `epoch()`, `sessionOk`, epoch checks on signals |
| `ui/sync/*` (modify) | session gating, Developer section, logout cancels sync |
| `ui/lab/AdapterLabScreen.kt`, `ui/lab/AdapterLabViewModel.kt` (new) | the spike screen |
| `ui/viewer/*` (modify) | `VideoSource`, cache-backed player, refresh-once on 403/410, prefetch, `collectLatest` |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` (modify) | media3-datasource and media3-database, profileinstaller, signing |
| `app/src/main/baseline-prof.txt` (new) | app wildcard profile |

---

### Task 1: Secret guard and fixture guard (M3 gate)

**Files:**
- Modify: `.githooks/pre-commit`
- Modify: `scripts/test-secret-guard.sh`
- Create: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/FixtureGuardTest.kt`
- Create: `instagram/src/test/resources/fixtures/web/.gitkeep`

**Interfaces:**
- Produces: the fixture directory `instagram/src/test/resources/fixtures/web/`, which Task 2 fills; and the guard test that every later fixture must pass.

- [ ] **Step 1: Extend the guard script with failing cases.** Build every secret-looking string at runtime, the way the script already does. Append these before the final `echo`:

```bash
sid_name="session""id"
csrf_name="csrf""token"
check blocked "JSON cookie export" "export/cookies.json" "[{\"name\": \"$sid_name\", \"value\": \"abc\", \"domain\": \".instagram.com\"}]"
check blocked "JSON csrftoken export" "export/state.json" "{\"name\":\"$csrf_name\",\"value\":\"abc\"}"
check blocked "Netscape cookie line" "export/jar.txt" "$(printf '.instagram.com\tTRUE\t/\tTRUE\t1999999999\t%s\t%s' "$sid_name" "abc")"
check blocked "csrftoken value" "notes2.txt" "$csrf_name=$(printf 'Ab%.0s' $(seq 1 10))"
check blocked "added line starting with ++" "pp.txt" "++token $planted_sid"
check allowed "prose that mentions csrftoken" "docs/csrf.md" "The csrftoken cookie is read from the jar"
```

- [ ] **Step 2: Run it and watch the new cases fail.**

Run: `bash scripts/test-secret-guard.sh`
Expected: `FAIL: JSON cookie export was allowed, expected blocked`. Each case is checked in turn, and the script exits at the first failure.

- [ ] **Step 3: Update the hook.** Replace the `added=` line and add the new checks after the existing two:

```bash
# Only the per-file "+++ b/path" header is skipped; an added line whose content starts with "++" is still scanned.
added="$(git diff --cached -U0 --diff-filter=ACMR | grep -E '^\+' | grep -vE '^\+\+\+ (b/|/dev/null)' || true)"
```

```bash
if grep -qiE '"name" *: *"(sessionid|csrftoken|ds_user_id)"' <<<"$added"; then
  echo "pre-commit: staged changes contain an exported cookie (JSON)"
  fail=1
fi
if grep -qE $'\t(sessionid|csrftoken|ds_user_id)\t' <<<"$added"; then
  echo "pre-commit: staged changes contain an exported cookie (Netscape format)"
  fail=1
fi
if grep -qE 'csrftoken=[A-Za-z0-9]{16,}' <<<"$added"; then
  echo "pre-commit: staged changes contain a csrftoken value"
  fail=1
fi
```

- [ ] **Step 4: Run the script again.**

Run: `bash scripts/test-secret-guard.sh`
Expected: every line is `ok: ...`, and the last line is `secret guard: all checks passed`.

- [ ] **Step 5: Write the fixture guard test** (spec 10). Build the forbidden words from parts, so this file passes its own scan and the hook:

```kotlin
package io.github.yuriimurha.reels.instagram

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Spec 10: fixtures are scrubbed. Fails if any fixture holds session material or a real Instagram CDN host. */
class FixtureGuardTest {
    private val forbidden = listOf(
        "session" + "id", "csrf" + "token", "Cookie" + ":", "ds_user" + "_id",
        "scontent", "fbcdn", "cdninstagram",
    )

    @Test
    fun fixturesHoldNoSessionMaterialOrRealHosts() {
        val dir = File("src/test/resources/fixtures")
        assertTrue(dir.isDirectory, "unit tests must run from the instagram module directory")
        val offenders = dir.walkTopDown().filter { it.isFile }.flatMap { file ->
            val text = file.readText()
            forbidden.filter { text.contains(it, ignoreCase = true) }.map { "${file.name}: $it" }
        }.toList()
        if (offenders.isNotEmpty()) fail("Unscrubbed fixtures:\n" + offenders.joinToString("\n"))
    }

    @Test
    fun theGuardCatchesAPlantedValue() {
        val planted = "{\"" + "session" + "id" + "\": \"s1\"}"
        assertTrue(forbidden.any { planted.contains(it, ignoreCase = true) })
    }
}
```

- [ ] **Step 6: Run the module's tests.**

Run: `./gradlew :instagram:test`
Expected: PASS. The directory holds only `.gitkeep` for now.

- [ ] **Step 7: Commit.**

```bash
git add .githooks/pre-commit scripts/test-secret-guard.sh instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/FixtureGuardTest.kt instagram/src/test/resources/fixtures/web/.gitkeep TODO.md
git commit -m "feat(guard): block exported cookie dumps and csrftoken values; fixture guard test"
```

In `TODO.md`, tick the M3 gate "the pre-commit hook catches JSON/Netscape cookie dumps, `csrftoken` and `++` diff lines …".

---

### Task 2: Media links and web parsers (M3)

**Files:**
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/InstagramClient.kt` (`mediaInfo` returns `RemoteMedia?`)
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/fake/FakeInstagramClient.kt` (`mediaInfo` returns `library.media(pk)?.let(::visible)`, with no throw)
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/MediaLinks.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebParsers.kt`
- Create fixtures in `instagram/src/test/resources/fixtures/web/`: `collections_list.json`, `saved_page_more.json`, `saved_page_last.json`, `collection_page.json`, `media_info.json`, `saved_page_unavailable.json`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/MediaLinksTest.kt`, `WebParsersTest.kt`
- Fix compile sites in `:app`, where a `RemoteMedia?` return reaches code that expected `RemoteMedia`. Tests and fakes only: today only the fake and tests call `mediaInfo`.

**Interfaces:**
- Produces, all `internal` to `:instagram`:
  - `MediaLinks.imageCandidates(o: JsonObject): List<ImageCandidate>`
  - `MediaLinks.chooseThumbnail(cs: List<ImageCandidate>): ImageCandidate?`
  - `MediaLinks.expiresAt(url: String): Instant?`
  - `WebParsers.collectionsPage(json: JsonObject): Page<RemoteCollection>`
  - `WebParsers.savedPage(json: JsonObject): Page<RemoteMedia>`
  - `WebParsers.mediaInfo(json: JsonObject): RemoteMedia?`
- Produces (public): `InstagramClient.mediaInfo(mediaPk: String): RemoteMedia?`

- [ ] **Step 1: Write the synthetic fixtures.** Hand-write them in the documented web shape. Use only synthetic values: hosts `cdn.example.invalid`, handles `user_1`, numeric ids of plausible length. They contain none of FixtureGuardTest's words.
  - `saved_page_more.json`: three items wrapped as `{"media": {...}}`:
    - a reel: `media_type` 2, `product_type` "clips", `video_versions`;
    - an image: `media_type` 1;
    - a carousel: `media_type` 8, `carousel_media_count` 3, and `carousel_media` whose first child has `image_versions2` while the top level has none.
    - Top level: `"more_available": true`, `"next_max_id": "QVFE_cursor_2"`, `"num_results": 3`, `"status": "ok"`.
    - Each media object has:
      - `pk` as a JSON number (e.g. `3100000000000000001`) and a string `id` `"3100000000000000001_42"`;
      - `code`, `taken_at` (epoch seconds), `user.username`, `caption.text` (null on the image item), `original_width` and `original_height`;
      - `image_versions2.candidates`: three candidates with widths 1080, 720 and 480, urls `https://cdn.example.invalid/v/t51/<n>.jpg?stp=x&oe=6720A3F0&_nc_ht=cdn`;
      - `saved_collection_ids` as an array of strings on the reel only.
  - `saved_page_last.json`: one item, with `"more_available": false` and no `next_max_id`.
  - `saved_page_unavailable.json`: three items. One is `{"media": null}`, one is a normal image, and one is a media with `pk`, `code`, `media_type` 1, `taken_at` and `user` but **no** `image_versions2`. `"more_available": false`.
  - `collection_page.json`: the same shape as `saved_page_last.json`, with two items.
  - `collections_list.json`: four items:
    - `{"collection_id": "17900000000000001", "collection_name": "All posts", "collection_type": "ALL_MEDIA_AUTO_COLLECTION"}`;
    - two `MEDIA` collections, "Food" and "Travel". "Food" has `cover_media: {"pk": 3100000000000000001, ...}`; "Travel" has no `cover_media`;
    - one `AUDIO_AUTO_COLLECTION`.
    - Top level: `"more_available": false`, `"status": "ok"`.
  - `media_info.json`: `{"items": [<the reel from saved_page_more>], "num_results": 1, "more_available": false, "status": "ok"}`.

- [ ] **Step 2: Write the failing tests.**

`MediaLinksTest`:

```kotlin
package io.github.yuriimurha.reels.instagram.web

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaLinksTest {
    private fun c(width: Int) = ImageCandidate("https://cdn.example.invalid/$width.jpg", width, width)

    @Test fun choosesTheNarrowestCandidateAtLeast720Wide() =
        assertEquals(720, MediaLinks.chooseThumbnail(listOf(c(1080), c(720), c(480)))?.width)

    @Test fun fallsBackToTheWidestWhenAllAreSmall() =
        assertEquals(640, MediaLinks.chooseThumbnail(listOf(c(320), c(640)))?.width)

    @Test fun noCandidatesMeansNoThumbnail() = assertNull(MediaLinks.chooseThumbnail(emptyList()))

    @Test fun readsTheHexExpiryInOe() =
        assertEquals(Instant.ofEpochSecond(0x6720A3F0), MediaLinks.expiresAt("https://cdn.example.invalid/a.mp4?x=1&oe=6720A3F0"))

    @Test fun missingOrMalformedOeIsNull() {
        assertNull(MediaLinks.expiresAt("https://cdn.example.invalid/a.mp4"))
        assertNull(MediaLinks.expiresAt("https://cdn.example.invalid/a.mp4?oe=zz"))
        assertNull(MediaLinks.expiresAt("not a url"))
    }
}
```

`WebParsersTest` loads fixtures with `javaClass.getResource("/fixtures/web/$name")!!.readText()` and `Json.parseToJsonElement(...).jsonObject`. Tests:
- `savedPageMapsEveryType`: REEL, IMAGE and CAROUSEL, in feed order. The reel has `videoUrl` and `videoUrlExpiresAt` equal to `Instant.ofEpochSecond(0x6720A3F0)` and `savedCollectionIds` equal to the fixture's list. The image has `caption == null` and `savedCollectionIds == null`. The carousel has `carouselCount == 3` and the 720 child candidate as its `thumbnailUrl`. `pk` is the exact digit string (no float rounding). `takenAt` equals the fixture seconds. `nextCursor == "QVFE_cursor_2"`.
- `lastPageHasNoCursor`: `saved_page_last.json` gives `nextCursor == null`.
- `unavailableItemsDoNotStopThePage` (Review Focus 1): `saved_page_unavailable.json` gives 2 items. The null-media item is skipped. The media with no image versions has `thumbnailUrl == ""`, and width and height of 0 when `original_*` are absent.
- `missingMoreAvailableIsAShapeChange`: remove `more_available` → `ShapeChanged("more_available")`.
- `moreAvailableWithoutCursorIsAShapeChange`: `"more_available": true` with no `next_max_id` → `ShapeChanged("next_max_id")`.
- `missingRequiredFieldNamesItsPath`: remove `user` from item 1 → `ShapeChanged("items[1].media.user")`.
- `unknownMediaTypeIsAShapeChange`: `media_type` 5 → `ShapeChanged("items[0].media.media_type")`.
- `missingItemsIsAShapeChange`: `{}` → `ShapeChanged("items")`.
- `collectionsKeepOnlyMediaCollections`: "Food" and "Travel" only, ids as strings, Food's `coverMediaPk == "3100000000000000001"`, Travel's is null, `nextCursor == null`.
- `mediaInfoReadsTheFirstItem`: it equals the reel from `savedPageMapsEveryType`. `{"items": [], "status": "ok"}` gives null.
- `unknownKeysAreIgnored`: adding `"brand_new_key": {"x": 1}` to an item changes nothing.

Build the mutated JSON in the tests (`JsonObject(map + ...)`). Never add more fixture files for these cases.

- [ ] **Step 3: Run the tests and watch them fail.**

Run: `./gradlew :instagram:test --tests '*MediaLinksTest' --tests '*WebParsersTest'`
Expected: compilation fails with `Unresolved reference: MediaLinks` / `WebParsers`.

- [ ] **Step 4: Implement `MediaLinks.kt`.**

```kotlin
package io.github.yuriimurha.reels.instagram.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant

internal data class ImageCandidate(val url: String, val width: Int, val height: Int)

/** How the adapter reads Instagram's media links (spec 4.1): candidate choice and CDN link expiry. */
internal object MediaLinks {
    /** Spec 14: thumbnails of about 720 px keep storage small and still look sharp in a two-column grid. */
    const val THUMBNAIL_MIN_WIDTH = 720

    fun imageCandidates(o: JsonObject): List<ImageCandidate> {
        val candidates = (o["image_versions2"] as? JsonObject)?.get("candidates") as? JsonArray ?: return emptyList()
        return candidates.mapNotNull { element ->
            val c = element as? JsonObject ?: return@mapNotNull null
            val url = c.string("url") ?: return@mapNotNull null
            ImageCandidate(url, c.int("width") ?: 0, c.int("height") ?: 0)
        }
    }

    /** The narrowest candidate at least [THUMBNAIL_MIN_WIDTH] wide, else the widest one. */
    fun chooseThumbnail(candidates: List<ImageCandidate>): ImageCandidate? =
        candidates.filter { it.width >= THUMBNAIL_MIN_WIDTH }.minByOrNull { it.width } ?: candidates.maxByOrNull { it.width }

    /** Instagram CDN links carry their expiry as hex Unix seconds in `oe` (spec 6.3 Q7). Null when absent or malformed. */
    fun expiresAt(url: String): Instant? =
        url.toHttpUrlOrNull()?.queryParameter("oe")?.toLongOrNull(16)?.let(Instant::ofEpochSecond)
}
```

Add these helpers to `WebJson.kt`, next to `string`:

```kotlin
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

/** An id that Instagram sends either as a JSON number or as a string; the exact digits, never a rounded double. */
internal fun JsonObject.idString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotEmpty() }
```

- [ ] **Step 5: Implement `WebParsers.kt`.**

```kotlin
package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException.ShapeChanged
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.time.Instant

/**
 * Instagram web JSON to adapter models (spec 6). Unknown keys are ignored; a missing required field throws
 * ShapeChanged with its path. Shapes follow instaloader/instagrapi until the M3 spike confirms them.
 */
internal object WebParsers {
    fun collectionsPage(json: JsonObject): Page<RemoteCollection> {
        val items = json.items()
        val collections = items.mapIndexedNotNull { i, element ->
            val o = element as? JsonObject ?: throw ShapeChanged("items[$i]")
            if (o.string("collection_type") != "MEDIA") return@mapIndexedNotNull null
            RemoteCollection(
                id = o.idString("collection_id") ?: throw ShapeChanged("items[$i].collection_id"),
                name = o.string("collection_name") ?: throw ShapeChanged("items[$i].collection_name"),
                coverMediaPk = (o["cover_media"] as? JsonObject)?.idString("pk"),
            )
        }
        return Page(collections, nextCursor(json))
    }

    fun savedPage(json: JsonObject): Page<RemoteMedia> {
        val media = json.items().mapIndexedNotNull { i, element ->
            val wrapper = element as? JsonObject ?: throw ShapeChanged("items[$i]")
            // An item Instagram can no longer show has no media object: nothing to store, not a shape change.
            val m = wrapper["media"] as? JsonObject ?: return@mapIndexedNotNull null
            media(m, "items[$i].media")
        }
        return Page(media, nextCursor(json))
    }

    fun mediaInfo(json: JsonObject): RemoteMedia? {
        val first = json.items().firstOrNull() ?: return null
        return media(first as? JsonObject ?: throw ShapeChanged("items[0]"), "items[0]")
    }

    private fun JsonObject.items(): JsonArray = this["items"] as? JsonArray ?: throw ShapeChanged("items")

    /** Spec 6.3 Q4. A page that says nothing about more pages is a shape change, never "last page" (P5). */
    private fun nextCursor(json: JsonObject): String? {
        val more = (json["more_available"] as? JsonPrimitive)?.booleanOrNull ?: throw ShapeChanged("more_available")
        if (!more) return null
        return json.idString("next_max_id") ?: throw ShapeChanged("next_max_id")
    }

    private fun media(o: JsonObject, path: String): RemoteMedia {
        val pk = o.idString("pk") ?: throw ShapeChanged("$path.pk")
        val code = o.string("code") ?: throw ShapeChanged("$path.code")
        val type = when (o.int("media_type")) {
            1 -> MediaType.IMAGE
            2 -> if (o.string("product_type") == "clips") MediaType.REEL else MediaType.VIDEO
            8 -> MediaType.CAROUSEL
            else -> throw ShapeChanged("$path.media_type")
        }
        val user = o["user"] as? JsonObject ?: throw ShapeChanged("$path.user")
        val author = user.string("username") ?: throw ShapeChanged("$path.user.username")
        val takenAt = o.long("taken_at") ?: throw ShapeChanged("$path.taken_at")
        val carousel = o["carousel_media"] as? JsonArray
        val imageSource = if (o["image_versions2"] is JsonObject) o else carousel?.firstOrNull() as? JsonObject
        val thumb = imageSource?.let { MediaLinks.chooseThumbnail(MediaLinks.imageCandidates(it)) }
        val videoUrl = ((o["video_versions"] as? JsonArray)?.firstOrNull() as? JsonObject)?.string("url")
        return RemoteMedia(
            pk = pk,
            code = code,
            type = type,
            author = author,
            caption = (o["caption"] as? JsonObject)?.string("text"),
            takenAt = Instant.ofEpochSecond(takenAt),
            width = o.int("original_width") ?: thumb?.width ?: 0,
            height = o.int("original_height") ?: thumb?.height ?: 0,
            carouselCount = if (type == MediaType.CAROUSEL) o.int("carousel_media_count") ?: carousel?.size else null,
            thumbnailUrl = thumb?.url.orEmpty(),
            videoUrl = videoUrl,
            videoUrlExpiresAt = videoUrl?.let(MediaLinks::expiresAt),
            savedCollectionIds = (o["saved_collection_ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        )
    }
}
```

- [ ] **Step 6: Change `mediaInfo` to nullable.** In `InstagramClient.kt`, change the KDoc and signature to `/** One item with fresh media links, or null when Instagram no longer has it (P8). */ suspend fun mediaInfo(mediaPk: String): RemoteMedia?`. In `FakeInstagramClient`, make the body `answer("mediaInfo:$mediaPk") { library.media(mediaPk)?.let(::visible) }`. Update `FakeInstagramClientTest` so an unknown pk now returns null: change any test that expected a ShapeChanged into one that asserts null.

- [ ] **Step 7: Run the tests until they pass.**

Run: `./gradlew :instagram:test :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit.** Update `ARCHITECTURE.md`'s `:instagram` row: `WebParsers`/`MediaLinks`, and the shapes are candidates until the spike.

```bash
git add instagram/src ARCHITECTURE.md
git commit -m "feat(instagram): web response parsers, thumbnail choice and CDN expiry"
```

---

### Task 3: WebInstagramClient (M3)

**Files:**
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebEndpoints.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebInstagramClient.kt`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/WebInstagramClientTest.kt`, `WebEndpointsTest.kt` (extend)

**Interfaces:**
- Consumes: `WebParsers` (Task 2), `getJsonObject` and `WebSessionProbe` (existing), and `HttpClientFactory.create` (existing).
- Produces:

```kotlin
class WebInstagramClient(
    http: () -> OkHttpClient,                 // built lazily: the user agent comes from the WebView provider
    cookies: CookieStore,
    override val reportsSavedCollectionIds: Boolean = SAVED_COLLECTION_IDS_CONFIRMED,
    base: HttpUrl = WebEndpoints.BASE,
) : InstagramClient {
    companion object { const val SAVED_COLLECTION_IDS_CONFIRMED = false }
}
```

- [ ] **Step 1: Write failing endpoint tests** in `WebEndpointsTest`:

```kotlin
@Test fun apiEndpointsMatchTheSpecCandidates() {
    val b = WebEndpoints.BASE
    assertEquals("/api/v1/feed/saved/posts/", WebEndpoints.savedPosts(b, null).encodedPath)
    assertEquals("c2", WebEndpoints.savedPosts(b, "c2").queryParameter("max_id"))
    assertEquals("/api/v1/feed/collection/17900000000000002/posts/", WebEndpoints.collectionPosts(b, "17900000000000002", null).encodedPath)
    assertEquals("/api/v1/media/3100000000000000001/info/", WebEndpoints.mediaInfo(b, "3100000000000000001").encodedPath)
    val list = WebEndpoints.collections(b, null)
    assertEquals("/api/v1/collections/list/", list.encodedPath)
    assertEquals("[\"ALL_MEDIA_AUTO_COLLECTION\",\"MEDIA\",\"AUDIO_AUTO_COLLECTION\"]", list.queryParameter("collection_types"))
}

/** P6: one host per client, so OkHttp never coalesces connections and never re-sends after a 421. */
@Test fun everyApiEndpointStaysOnTheBaseHost() {
    val b = WebEndpoints.BASE
    listOf(
        WebEndpoints.currentUser(b, "42"), WebEndpoints.collections(b, "x"), WebEndpoints.savedPosts(b, "x"),
        WebEndpoints.collectionPosts(b, "1", "x"), WebEndpoints.mediaInfo(b, "1"),
    ).forEach { assertEquals("www.instagram.com", it.host); assertEquals("https", it.scheme) }
}

@Test fun idsThatAreNotDigitsAreRefused() {
    assertFailsWith<InstagramException.ShapeChanged> { WebEndpoints.collectionPosts(WebEndpoints.BASE, "../x", null) }
    assertFailsWith<InstagramException.ShapeChanged> { WebEndpoints.mediaInfo(WebEndpoints.BASE, "1/2") }
}
```

- [ ] **Step 2: Write failing client tests** against `MockWebServer`, mirroring `WebSessionProbeTest`'s setup: an `InMemoryCookieStore` holding fake `ds_user_id=42` and `sessionid=s1`, and `HttpClientFactory.create(cookies, "test-agent")`. Serve the Task 2 fixtures.
  - `walksSavedPagesWithMaxId`: page 1 (`saved_page_more.json`) then page 2 (`saved_page_last.json`). The second recorded request has `max_id=QVFE_cursor_2`, and the second page's `nextCursor` is null.
  - `collectionPostsHitsTheCollectionPath`: the recorded path is `/api/v1/feed/collection/17900000000000002/posts/`.
  - `collectionsFiltersToMediaCollections`: 2 collections.
  - `mediaInfoReturnsNullWhenNotFound`: 404 with `{"message":"Media not found or unavailable","status":"fail"}` gives null. A 400 with the same body gives null. `{"items":[],"status":"ok"}` gives null.
  - `mediaInfoStillStopsOnAChallenge`: 400 with `{"message":"challenge_required","challenge":{"url":"/challenge/x/"}}` throws `ChallengeRequired`, not null.
  - `currentUserDelegatesToTheProbe`: `/api/v1/users/42/info/` gives `Account("42", "user_1")`.
  - `noRequestUntilFirstCall`: constructing the client calls the `http` provider zero times. Count with a lambda counter.
  - `reportsSavedCollectionIdsDefaultsToFalse` (P3).

- [ ] **Step 3: Run the tests and watch them fail.**

Run: `./gradlew :instagram:test --tests '*WebEndpointsTest' --tests '*WebInstagramClientTest'`
Expected: compilation fails on the missing builders and the missing client.

- [ ] **Step 4: Implement the endpoint builders** in `WebEndpoints`:

```kotlin
private val DIGITS = Regex("[0-9]{1,30}")

/** An id goes into a URL path, so anything but digits ("..", "1/2") is refused before a request is made. */
private fun pathId(id: String, field: String): String =
    id.takeIf { DIGITS.matches(it) } ?: throw InstagramException.ShapeChanged(field)

private fun HttpUrl.Builder.cursor(cursor: String?) = apply { cursor?.let { addQueryParameter("max_id", it) } }

fun collections(base: HttpUrl, cursor: String?): HttpUrl =
    base.newBuilder().addPathSegments("api/v1/collections/list/")
        .addQueryParameter("collection_types", "[\"ALL_MEDIA_AUTO_COLLECTION\",\"MEDIA\",\"AUDIO_AUTO_COLLECTION\"]")
        .cursor(cursor).build()

fun savedPosts(base: HttpUrl, cursor: String?): HttpUrl =
    base.newBuilder().addPathSegments("api/v1/feed/saved/posts/").cursor(cursor).build()

fun collectionPosts(base: HttpUrl, collectionId: String, cursor: String?): HttpUrl =
    base.newBuilder().addPathSegments("api/v1/feed/collection").addPathSegment(pathId(collectionId, "collection_id"))
        .addPathSegments("posts/").cursor(cursor).build()

fun mediaInfo(base: HttpUrl, mediaPk: String): HttpUrl =
    base.newBuilder().addPathSegments("api/v1/media").addPathSegment(pathId(mediaPk, "pk")).addPathSegments("info/").build()
```

- [ ] **Step 5: Implement the client.**

```kotlin
package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * The real adapter (spec 4.2) over Instagram's web API, sharing the WebView's cookie jar. One request per call, no
 * retries: the caller paces every call through the Pacer.
 */
class WebInstagramClient(
    http: () -> OkHttpClient,
    private val cookies: CookieStore,
    override val reportsSavedCollectionIds: Boolean = SAVED_COLLECTION_IDS_CONFIRMED,
    private val base: HttpUrl = WebEndpoints.BASE,
) : InstagramClient {
    private val http by lazy(http)
    private val probe by lazy { WebSessionProbe(this.http, cookies, base) }

    override suspend fun currentUser(): Account = probe.currentUser()

    override suspend fun collections(cursor: String?): Page<RemoteCollection> =
        WebParsers.collectionsPage(http.getJsonObject(WebEndpoints.collections(base, cursor)))

    override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> {
        val url = if (collectionId == null) {
            WebEndpoints.savedPosts(base, cursor)
        } else {
            WebEndpoints.collectionPosts(base, collectionId, cursor)
        }
        return WebParsers.savedPage(http.getJsonObject(url))
    }

    /** Null when Instagram no longer has the item (P8). A challenge, login or rate limit still throws. */
    override suspend fun mediaInfo(mediaPk: String): RemoteMedia? = try {
        WebParsers.mediaInfo(http.getJsonObject(WebEndpoints.mediaInfo(base, mediaPk)))
    } catch (e: InstagramException.ShapeChanged) {
        if (e.fieldPath == "http.400" || e.fieldPath == "http.404") null else throw e
    }

    companion object {
        /** Spec 6.3 Q2. Flip to true only after the Adapter lab shows saved_collection_ids on saved items (P3). */
        const val SAVED_COLLECTION_IDS_CONFIRMED = false
    }
}
```

- [ ] **Step 6: Run the tests.**

Run: `./gradlew :instagram:test`
Expected: PASS.

- [ ] **Step 7: Commit.** In `ARCHITECTURE.md`'s `:instagram` row, mention `WebInstagramClient`, P3, P6 and P8.

```bash
git add instagram/src ARCHITECTURE.md
git commit -m "feat(instagram): WebInstagramClient over the saved, collection and media endpoints"
```

---

### Task 4: Adapter lab core in `:instagram` (M3)

**Files:**
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebJson.kt` (add `getRaw`)
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/lab/ShapeDump.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/lab/Scrubber.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/lab/AdapterLab.kt`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/lab/ShapeDumpTest.kt`, `ScrubberTest.kt`, `AdapterLabTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class LabCall { CURRENT_USER, COLLECTIONS, SAVED_ALL, SAVED_COLLECTION, MEDIA_INFO }

/** One lab call's answer. [ids] are real ids held in memory only, to chain the next call; toString() omits them. */
class LabResult(
    val call: LabCall,
    val httpCode: Int,
    val classification: String,          // "ok", or the InstagramException simple name ErrorClassifier picked
    val error: InstagramException?,      // non-null when classified as a failure
    val shape: String,                   // ShapeDump of the body, every identifying value redacted
    val scrubbedJson: String?,           // Scrubber output, pretty-printed; null when the body was not JSON
    val ids: LabIds,
)
class LabIds(val firstCollectionId: String?, val firstMediaPk: String?)

class AdapterLab(http: () -> OkHttpClient, cookies: CookieStore, base: HttpUrl = WebEndpoints.BASE) {
    /** Sends exactly one request. [arg] is a collection id (SAVED_COLLECTION) or a media pk (MEDIA_INFO). Never retries. */
    suspend fun run(call: LabCall, arg: String?): LabResult
    /** Ids → synthetic ids stay consistent across the calls of one lab session, so fixtures cross-reference. */
    val scrubber: Scrubber
}

object ShapeDump { fun of(element: JsonElement): String }

class Scrubber { fun scrub(element: JsonElement): JsonElement }
```

- **Behaviour.**
  - `ShapeDump.of` writes one line per key, indented two spaces per level. Arrays show `array[N]`, and only element `[0]` is expanded.
  - **Primitives:**
    - `boolean = <value>` and `null`.
    - Numbers show their value only when the key is in `VISIBLE_VALUE_KEYS`. Otherwise they show `number(<n> digits)`.
    - Strings show `"<value>"` only when the key is in `VISIBLE_VALUE_KEYS`. A URL shows `url(host=<instagram|cdn|other>, params=[sorted names], oe=<hex|absent|malformed>)`. Any other string shows `string(len <n>, <digits|hex|base64url|text>)`.
  - `VISIBLE_VALUE_KEYS = setOf("media_type", "product_type", "collection_type", "status", "more_available", "num_results", "width", "height", "original_width", "original_height", "carousel_media_count", "error_type", "message", "spam", "require_login", "lock", "feedback_title", "has_more")`.
  - Host classes: `instagram` for `instagram.com` and its subdomains; `cdn` for a host containing `cdninstagram.com` or `fbcdn.net`; `other` for the rest. The host itself is never printed.
- **`Scrubber`.** It keeps the structure and the keys. Its rules, by value type:
  - Numbers or strings under keys in `VISIBLE_VALUE_KEYS` are kept.
  - Ids:
    - A value under a key equal to `pk`, `id` or `fbid`, or ending in `_id`, is an id.
    - So is every all-digit string, plus elements of `saved_collection_ids`.
    - Ids are replaced by a synthetic id of the same digit count, and the mapping is consistent within one `Scrubber`.
    - A `"<digits>_<digits>"` id maps each part.
  - `username` and `full_name` become `user_<n>`. `text` becomes `caption <n>`. `code` becomes `C` followed by `<n>` padded to the original length.
  - A URL becomes `https://cdn.example.invalid/m/<n>` plus the original param names. Every value becomes `x`, except `oe`, which is kept.
  - Other strings become `s_<n>`.
  - `taken_at` and any other number key ending in `_at` or `timestamp` become `1700000000 + n * 86400`. Other non-visible numbers become `n`.
  - Booleans and null are kept.
  - The output must pass `FixtureGuardTest`'s word list. Assert that in `ScrubberTest`.
- **`AdapterLab.run`.**
  - It builds the URL with the Task 3 `WebEndpoints` builders: `CURRENT_USER` uses the `ds_user_id` cookie, the same way `WebSessionProbe` does, and with no cookie it throws `LoginRequired` before any request.
  - It sends one GET through `getRaw`, which returns code, `Location`, `Content-Type` and the body string. It must not throw on HTTP errors.
  - It runs `ErrorClassifier.classify(...)` itself.
  - Then it fills `LabResult`: `classification`, `error`, `shape` (empty-body: `"(empty body)"`), `scrubbedJson` and `ids` (the first `MEDIA` collection id, and the first saved or info item's `pk`).
  - An `IOException` still becomes `InstagramException.Transient` and is thrown, like `getJsonObject`.
  - The raw body is never stored, logged or returned.

- [ ] **Step 1: Write failing tests.**
  - `ShapeDumpTest`, on the Task 2 fixtures:
    - `media_type number = 2` and `product_type string = "clips"` appear;
    - `pk` shows as `number(19 digits)`;
    - `username` shows as `string(len 6, text)`;
    - a candidate URL shows `url(host=other, params=[_nc_ht, oe, stp], oe=hex)`;
    - `items array[3]`;
    - the output contains no `user_1`, no `cdn.example.invalid` and no fixture id digits.
  - `ScrubberTest`:
    - structure and keys are kept;
    - `pk` digit counts are kept and the values differ from the input;
    - the same id in two places maps to the same synthetic id, e.g. Food's `cover_media.pk` and the reel's `pk`;
    - `oe` is kept;
    - the result passes the FixtureGuard word list, including on an input planted with `"scontent-x.cdninstagram.com"` URLs;
    - `media_type` is kept.
  - `AdapterLabTest` (MockWebServer):
    - each call hits exactly one expected path;
    - `SAVED_ALL` fills `ids.firstMediaPk`, and `COLLECTIONS` fills `firstCollectionId`;
    - a 429 gives `classification == "RateLimited"` with `error is RateLimited` and a shape of the error body, with no throw;
    - a challenge body gives `ChallengeRequired`;
    - the server's request count equals the number of `run` calls (no retries);
    - `LabResult.toString()` contains neither id.

- [ ] **Step 2: Run them and watch them fail.**

Run: `./gradlew :instagram:test --tests '*lab*'`
Expected: compilation fails.

- [ ] **Step 3: Implement** `getRaw` (in `WebJson.kt`, next to `getJsonObject`, sharing its IOException mapping), `ShapeDump`, `Scrubber` and `AdapterLab`, as specified above.

- [ ] **Step 4: Run the module.**

Run: `./gradlew :instagram:test`
Expected: PASS.

- [ ] **Step 5: Commit.** In `ARCHITECTURE.md`, add a `lab` row: what it shows, and that the raw body never leaves memory.

```bash
git add instagram/src ARCHITECTURE.md
git commit -m "feat(instagram): adapter lab core with redacted shape dumps and scrubbed fixtures"
```

---

### Task 5: Pacer gates (M4)

**Files:**
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/PacingPolicy.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/Pacer.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/pacing/RequestLog.kt`, `RoomRequestLog.kt`, `app/src/main/kotlin/io/github/yuriimurha/reels/data/db/ApiRequestDao.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/sync/pacing/PacingPolicyTest.kt` (create or extend), `PacerTest.kt` (extend), and the DAO test for `latest()`

**Interfaces:**
- Produces: `RequestLog.latest(): Long?`, the most recent recorded request time. `ApiRequestDao.latest()` is `@Query("SELECT MAX(at) FROM api_request")`.

- [ ] **Step 1: Write failing tests.**
  - `PacingPolicyTest.gapsAreTruncatedNotClamped`: draw 10,000 gaps from `Conservative` with `Random(1)`.
    - All are in 4,000..12,000.
    - Fewer than 1 % equal exactly 4,000, and fewer than 1 % equal exactly 12,000. (Today about 18 % sit at 4,000: the test must fail on the current code.)
    - The median is in 5,500..7,000.
  - `PacerTest.firstRequestOfAProcessWaitsForTheLastLoggedOne`: a `Pacer` with a log whose latest entry is `now - 1_000`, using `Conservative` and virtual time, issues a sync request. It is sent no earlier than `latest + minGapMs` (4,000). Compare R16: the first request was immediate when the log was empty, and that stays true.
  - `PacerTest.syncAfterAnInteractiveRequestWaitsAFreshGap`:
    - Use a seeded `Random` and capture the policy's next gap with a second `Random` with the same seed.
    - A sync request plans its gap. An interactive request runs meanwhile. The sync request then waits `interactiveEnd + sampledGap`, not exactly `interactiveEnd + minGapMs`.
    - Assert the actual wait is at least `minGapMs` and equals the second draw from the stream.
    - If the stream coupling makes exact assertion brittle, assert that over 50 seeds the waits are not all exactly `minGapMs`.

- [ ] **Step 2: Run the tests and watch them fail.**

Run: `./gradlew :app:testDebugUnitTest --tests '*PacingPolicyTest' --tests '*PacerTest'`
Expected: `gapsAreTruncatedNotClamped` fails, around 18 % at 4,000. The two Pacer tests fail on timing.

- [ ] **Step 3: Implement.** In `PacingPolicy`:

```kotlin
/** Next gap between two sync requests: log-normal around the median, redrawn until it falls in [minGapMs, maxGapMs]. */
fun sampleGap(random: Random): Long {
    repeat(MAX_DRAWS) {
        val gap = (medianGapMs * exp(GAP_SIGMA * random.nextGaussian())).toLong()
        if (gap in minGapMs..maxGapMs) return gap
    }
    return random.nextLong(minGapMs, maxGapMs + 1) // vanishingly rare; still inside the bounds
}
```

Add `private const val MAX_DRAWS = 32` next to `GAP_SIGMA`. Update the KDoc: clamping put about 18 % of gaps at exactly 4 s, a timing signature. Truncation also lifts the median to about 6.4 s, which is slower, not faster.

In `Pacer`:
- Add `private var seeded = false`. As the first statement inside the gate, in both `sync` (once, when `!planned`) and `interactive`, run:
  `if (!seeded) { lastRequestEndedAt = lastRequestEndedAt ?: requestLog.latest(); seeded = true }`
- In `sync`, replace `notBefore = maxOf(notBefore, last + policy.minGapMs)` with `notBefore = maxOf(notBefore, last + policy.sampleGap(random))`, and update its comment: the gap after an interleaved interactive request is a fresh draw, so it is never exactly `minGapMs`.
- Add `latest()` to `InMemoryRequestLog` (`synchronized(lock) { times.maxOrNull() }`), to `RoomRequestLog` (delegates to the DAO) and to `ApiRequestDao`.

- [ ] **Step 4: Run all pacing and engine tests.**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. If an existing test asserted an exact `minGapMs` wait after an interactive request, change it to assert `>= minGapMs` and say so in the report. Never relax a bound.

- [ ] **Step 5: Commit.** In `ARCHITECTURE.md`'s Pacer row, add truncated gaps, the re-sampled interleave gap and seeding from the log. The commit message must state that rates go down, not up. In `TODO.md`, tick the two M4 Pacer gates.

```bash
git add app/src ARCHITECTURE.md TODO.md
git commit -m "fix(pacer): truncated gaps, fresh gap after interactive requests, seed from the request log

Lowers the request rate slightly (median gap ~6.0 s -> ~6.4 s); never raises it."
```

---

### Task 6: Adapter lab screen and Developer section (M3)

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/lab/AdapterLabViewModel.kt`, `AdapterLabScreen.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/DeveloperSection.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/ReelsNavHost.kt` (an `AdapterLabRoute`), `ui/sync/SyncScreen.kt` (shows `DeveloperSection` when `BuildConfig.DEBUG`)
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt` (`val adapterLab: AdapterLab`, built with the same lazy `instagramHttp` and `cookieStore`)
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/ui/lab/AdapterLabViewModelTest.kt`, `AdapterLabScreenTest.kt`

**Interfaces:**
- Consumes: `AdapterLab`, `LabCall`, `LabResult` (Task 4); `AppContainer.instagramPacer`; `SessionRepository`.
- Produces:
  - `AdapterLabRoute` (a `data object`);
  - `DeveloperSection(mockMode: Boolean?, mockSwitchEnabled: Boolean, onMockModeChange: (Boolean) -> Unit, onOpenLab: () -> Unit, labEnabled: Boolean)`. Task 7 wires the mock switch; in this task, pass `mockMode = null`, which hides the switch.

**Behaviour:**
- **The screen.**
  - Title: "Adapter lab". One button per `LabCall`, labelled "Who am I", "Collections", "All Saved (page 1)", "First collection (page 1)" and "Media info (first saved item)".
  - "First collection" is enabled only after Collections has returned an id; "Media info" only after All Saved has returned a pk.
  - Every button is disabled while a call is in flight, and while the session state isn't `Valid`.
  - Under the buttons, the latest result shows: the call, the HTTP code, the classification, and the shape in a monospace, horizontally scrollable `Text`.
  - Text at the top says: "Each tap sends one paced request to Instagram as the logged-in test account."
- **The ViewModel.**
  - It runs each call as `pacer.interactive { ... }`. Inside the lambda, when `result.error is InstagramException.RateLimited`, it stores the result in a local before throwing that error, so the Pacer arms the cooldown and the screen still shows the shape.
  - On `ChallengeRequired` or `LoginRequired`, it calls `session.challengeRequired(url)` or `session.loginRequired()` (the existing signal methods; Task 9 adds the epoch), so the Sync screen reflects it.
  - A `PacerRefusal` shows its user message (`userMessage`) and sends no request.
  - It writes `scrubbedJson` to `filesDir/lab/<call name lowercased>.json` (overwriting), and never anything else. The path shows under the result.
  - Ids live only in ViewModel fields.

**Tests:**
- `AdapterLabViewModelTest`, with a fake `AdapterLab` stand-in. Put an interface `LabRunner { suspend fun run(call: LabCall, arg: String?): LabResult }` in `:app`, implemented by a thin adapter over `AdapterLab`, so tests don't need HTTP.
  - `eachTapIsOnePacedRequest`: uses a real `Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore())` in virtual time. After 2 taps, the log count is 2.
  - `rateLimitArmsTheCooldownAndKeepsTheShape`.
  - `refusalSendsNothing`: under a cooldown, `run` is never called.
  - `writesOnlyScrubbedJson`: the file content equals `scrubbedJson`.
  - `chainedButtonsWaitForIds`.
- `AdapterLabScreenTest`: the buttons are disabled when the session isn't Valid; the shape text renders.
- `SyncScreen`: in debug unit tests (`BuildConfig.DEBUG` is true there), the Developer section shows an "Adapter lab" button.

- [ ] **Step 1:** Write the failing tests above.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*AdapterLab*'`. Expected: compilation fails.
- [ ] **Step 3:** Implement the screen, ViewModel, route and Developer section.
- [ ] **Step 4:** Run `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Docs.**
  - `README.md` gets a new section, "Adapter lab (M3 spike, debug builds)". It covers:
    - how to open it (Sync → Developer → Adapter lab), and running each button once, top to bottom;
    - exporting the scrubbed files:
      `adb exec-out run-as io.github.yuriimurha.reels cat files/lab/saved_all.json > saved_all.json`
      (one command per file: `current_user`, `collections`, `saved_all`, `saved_collection`, `media_info`);
    - handing the files and the on-screen shapes to a Claude session to answer the seven spike questions.
  - `ARCHITECTURE.md` gets a Lab row.

```bash
git add app/src README.md ARCHITECTURE.md
git commit -m "feat(ui): debug Adapter lab screen and Developer section"
```

---

### Task 7: Real backend, Mock mode and the CDN fetcher (M4)

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/di/BackendChoice.kt`, `di/ProcessRestart.kt`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/media/HttpMediaFetcher.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/di/Backend.kt` (add `Real`), `di/AppContainer.kt`, `data/db/ReelsDatabase.kt` (`build(context, name)`)
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncScreen.kt`, `SyncViewModel.kt`, `DeveloperSection.kt` (wires the mock switch)
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/di/BackendSelectionTest.kt`, `data/media/HttpMediaFetcherTest.kt`, `ui/sync/DeveloperSectionTest.kt`

**Interfaces:**
- Produces:

```kotlin
class BackendChoice(private val prefs: SharedPreferences, private val debugBuild: Boolean) {
    /** Release builds never use the fake library. Debug builds default to it, so emulators never need a login. */
    val useFake: Boolean get() = debugBuild && prefs.getBoolean(KEY_USE_FAKE, true)
    fun setUseFake(value: Boolean) { prefs.edit().putBoolean(KEY_USE_FAKE, value).commit() }
    companion object { const val PREFS = "backend"; const val KEY_USE_FAKE = "use_fake" }
}

sealed interface Backend {
    // existing Fake unchanged
    /** Real Instagram. Its pacer is the process's one Conservative instagramPacer, never a new one. */
    class Real(override val client: InstagramClient, override val fetcher: MediaFetcher, override val pacer: Pacer) : Backend
}

/** The CDN said 429. Not an API rate limit: no cooldown, but no more CDN requests this run (Task 8). */
class CdnRateLimited : IOException("CDN rate limit")

/** 200 → bytes; 403/404/410 → null; 429 → CdnRateLimited; other codes → IOException; blank or non-https url → null, no request. */
class HttpMediaFetcher(http: () -> OkHttpClient, private val requireHttps: Boolean = true) : MediaFetcher {
    companion object {
        /** The CDN client: no cookie jar at all, retryOnConnectionFailure(false), 15 s connect / 30 s read, the WebView user agent. */
        fun client(userAgent: String): OkHttpClient
    }
}
```

- **`AppContainer` changes:**
  - `val backendChoice = BackendChoice(context.getSharedPreferences(BackendChoice.PREFS, MODE_PRIVATE), BuildConfig.DEBUG)`.
  - `val usesFake: Boolean = backendChoice.useFake`, read once per process.
  - `val requestLogDb: ReelsDatabase by lazy { ReelsDatabase.build(context, "library.db") }`.
  - `val db: ReelsDatabase by lazy { if (usesFake) ReelsDatabase.build(context, "reels.db") else requestLogDb }`.
  - Thumbnails live in `File(filesDir, if (usesFake) "thumbs" else "library-thumbs")`.
  - `instagramPacer` uses `RoomRequestLog(requestLogDb.apiRequestDao())`.
  - `backend = if (usesFake) Backend.Fake() else Backend.Real(WebInstagramClient({ instagramHttp }, cookieStore), HttpMediaFetcher({ cdnHttp }), instagramPacer)`, where `cdnHttp` is `HttpMediaFetcher.client(WebSettings.getDefaultUserAgent(context))`, built lazily.
  - `syncEngine()`'s `when` gains the `Real` branch: `signals = session`. Task 9 adds epochs. For now, pass `session` (it already implements `SessionSignals`).
- **`ProcessRestart.restart(context)`:**
  - `context.startActivity(Intent(context, MainActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK))`, then `Runtime.getRuntime().exit(0)`.
- **`DeveloperSection`'s mock switch:**
  - It reads "Mock mode (fake library)" and is disabled while a run is RUNNING.
  - On change, it calls `backendChoice.setUseFake(it)` and then `ProcessRestart.restart`.
  - Its caption reads "The app restarts. Real and fake libraries are kept separately."

**Tests:**
- `BackendSelectionTest` (Robolectric):
  - `releaseNeverUsesFake`: `BackendChoice(prefs with use_fake=true, debugBuild = false).useFake == false`.
  - `debugDefaultsToFake`.
  - `realBackendReusesTheInstagramPacer`: an `AppContainer` with prefs `use_fake=false` gives `backend is Backend.Real` and `backend.pacer === instagramPacer`. Constructing it must not touch WebView: the clients are lazy.
  - `backendsUseSeparateLibrariesAndShareTheRequestLog` (Review Focus 2): in Fake mode, `db !== requestLogDb`, and the instagramPacer's log writes land in `library.db`. Assert through `requestLogDb.apiRequestDao().countSince(0)` after one `instagramPacer.interactive { }`.
- `FastPolicyGuardTest` still passes unchanged.
- `HttpMediaFetcherTest` (MockWebServer; `fetcherMapsStatusCodes`):
  - 200 gives the bytes; 403, 404 and 410 give null; 429 throws `CdnRateLimited`; 500 throws an `IOException`;
  - a blank url gives null with no request;
  - an `http://` url gives null with no request. MockWebServer serves http, so use a test-only `requireHttps = false` constructor parameter, which defaults to `true`, and a separate test asserting that the default refuses http;
  - no `Cookie` header is sent even when a cookie store holds instagram cookies, because the client has no jar.
- `DeveloperSectionTest`: `mockSwitchDisabledWhileRunning`.

- [ ] **Step 1:** Write the failing tests.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*BackendSelectionTest' --tests '*HttpMediaFetcherTest' --tests '*DeveloperSectionTest'`. Expected: compilation fails.
- [ ] **Step 3:** Implement as specified. `ReelsDatabase.build(context, name: String)` keeps the existing builder settings (no destructive migration).
- [ ] **Step 4:** Run `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Docs.**
  - `README.md`: under "Install and run", add "Mock mode (debug builds)": where the switch is, that it's on by default, and to turn it off on the phone for real sync. Change "What works today".
  - `ARCHITECTURE.md`: the Backend and DI rows, P1 and P2.
  - `TODO.md`: tick the M4 gates "`Backend.Real` reuses `AppContainer.instagramPacer` …" and "421 coalesced-connection re-send" (P6, pinned in Task 3).

```bash
git add app/src README.md ARCHITECTURE.md TODO.md
git commit -m "feat(sync): real backend behind a debug Mock mode switch, separate libraries, CDN fetcher"
```

---

### Task 8: Engine safety gates (M4)

**Files:**
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncEngine.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/data/db/CollectionDao.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/sync/SyncEngineTest.kt` (extend), plus the DAO test for the new queries

**Interfaces:**
- Consumes: `CdnRateLimited` (Task 7).
- Produces, in `CollectionDao`:

```kotlin
@Query("SELECT COUNT(*) FROM collection WHERE removedAt IS NULL AND id != '$ALL_SAVED_ID'")
suspend fun liveCollectionCount(): Int

@Query("SELECT COUNT(*) FROM collection_media WHERE collectionId = :collectionId")
suspend fun memberCount(collectionId: String): Int

/** Strategy A: rewrites [pk]'s memberships only among [known] collections; others are left alone. */
@Query("DELETE FROM collection_media WHERE mediaPk = :pk AND collectionId IN (:known) AND collectionId NOT IN (:keep)")
suspend fun deleteRealMembershipsExcept(pk: String, keep: List<String>, known: List<String>)
```

**Behaviour:**
1. **Empty collection list.** In `fetchCollections`, after paging, if `remote.isEmpty() && collectionDao.liveCollectionCount() > 0`, then `throw InstagramException.ShapeChanged("empty collection list")`. This must happen before the transaction, so nothing is marked removed.
2. **Scoped membership rewrite.** Strategy A calls `deleteRealMembershipsExcept(item.pk, ids, knownCollections.toList())`.
3. **Proportional reconcile guard (P7).** In `reconcile` for `ALL_SAVED_ID`, before marking anything:
   - read `val live = collectionDao.memberCount(ALL_SAVED_ID)`;
   - if `unsaved.size >= RECONCILE_GUARD_MIN_ITEMS && unsaved.size * 2 > live`, then `throw InstagramException.ShapeChanged("full sync would remove ${unsaved.size} of $live items")`.
   - The throw is inside the page transaction, so the page and its cursor roll back and the run ends STOPPED_SHAPE.
   - Constants in a `companion object`: `RECONCILE_GUARD_MIN_ITEMS = 20`.
4. **The CDN stops for the run after a 429.**
   - `Progress` gets `var cdnBlocked = false`.
   - In `cacheThumbnails`, each async block first checks `if (progress.cdnBlocked) return@async item.pk to null`. It catches `CdnRateLimited` explicitly: set `progress.cdnBlocked = true` and give null.
   - Items skipped because of the block are **not** counted as failures. They have no thumbnail yet, and a resume or the next Full sync fetches them.
   - A `kotlinx.coroutines.TimeoutCancellationException` from a download counts as a failed item and never cancels the run. Catch it before the generic `CancellationException` rethrow.

**Tests (extend `SyncEngineTest`, with its existing fakes):**
- `emptyCollectionListWithLiveCollectionsStopsWithoutMarkingRemoved`: run 1 syncs normally. Run 2's fake returns no collections. Status is STOPPED_SHAPE, and every collection still has `removedAt == null`.
- `strategyAOnlyRewritesKnownCollections`: a membership in a collection that isn't in this run's list survives.
- `reconcileGuardRefusesMassRemoval`:
  - Library of 100. The FULL run's fake feed returns 30, so 70 would be removed.
  - Status is STOPPED_SHAPE with `lastError` containing "would remove 70 of 100", and every item has `removedAt == null`.
  - Second case: removing 19 of 100 passes.
  - Third case: removing 60 of 100 is refused.
- `cdnRateLimitStopsThumbnailFetchesForTheRun` (Review Focus 4):
  - A fetcher that throws `CdnRateLimited` on its 3rd call. Count fetcher calls.
  - After the throw, no more than the in-flight concurrent calls happen (cdnConcurrency for the policy). Every later page makes 0 fetches.
  - The run still finishes DONE.
  - `failures` counts only items whose fetch actually failed.
- `downloadTimeoutDoesNotCancelTheRun`: a fetcher that throws `TimeoutCancellationException` (use `withTimeout(1) { delay(10) }` inside the fake fetcher) gives status DONE and `failures == 1`.

- [ ] **Step 1:** Write the failing tests.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*SyncEngineTest'`. Expected: the new tests fail.
- [ ] **Step 3:** Implement items 1–4.
- [ ] **Step 4:** Run `./gradlew :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.**
  - `ARCHITECTURE.md` engine row: the guards.
  - `TODO.md`: tick the strategy-A gate, the proportional reconcile gate and the CDN gate.
  - `README.md` Troubleshooting: "Adapter needs repair: full sync would remove N of M items" means Instagram returned far fewer saved items than the library holds. If that's real, use Delete library, then Full sync.

```bash
git add app/src ARCHITECTURE.md TODO.md README.md
git commit -m "fix(sync): guard collection lists and mass reconciles, stop the CDN after a 429"
```

---

### Task 9: Session wiring for real sync (M4)

**Files:**
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/SessionSignals.kt`, `sync/SyncEngine.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/session/SessionRepository.kt`
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/SessionGuard.kt`; modify `web/HttpClientFactory.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/SyncViewModel.kt`, `SyncUiState.kt`, `SyncScreen.kt`; `ui/lab/AdapterLabViewModel.kt` (signals with an epoch)
- Test: `SessionRepositoryTest`, `SyncEngineTest`, `SyncViewModelTest`, `SyncUiStateTest` (extend); `instagram/.../web/SessionGuardTest.kt`

**Interfaces:**
- Produces:

```kotlin
interface SessionSignals {
    /** Identifies the session a run starts with; a signal carrying an older epoch is ignored. */
    fun epoch(): Int
    suspend fun sessionOk(username: String, epoch: Int)
    suspend fun loginRequired(epoch: Int)
    suspend fun challengeRequired(challengeUrl: String?, epoch: Int)

    object None : SessionSignals { /* epoch() = 0; all no-ops */ }
}

fun syncUiState(run: SyncRunEntity?, pacer: PacerStatus?, now: Long, sessionReady: Boolean = true): SyncUiState
```

**Behaviour:**
1. **Epochs in `SessionRepository`.**
   - `sessionEpoch` becomes `@Volatile`, and `override fun epoch() = sessionEpoch`.
   - `loginRequired`, `challengeRequired` and `sessionOk` take the lock, then return without change if `epoch != sessionEpoch`.
   - `sessionOk(username, epoch)`: if the epoch matches and `hasSessionCookies()`, and the stored state isn't already `Valid(username)`, flush the cookies and store `Valid(username)`.
2. **The engine** captures `val epoch = signals.epoch()` at the start of `run`. After `client.currentUser()` succeeds, it calls `notifySession { signals.sessionOk(account.username, epoch) }` and continues. The existing stop signals pass `epoch`.
3. **`SessionGuard`** (`:instagram`) is a network interceptor:
   - before `proceed`, it reads `cookies.cookieValue(url, "sessionid")`; after the response, it reads it again;
   - if the value changed during the flight (a logout, a paste, or another login), it returns the response with every `Set-Cookie` header removed, so the cookie bridge can't resurrect the old session.
   - Register it as the **first** network interceptor in `HttpClientFactory.create`, so it's outermost and sees the response just before OkHttp's bridge stores cookies.
4. **Log out cancels the run.** `SyncViewModel.logout()` becomes `viewModelScope.launch { controller.cancel(); session.logout() }`.
5. **Sync gating.**
   - `SyncViewModel` gets `requiresSession: Boolean`: true for `Backend.Real`, false for Fake. `SyncScreen` passes `container.backend is Backend.Real`.
   - `sessionReady = !requiresSession || sessionState is SessionState.Valid`, with `null` (loading) counting as not ready.
   - `syncUiState(..., sessionReady)` makes `canStart = !running && coolingUntil == null && sessionReady`.
   - When there is no other banner, `!sessionReady` sets the banner "Log in to Instagram to sync".
6. **The lab's** signal calls pass `session.epoch()` read before the call.

**Tests:**
- `SessionGuardTest` (MockWebServer + `InMemoryCookieStore`):
  - `setCookieFromARequestThatOutlivedItsSessionIsDropped` (Review Focus 3): the server dispatcher changes the store's sessionid (from `s1` to nothing) while serving, and replies with a Set-Cookie header that sets sessionid to `s2` on `.instagram.com` (build the header string in the test from parts). Afterwards the store has no sessionid.
  - `setCookieIsKeptWhenTheSessionDidNotChange`: `csrftoken=c2` arrives in the jar.
- `SessionRepositoryTest`:
  - `staleEpochSignalsAreIgnored`: take an epoch, logout, then `loginRequired(oldEpoch)`. The state stays LoggedOut.
  - `sessionOkRestoresValidAfterAStaleExpiredBanner`.
  - `sessionOkIgnoredAfterLogout`.
- `SyncEngineTest`:
  - `successfulCurrentUserSignalsSessionOk`, using a recording `SessionSignals`.
  - `stopSignalsCarryTheRunsEpoch`.
- `SyncViewModelTest`:
  - `logoutCancelsTheRunThenLogsOut`: order verified on a recording controller or scheduler.
  - `realBackendNeedsAValidSessionToStart`.
- `SyncUiStateTest`: `sessionNotReadyDisablesStartAndExplains`.

- [ ] **Step 1:** Write the failing tests.
- [ ] **Step 2:** Run `./gradlew :instagram:test :app:testDebugUnitTest`. Expected: the new tests fail or don't compile.
- [ ] **Step 3:** Implement items 1–6. Every existing call site of the old `SessionSignals` methods gets updated: the engine, the lab ViewModel, and test fakes.
- [ ] **Step 4:** Run `./gradlew :instagram:test :app:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.**
  - `TODO.md`: tick the M4 gates "engine signals carry the session epoch", "logout stops a running sync" and "a session OK signal". Also tick "redact request lines if a challenge URL is ever requested", noting it as moot: redirects are never followed, the client only requests `/api/v1/` paths on one host (P6), and challenge URLs only ever open in the WebView.
  - `ARCHITECTURE.md` Session row: epochs, SessionGuard, gating.
  - `README.md`: Sync needs "Logged in as" when Mock mode is off.

```bash
git add app/src instagram/src ARCHITECTURE.md TODO.md README.md
git commit -m "feat(session): epoch-checked sync signals, Set-Cookie guard, logout stops sync, sync needs a session"
```

---

### Task 10: On-demand video with a pk-keyed cache (M5)

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`. Add `media3-datasource` and `media3-database` (version ref `media3`), and use them directly.
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/media/VideoCache.kt`, `RealVideoSourceResolver.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/data/media/VideoSourceResolver.kt`, `data/db/MediaDao.kt` (`setVideoLink`), `data/library/LibraryRepository.kt` (Delete library clears the video cache), `sync/SyncEngine.kt` (removed pks are evicted from the video cache), `di/AppContainer.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/viewer/ViewerViewModel.kt`, `ViewerScreen.kt`
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/data/media/RealVideoSourceResolverTest.kt`, `VideoCacheTest.kt`; `ui/viewer/ViewerViewModelTest.kt` (create or extend); `SyncEngineTest` (eviction)

**Interfaces:**
- Produces:

```kotlin
sealed interface VideoSource {
    /** Play [uri]; [cacheKey] (the media pk) makes a refreshed link still hit cached bytes (spec 8.3). */
    data class Play(val uri: Uri, val cacheKey: String) : VideoSource
    /** Show the thumbnail, [message] and Open on Instagram (spec 8.5). */
    data class Unavailable(val message: String) : VideoSource
}

interface VideoSourceResolver {
    /** Null for items that aren't videos. [forceRefresh] skips the freshness check (one retry after a 403/410). */
    suspend fun resolve(media: MediaEntity, forceRefresh: Boolean = false): VideoSource?
}

@UnstableApi
class VideoCache(dir: File, databaseProvider: DatabaseProvider, maxBytes: Long = MAX_BYTES) {
    val cache: Cache
    fun isFullyCached(pk: String): Boolean
    fun remove(pk: String)
    fun clear()
    companion object { const val MAX_BYTES = 512L * 1024 * 1024 }
}

/** Removed items' cached videos go too (engine reconcile, Delete library). */
fun interface MediaEviction { fun evict(pks: List<String>) }

@MediaDao suspend fun setVideoLink(pk: String, url: String?, expiresAt: Long?)  // @Query("UPDATE media SET videoUrl = :url, videoUrlExpiresAt = :expiresAt WHERE pk = :pk")
```

**`RealVideoSourceResolver(client: InstagramClient, pacer: Pacer, mediaDao: MediaDao, cache: VideoCache, signals: SessionSignals, now: () -> Long)`** (spec 8):
- Non-video types → null.
- If `!forceRefresh`, `videoUrl != null` and `videoUrlExpiresAt` is more than `FRESH_MARGIN_MS = 10 * 60_000` away → `Play(Uri.parse(videoUrl), pk)`. No request.
- Otherwise `pacer.interactive { client.mediaInfo(pk) }`:
  - null → `Unavailable("This item is no longer available on Instagram")`;
  - a result with a null `videoUrl` → the same;
  - otherwise `setVideoLink`, then `Play`.
- `PacerRefusal` (cooldown or budget) → `Play` with the stored url when `cache.isFullyCached(pk)` and the url isn't null; otherwise `Unavailable("Video can't load right now: " + refusal user message)`.
- `InstagramException.Transient` or `IOException` → the cached `Play` as above, otherwise `Unavailable("Offline: this video isn't cached yet")`.
- `LoginRequired` and `ChallengeRequired` → signal the session with the current epoch, then `Unavailable("Instagram session needs attention (Sync screen)")`.
- `RateLimited` → `Unavailable("Instagram is limiting requests")`. The Pacer has already armed the cooldown.
- `FakeVideoSourceResolver` returns `Play(rawUri, pk)` for REEL and VIDEO and null otherwise.

**Viewer:**
- Settled items are collected with `collectLatest`, so a superseded resolve is cancelled. After the resolve, the code re-checks that `pagerState.settledPage` still shows the same pk before calling `setMediaItem`.
- `MediaItem.Builder().setUri(uri).setCustomCacheKey(cacheKey).build()`.
- The player is built with `DefaultMediaSourceFactory(CacheDataSource.Factory().setCache(videoCache.cache).setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory().setUserAgent(userAgent))).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR))`.
  - `userAgent` is the WebView default UA, read once.
  - `DefaultHttpDataSource` sends no cookies.
- **Refresh once.** A `Player.Listener.onPlayerError` whose cause chain holds `HttpDataSource.InvalidResponseCodeException` with `responseCode` 403 or 410, on the first error for that pk, calls `resolve(media, forceRefresh = true)` and plays again. On a second error, or any other error, the page shows the thumbnail plus "Can't play this video" and Open on Instagram.
- `Unavailable` shows the thumbnail plus its message plus Open on Instagram. The overlay already has the button: show the message above it.
- **Prefetch.** After settling on page `i`, if item `i + 1` is a video whose link is expired, or expires within the margin, call `resolve(next)` once in a single prefetch job, cancelled when a new page settles. It is never repeated for the same pk.
- `AppContainer`:
  - `videoCache` is lazy: `VideoCache(File(context.cacheDir, "video"), StandaloneDatabaseProvider(context))`, a single instance.
  - `videoResolver` is `if (usesFake) FakeVideoSourceResolver(...) else RealVideoSourceResolver(backend.client, instagramPacer, db.mediaDao(), videoCache, session)`.
  - `SyncEngine` gets `eviction = MediaEviction { videoCache.remove(it) }`. In the engine, `outcome.removedPks` go to `thumbnails::delete` **and** `eviction.evict(...)`.
  - `LibraryRepository.deleteLibrary()` also calls `videoCache.clear()`. It is injected as a `() -> Unit` named `clearVideoCache`, defaulting to `{}` for tests.

**Tests:**
- `RealVideoSourceResolverTest`, with a fake client, an in-memory Pacer, an in-memory `TestDb` and a temp-dir `VideoCache`. Robolectric provides `StandaloneDatabaseProvider`.
  - `resolverRefreshesOnlyExpiredLinks` (Review Focus 5): a fresh link means 0 client calls. An expired link means 1 call, the row is updated, and `Play` uses the new url and `cacheKey == pk`. `forceRefresh` means 1 call even when fresh.
  - `notFoundIsUnavailable`: `mediaInfo` null.
  - `refusalFallsBackToCacheOrMessage`: under a cooldown, a fully cached item with a stored url gives `Play`, and an uncached one gives `Unavailable` mentioning the cooldown. Neither makes a client call.
  - `challengeSignalsTheSession`: a recording `SessionSignals` gets `challengeRequired`.
  - `nonVideoIsNull`.
- `VideoCacheTest`: write a span through `CacheDataSink`/`CacheWriter` from a local file (`FileDataSource`), then `isFullyCached`; `remove`; `clear`.
- `ViewerViewModelTest`: `prefetchesTheNextExpiredLinkOnce` (2 settles on the same page give 1 prefetch call).
- `viewerPlaysOnlyTheSettledItem` (Review Focus 5): pin the `collectLatest` + re-check logic in a plain function, `suspend fun playIfStillSettled(...)`. A unit test drives a fake resolver that suspends until released, settles on page 1, then on page 2, and releases page 1's resolve. The player-facing callback is never called for page 1.
- `SyncEngineTest.reconcileEvictsCachedVideos`: a recording `MediaEviction` gets exactly the removed pks.

- [ ] **Step 1:** Add the media3 catalog entries, then write the failing tests.
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*Video*' --tests '*ViewerViewModelTest' --tests '*SyncEngineTest'`. Expected: compilation fails.
- [ ] **Step 3:** Implement as specified.
- [ ] **Step 4:** Run `./gradlew check`. Expected: PASS, lint included.
- [ ] **Step 5: Emulator check, in Mock mode only.** Install the debug build on the running emulator (`ANDROID_SERIAL=emulator-5554 ./gradlew installDebug`), open a reel in the viewer, and confirm the synthetic clip plays and loops. Swipe to an image and back; it plays again. Capture `adb exec-out screencap -p > <scratchpad>/viewer.png` and name the file in the report. Never switch Mock mode off on the emulator.
- [ ] **Step 6: Commit.**
  - `ARCHITECTURE.md`: a Video row.
  - `TODO.md`: tick the M5 gate "`mediaInfo` needs a not-found outcome" (done in Tasks 2–3), and mark M5's code done (on-phone check pending).
  - `README.md`: a "Watching" paragraph covering on-demand, cache, offline and Open on Instagram.

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src ARCHITECTURE.md TODO.md README.md
git commit -m "feat(video): on-demand links on the interactive lane, pk-keyed cache, refresh once, prefetch"
```

---

### Task 11: Release build (M6)

**Files:**
- Modify: `app/build.gradle.kts` (signing config, profileinstaller), `gradle/libs.versions.toml` (`androidx-profileinstaller`; use the latest stable on Google Maven and say which in the report)
- Create: `app/src/main/baseline-prof.txt`
- Modify: `app/proguard-rules.pro` only if the R8 build or the release run proves a rule is needed. Name each rule's reason in a comment.

**Behaviour:**
- **Signing.** In `app/build.gradle.kts`:

```kotlin
import java.util.Properties

val keystoreProperties = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use(::load) }
}

android {
    signingConfigs {
        if (keystoreProperties != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            // P10: without keystore.properties the release build is signed with the debug key, so installRelease still works.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
```

- **Baseline profile (P9).** Add the dependency `implementation(libs.androidx.profileinstaller)`, and create `app/src/main/baseline-prof.txt`:

```
# App code that runs on every launch and while scrolling: compile it ahead of time (spec 11).
# The Compose libraries ship their own profiles; profileinstaller installs all of them on sideloaded builds.
HSPLio/github/yuriimurha/reels/**->**(**)**
Lio/github/yuriimurha/reels/**;
```

**Steps:**
- [ ] **Step 1:** Run `./gradlew assembleRelease`. Expected: BUILD SUCCESSFUL. Fix only real R8 failures.
- [ ] **Step 2:** Run `./gradlew check`. Expected: PASS.
- [ ] **Step 3: Release smoke on the emulator.**
  - A release build is always the real backend, and the emulator is never logged in.
  - Run `ANDROID_SERIAL=emulator-5554 ./gradlew installRelease`.
  - Launch the app with `adb shell am start -n io.github.yuriimurha.reels/.MainActivity`.
  - Confirm Home shows the empty state, **Open Sync** opens the Sync screen with "Not logged in" and Sync disabled, and Back works.
  - Run `adb logcat -d | grep -E "FATAL|AndroidRuntime"`. Expected: no output for the app.
  - **Do not tap Log in** (it would load Instagram).
  - Uninstall afterwards with `adb uninstall io.github.yuriimurha.reels`, so the emulator's debug install can come back. Then reinstall the debug build: `ANDROID_SERIAL=emulator-5554 ./gradlew installDebug`.
- [ ] **Step 4: Commit.**
  - `README.md`: a new "Release build" section. The owner runs the `keytool -genkeypair -v -keystore reels-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias reels` command themselves, and writes `keystore.properties` with the four keys above. Then they run `installRelease`. Note that switching between debug-key and release-key builds needs an uninstall, which wipes the library and the session.
  - `ARCHITECTURE.md`: a Build row.
  - `TODO.md`: M6 progress.

```bash
git add app/build.gradle.kts gradle/libs.versions.toml app/src/main/baseline-prof.txt app/proguard-rules.pro README.md ARCHITECTURE.md TODO.md
git commit -m "build: release signing from keystore.properties, baseline profile via profileinstaller"
```

---

### Task 12: README and docs pass (M6)

**Files:**
- Modify: `README.md`, `TODO.md`, `PROGRESS.md` (append), `ARCHITECTURE.md`

**Behaviour:** `README.md` describes the finished app end to end, in this order:
1. Warning and what the app is.
2. "What works today": an accurate table for M0–M6, saying which steps still need the owner's phone.
3. One-time Mac setup.
4. Connect the phone.
5. Install and run, covering Mock mode.
6. Log in.
7. First real sync: turn Mock mode off, log in, run the Adapter lab once before the first sync (recommended), then Sync and Full sync, with the budgets and cooldowns explained.
8. Watching videos.
9. Adapter lab and the spike handback.
10. On-phone checklist (M2 + M4 + M5).
11. Release build.
12. Troubleshooting.

Every command is complete and runnable, in its own fenced `bash` block. `PROGRESS.md` gets a 2026-10-07 entry summarising M3–M6. `TODO.md` ticks what is done and lists the owner steps:
- run the M2 checklist;
- run the Adapter lab and hand back the seven answers;
- flip `SAVED_COLLECTION_IDS_CONFIRMED` if Q2 says so;
- confirm `X-IG-App-ID`;
- the first real Full sync;
- video playback on the phone;
- `installRelease` with a real keystore.

- [ ] **Step 1:** Write the docs.
- [ ] **Step 2:** Check every relative link in README (`ls` each path) and that the commands match the Gradle tasks that exist (`./gradlew tasks --all | grep -E "installDebug|installRelease|assembleRelease"`).
- [ ] **Step 3: Commit.**

```bash
git add README.md TODO.md PROGRESS.md ARCHITECTURE.md
git commit -m "docs: README for real sync, video, the adapter lab and the release build"
```
