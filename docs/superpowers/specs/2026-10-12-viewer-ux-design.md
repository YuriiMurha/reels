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
- **R3: Mock mode plays the bundled clip for a carousel's video items (§3.1, Mock mode).** Cache key `{pk}_{position}`, as fake reels do. Why: the fake library's video links name nothing a player can open.
- **R4: an unreadable child leaves the items unknown (amends §3.1 and §5).** A child with an unknown or missing `media_type`, or one that is not an object, or a null, makes that post's `carouselItems` `null` instead of a `ShapeChanged`. Why: before this update such a carousel synced fine (only its cover was read), and stopping a whole sync for an enhancement is a regression. Under R2 the stored items stay, and the viewer falls back to the cover. Cost: a real shape change in the children goes unnoticed except as "1/N", cover only (the Adapter lab still shows the shape).
- **R5: the Room schema files are debug assets (amends §5).** `app/schemas` is added to the debug variant's assets, not to a test source set. Why: AGP 9.4.1 never hands a unit test's assets to Robolectric, and `MigrationTestHelper` reads the schemas from assets. The files hold table definitions only, no data and no secrets, and a release build carries none. Cost: about 26 KB in the debug APK; removable by switching the migration test to open a version-1 file through `ReelsDatabase.build`.
- **R6: evicting an unsaved post also drops its carousel videos (§3.1: items go with the post).** Videos cached under `{pk}_{position}` go by the prefix `{pk}_`, safe because pks are digits only; the post's carousel images go too. Why: they would otherwise sit in the cache until size pressure. Each eviction step runs on its own (`MediaEviction.eachStep`): a failing one skips no other step and no later post.
- **R7: image downloads run on the Pacer's CDN lane (§3.2 and §4).** As thumbnails do. Why: the cookieless CDN fetcher's contract is paced, and this can only lower rates. Cost: 200–800 ms of first-view jitter per image, and waiting behind a running sync's thumbnails.
- **R8: a visit is spent by any outcome (§3.3).** A `Visit`, the one `mediaInfo` a visit may send, is spent by a failure and by a cancelled call too, so "at most one per visit" is strict. The viewer makes one `Visit` per settled post, and runs the fill, the images and the items' video resolves in a coroutine of the post, which an item swipe does not cancel. Why: pacing is a hard rule, and telling a sent request from an unsent cancellation is fragile. Cost: a cancelled fill leaves the post cover-only until it is shown again.
- **R9: the cached-video fallback is the reels' (§3.3).** A carousel video item whose renewal could not be made plays cached bytes for `NO_SESSION`, `REFUSED` and `OFFLINE` only, as the reel resolver does. Why: one rule for "may cached bytes play" across reels and items. Cost: an item whose renewal hit a login or challenge reply, or a rate limit, says unavailable even if its bytes are cached, as a reel does.
- **R10: the viewer's product calls (§3.2).** Item 0 shows the stored cover (the same picture, no download). An image item downloads when it settles: on demand, once a visit, with no prefetch of items. Video items have no poster. An item swipe does not cancel the next post's prefetch. Why: each matches "on demand" and saves CDN requests. Cost: a 200–800 ms placeholder on each newly viewed image, and a dark frame before a video item starts.
- **R11: only a post's first unit starts the next post's prefetch (§4).** An item settle never does. Why: pacing is a hard rule, and item swipes must not become a prefetch trigger. Cost: none visible (the prefetch was decided at the post settle before this update).
- **R12: the notice file is `res/raw/license_material_symbols.txt` (§3.4).** Why: AAPT2 rejects upper case and hyphens in resource names.
- **R13: the monochrome layer cuts the play triangle out of its tile (§3.5).** An even-odd fill, not a second single-colour path. Why: a themed icon tints every path one colour, so an uncut triangle would vanish into its tile. The full-colour layer is as designed.
- **R14: the post and its tile share bounds, not an element (amends §3.6).** The key `media-<pk>` goes on `sharedBounds` with `ResizeMode.scaleToBounds()` and a cross-fade, not on `sharedElement`. Why: on the emulator `sharedElement` zoomed the picture visibly at the start of the shrink (the tile crops, the viewer fits) and cut a playing video straight to the cover. Cost: a cross-fade instead of one continuous picture. A test pins the choice, and reverting is one line.
- **R15: the nav host names its own transitions, and the grid scrolls only when it must (amends §3.6).** Navigation 2.10's `NavHost` uses its own 70 % shrink for the back gesture, so the NavHost sets the predictive transitions itself. The page under the viewer does not slide. Returning, the grid scrolls only when the returned tile is not wholly in view (`revealTile`: a partly hidden tile scrolls just enough, any other goes to the top), where the old code scrolled every returned tile to the top (§3.6 asks for the scroll only when the owner swiped to another post). Why: each keeps the shrink landing on a still, visible tile. Cost: a return no longer scrolls the tile to the top, a change from before this update.

Smaller changes, none of which raises a rate:

- **§3.3, what renews a link.** An image item has no stored expiry, so it is renewed when the CDN refuses its link (a 403, 404 or 410) or it has none; a video item when its stored link has 10 minutes or less left or the player is refused with a 403 or 410. A 429 from the CDN renews nothing.
- **§3.3, new copy.** "Can't load this item right now: <reason>", where the reason is the Pacer's own, when the Pacer refuses (a cooldown or the 24 h budget).
- **§3.2, the viewer's state.** The carousel pager's state is hoisted to the page, to put the dots above the overlay, and is seeded with the visit's item so a rotation does not flip to item 0. `CarouselUiState` has a `loading` flag for the small loading mark. The public `ViewerScreen` delegates to an `internal` overload that takes the player, for tests only.
- **§3.6, the viewer's player effects** now run on `Dispatchers.Main.immediate`: Media3 requires the main thread, and a test's dispatcher once woke an effect on a Room thread.
- **`PacedMediaInfo`** is the reel resolver's `mediaInfo` request and its session gates (R76 and R79), cut out so that a reel and a carousel share them. The resolver's answers are unchanged.
