# Architecture

Current state of the app. Updated in the same commit as the code it describes.

## Status

Pre-implementation (2026-10-06). No app code yet; design in progress.

## Decided so far

| Decision | Choice | Why |
|---|---|---|
| Where it runs | Everything on the Android phone: login, sync, storage, playback. No companion service. | The phone is where reels get watched. A Mac-hosted service would need LAN HTTPS and the Mac switched on. |
| Stack | Native Kotlin + Jetpack Compose | WebView login sharing one cookie jar with the HTTP client, WorkManager background sync, Media3 playback. |
| Auth | In-app WebView login to instagram.com; the app reads cookies from Android's `CookieManager`. Pasted `sessionid` as a fallback. | No password in app code, 2FA and checkpoints handled by Instagram's own UI, one consistent device identity. |
| Instagram access | Private web endpoints, isolated in one adapter module | No official API exposes saved items, and the endpoints change without notice. |

## Superseded from the PRD draft

- PWA + Node companion service over `localhost` → native Android app, no companion.
- Username/password login with 2FA handling → not needed; the WebView login covers it.
