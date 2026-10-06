# TODO

## Now

- [ ] Finish the design: video strategy, unsave semantics, sync triggers, screens, error handling
- [ ] Write and review the design spec
- [ ] Write the implementation plan (it replaces the provisional sequence below)

## Owner actions

- [ ] Install Android Studio (bundles the JDK, Android SDK and emulator)
- [ ] Decide repo visibility (currently public on GitHub)

## Later: provisional build sequence

1. Gradle project and Compose shell on a mock fixture: collections grid, staggered grid, full-screen pager
2. Room database and repository layer, still on mock data
3. WebView login and session validation
4. Instagram adapter: list collections, page saved media, normalize, cache thumbnails
5. Incremental, resumable, throttled sync in WorkManager, with a hard stop on challenges
6. Video playback and caching
