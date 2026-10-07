# TODO

## Now

- [x] Finish the design: video strategy, unsave semantics, sync triggers, screens, error handling
- [x] Write the design spec: [`docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`](docs/superpowers/specs/2026-10-06-saved-reels-android-design.md)
- [x] Owner reviews the written spec
- [x] Write the M0–M2 implementation plan: [`docs/superpowers/plans/2026-10-06-saved-reels-m0-m2.md`](docs/superpowers/plans/2026-10-06-saved-reels-m0-m2.md)
- [x] Owner reviews the plan and picks the execution method (subagent-driven)

## Owner actions

- [x] Install Android Studio (bundles the JDK, Android SDK and emulator)
- [ ] Decide repo visibility (currently public on GitHub)
- [x] Decide the integration flow: PRs on GitHub (PR #1)
- [ ] Log in on the phone and run the M2 checklist (README section 5)
  - [ ] In `chrome://inspect`, see whether the WebView sends `X-Requested-With: io.github.yuriimurha.reels`
  - [ ] Note whether `sessionid` changes during a checkpoint flow

## Milestones (spec section 12)

- [x] M0 Skeleton: both modules build, `./gradlew check` passes, the pre-commit guard blocks a planted secret
- [x] M1 Mock app: home, grid, viewer, search on the fake library; fake sync through the real worker and Pacer
- [ ] M2 Session: WebView login, cookie bridge, `currentUser()`, paste fallback, session states
- [ ] M3 Adapter spike on the phone: seven questions answered, scrubbed fixtures, real parsers
  - [x] Gate: the pre-commit hook catches JSON/Netscape cookie dumps, `csrftoken` and `++` diff lines before scrubbed fixtures are committed (with the spec-10 fixture guard test)
  - [ ] Gate: offset-cursor stability
  - [x] Gate (M3/M4, strategy A): limit `deleteRealMembershipsExcept` to `collectionId IN (:known)`; an empty `collections()` list while live collections exist means `STOPPED_SHAPE`
- [ ] M4 Real sync: quick and full, resume, budgets, cooldowns, challenge hard stop, thumbnails
  - [x] Gate: truncated-resampling gaps instead of clamping (today about 18 % of gaps are exactly 4,000 ms)
  - [x] Gate: seed `lastRequestEndedAt` from `RequestLog`
  - [x] Gate: `Backend.Real` reuses `AppContainer.instagramPacer`, with a guard test, and the Sync screen shows that Pacer
  - [x] Gate: a proportional reconcile guard (P7: a FULL All Saved reconcile that would remove at least 20 items and more than half of the live ones is refused as `STOPPED_SHAPE`)
  - [ ] Gate: engine signals carry the session epoch
  - [ ] Gate: logout stops a running sync
  - [ ] Gate: a "session OK" signal from a sync's `currentUser`
  - [x] Gate: CDN `RateLimited`/timeout handling inside the thumbnail try (a 429 stops thumbnail fetches for the rest of the run; a download timeout is one failed thumbnail)
  - [x] Gate: 421 coalesced-connection re-send. API client: one host per client (P6, pinned in Task 3). CDN client (many hosts, so P6 doesn't cover it): `HttpClientFactory.createCdn` turns a 421 into an IOException, strips a 503 `Retry-After`, follows no redirects and does no connection retries, so every download is one request (pinned by MockWebServer tests)
  - [ ] Gate: redact request lines if a challenge URL is ever requested through OkHttp
- [ ] M5 Video: on-demand playback, link refresh, `pk`-keyed cache
  - [ ] Gate: `mediaInfo` needs a not-found outcome
- [ ] M6 Polish: release signing, baseline profile, README
