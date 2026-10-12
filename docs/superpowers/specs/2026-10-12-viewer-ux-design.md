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
