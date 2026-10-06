# TODO

## Now

- [x] Finish the design: video strategy, unsave semantics, sync triggers, screens, error handling
- [x] Write the design spec: [`docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`](docs/superpowers/specs/2026-10-06-saved-reels-android-design.md)
- [x] Owner reviews the written spec
- [x] Write the M0–M2 implementation plan: [`docs/superpowers/plans/2026-10-06-saved-reels-m0-m2.md`](docs/superpowers/plans/2026-10-06-saved-reels-m0-m2.md)
- [ ] Owner reviews the plan and picks the execution method

## Owner actions

- [ ] Install Android Studio (bundles the JDK, Android SDK and emulator)
- [ ] Decide repo visibility (currently public on GitHub)
- [ ] Decide the integration flow: PRs on GitHub (needs `gh` installed and logged in) or direct pushes to `main`
- [ ] Log in on the phone and run the M2 checklist (task-18 report)

## Milestones (spec section 12)

- [x] M0 Skeleton: both modules build, `./gradlew check` passes, the pre-commit guard blocks a planted secret
- [x] M1 Mock app: home, grid, viewer, search on the fake library; fake sync through the real worker and Pacer
- [ ] M2 Session: WebView login, cookie bridge, `currentUser()`, paste fallback, session states
- [ ] M3 Adapter spike on the phone: seven questions answered, scrubbed fixtures, real parsers
- [ ] M4 Real sync: quick and full, resume, budgets, cooldowns, challenge hard stop, thumbnails
- [ ] M5 Video: on-demand playback, link refresh, `pk`-keyed cache
- [ ] M6 Polish: release signing, baseline profile, README
