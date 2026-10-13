# Viewer UX: carousels, icon buttons, the app icon and motion: design spec

Status: approved in chat 2026-10-11/12 (the owner chose on-demand carousel loading "A", icon direction "B", "shrink back into the tile" for posts with a page transition between pages, and approved both design parts). Amends `2026-10-06-saved-reels-android-design.md` §5 (data model) and §8 (viewer). Everything else there stands.

## 1. Why

The owner's first real use on the phone (2026-10-11) found four rough edges:

1. **Carousel posts can't be swiped.** The app keeps only a carousel's first image and its item count ("1/N" badge). The other images and videos are never shown.
2. **The mute control is a text button** ("Mute"/"Unmute").
3. **"Open on Instagram" is a text button.**
4. **Navigation has no meaningful motion.** Going back from a post doesn't relate to where it came from, and pages switch with the default fade.

The owner also asked for a custom app icon. The app has none (the system default shows).

## 2. Goals and success criteria

- In the viewer, a carousel post swipes horizontally through all its items. Images show, and video items play under the same mute setting. A position indicator ("2/5" plus dots) shows where you are.
- Mute and Open on Instagram are icon buttons, each with an accessible label and a tooltip.
- The app has its own adaptive icon, with the monochrome layer for themed icons.
- Opening a post grows its grid tile into the viewer. Going back shrinks the post into its tile, with predictive back. Pages use the Material shared-axis X transition, with predictive back.
- **Unchanged:** sync pacing (no new request type during a sync), the session gates, the transport, the Mock-mode behaviour, the vertical post pager and the video cache.

Out of scope: editing or reordering carousels; zoom and pan on images; downloading carousel media during sync (the owner chose on-demand).

## 3. Design

### 3.1 Data: carousel items (`:instagram` + Room)

- **Parsing.** `WebParsers` reads each saved item's `carousel_media` array, which is already in the saved-feed reply the sync downloads. Each child becomes a `RemoteCarouselItem`:
  - `position`, its 0-based index;
  - `type`, IMAGE or VIDEO;
  - `width`, `height`;
  - `imageUrl`, the best candidate from the child's `image_versions2`, chosen by the existing `MediaLinks` rules;
  - `videoUrl` and `videoUrlExpiresAt`, from `video_versions` when it's a video.

  `RemoteMedia` gains `carouselItems: List<RemoteCarouselItem>?`. It is null when the reply has no `carousel_media` (not a carousel, or the shape says nothing). A child the parser can't read is a `ShapeChanged` with its path, as for any item.
- **Storage.** A new Room table `carousel_item`:
  - columns: `parentPk`, `position`, `type`, `width`, `height`, `imageUrl`, `videoUrl`, `videoUrlExpiresAt`;
  - primary key `(parentPk, position)`;
  - a foreign key to `media.pk` with cascade delete, so items go with their post.
- **Database upgrade.** The database goes from version 1 to version 2 with a migration that only creates the table. Every existing row stays.
- **Sync.** When a sync stores a carousel post (QUICK or FULL), it replaces that post's items in the same transaction as the post. A QUICK sync stops at the first known post, so carousels synced before this update get their items from a FULL sync, or lazily (3.3).
- **Mock mode.** `FakeLibrary` generates items for its carousels (count = `carouselCount`, some videos), so the viewer is testable without Instagram.

### 3.2 Viewer: swiping a carousel

- **Paging.** The vertical post pager is unchanged. A carousel post holds a horizontal pager of its items, so swiping up and down moves between posts and swiping left and right moves between items. A horizontal swipe never changes the post.
- **Indicator.** A "2/5" counter (top end) and dots (bottom centre) replace the static "1/N" badge in the viewer. The grid tile keeps its "1/N"-style badge.
- **Images.** An image item loads on demand with Coil from its `imageUrl`. Coil's disk cache keeps it after the first view, so viewed items work offline. Item 0 shows the stored cover thumbnail at once, so the first frame never waits for the network.
- **Videos.** A video item plays through the existing playback: one player, played only when its item is the settled page of the settled post, with the shared mute state and the video cache keyed by `parentPk` plus `position`.
- **Position.** It resets to item 0 each time the post is shown again.

### 3.3 Missing or expired carousel links

- **Lazy fill.** A carousel with no stored items gets them on its first open. The app asks Instagram once for that post through the existing `mediaInfo` call, in the interactive Pacer lane, and stores the items. Meanwhile only the cover shows, with a small loading mark. If the call fails, the cover stays and the indicator reads "1/N".
- **Expiry.** CDN links expire. When an image or video item fails to load with an HTTP error, or its stored expiry has passed, the app renews the whole post's links once per visit, using the same `mediaInfo` call and the same rules as reel video links today: a forbidden or gone reply ends in "Can't load this item", with the Open on Instagram button.
- **Pacing.** No new request type: `mediaInfo` is already used for reel links, through the same interactive lane and budget. At most one renewal per carousel visit.

### 3.4 Icon buttons

- **Mute** becomes an `IconButton`: speaker-off when muted, speaker-on when not, with the content description "Unmute" or "Mute" and the same text as a long-press tooltip. It sits bottom-end over the post.
- **Open on Instagram** becomes an `IconButton` with the Material "open in new" glyph, content description and tooltip "Open on Instagram". Instagram's logo is not used, because the repo is public and the logo is a trademark.
- Both sit on a translucent round scrim, so they stay visible over bright media. The glyphs are vector drawables copied from Material Symbols (Apache 2.0) into `res/drawable`, so the app doesn't add the large material-icons-extended dependency.

### 3.5 App icon

- **Adaptive icon** (`mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`):
  - a teal background layer;
  - a vector foreground: four rounded tiles in a masonry layout, one with a play triangle (the concept the owner chose, B);
  - a `<monochrome>` layer for themed icons.
- Every layer is a vector inside the 108 dp adaptive-icon canvas, with the content in the 66 dp safe zone. No raster files are needed, because minSdk 29 is above 26.
- The manifest sets `android:icon` and `android:roundIcon`.

### 3.6 Motion

- **Post ↔ tile.** A `SharedTransitionLayout` wraps the nav host.
  - The grid tile's thumbnail and the viewer's current post media share the key `media-<pk>`. Opening a post grows the tile into the viewer.
  - Going back shrinks the viewer's current post into its tile. If the owner swiped to another post, the grid first scrolls that tile into view, and the post shrinks into it.
  - Predictive back drives the shrink with the gesture and can be cancelled.
- **Pages.** Every other navigation (home, a collection, search, Sync, the lab, login) uses the Material shared-axis X transition:
  - forward: the new page slides in from the end, with a short fade, while the old one slides out to the start;
  - back: the reverse, driven by predictive back.
- **Settings.** The manifest sets `android:enableOnBackInvokedCallback="true"`. When the system's animator duration scale is 0 (Remove animations), every transition is skipped.

## 4. Safety

- **Pacing.** A sync's requests are unchanged: the items come in the replies it already downloads. Opening an old carousel or renewing an expired one costs one `mediaInfo` request in the interactive lane, at most once per carousel visit. CDN image downloads are on demand, through the existing cookieless CDN client, never with the session.
- **Privacy.** Carousel links are stored like the cover and video links today. They are never logged, and test fixtures are scrubbed.
- **Data.** The migration only adds a table. A failed fill or renewal never deletes the post or its cover.

## 5. Testing

- **JVM:**
  - the `carousel_media` parsing (images, videos, a mixed carousel, missing fields as `ShapeChanged`, no `carousel_media` gives null);
  - the sync storing and replacing items in the post's transaction;
  - the Room migration from 1 to 2 (an exported-schema test: old rows kept, the table created);
  - the lazy fill and the renewal through the interactive lane (once per visit, failure keeps the cover);
  - the ViewModel's item paging state.
- **Compose and UI (Robolectric):**
  - swiping a carousel changes the item, not the post;
  - the indicator text;
  - the icon buttons' content descriptions and toggling;
  - back from the viewer returns to the grid with the tile visible.
- **Emulator:** the existing smoke suite stays green in Mock mode, extended with a carousel swipe and back navigation.
- **Phone (owner):** swipe a real carousel; reopen it in airplane mode (viewed items show, unviewed ones say they can't load); check the icons; check the icon on the launcher (and themed icons on); open a post and go back with the back swipe.

## 6. Amendments during implementation

Added 2026-10-12, while the plan was executed. Each is a ruling taken when the design and the code could not both stand. Where one changes a sentence above, the amendment wins. Each gives the decision and why, and the cost where there is one. `ARCHITECTURE.md` describes the result.

- **R1: carousel images use the CDN fetcher, not Coil's network layer (amends §3.2 and §4).** An image downloads through the existing cookieless CDN `MediaFetcher` (`backend.fetcher`) into a `CarouselImageStore` of files, like thumbnails, and Coil only shows the local file. Why: Coil 3 here has only `coil-compose` and no network fetcher, so its network layer means a new dependency and a second HTTP client that would not share the cookieless CDN client's rules. Files behave like thumbnails: offline after the first view, removed with the post. Cost: a little more code than Coil's cache.
- **R2: an empty item list keeps the stored items (§3.1, Sync).** The sync treats an empty `carouselItems` list like `null`. Why: an empty array is more likely a shape blip than a carousel without items, and wiping the items would force a refill request. Cost: a carousel whose items Instagram really emptied keeps stale items until a renewal replaces them.
- **R3: Mock mode plays the bundled clip for a carousel's video items (§3.1, Mock mode).** As fake reels do; the cache key is `{pk}_{position}` (a fake reel's is its pk). Why: the fake library's video links name nothing a player can open.
- **R4: an unreadable child leaves the items unknown (amends §3.1 and §5).** A child with an unknown or missing `media_type`, or one that is not an object, or a null, makes that post's `carouselItems` `null` instead of a `ShapeChanged`. Why: before this update such a carousel synced fine (only its cover was read), and stopping a whole sync for an enhancement is a regression. Under R2 the stored items stay, and the viewer falls back to the cover. Cost: a real shape change in the children goes unnoticed except as "1/N", cover only (the Adapter lab still shows the shape).
- **R5: the Room schema files are debug assets (amends §5).** `app/schemas` is added to the debug variant's assets, not to a test source set. Why: AGP 9.4.1 never hands a unit test's assets to Robolectric, and `MigrationTestHelper` reads the schemas from assets. The files hold table definitions only, no data and no secrets, and a release build carries none. Cost: about 30 KB of table definitions (under 5 KB compressed) in the debug APK; removable by switching the migration test to open a version-1 file through `ReelsDatabase.build`.
- **R6: evicting an unsaved post also drops its carousel videos (§3.1: items go with the post).** Videos cached under `{pk}_{position}` go by the prefix `{pk}_`, safe because pks are digits only; the post's carousel images go too. Why: they would otherwise sit in the cache until size pressure. Each eviction step runs on its own (`MediaEviction.eachStep`): a failing one skips no other step and no later post.
- **R7: image downloads run on the Pacer's CDN lane (§3.2 and §4).** As thumbnails do. Why: the cookieless CDN fetcher's contract is paced, and this can only lower rates. Cost: 200–800 ms of first-view jitter per image, and waiting behind a running sync's thumbnails.
- **R8: a visit is spent by any outcome (§3.3).** A `Visit`, the one `mediaInfo` a visit may send, is spent by a failure and by a cancelled call too, so "at most one per visit" is strict. The viewer makes one `Visit` per settled post, and runs the fill, the images and the items' video resolves in a coroutine of the post, which an item swipe does not cancel. Why: pacing is a hard rule, and telling a sent request from an unsent cancellation is fragile. Cost: a cancelled fill leaves the post cover-only until it is shown again.
- **R9: the cached-video fallback is the reels' (§3.3).** A carousel video item whose renewal could not be made plays cached bytes for `NO_SESSION`, `REFUSED` and `OFFLINE` only, as the reel resolver does. Why: one rule for "may cached bytes play" across reels and items. Cost: an item whose renewal hit a login or challenge reply, or a rate limit, says unavailable even if its bytes are cached, as a reel does.
- **R10: the viewer's product calls (§3.2).** Item 0 shows the stored cover (the same picture, no download). An image item downloads when it settles: on demand, once a visit, with no prefetch of items. Video items have no poster (reversed by R26 below: a video item now shows its poster until its first frame). An item swipe does not cancel the next post's prefetch. Why: each matches "on demand" and saves CDN requests. Cost: a 200–800 ms placeholder on each newly viewed image, and a dark frame before a video item starts.
- **R11: only a post's first unit starts the next post's prefetch (§4).** An item settle never does. Why: pacing is a hard rule, and item swipes must not become a prefetch trigger. Cost: none visible (the prefetch was decided at the post settle before this update).
- **R12: the notice file is `res/raw/license_material_symbols.txt` (§3.4).** Why: AAPT2 rejects upper case and hyphens in resource names.
- **R13: the monochrome layer cuts the play triangle out of its tile (§3.5).** An even-odd fill, not a second single-colour path. Why: a themed icon tints every path one colour, so an uncut triangle would vanish into its tile. The full-colour layer is as designed.
- **R14: the post and its tile share bounds, not an element (amends §3.6).** The key `media-<pk>` goes on `sharedBounds` with `ResizeMode.scaleToBounds()` and a cross-fade, not on `sharedElement`. Why: on the emulator `sharedElement` zoomed the picture visibly at the start of the shrink (the tile crops, the viewer fits) and cut a playing video straight to the cover. Cost: a cross-fade instead of one continuous picture. A test pins the choice, and reverting is one line.
- **R15: the nav host names its own transitions, and the grid scrolls only when it must (amends §3.6).** Navigation 2.10's `NavHost` uses its own 70 % shrink for the back gesture, so the NavHost sets the predictive transitions itself. The page under the viewer does not slide. Returning, the grid scrolls only when the returned tile is not wholly in view (`revealTile`: a partly hidden tile scrolls just enough, any other goes to the top), where the old code scrolled every returned tile to the top (§3.6 asks for the scroll only when the owner swiped to another post). Why: each keeps the shrink landing on a still, visible tile. Cost: a return no longer scrolls the tile to the top, a change from before this update.

R16 to R22 come from the final review (2026-10-13). R16, R20 and R21 change the code, each with a test that fails without it; R17, R18, R19 and R22 are decisions about the review, the history or what stays as it is.

- **R16: an offline video item says it is offline (§3.3, copy).** A carousel video item the player cannot reach (no connection, or a timeout) says "Offline: this item isn't cached yet" unless every byte of it is cached under `{pk}_{position}`, as an unviewed image does; before, a video item whose stored link was still good said "Can't load this item" offline. Every other failure keeps "Can't load this item". Why: the copy promises the offline message for any item not viewed before. Cost: a small viewer change.
- **R17: Task 7's own review is the final review's docs lens.** Why: the docs describe the whole update, which the final review reads anyway. (Process only.)
- **R18: the update's own PROGRESS entry may be amended before the merge.** Why: it is not yet published history (the squash lands it once); "append-only" protects merged entries.
- **R19: what the pacing lens left as it is (§3.3, §4).** A failed or gone fill is not remembered across visits; the viewer has no backoff for the CDN's 429 on item images; an image refused with a 404 renews, a video's 404 does not. Why: each is bounded by the owner's own swipes on the paced lanes, matches how reel videos behave, and §3.3 allows the 404 split. Cost: revisiting a gone carousel spends one `mediaInfo` a visit.
- **R20: items of another shape drop the post's cached item media (§3.1, §3.2).** After the sync's page transaction, or a renewal's store, commits new items whose count, or whose type at any position, differs from the stored ones (a slide deleted, an image swapped for a video), the post's downloaded item images and its `{pk}_*` item videos are removed: the sync's after the commit, as the reconcile's eviction. The same items with renewed links keep everything, so viewed items still show offline. Why: images and item videos are keyed by position, so a shifted position showed (or played) the deleted item. Comparing links would also catch a reorder, but it rests on CDN paths staying the same when Instagram signs them again, which cannot be checked without contacting Instagram, and if wrong it would wipe every carousel's offline images on each sync. Cost: after a reorder that keeps every position's type, the moved positions show the old picture until the post is unsaved or Delete library runs (a known gap in `TODO.md`); and the sync reads each carousel's stored items once more, locally.
- **R21: `versionCode` tracks the schema (§5).** `versionCode` is 2, the Room version, and a guard test keeps it at least the database version, with no destructive fallback. A build from before a schema change is then refused by Android (`INSTALL_FAILED_VERSION_DOWNGRADE`) instead of installing over the upgraded library and failing at every start; the README says never to uninstall or clear data to get past it (that deletes the library and the session) but to install a current build. Why: older checkouts exist (the main checkout, a branch cut before this update). Cost: none.
- **R22: a 403 on a spent visit may still cancel the next post's prefetch (§4, R79).** The final review's pacing lens raised it, and its refuter showed it does not happen on the real stack in the reported order. Why: the only correct guard (a finished visit) adds code for a request that needs a 403 at least 2 s after a fresh fill. Cost: in that rare order the next reel's settle sends its `mediaInfo` again, R79's documented cost.

R23 comes from the owner's use of the viewer update on the phone (2026-10-13). It changes the code, with tests that fail without it.

- **R23: an open carousel fetches its other pictures ahead (amends §3.2, R10 and §4).** Why: on the phone (2026-10-13) swiping through a carousel showed a black placeholder on every item, because an item's picture downloaded only when the item settled: 200–800 ms of the CDN lane's jitter, then the download. The owner first approved fetching the next item's picture when an item settles, then, having tried the viewer again on the phone, asked to load the whole carousel. The decision: once a carousel post is the settled post and its items are known (stored, or right after the fill), every IMAGE item except item 0 (the cover) downloads ahead, through the same cookieless CDN fetcher and the Pacer's CDN lane into `CarouselImageStore`, as one queue with one download out at a time: the items after the settled one in order, then those before it, the nearest first. An item settle reorders what is left (the items after it first) without restarting the queue or cancelling the download that is out; leaving the post cancels it with the visit (R8's post scope). An item whose picture is out when it settles waits for that download instead of fetching it again; a picture that does not come is skipped without a word, and the item's own load deals with it when it settles, with its own renewal. Video items are not fetched ahead: they stream and are cached as they play, as before. Why this shape: it never fills, the lane's concurrency and jitter are unchanged, and one download at a time leaves the lane's second slot for the item on screen. Cost: CDN downloads for items the owner may never swipe to, at most one request a visit per refused link (R25); a link that met the CDN's 429, or offline, may be asked again by the item's own load. R25 below amends it: a refused link ahead may spend the visit's one renewal, and the queue stops on the CDN's 429 or offline, so the queue sends a limited or dead CDN one request a visit (the item on screen's own load is apart from it).

R24 comes from the owner's first use of the motion on the phone (2026-10-13).

- **R24: pages push instead of the shared axis (amends §3.6, Pages).** Every page change but the viewer's is a push, as in Instagram. Forward, the new page slides in from the end edge, its whole width, over the page left, which drifts a third of its width towards the start. Back, and the back gesture, is the exact reverse: the page closed slides out to the end edge over the previous page, which comes back from a third. 300 ms with `FastOutSlowInEasing`; the gesture seeks the same slides with linear timing, so the page moves with the finger. Nothing fades. The moving page is on top in both directions with no code of ours: Navigation 2.10's `NavHost` gives the page coming back on a pop, and under the gesture, the z-index of the page closed minus one, and a test reads it from the screen's pixels. The viewer's fade, the shared bounds (R14), the page under the viewer (R15) and no motion with animations off are unchanged. Why: on the phone (gesture navigation) the grow and shrink and the gestures were fine, but the owner did not like the shared axis's 10 % slide and fade between pages and asked for "just slide, or whatever is used in the real Instagram". Cost: the page left is not dimmed under the new one, as Instagram's and iOS's are; and letting go of the gesture finishes the slide at the gesture's constant pace (the rest of the 300 ms) rather than easing into the end, because Navigation continues the transition the gesture seeked.

R25 comes from the review of R23's branch (2026-10-13), as the controller's ruling on its finding that refused links were asked again on every visit. It changes the code, with tests that fail without it.

- **R25: a refused link ahead may spend the visit's one renewal; a refused link is asked for once a visit (amends R23).** When a picture ahead is refused (a 4xx, or an item with no link), the queue asks for the visit's ONE renewal, by the same path, lane and session gates as an item's own load: the first call that needs it sends it, every later call shares what it brought. If it brings the item a new link, the item loads from it and the queue goes on with the fresh links (R20's shape check applies to the renewed items). If the renewal was spent already (on the links stored now), or failed, the queue stops; it does not skip and move on. Within a visit, a link the CDN refused is never requested again: an item whose link was refused ahead goes straight to the visit's renewal, or to "Can't load this item" when that cannot help. Why: stored links age (a Sync never refreshes an old post's), so without this, loading a whole carousel ahead did nothing for any carousel older than its links, and the queue sent refused requests on every visit, and twice for an item the owner then swiped to. Cost: R25 adds a request. A visit to a carousel with old links that the owner opens and never swipes sent no `mediaInfo` before; it now sends its one as it opens. The bound is unchanged: at most one `mediaInfo` a visit, on the interactive lane, the fill, the queue's and the items' own renewals together, also when they are refused at the same moment.

Smaller changes, none of which raises a rate:

- **§3.2, the queue stops on a CDN 429 or offline (R23's review, P1).** The first 429 (or a failure to reach the CDN) of a picture ahead ends the queue for the visit, as the sync stops its thumbnails on its first 429, instead of asking for every other item one by one. The CDN's limit stays per visit: the sync's is per run, and nothing shares one.
- **§3.3, no picture of a link no longer stored (R23's review, P2).** A download, ahead or on demand, reads the item's stored link again before it writes, and writes nothing when the items were replaced while it was on the way (a renewal or a sync, whose R20 drop may already have run): those bytes may be another slide's. It then loads the item as stored now, once.

- **§3.2, the settle gate (final review, C1).** A settle is reported only when the list's row at the settled index and the pager's laid-out key there agree (a placeholder row always settles). A sync adding or removing a post above the viewed one changes the list at once while the pager re-keys in its next measure; the viewer then reset the carousel to item 1 and handed the player the post that slid in. Now the move arrives as the same post at its new index, which keeps its visit.
- **§3.3, the removed-post guard (final review, D3).** The repository treats a post that a sync marked removed as gone too: it writes no image file for it (the reconcile's eviction has already run) and sends no request for it.
- **§3.3, what renews a link.** An image item has no stored expiry, so it is renewed when the CDN refuses its link (a 403, 404 or 410) or it has none; a video item when its stored link is missing, names no expiry, or has 10 minutes or less left, or the player is refused with a 403 or 410, or (R26, R27) its start written ahead was refused with a 403 or 410. A 429 from the CDN renews nothing, and neither does a 404 for a video item.
- **§3.3, new copy.** "Can't load this item right now: <reason>", where the reason is the Pacer's own, when the Pacer refuses (a cooldown or the 24 h budget).
- **§3.2, the viewer's state.** The carousel pager's state is hoisted to the page, to put the dots above the overlay, and is seeded with the visit's item so a rotation does not flip to item 0. `CarouselUiState` has a `loading` flag for the small loading mark. The public `ViewerScreen` delegates to an `internal` overload that takes the player, for tests only.
- **§3.6, the viewer's player effects** now run on `Dispatchers.Main.immediate` (`onMainThread`): Media3 requires the main thread, and a test's dispatcher once woke an effect on a Room thread. A test pins it with a player that checks its thread on every call.
- **§3.6, a tile taller than the grid** (landscape, a short split-screen window) aligns by its top on the way back, never scrolled past it.
- **`PacedMediaInfo`** is the reel resolver's `mediaInfo` request and its session gates (R76 and R79), cut out so that a reel and a carousel share them. The resolver's answers are unchanged.

R26 comes from the owner's test of R23 on the phone (2026-10-13). It changes the code, with tests that fail without it.

- **R26: a carousel's video items start sooner: a poster until the first frame, and their start written ahead (amends §3.2,
  R10, R23 and §4; it reverses R10's "Video items have no poster").** Why: on the phone (2026-10-13) a carousel's pictures
  were there as they slid in (R23), but a video item stayed black until it played: it began to stream only when it settled,
  the player waited for its start buffer, and nothing covered the surface until the first frame, because R10 gave video
  items no poster. The app sent no Instagram request during that test (the links were fresh from a Full sync), so the delay
  was the CDN stream and the start buffer. The decision, in three parts:
  1. **The start buffer.** The viewer's one player is built with a `DefaultLoadControl` that starts a streamed video once
     1 s is buffered and goes on after a stall once 2 s is, every other value Media3's default, for every video, reels
     included. Media3 1.11.1's own streaming defaults turned out to be these two values already (older ExoPlayer releases
     used 2.5 s and 5 s), so this part pins them rather than changing today's playback; the start-up delay was the stream
     and the black surface.
  2. **A poster for video items.** A video item's `imageUrl` (its preview frame) is fetched by the same queue as the
     pictures, at the same place in its order, into the same `CarouselImageStore` file `{pk}_{position}` (a video item has no
     other picture there), with every rule a picture has: R20's drop, D3, P2, R25's renewal and refusal, and P1's stop on a
     429 or offline. An item's own load fetches its poster when it settles before the queue got to it; a poster that does not
     come says nothing. The poster is the shutter of Media3's `ContentFrame` for that item's page, so it shows until the
     player renders that item's first frame (the shutter's own first-frame event), then hides; every item's page has its own
     frame, so no item's poster covers another's video. When the video fails, the poster stays under the message.
  3. **The start of each video item written ahead.** Once no picture is left, the same queue writes the first 1 MiB of each
     video item but the settled one into the video cache under `{pk}_{position}`, with Media3's `CacheWriter` and a bounded
     request, through the player's own cookieless stack, in the queue's order, one write at a time inside `pacer.cdn { }`.
     Only a link the item would play as it is (more than 10 minutes left) is used: a stale one is skipped and never renewed,
     and no `mediaInfo` is ever sent for a start. A start already cached is not fetched. A link the CDN refuses is skipped
     quietly and not asked again in the visit by a start; what the item's own play then does is R27's. The CDN's 429, offline
     or a failing cache stops the queue; leaving the post cancels the write, which leaves the cache consistent. The item the
     owner settles on stops its write first (what it wrote stays, and the player reads it first), waiting 300 ms at most: a
     write that has not stopped by then finishes stopping on its own while the player reads past its lock from the network
     (M5). The one exception is a write whose link the item no longer has (N-M1): its bytes are another video's, so the item
     waits until it has stopped and been checked. A start written while its link was replaced, or its post unsaved, is
     dropped again (P2, D3), whether the write finished or was cancelled. Mock mode writes none: its clip is in the app (and
     a copy of the short clip would make every Mock-mode item read as fully cached).

  `CacheWriter` needed one guard: it opens an unbounded request (the whole video) whenever its bounded one fails, which for a
  refused link, a 429, no connection or a failing cache would be a second request, and with the player's
  `FLAG_IGNORE_CACHE_ON_ERROR` a download of the whole video that caches nothing. The write's upstream allows one try and
  refuses an unbounded request outright (`OneTryUpstream`), and the write's stack does not ignore a cache error.

  Pacing: no request to Instagram that the visit would not already allow, and none from a pre-load itself; no new request
  type; still at most one `mediaInfo` a visit, every path together (a refused poster may spend it by R25's rule, a start
  never; R27 says when an item's own play after a refused start does). The posters and the starts are extra CDN downloads on
  the existing CDN lane, one at a time, with the lane's concurrency (2), jitter (0.2–0.8 s) and rate unchanged; the player's
  own stream stays off the lane, as before. Cost: about 1 MB of data per video item of every carousel opened, even for items
  the owner never watches, and one small poster image each (the CDN request R10 had saved). And the starts share the one
  512 MiB least-recently-used video cache with the videos the owner watched: starts written ahead and never watched are newer than an
  old watched video, so enough carousels opened (about 27 visits to carousels of 20 video items) push the oldest watched
  videos out, and those no longer play offline (R16 then says "isn't cached yet" for an item the owner did watch).

R27 is the controller's ruling on R26's review (2026-10-13). It changes the code, with tests that fail without it.

- **R27: a refused video start sends the item's own play to the visit's renewal only for a 403 or 410 (amends R26).** A
  start refused with a 403 or 410, the answers the player's own error path renews a link on (R19, R9), keeps the link out of
  the item's play: when the owner settles on the item, it goes to the visit's renewal, as it would have after the player's
  own 403 or 410, one CDN request sooner. Any other 4xx but 429 (a 404, a 408, ...) is passed quietly by the queue, not asked
  again by a start in the visit, and changes nothing for the play: the player plays the stored link, and a 404 there renews
  nothing, as R19 decided. A 429 still stops the queue. Why: R19 sends no request for a video's 404, and a pre-load must not
  change what a play costs; under R26 a 404 ahead spent the visit's `mediaInfo` on a newly signed link to the same dead
  object. Cost: none; a 404'd item asks the CDN once more when it plays, as before R26.

Smaller changes from R26's review, none of which raises a rate:

- **I1, a failing cache.** With the disk full (SQLite's error, wrapped by Media3 as a cache error) or a cache that failed to
  start, `CacheWriter`'s unbounded fallback, through the player's `FLAG_IGNORE_CACHE_ON_ERROR`, downloaded the whole video,
  cached nothing and answered "written", for every video item of every carousel opened. Now the write's upstream refuses any
  unbounded request, its stack does not ignore a cache error, and a cache error stops the queue for the visit.
- **M2, a cancelled write.** A write cancelled part way keeps what it wrote, so it gets the same re-check as a finished one:
  the bytes go when the item's link was replaced meanwhile or the post left the library.
- **M4, a video item with no preview frame.** It has no poster: the queue passes it without a renewal and goes on to the
  starts, and its own load asks nothing. Before, it spent the visit's `mediaInfo` on every visit and its stop ended the
  starts phase.
- **M5, a prompt give-way.** Cancelling a write closes its request from the cancelling side, so a write waiting for the CDN's
  answer stops at once, and the settled item waits for its write 300 ms at most whatever the network does (a body read that
  stalls inside the platform's stream can outlast the close; it finishes stopping on its own, and the player reads past its
  lock meanwhile). N-M1 below makes one exception.
- **M3, the shared cache.** R26's cost now says that starts written ahead share the 512 MiB cache with watched videos and can
  push the oldest of them out (above).

The fix round's re-review (2026-10-14) passed it and left two Minors, fixed after the merge. Neither raises a rate.

- **N-M1, a late write of a replaced link.** With M5's cap, a write that outlasts 300 ms commits what it wrote when it
  stops, after the player has started. When the item's link was replaced while it was written (a reshape, a sync, the
  renewal), those are another video's bytes, and M2's check that drops them came later still: a player reading past the
  write's lock could meet them at its next look at the cache, in the middle of its own video. Now the item's source is
  handed out only once such a write has stopped and been checked, however long that takes (the connection's 8 s timeouts
  at worst); a write of the very link the item plays is the same video, so the cap still holds for it. The check of a
  finished write no longer gives way to a cancel either.
- **N-M2, a cancel during the handshake.** A write cancelled while its connection was still being made (a TCP or TLS
  handshake) closed nothing, and its connection stayed open after the request. The abort now wraps the HTTP data source
  itself, and an open the abort came during closes its connection once it returns. The platform's blocking connect cannot
  be cut, so the one bounded request still goes out once the handshake is done, and the write still waits for the
  handshake; it is then closed at once.
