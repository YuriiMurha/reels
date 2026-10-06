# Progress

Append-only log. Newest entry at the bottom; never edit past entries.

## 2026-10-06: Project setup

- Reviewed the PRD draft and changed two locked decisions: the app runs entirely on the Android phone
  (no Node companion, no PWA) and is built natively in Kotlin + Jetpack Compose.
- Login moves to an in-app WebView that reads Instagram's cookies; pasting `sessionid` stays as a fallback.
- Added `.gitignore` (Android/Gradle plus session and secret patterns), a project `CLAUDE.md`, and these docs.
- Found the GitHub repo is public; flagged to the owner.
