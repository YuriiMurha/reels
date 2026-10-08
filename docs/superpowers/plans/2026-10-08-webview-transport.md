# WebView Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send every Instagram API request as a same-origin `fetch()` from a hidden WebView on `https://www.instagram.com`, so Instagram sees its own website's headers and network fingerprint.

**Architecture:** A transport seam in `:instagram` (`InstagramTransport` returning a `RawReply`) replaces the direct OkHttp calls in `WebInstagramClient`, the session probe and `AdapterLab`. `:app` implements it with `WebViewTransport`, built on a small `WebPage` abstraction (the real one wraps a hidden `WebView`; a fake one drives unit tests). The OkHttp transport remains for JVM tests only.

**Tech Stack:** As before, plus `androidx.webkit` (`WebViewCompat.addWebMessageListener`). Kotlin 2.4.20, AGP 9.4.1, OkHttp 5.5.0 (JVM tests), Robolectric 4.17, androidx.test for the emulator suite.

**Spec:** `docs/superpowers/specs/2026-10-08-webview-transport-design.md`. It amends the base spec `docs/superpowers/specs/2026-10-06-saved-reels-android-design.md`.

## Global Constraints

- Every Instagram API call still goes through the single Conservative `instagramPacer` and its in-gate `runSession` check. The transport makes exactly one `fetch` per call. No rate, budget or concurrency rises.
- No agent ever contacts Instagram. Tests use fakes, MockWebServer, or a local test server. The emulator stays in Mock mode or logged out. Only the owner's phone talks to Instagram.
- No secret (cookie, CSRF token, www-claim) is passed from the page to Kotlin or logged. The message listener only accepts the origin `https://www.instagram.com` (tests may inject a local origin).
- No `addJavascriptInterface` anywhere.
- Header constants, verbatim from the spec: `x-ig-app-id: 1217981644879628`, `x-asbd-id: 359341`, `x-requested-with: XMLHttpRequest`.
- The login-check endpoint is `api/v1/accounts/edit/web_form_data/`, reading `form_data.username`; the pk comes from the `ds_user_id` cookie.
- Timeouts: the page load is bounded at 30 s; each call at 30 s. Expiry → `InstagramException.Transient`.
- Redirects are never followed. A redirect is classified as `ChallengeRequired(null)`.
- The CDN path (thumbnails, video) is unchanged.
- Package root `io.github.yuriimurha.reels`. `:instagram` stays pure JVM.
- Stage explicit paths only. Commit trailers name the authoring model. Never use `--no-verify`. Build session-like strings in tests from parts.
- Env for Gradle: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk";`

## Review Focus

1. **The hidden page lands on the login form or a checkpoint** (a logged-out jar, a flagged account). Expected: the call fails `LoginRequired` / `ChallengeRequired(null)` with NO API fetch. Pinned in Task 2, `aPageThatLandsOnLoginMakesNoCall`.
2. **A second call while one is in flight** (lab, viewer and sync at once). The Pacer serialises calls, but the transport must not cross-wire replies. Expected: replies are matched by call id; a concurrent call is refused with `Transient`. Pinned in Task 2, `repliesAreMatchedByIdAndConcurrentCallsAreRefused`.
3. **Log out while a fetch is in flight.** Expected: the page is destroyed first, the waiting call ends `Transient`, and no reply for it is accepted later. Pinned in Task 2, `destroyCancelsTheCallInFlight`, and in Task 3, `logoutDestroysThePageBeforeClearingCookies`.
4. **A message from another origin, or a malformed message** (a frame, an ad, a tampered script). Expected: ignored, never parsed into a reply. Pinned in Task 4, `messagesFromOtherOriginsAreIgnored` (on the emulator), and in Task 2, `aMalformedMessageIsIgnored`.
5. **The WebView provider is missing or updating, so the page can't be created.** Expected: the call fails `Transient`, the run pauses, and nothing crashes. Pinned in Task 2, `aPageThatCannotBeCreatedFailsTransient`.

---

## File structure

| File | Change | Responsibility |
|---|---|---|
| `instagram/.../web/InstagramTransport.kt` | create | `InstagramTransport`, `RawReply`, `classifyReply`, `jsonOrThrow` |
| `instagram/.../web/OkHttpTransport.kt` | create | the OkHttp implementation (JVM tests; used by MockWebServer suites) |
| `instagram/.../web/WebEndpoints.kt` | modify | `currentUser()` → `accounts/edit/web_form_data/`; `relative(url)` helper |
| `instagram/.../web/WebHeaders.kt` | modify | `APP_ID = "1217981644879628"`, `ASBD_ID = "359341"` constants; OkHttp interceptor adds `X-ASBD-ID` |
| `instagram/.../web/WebSessionProbe.kt` | modify | uses the transport and the new endpoint |
| `instagram/.../web/WebInstagramClient.kt` | modify | takes `transport: () -> InstagramTransport` |
| `instagram/.../lab/AdapterLab.kt` | modify | takes `transport: () -> InstagramTransport` |
| `instagram/.../web/ErrorReplySummary.kt` | create | the redacted one-line summary, shared by `ErrorReplyLogger` and the WebView transport |
| `app/.../transport/WebPage.kt` | create | `WebPage` interface plus `AndroidWebPage` (the hidden WebView) |
| `app/.../transport/WebViewTransport.kt` | create | call ids, timeouts, single in-flight, landing checks, destroy |
| `app/src/main/assets/ig_fetch.js` | create | the fixed fetch script |
| `app/.../di/AppContainer.kt` | modify | wires `WebViewTransport` into the client, probe and lab; destroy hooks |
| `app/.../session/SessionRepository.kt` | modify | a `beforeSessionChange` hook called first in logout and paste |
| `app/.../data/library/LibraryRepository.kt` | modify | the same hook at the start of Delete library |

---

### Task 1: The transport seam in `:instagram`

**Files:**
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/InstagramTransport.kt`, `OkHttpTransport.kt`, `ErrorReplySummary.kt`
- Modify: `WebEndpoints.kt`, `WebHeaders.kt`, `WebSessionProbe.kt`, `WebInstagramClient.kt`, `lab/AdapterLab.kt`, `ErrorReplyLogger.kt` (it delegates to `ErrorReplySummary`), `WebJson.kt` (keep `parseObject`, `string`, `int`, `long`, `idString`, `classifyUnreadable`; remove the OkHttp `getJsonObject` once nothing uses it)
- Test: update every existing `:instagram` test that built these classes from an `OkHttpClient` so it passes `OkHttpTransport`. Add `InstagramTransportTest.kt` and `WebSessionProbeTest` cases.

**Interfaces (produced):**

```kotlin
package io.github.yuriimurha.reels.instagram.web

/** One Instagram API GET. Implementations make exactly one request per call and never retry or follow redirects. */
interface InstagramTransport {
    /** [pathAndQuery] is relative to https://www.instagram.com/ (no leading slash), built only by [WebEndpoints]. */
    suspend fun get(pathAndQuery: String): RawReply
}

/**
 * One reply. [body] is null when it could not be read. [redirected] is true for a redirect that was not followed (its
 * target is unknown to a browser fetch). toString() never prints the body.
 */
class RawReply(val code: Int, val contentType: String?, val body: String?, val redirected: Boolean = false) {
    override fun toString() = "RawReply(code=$code, redirected=$redirected, body=${body?.let { "<${it.length} chars>" } ?: "<unreadable>"})"
}

/** The failure [reply] signals, or null when it is safe to parse (spec 6.4, one rule for every transport). */
fun classifyReply(reply: RawReply): InstagramException? = when {
    reply.redirected -> InstagramException.ChallengeRequired(null)
    reply.body == null -> if (reply.code in 200..299 || reply.code >= 500) InstagramException.Transient()
        else classifyUnreadable(reply.code, null, reply.contentType)
    else -> ErrorClassifier.classify(reply.code, null, reply.contentType, reply.body)
}

/** The reply's JSON object, or the InstagramException it signals. */
internal fun RawReply.jsonOrThrow(): kotlinx.serialization.json.JsonObject {
    classifyReply(this)?.let { throw it }
    return parseObject(body!!) ?: throw InstagramException.ShapeChanged("$")
}
```

`OkHttpTransport(http: OkHttpClient, base: HttpUrl = WebEndpoints.BASE)`:
- `get` resolves `pathAndQuery` against `base` and sends one GET through the existing no-redirect client.
- It maps a 3xx to `RawReply(code, ct, null, redirected = true)`. If the `Location` contains `/accounts/login`, it returns `RawReply(code, ct, "{\"require_login\":true}")` instead, so the existing login-bounce rule still applies.
- A body that can't be read becomes `body = null`. Connect failures throw `Transient`, as today.

`WebEndpoints.relative(url: HttpUrl): String` returns `url.encodedPath.removePrefix("/") + (url.encodedQuery?.let { "?$it" } ?: "")`. The builders stay as they are. Callers do `transport.get(WebEndpoints.relative(WebEndpoints.savedPosts(WebEndpoints.BASE, cursor)))`.

`WebEndpoints.currentUser()` takes no id and returns `BASE` + `api/v1/accounts/edit/web_form_data/`. A pin test asserts that path.

`WebSessionProbe(transport: () -> InstagramTransport, cookies: CookieStore)`:
1. `userId = cookies.sessionUserId() ?: throw LoginRequired()`. No request is made.
2. `json = transport().get(relative(WebEndpoints.currentUser())).jsonOrThrow()`.
3. `form = json["form_data"] as? JsonObject ?: throw ShapeChanged("form_data")`.
4. `username = form.string("username") ?: throw ShapeChanged("form_data.username")`.
5. Return `Account(pk = userId, username)`.

`WebInstagramClient(transport: () -> InstagramTransport, cookies: CookieStore, reportsSavedCollectionIds: Boolean = …)`: the same logic as today, with each `http.getJsonObject(url)` replaced by `transport.get(relative(url)).jsonOrThrow()`. `mediaInfo` keeps its http.400/http.404 → null rule.

`AdapterLab(transport: () -> InstagramTransport, cookies: CookieStore)` builds the same `LabResult`, from `RawReply`:
- the error comes from `classifyReply`;
- a redirect shapes as `"(redirect, not followed)"`;
- a null body shapes as `"(unreadable body)"`.

`ErrorReplySummary.of(code: Int, contentType: String?, body: String?): String` holds the existing allow-listed one-line format, moved out of `ErrorReplyLogger`. The logger keeps peeking and gunzipping, then calls it.

`WebHeaders`: `APP_ID = "1217981644879628"` and a new `ASBD_ID = "359341"`. The OkHttp interceptor also sets `X-ASBD-ID`. A test pins both values.

- [ ] **Step 1: Write failing tests**
  - `InstagramTransportTest` covers `classifyReply`: redirected gives `ChallengeRequired(null)`; null body with 429 gives `RateLimited`; null body with 200 gives `Transient`; a JSON challenge body gives `ChallengeRequired`; a clean 200 gives null.
  - `RawReply.toString()` never contains the body.
  - `WebSessionProbeTest`, through `OkHttpTransport` + MockWebServer:
    - the path is `/api/v1/accounts/edit/web_form_data/`;
    - `{"form_data":{"username":"user_1"}}` gives `Account("42","user_1")`;
    - a missing `form_data` gives `ShapeChanged("form_data")`;
    - no `ds_user_id` cookie means no request;
    - a 302 to `/accounts/login/` gives `LoginRequired`;
    - a 302 elsewhere gives `ChallengeRequired(null)`.
  - `WebHeadersTest`: the app id and ASBD id values, and the OkHttp interceptor sends both.
- [ ] **Step 2: Run them and watch them fail.** `./gradlew :instagram:test`. Expect compilation errors on the new types.
- [ ] **Step 3: Implement.** Move every existing test's construction to `OkHttpTransport` with no behaviour change. Their assertions must keep passing unchanged, except tests of the old `users/<id>/info/` endpoint, which move to the new endpoint and shape.
- [ ] **Step 4: Run** `./gradlew :instagram:test`. Expect PASS. Also run `./gradlew :app:compileDebugKotlin`: `:app` will not compile yet; Task 3 fixes it. To keep the tree green, Task 1 may change `AppContainer` minimally, to pass `{ OkHttpTransport(instagramHttp) }`. Task 3 replaces that.
- [ ] **Step 5: Commit.** `refactor(instagram): a transport seam for API calls; login check on accounts/edit/web_form_data; mobile web app id`

---

### Task 2: `WebViewTransport` over a `WebPage` (`:app`, unit-tested with a fake page)

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/transport/WebPage.kt`, `WebViewTransport.kt`
- Create: `app/src/main/assets/ig_fetch.js`
- Modify: `gradle/libs.versions.toml` and `app/build.gradle.kts` to add `androidx-webkit` (the latest **stable** on Google Maven; name the version in the report)
- Test: `app/src/test/kotlin/io/github/yuriimurha/reels/transport/WebViewTransportTest.kt`

**Interfaces (produced):**

```kotlin
package io.github.yuriimurha.reels.transport

/** The hidden page the transport runs in. Main-thread only. The real one wraps a WebView; tests use a fake. */
interface WebPage {
    /** Loads [url]; returns the URL the page finished on (after the site's own redirects), or throws on failure/timeout. */
    suspend fun load(url: String): String
    /** Runs [script] in the page; the result arrives later through [onMessage]. */
    fun evaluate(script: String)
    /** Messages posted by the page from the allowed origin only. */
    fun onMessage(listener: (String) -> Unit)
    fun destroy()
}

class WebViewTransport(
    private val createPage: () -> WebPage,          // main thread; may throw (no WebView provider)
    private val homeUrl: String,                     // WebEndpoints.HOME_URL in the app
    private val script: String,                      // ig_fetch.js contents
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val callTimeoutMs: Long = 30_000,
    private val loadTimeoutMs: Long = 30_000,
    private val log: ((String) -> Unit)? = null,     // debug builds only
) : InstagramTransport {
    override suspend fun get(pathAndQuery: String): RawReply
    /** Destroys the page (cancelling any call in flight); the next call creates and loads a new one. */
    suspend fun reset()
}
```

**Behaviour:**
- **First use.** On first use, or after `reset`, the transport creates the page on `main` and loads `homeUrl` within `loadTimeoutMs`.
- **Where the page landed.** If the URL it finished on has a path starting with `/accounts/login`, the transport fails every call `LoginRequired` until the next `reset`, without evaluating anything. A path starting with `/challenge` or `/accounts/suspended` gives `ChallengeRequired(null)`. A host other than `www.instagram.com` (or the test origin's host) gives `Transient`. If creating or loading throws, the call fails `Transient` and the page is dropped.
- **Each call:**
  - It allocates a call id: an increasing Long.
  - It refuses with `Transient` if another call is still in flight.
  - It evaluates `"window.__igFetch && window.__igFetch(" + id + "," + JSON-quoted path + ")"`, injecting `script` once per page load before the first call.
  - It waits up to `callTimeoutMs` for a message `{"id":<id>,"code":N,"contentType":…,"body":…,"redirected":bool}`. Parse it with kotlinx.serialization into a private data class. A message that doesn't parse, or whose id isn't the awaited one, is ignored.
  - A timeout fails `Transient` and drops the page, because a stuck page is not reused.
- **Debug log.** One line per call: `GET <path with \d{3,} runs replaced by <n>> -> <code> (<ms> ms)`. For a non-2xx reply, also `ErrorReplySummary.of(code, contentType, body)`.
- **`reset()`.** It destroys the page on `main` and completes any waiting call with `Transient`.

`ig_fetch.js` (verbatim):

```javascript
(function () {
  if (window.__igFetch) return;
  function cookie(name) {
    var m = document.cookie.match('(?:^|; )' + name + '=([^;]*)');
    return m ? decodeURIComponent(m[1]) : '';
  }
  function claim() {
    try { return sessionStorage.getItem('www-claim-v2') || '0'; } catch (e) { return '0'; }
  }
  window.__igFetch = function (id, path) {
    var headers = {
      'x-ig-app-id': '1217981644879628',
      'x-asbd-id': '359341',
      'x-requested-with': 'XMLHttpRequest',
      'x-csrftoken': cookie('csrftoken'),
      'x-ig-www-claim': claim()
    };
    fetch('/' + path, { method: 'GET', credentials: 'same-origin', redirect: 'manual', headers: headers })
      .then(function (r) {
        if (r.type === 'opaqueredirect') {
          window.igBridge.postMessage(JSON.stringify({ id: id, code: 0, contentType: null, body: null, redirected: true }));
          return;
        }
        var ct = r.headers.get('content-type');
        return r.text().then(
          function (t) { window.igBridge.postMessage(JSON.stringify({ id: id, code: r.status, contentType: ct, body: t, redirected: false })); },
          function () { window.igBridge.postMessage(JSON.stringify({ id: id, code: r.status, contentType: ct, body: null, redirected: false })); }
        );
      })
      .catch(function () {
        window.igBridge.postMessage(JSON.stringify({ id: id, code: -1, contentType: null, body: null, redirected: false }));
      });
  };
})();
```

The transport maps `code == -1` (a network failure) to `Transient`. A `redirected` message becomes `RawReply(0, null, null, redirected = true)`. The header values in the script must equal `WebHeaders.APP_ID` and `WebHeaders.ASBD_ID`; a JVM test reads the asset file and asserts it.

`AndroidWebPage(context: Context, allowedOrigin: String = "https://www.instagram.com")`:
- **Construction:** `WebView(context.applicationContext)`, with JavaScript on and DOM storage on. Never call `addJavascriptInterface`.
- **Messages:** `WebViewCompat.addWebMessageListener(webView, "igBridge", setOf(allowedOrigin)) { _, message, sourceOrigin, isMainFrame, _ -> if (isMainFrame && sourceOrigin.toString() == allowedOrigin) listener(message.data ?: return@…) }`. Check `WebViewFeature.isFeatureSupported(WEB_MESSAGE_LISTENER)` first, and throw (giving `Transient`) when it isn't supported.
- **`load`** uses a `WebViewClient`:
  - `shouldOverrideUrlLoading` allows only `WebEndpoints.isLoginPage` hosts, plus the test origin's host in tests;
  - `onPageFinished` completes a `CompletableDeferred<String>`;
  - `onReceivedError` on the main frame fails it.
- **`destroy()`** stops loading, removes the listener, and calls `webView.destroy()`.

- [ ] **Step 1: Write failing tests** with a `FakeWebPage`. It records evaluated scripts, lets the test post messages, and can be scripted to land on a URL or to throw on create or load. Tests:
  - `aCallLoadsTheHomePageOnceAndSendsOneFetch`;
  - `repliesAreMatchedByIdAndConcurrentCallsAreRefused` (Review Focus 2);
  - `aPageThatLandsOnLoginMakesNoCall` (Review Focus 1), plus a `/challenge/` variant;
  - `aRedirectMessageBecomesARedirectedReply`;
  - `aNetworkFailureMessageIsTransient`;
  - `aMalformedMessageIsIgnored` (Review Focus 4): the call still times out to `Transient`;
  - `theCallTimesOutAndDropsThePage`;
  - `destroyCancelsTheCallInFlight` (Review Focus 3);
  - `aPageThatCannotBeCreatedFailsTransient` (Review Focus 5);
  - `theScriptsHeadersMatchTheConstants`, which reads `app/src/main/assets/ig_fetch.js` from the module directory;
  - `theDebugLogShowsPathStatusAndTimeButNoBody`;
  - `theLogShowsTheRedactedSummaryForErrors`.

  Use `StandardTestDispatcher` for `main` and virtual time for the timeouts.
- [ ] **Step 2: Run them and watch them fail.** `./gradlew :app:testDebugUnitTest --tests '*WebViewTransportTest*'`.
- [ ] **Step 3: Implement** `WebViewTransport`, `WebPage`, `AndroidWebPage` and the asset, and add the webkit dependency.
- [ ] **Step 4: Run.** `./gradlew :app:testDebugUnitTest`. Expect PASS. `AndroidWebPage` is exercised on the emulator in Task 4.
- [ ] **Step 5: Commit.** `feat(transport): WebViewTransport runs each API call as a same-origin fetch in a hidden instagram.com page`

---

### Task 3: Wiring, destroy hooks and the production pin

**Files:**
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt`
- Modify: `session/SessionRepository.kt`, `data/library/LibraryRepository.kt`
- Test: `di/BackendWiringGuardTest.kt` (extend), `session/SessionRepositoryTest.kt`, `data/library/LibraryRepositoryTest.kt`

**Changes:**
- **`AppContainer`:**
  - Add `val instagramTransport: WebViewTransport by lazy { WebViewTransport(createPage = { AndroidWebPage(context) }, homeUrl = WebEndpoints.HOME_URL, script = context.assets.open("ig_fetch.js").bufferedReader().use { it.readText() }, log = if (BuildConfig.DEBUG) { line -> Log.d("InstagramHttp", line) } else null) }`.
  - `Backend.Real` gets `WebInstagramClient({ instagramTransport }, cookieStore)`. The session probe gets `LazySessionProbe { WebSessionProbe({ instagramTransport }, cookieStore) }`, and `adapterLab` gets `AdapterLab({ instagramTransport }, cookieStore)`.
  - Remove `instagramHttp` and the API `HttpClientFactory.create` use from `src/main`. `cdnHttp` stays.
- **`SessionRepository`** gets a constructor parameter `beforeSessionChange: suspend () -> Unit = {}`. It is called first in `logout()` (before `cookies.clearAll()`) and in `pasteSessionId` (before the cookie write). The container passes `{ instagramTransport.reset() }`, and only if the transport was ever created: guard with a `Lazy` and `isInitialized()`, so Mock mode and a cold logout never create a WebView.
- **`LibraryRepository.deleteLibrary`** takes the same hook and calls it first.
- **`BackendWiringGuardTest`** source pins:
  - `src/main` of `:app` never references `OkHttpTransport` or `HttpClientFactory.create(` (only `createCdn`);
  - the Real client, the probe and the lab all get `instagramTransport`;
  - `AndroidWebPage` is constructed only in `AppContainer`;
  - no file in `src/main` contains `addJavascriptInterface`.
- **`SessionRepositoryTest.logoutDestroysThePageBeforeClearingCookies`** (Review Focus 3) asserts the order of events `[reset, clear]`. Add the paste equivalent.

- [ ] **Step 1:** Write the failing tests above.
- [ ] **Step 2:** Run them and watch them fail.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run `./gradlew check`. Expect PASS.
- [ ] **Step 5:** Commit `feat(sync): the real backend, login check and Adapter lab send through the WebView transport`.

---

### Task 4: Emulator tests of the real page

**Files:**
- Create: `app/src/androidTest/kotlin/io/github/yuriimurha/reels/transport/AndroidWebPageTest.kt`
- Modify: `app/build.gradle.kts`, adding `androidTestImplementation(libs.okhttp.mockwebserver)`

These tests use a local MockWebServer, never Instagram, and run on the emulator only (reuse `SmokeGuard`'s emulator check).

1. **The test page.** Serve an HTML page at `/` from MockWebServer, and construct `AndroidWebPage(context, allowedOrigin = "http://127.0.0.1:<port>")`. The real Instagram origin check is replaced by the server's origin. If `addWebMessageListener` rejects an `http://127.0.0.1` rule, use `http://localhost:<port>`. As a last resort, use a debug-only `"*"`, and document which you used.
2. **`oneGetSendsExactlyOneRequestWithTheSiteHeaders`.** `WebViewTransport` with that page and `homeUrl` = server root. The server sees one GET to `/api/v1/accounts/edit/web_form_data/` carrying `x-ig-app-id: 1217981644879628`, `x-asbd-id: 359341`, `x-requested-with: XMLHttpRequest` and an `x-csrftoken` header equal to a fake `csrftoken` cookie the test set on the server origin (built from parts). The reply's body round-trips.
3. **`aRedirectIsReportedNotFollowed`.** A 302 to `/elsewhere` gives `redirected = true`, and the server never sees `/elsewhere`.
4. **`messagesFromOtherOriginsAreIgnored`** (Review Focus 4). The page embeds an iframe from a second MockWebServer (different port) that posts a forged reply. It is ignored, and the real call still completes with the real reply.
5. **`theTransportWorksFromABackgroundCoroutine`.** Call `get` from `Dispatchers.Default`, as the sync worker does. It completes.

- [ ] **Step 1:** Write the tests.
- [ ] **Step 2:** Run `SERIAL="$(adb -e get-serialno)" && ANDROID_SERIAL="$SERIAL" ./gradlew connectedDebugAndroidTest`. Iterate until green. The existing smoke suite must stay green: Mock mode never creates the WebView. Check that, or add an assertion.
- [ ] **Step 3:** Commit `test(transport): emulator tests of the hidden page against a local server`.

---

### Task 5: Docs and rollout

**Files:** `README.md`, `ARCHITECTURE.md`, `TODO.md`, `PROGRESS.md` (append only)

- **ARCHITECTURE:**
  - the transport seam;
  - `WebViewTransport` and `AndroidWebPage`;
  - the destroy hooks;
  - the new login-check endpoint and app id;
  - the logging change (spec 3.4);
  - that `SessionGuard` and the API cookie bridge are no longer on the API path. Remove dead code if nothing uses it, and say so.
- **README**, under "First real sync", gets an "After an update that changes how the app talks to Instagram" box:
  1. wait for any cooldown to end;
  2. open **Sync → Developer → Adapter lab**;
  3. tap **Who am I** only, which sends one request;
  4. paste the `InstagramHttp` log lines into a Claude session;
  5. only then **Log in**, the other lab buttons, and Sync.

  Update the M2 checklist's expected log line to `GET api/v1/accounts/edit/web_form_data/ -> 200`.
- **TODO:** tick the M2 app-id check item (now known: `1217981644879628`). Add a phone check for "the hidden page loads and Who am I answers 200".
- **PROGRESS:** one dated entry.

- [ ] **Step 1:** Write the docs.
- [ ] **Step 2:** Check that every command is runnable as pasted.
- [ ] **Step 3:** Commit `docs: the WebView transport and the post-update rollout steps`.
