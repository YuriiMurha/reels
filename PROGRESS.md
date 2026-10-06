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
