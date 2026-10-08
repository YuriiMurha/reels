# WebView transport for Instagram API calls: design spec

Status: approved in chat 2026-10-08 (owner chose "through the WebView"). Amends `2026-10-06-saved-reels-android-design.md`
sections 4.3 (data flow), 4.4 (privacy: logging) and 6.1–6.2 (client identity, endpoints). Everything else there stands.

## 1. Why

On the owner's phone, the app's first two Instagram API requests (the login check, `GET /api/v1/users/<id>/info/` via
OkHttp) were answered **HTTP 429 with an empty HTML body in 0.4 s**: not a quota, a refusal. A capture of what the real
mobile website sends from the same WebView (debugger, values of secrets never read) showed our requests differed in
almost every header the site uses:

| | Mobile website (captured) | OkHttp client |
|---|---|---|
| `x-ig-app-id` | `1217981644879628` (mobile web) | `936619743392459` (desktop) |
| `x-asbd-id` | `359341` | absent |
| `sec-fetch-site/mode/dest` | `same-origin` / `cors` / `empty` | absent |
| `sec-ch-ua`, `-mobile`, `-platform`, `-model` | present | absent |
| `x-ig-www-claim`, `x-web-session-id`, `x-ig-max-touch-points` | present | absent |
| network fingerprint (TLS, HTTP/2) | Chromium | OkHttp |

Copying headers into OkHttp cannot fix the fingerprint, and every further refusal costs a cooldown (the next within
24 h costs 24 h). So the API calls move into the browser that already holds the session.

## 2. Goal and success criteria

- Every Instagram **API** request the app sends is a same-origin `fetch()` made by Chromium inside an
  `https://www.instagram.com` page, so cookies, client hints, Sec-Fetch headers, UA and network fingerprint are the
  browser's own.
- Success: the first login check after the change gets a normal answer (Valid / LoggedOut / Challenge), not a 429; the
  Adapter lab's calls get normal answers.
- Unchanged: the Pacer (gaps, breaks, 300 per run, 600 per 24 h, interactive lane, cooldowns), session epochs and the
  in-gate `runSession` check, the error classifier, the parsers, the reconcile guards, the Mock mode backend, the CDN path
  (thumbnails and video stay on the cookieless client).

## 3. Design

### 3.1 Transport seam (`:instagram`, pure JVM)

```kotlin
/** One Instagram API GET. Implementations make exactly one request per call and never retry or follow redirects. */
interface InstagramTransport {
    suspend fun get(pathAndQuery: String): RawReply
}

/** [redirected] is true when the server answered with a redirect that was not followed (its target is unknown). */
data class RawReply(val code: Int, val contentType: String?, val body: String?, val redirected: Boolean = false)
```

- `pathAndQuery` is relative to `https://www.instagram.com/` and built only by `WebEndpoints` (ids stay digits-only).
- `WebInstagramClient`, the session probe and `AdapterLab` take an `InstagramTransport` instead of an `OkHttpClient`.
  Classification moves to one function over a `RawReply`: a redirect is classified like today's unknown 3xx
  (`ChallengeRequired(null)`); a null body (unreadable/cut) uses the existing header-only rules (`classifyUnreadable`);
  otherwise `ErrorClassifier.classify(code, null, contentType, body)`.
- An `OkHttpTransport` (the existing no-redirect, no-retry client) remains for **JVM tests only** (MockWebServer).
  A source-pin test ensures `src/main` of `:app` never wires it for API calls.

### 3.2 WebView transport (`:app`)

- **One hidden WebView per process**, created lazily on the main thread from the application context when the first API
  call needs it; it is never attached to a window. JavaScript on; no `addJavascriptInterface`.
- **Page.** It loads `https://www.instagram.com/` once and waits for the page to finish loading (bounded at 30 s). The
  site's own background requests during that load are not counted by the Pacer: they are what any visit to Instagram
  sends. If the page load ends somewhere else (login page, checkpoint), the transport reports `LoginRequired` /
  `ChallengeRequired(null)` without making the API call.
- **Call.** For each `get`, the app runs a small fixed script via `evaluateJavascript` with a call id and the path. The
  script does:
  `fetch(path, { method: 'GET', credentials: 'same-origin', redirect: 'manual', headers })` where `headers` are the
  ones the site's own code adds: `x-ig-app-id: 1217981644879628`, `x-asbd-id: 359341`,
  `x-requested-with: XMLHttpRequest`, `x-csrftoken` (read from `document.cookie` inside the page),
  `x-ig-www-claim` (the site's `sessionStorage` value, else `0`). The browser adds everything else.
- **Result channel.** The script posts `{id, code, contentType, body, redirected}` back through
  `WebViewCompat.addWebMessageListener` registered for the origin rule `https://www.instagram.com` only. Messages from
  any other origin are ignored. The CSRF token and claim never leave the page.
- **Rules.** Calls are serialised (the Pacer already guarantees one at a time; the transport also refuses a second
  concurrent call). Each call has a 30 s timeout → `Transient`. A `fetch` rejection (network) → `Transient`. Bodies are
  read with `response.text()`; an opaque redirect sets `redirected = true` with a null body.
- **Lifecycle.** Log out, a paste and Delete library first **destroy** the hidden WebView (cancelling any request in
  flight), then change cookies. A new WebView is created on the next call. The process's first call pays the page load.
- **Background.** The sync runs in the foreground worker; the transport hops to the main thread for WebView work and
  suspends the worker's coroutine until the reply. If WebView can't be created (provider missing or updating), the call
  fails `Transient` and the run pauses.

### 3.3 Endpoints and identity (spec 6.1–6.2 amended)

| Call | Endpoint |
|---|---|
| `currentUser` | `api/v1/accounts/edit/web_form_data/` (the website calls it on its edit-profile page; reads `form_data.username`; pk from the `ds_user_id` cookie as today) |
| `collections` | `api/v1/collections/list/` (unchanged) |
| `savedMedia(null)` | `api/v1/feed/saved/posts/` (unchanged) |
| `savedMedia(id)` | `api/v1/feed/collection/{id}/posts/` (unchanged) |
| `mediaInfo` | `api/v1/media/{pk}/info/` (unchanged) |

`WebHeaders.APP_ID` becomes the mobile-web value `1217981644879628`; `X-ASBD-ID` `359341` is added. Both are constants
in `:instagram`, checked against the website again in the Adapter lab. The mobile website renders saved posts through
GraphQL rather than these `api/v1` paths; the lab verifies each path with one request before the first sync.

### 3.4 Logging (spec 4.4 amended)

Debug builds log one line per API call under `InstagramHttp`: `GET <path with digit runs as <n>> -> <code>
(<ms> ms)`, plus the redacted error summary (PR #6's allow-listed fields) for non-2xx replies. No headers are visible to
the app, so none are logged. Release builds log nothing.

### 3.5 What leaves the API path

`SessionGuard` and the OkHttp cookie bridge are no longer used for API calls (Chromium stores cookies itself; the
destroy-on-logout rule replaces the Set-Cookie guard). They stay where the CDN or tests still need them, or are removed
if unused.

## 4. Safety

- Request accounting is unchanged: every API call still passes the Pacer's gate and the in-gate `runSession` check;
  the transport makes exactly one `fetch` per call.
- Chromium may retry a GET on a dropped connection the same way it would for the website itself; accepted.
- The WebView's own page load (once per process) and the site's background requests are new, unpaced traffic. They
  are identical to opening instagram.com in a browser and happen at most once per app run.
- No secret is passed to Kotlin. The message listener only accepts the Instagram origin.

## 5. Testing

- JVM: transport-level unit tests with a fake `InstagramTransport`; the existing MockWebServer suites run through the
  `OkHttpTransport`; classifier tests cover the redirected and null-body cases.
- Emulator (instrumented): the real script against a local test server whose origin is injected for tests only
  (debug test build): one `get` sends exactly one request with the expected headers; a redirect is reported, not
  followed; timeout and destroy-on-logout behave.
- Emulator smoke suite (Mock mode) still passes; the fake backend never creates the WebView.
- Phone (owner), after the current cooldown: Adapter lab → **Who am I** first (one request), then the other lab calls,
  then log in and Sync.

## 6. Out of scope

GraphQL `doc_id` queries; moving the CDN downloads into the WebView; changing pacing numbers.

## 7. Amendments during implementation

The text above is the approved design and stays as written. What changed while it was built, and why (the numbers are
the controller's rulings; `ARCHITECTURE.md` describes the result):

- **Page cap (R89, R91).** At most 3 pages are created per user action (a sync run or Resume, a session check the owner
  asked for, a lab tap), however they end; the count starts again at construction, at `reset()` and at each such action
  (`allowNewAttempts()`). Past the cap every call is `Transient` with no page. This bounds how often the site is loaded
  (3.2 said once, 4 said at most once per app run).
- **R92.** A session check that starts on a stored state that is not Valid resets the transport inside the Pacer's gate,
  right before its request, so it goes out on a fresh page rather than the one that said "login" or "challenge". Never
  while the state is Valid, never before the Pacer has agreed.
- **R93 and R97, the idle close.** The page closes after 5 minutes without a call (`IDLE_MS = 300_000`): it is a live
  single-page app whose own background requests would otherwise run for as long as the process lives. "Once per app run"
  in 3.2 and 4 therefore becomes "once per active period". An idle close gives the page cap back (a page dropped by a
  failure still counts). The viewer's link refresh does not call `allowNewAttempts()`. No request rate or concurrency
  changes; the site's background traffic stops at most 5 minutes after the last call, at the price of one more home-page
  load per active period.
- **R100.** The WebViews keep sending `X-Requested-With: <package>`. `androidx.webkit` 1.17.1's allow-list API is a
  deprecated no-op and unsupported on WebView 145, and overriding the header on the main-frame `loadUrl` alone would make
  the fingerprint inconsistent (the header goes on every request the WebView makes itself; the API `fetch` carries only the
  script's own `XMLHttpRequest`). A known marker, accepted.
- **Frames, and the trust boundary (R109).** Found on the emulator: a frame of another origin gets no `window.igBridge` at
  all (the origin rule keeps it undefined there), so "messages from any other origin are ignored" holds by construction. A
  frame of the same origin does get one, and its messages through it are dropped because the listener accepts only the
  main frame. But that frame can also reach `parent.igBridge` and `top.igBridge`, and the platform credits those messages to
  the main frame, so its forged reply is accepted. The trust boundary is therefore the instagram.com origin, not the main
  frame: any script of that origin can forge a reply or replace `__igFetch`. That is accepted, because it is no more than
  trusting Instagram's own replies; no nonce is added (R89: a same-origin script owns the realm anyway).
- **Also, in 3.2.** The page's location is checked before every call (a client-side redirect or `pushState` can move it),
  and a login or challenge landing is remembered until `reset()`. A home page answered with an HTTP error fails the load:
  429 is `RateLimited` (the cooldown arms with no API request made), any other status `Transient`.
- **3.5.** Nothing was deleted: `SessionGuard`, the cookie bridge and the OkHttp API client are used only by the JVM
  tests, which build their MockWebServer client with them, so removing them is a separate clean-up.
