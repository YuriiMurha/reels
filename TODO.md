# TODO

## Now

- [x] Finish the design: video strategy, unsave semantics, sync triggers, screens, error handling
- [x] Write the design spec: [`docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`](docs/superpowers/specs/2026-10-06-saved-reels-android-design.md)
- [ ] Owner reviews the written spec
- [ ] Write the implementation plan (it replaces the provisional sequence below)

## Owner actions

- [ ] Install Android Studio (bundles the JDK, Android SDK and emulator)
- [ ] Decide repo visibility (currently public on GitHub)
- [ ] Decide the integration flow: PRs on GitHub (needs `gh` installed and logged in) or direct pushes to `main`

## Milestones (spec section 12)

- [ ] M0 Skeleton: both modules build, `./gradlew check` passes, the pre-commit guard blocks a planted secret
- [ ] M1 Mock app: home, grid, viewer, search on the fake library; fake sync through the real worker and Pacer
- [ ] M2 Session: WebView login, cookie bridge, `currentUser()`, paste fallback, session states
- [ ] M3 Adapter spike on the phone: seven questions answered, scrubbed fixtures, real parsers
- [ ] M4 Real sync: quick and full, resume, budgets, cooldowns, challenge hard stop, thumbnails
- [ ] M5 Video: on-demand playback, link refresh, `pk`-keyed cache
- [ ] M6 Polish: release signing, baseline profile, README
