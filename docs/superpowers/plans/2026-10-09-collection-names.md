# Saved-collection names, self-repairing: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sync shows the account's saved collections under their Instagram names, fetched with the website's own GraphQL query from the hidden mobile page, and the app learns a changed query id on the phone by itself.

**Architecture:**
- `:instagram` gains a GraphQL seam:
  - `InstagramTransport.graphql(query, docId, variables)`;
  - an allow-listed `GraphQlQuery`;
  - a collections parser for `collections_unified_with_auto_collections`;
  - an `InstagramException.StaleQuery`;
  - a `DocIdStore`.
- `:app` adds a few pieces:
  - The hidden page's script gains `__igGraphQl`, with tokens read and used only in the page.
  - A separate desktop-mode `RepairPage` watches the site's own collections query and hands back its `doc_id` and reply.
  - Sync uses the saved feed's `saved_collection_ids` (strategy A) instead of per-collection feeds. When the stored id is stale it repairs, at most once per 24 h; failing that, it keeps the last names.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1, Compose, Room, DataStore, WorkManager, androidx.webkit 1.17.1, kotlinx.serialization, OkHttp 5.5.0 / MockWebServer (JVM tests), Robolectric 4.17, emulator tests on `emulator-5554`.

**Spec:** `docs/superpowers/specs/2026-10-09-collection-names-design.md`. It amends `2026-10-08-webview-transport-design.md` and `2026-10-06-saved-reels-android-design.md`.

## Global Constraints

- Never commit or log session material (`sessionid`, cookies, CSRF tokens, `fb_dtsg`, `lsd`, www-claim) or raw Instagram responses. Test fixtures are scrubbed. The repo is public. The pre-commit guard blocks session-like values; never use `--no-verify`, and build fake token strings from parts.
- All Instagram-specific names (endpoints, friendly names, doc ids, JSON fields) live in `:instagram`. The only exceptions are the page scripts' header/form names, pinned by tests against `:instagram` constants.
- Pacing is a hard requirement. A normal sync must send FEWER API requests than before. A repair is at most one desktop page view per 24 h. Every commit that changes request patterns says so in a "Pacing:" paragraph, and ARCHITECTURE.md says so too.
- Built-in collections query: friendly name `PolarisProfileSavedTabContentQuery`, `doc_id` `27584326974521636`, variables `{"collection_types":["ALL_MEDIA_AUTO_COLLECTION","MEDIA","AUDIO_AUTO_COLLECTION"],"first":12}`, reply root `data.viewer.collections_unified_with_auto_collections`.
- Mobile app id `1217981644879628`, `x-asbd-id` `359341` (from `WebHeaders`).
- Repair limit: at most one repair per `REPAIR_INTERVAL_MS = 86_400_000`. Repair bound: `REPAIR_TIMEOUT_MS = 45_000`.
- Developer action label: **Forget collections query id**. Sync notice text: **Couldn't refresh collection names**. Placeholder name: `Collection N`, numbered from 1 in order of first sighting.
- Agents never log into Instagram, never type credentials, never run tests against the owner's phone (serial `56191FDCR001TB`). Emulator tests use a local MockWebServer only. Task 1 is the one exception: it runs on the phone, driven by the controller with the owner's taps.
- Env for Gradle: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk";`. Emulator: `ANDROID_SERIAL=emulator-5554`.

## Review Focus

1. **A collection whose name has quotes, emoji or RTL text, or is empty.** It must be stored as-is (an empty name gets the `Collection N` placeholder), never crash the parser. Pinned in Task 4.
2. **The site's own query goes through `XMLHttpRequest` instead of `fetch`, or sends its body as `FormData`/`URLSearchParams`/string.** The repair script must catch every form. Pinned in Task 3's emulator tests.
3. **A repair page that lands on the login or a challenge page, or gets a 429.** It fails the repair, never retries within 24 h, and a 429 still arms the cooldown. Pinned in Task 3 (page) and Task 5 (sync).
4. **A saved item whose `saved_collection_ids` names a collection the names fetch didn't return** (a collection created since, or the names fetch fell back). It gets a placeholder collection and its membership, and is never dropped. Pinned in Task 5.
5. **The owner taps Forget collections query id, then the lab's Collections button or a sync.** Exactly one stale query, then one repair, then the names. Pinned in Task 5.

---

### Task 1: Phone spike (controller-run with the owner, nothing committed)

Three facts the parser and the stale rule need (spec §5). The controller runs this itself over adb and CDP, because subagents never touch the phone. It needs one **Who am I** tap from the owner to open the hidden page.

**Files:**
- Create (scratch, not committed): `<scratchpad>/cdp-spike2.mjs`. It is adapted from the 2026-10-09 `cdp-probe.mjs`: the same handle lookup from settings, the same in-page token read.
- Output: `<sdd workspace>/task-1-facts.md`, which Tasks 4–5 read.

- [ ] **Step 1: Ask the owner to tap Who am I once, then run these 4 in-page POSTs from the mobile page.** Each uses the page's own tokens, as in `cdp-probe.mjs`. Print only status, content-type, byte length, the top-level JSON keys, `errors[].message` (first 100 chars), and for edges: `__typename`, whether `collection_id` is all digits, its length, and `collection_media_count`. Never print names, ids or tokens.
  1. Built-in doc_id, variables with `"first":1` → record `page_info.has_next_page` and whether `end_cursor` is non-empty.
  2. Same, plus `"after":"<end_cursor from 1>"` → does it return the next edge? This confirms the cursor variable name `after`.
  3. `doc_id` = `"1"` (deliberately wrong) → record exactly what an outdated id returns.
  4. Built-in doc_id with the full variables (`"first":12`) → per-edge `__typename` and digit-ness of `collection_id` (how "All posts" is marked).
- [ ] **Step 2: Write `task-1-facts.md`** with three findings:
  - **`STALE`:** how to recognise an outdated id (status, content type, top-level keys, error text pattern).
  - **`CURSOR`:** the variable name for page 2.
  - **`AUTO`:** how automatic collections differ from user ones (`collection_id` non-digit? a `__typename`?).
- [ ] **Step 3: Leave the page on `about:blank`** (the app drops it on its next call) and remove the adb forward.

The defaults the later tasks assume, used if a fact cannot be determined:
- STALE = HTTP 200 with a JSON `errors` array and no `data.viewer.collections_unified_with_auto_collections`, or any HTTP 400/404/500 whose body is not a 429/login/challenge reply.
- CURSOR = `after`.
- AUTO = `collection_id` not all digits.

---

### Task 2: GraphQL transport (`:instagram` seam, `:app` page script and `WebViewTransport`)

**Files:**
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebGraphQl.kt`
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/InstagramTransport.kt` (interface method)
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/OkHttpTransport.kt` (JVM-test implementation)
- Modify: `app/src/main/assets/ig_fetch.js`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/transport/WebViewTransport.kt`
- Test: `instagram/src/test/kotlin/io/github/yuriimurha/reels/instagram/web/OkHttpTransportTest.kt`, `app/src/test/kotlin/io/github/yuriimurha/reels/transport/WebViewTransportTest.kt`, `app/src/androidTest/kotlin/io/github/yuriimurha/reels/transport/AndroidWebPageTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // WebGraphQl.kt
  class GraphQlQuery internal constructor(val friendlyName: String, val builtInDocId: String)
  object WebGraphQl {
      val SAVED_COLLECTIONS = GraphQlQuery("PolarisProfileSavedTabContentQuery", "27584326974521636")
      /** Every query any page script may send; the scripts pin their own list against this. */
      val ALL: List<GraphQlQuery> = listOf(SAVED_COLLECTIONS)
      fun savedCollectionsVariables(cursor: String?): String  // JSON text, see Step 3
      const val PATH = "api/graphql"
  }
  // InstagramTransport
  suspend fun graphql(query: GraphQlQuery, docId: String, variables: String): RawReply
  ```
- Consumes: `RawReply`, `classifyReply` (unchanged), `WebHeaders.APP_ID`, `WebHeaders.ASBD_ID`.

- [ ] **Step 1: Write the failing JVM tests for `WebGraphQl` and `OkHttpTransport.graphql`** (`OkHttpTransportTest`):

```kotlin
@Test fun savedCollectionsVariablesMatchTheWebsite() {
    assertEquals(
        """{"collection_types":["ALL_MEDIA_AUTO_COLLECTION","MEDIA","AUDIO_AUTO_COLLECTION"],"first":12}""",
        WebGraphQl.savedCollectionsVariables(null),
    )
    assertEquals(
        """{"collection_types":["ALL_MEDIA_AUTO_COLLECTION","MEDIA","AUDIO_AUTO_COLLECTION"],"first":12,"after":"c\"1"}""",
        WebGraphQl.savedCollectionsVariables("c\"1"),
    )
}

@Test fun graphqlSendsOnePostWithTheQueryForm() = runTest {
    server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body("""{"data":{}}""").build())
    val reply = OkHttpTransport(client, server.url("/")).graphql(WebGraphQl.SAVED_COLLECTIONS, "123", "{}")
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    assertEquals("/api/graphql", request.url.encodedPath)
    val form = request.body!!.utf8()
    assertTrue("doc_id=123" in form)
    assertTrue("fb_api_req_friendly_name=PolarisProfileSavedTabContentQuery" in form)
    assertEquals("PolarisProfileSavedTabContentQuery", request.headers["x-fb-friendly-name"])
    assertEquals(200, reply.code)
    assertEquals(1, server.requestCount)
}
```

(`OkHttpTransport` sends no tokens: it is the JVM-test transport.)

- [ ] **Step 2: Run them.** `./gradlew :instagram:test --tests '*OkHttpTransportTest*'`. Expected: compile FAIL, `WebGraphQl`/`graphql` unresolved.

- [ ] **Step 3: Implement `WebGraphQl.kt` and the interface method.**

```kotlin
package io.github.yuriimurha.reels.instagram.web

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One GraphQL query the app may send: the website's own, by its friendly name. [builtInDocId] is today's id. */
class GraphQlQuery internal constructor(val friendlyName: String, val builtInDocId: String) {
    override fun toString() = "GraphQlQuery($friendlyName)"
}

/** The website's GraphQL queries the app uses (spec 2026-10-09 §3.1). Nothing outside `:instagram` spells one out. */
object WebGraphQl {
    const val PATH = "api/graphql"

    /** The desktop website's Saved tab: the account's collections with their names. */
    val SAVED_COLLECTIONS = GraphQlQuery("PolarisProfileSavedTabContentQuery", "27584326974521636")

    val ALL: List<GraphQlQuery> = listOf(SAVED_COLLECTIONS)

    /** As the website sends them; [cursor] (page 2 on) goes in as `after` (Task 1 fact CURSOR). */
    fun savedCollectionsVariables(cursor: String?): String = buildJsonObject {
        put("collection_types", buildJsonArray {
            listOf("ALL_MEDIA_AUTO_COLLECTION", "MEDIA", "AUDIO_AUTO_COLLECTION").forEach { add(JsonPrimitive(it)) }
        })
        put("first", 12)
        if (cursor != null) put("after", cursor)
    }.toString()
}
```

Add the method to `InstagramTransport`:

```kotlin
    /**
     * One POST of the website's own GraphQL [query] with [docId] and [variables] (JSON text). The same rules as [get]: one
     * request, never retried, never redirected. Tokens are the transport's business and never leave it.
     */
    suspend fun graphql(query: GraphQlQuery, docId: String, variables: String): RawReply
```

`OkHttpTransport.graphql`: POST `base.resolve(WebGraphQl.PATH)` with a `FormBody`. Fields: `fb_api_caller_class=RelayModern`, `fb_api_req_friendly_name`, `variables`, `server_timestamps=true`, `doc_id`. Header `x-fb-friendly-name`. Reply via the same `toReply()`. Every other `InstagramTransport` in tests gets a `graphql` that fails the test unless the test sets it.

- [ ] **Step 4: Run.** `./gradlew :instagram:test`. Expected: PASS.

- [ ] **Step 5: Write the failing JVM tests for `WebViewTransport.graphql`** (`WebViewTransportTest`, with the existing fake page):
  - `graphqlEvaluatesTheGraphQlCallWithItsArguments`: after a reply, the evaluated scripts are `[script, "window.__igGraphQl && window.__igGraphQl(1,\"PolarisProfileSavedTabContentQuery\",\"123\",\"{\\\"first\\\":12}\")"]`. Every argument is quoted with `JsonPrimitive`.
  - `graphqlSharesTheBusyRuleAndTheLandingCheck`: a `get` in flight makes a `graphql` call `Transient`, and a login landing makes it `LoginRequired`.
  - `aPageWithoutTokensIsTransientAndDropped`: the page posts `code: -2`, the call is `Transient`, the page is destroyed, and the next call creates a new page.
  - `graphqlIsLogged`: the log line is `GRAPHQL PolarisProfileSavedTabContentQuery -> 200 (<ms> ms)`. It never contains the doc id or the variables.
  - `aGraphQlQueryOutsideTheAllowListIsRefused`: a `GraphQlQuery` not in `WebGraphQl.ALL` (built in a test with reflection, or via a test-only factory) throws `IllegalArgumentException` before anything is evaluated.

- [ ] **Step 6: Run.** `./gradlew :app:testDebugUnitTest --tests '*WebViewTransportTest*'`. Expected: FAIL (no `graphql`).

- [ ] **Step 7: Implement in `WebViewTransport`.** Generalise `call(path)` to `call(label, invocation: (id: Long) -> String)`. Inside it, replace the fixed `__igFetch` line with `current.evaluate(invocation(id))`. Then:

```kotlin
    override suspend fun get(pathAndQuery: String): RawReply {
        require(isPath(pathAndQuery)) { "not a path on the Instagram origin" }
        return withContext(main) {
            logged("GET ${pathAndQuery.replace(DIGIT_RUN, "<n>")}") { call { id -> "window.__igFetch && window.__igFetch($id,${JsonPrimitive(pathAndQuery)})" } }
        }
    }

    override suspend fun graphql(query: GraphQlQuery, docId: String, variables: String): RawReply {
        require(query in WebGraphQl.ALL) { "not an allowed query" }
        return withContext(main) {
            logged("GRAPHQL ${query.friendlyName}") {
                call { id ->
                    "window.__igGraphQl && window.__igGraphQl($id,${JsonPrimitive(query.friendlyName)},${JsonPrimitive(docId)},${JsonPrimitive(variables)})"
                }
            }
        }
    }
```

`logged` takes the ready-made line prefix (keep the existing `-> <outcome> (<ms> ms)` suffix and the `ErrorReplySummary` line). In `toReply`, `-2` becomes `dropPage(); throw Failed("no tokens", InstagramException.Transient())`. Every other non-HTTP code stays "network error". The abort on cancellation (`__igAbort`) applies to both kinds of call.

- [ ] **Step 8: Extend `ig_fetch.js`** (inside the same IIFE, after `__igFetch`):

```js
  var QUERIES = ['PolarisProfileSavedTabContentQuery'];
  function tokens() {
    var dtsg = null, lsd = null;
    try { dtsg = require('DTSGInitialData').token; } catch (e) {}
    try { lsd = require('LSD').token; } catch (e) {}
    if (!dtsg || !lsd) {
      var html = document.documentElement.innerHTML;
      var d = html.match(/"DTSGInitialData",\[\],\{"token":"([^"]+)"/);
      var l = html.match(/"LSD",\[\],\{"token":"([^"]+)"/);
      dtsg = dtsg || (d && d[1]);
      lsd = lsd || (l && l[1]);
    }
    return dtsg && lsd ? { dtsg: dtsg, lsd: lsd } : null;
  }
  window.__igGraphQl = function (id, name, docId, variables) {
    if (QUERIES.indexOf(name) < 0) {
      window.igBridge.postMessage(JSON.stringify({ id: id, code: -3, contentType: null, body: null, redirected: false }));
      return;
    }
    var t = tokens();
    if (!t) {
      window.igBridge.postMessage(JSON.stringify({ id: id, code: -2, contentType: null, body: null, redirected: false }));
      return;
    }
    var body = new URLSearchParams({ fb_dtsg: t.dtsg, lsd: t.lsd, fb_api_caller_class: 'RelayModern',
      fb_api_req_friendly_name: name, variables: variables, server_timestamps: 'true', doc_id: docId });
    var headers = { 'content-type': 'application/x-www-form-urlencoded', 'x-fb-friendly-name': name, 'x-fb-lsd': t.lsd,
      'x-ig-app-id': '1217981644879628', 'x-asbd-id': '359341', 'x-csrftoken': cookie('csrftoken') };
    var controller = new AbortController();
    aborts[id] = controller;
    function forget() { delete aborts[id]; }
    fetch('/api/graphql', { method: 'POST', credentials: 'same-origin', redirect: 'manual', headers: headers, body: body, signal: controller.signal })
      .then(/* identical to __igFetch's .then: opaqueredirect, text(), postMessage {id, code, contentType, body, redirected} */)
      .catch(function () { window.igBridge.postMessage(JSON.stringify({ id: id, code: -1, contentType: null, body: null, redirected: false })); })
      .then(forget, forget);
  };
```

Factor the shared `.then` into one local function `answer(id)` used by both `__igFetch` and `__igGraphQl`, so the posted shape cannot drift. Code `-3` (refused name) becomes `Failed("refused query", Transient)` in `toReply`.

Extend `theScriptsHeadersMatchTheConstants` and the S1 value pins (`WebViewTransportTest`):
- `QUERIES` equals `WebGraphQl.ALL.map { it.friendlyName }`.
- Every posted object still has only the keys `{id, code, contentType, body, redirected}` and the value rules (`body` ∈ {text var, null}).
- `fb_dtsg`/`lsd` appear only inside `tokens()` and the form/headers, never in a `postMessage`.
- Add the mutant `body: t.dtsg` to the self-check.

- [ ] **Step 9: Write the emulator tests** (`AndroidWebPageTest`, local server only). Serve a home page whose HTML contains `"DTSGInitialData",[],{"token":"` + fake + `"}` and `"LSD",[],{"token":"` + fake + `"}`, built from parts:
  - `graphqlSendsOnePostWithTheFormAndHeaders`: the server sees exactly one `POST /api/graphql`. Its form has `fb_dtsg`/`lsd` equal to the fakes, plus `doc_id`, `variables` and the friendly name. Its headers include `x-fb-lsd`, `x-ig-app-id`, `x-asbd-id` and `x-csrftoken`. The reply body round-trips.
  - `aPageWithoutTokensSendsNothing`: a home page without the token markers → the call is `Transient`, and the server saw zero `POST`s.
  - `theTokensNeverReachTheApp`: the page message the transport receives (capture it with a test listener on the page) contains neither fake token.

- [ ] **Step 10: Run.** `./gradlew check`, then `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`. Expected: all green.

- [ ] **Step 11: Commit.**

```bash
git add instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebGraphQl.kt instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/InstagramTransport.kt instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/OkHttpTransport.kt app/src/main/assets/ig_fetch.js app/src/main/kotlin/io/github/yuriimurha/reels/transport/WebViewTransport.kt <the test files you changed>
git commit -m "feat(transport): one allow-listed GraphQL POST from the hidden page, tokens read and used in the page" -m "Pacing: no new call site yet; graphql() is one request like get()."
```

---

### Task 3: Repair page (desktop-mode hidden WebView that watches the site's own collections query)

**Files:**
- Create: `app/src/main/assets/ig_watch.js`
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/transport/RepairPage.kt` (interface + `WatchedQuery`)
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/transport/AndroidRepairPage.kt`
- Test: `app/src/androidTest/kotlin/io/github/yuriimurha/reels/transport/AndroidRepairPageTest.kt`, `app/src/test/kotlin/io/github/yuriimurha/reels/transport/RepairPageGuardTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /** What the site's own request for [friendlyName] carried and got back. */
  class WatchedQuery(val docId: String, val code: Int, val body: String?)
  interface RepairPage {
      /**
       * Loads [url] in desktop mode and waits (≤ [timeoutMs]) for the site's own POST to /api/graphql named [friendlyName].
       * Throws PageHttpError (429 included) for an HTTP error page, RepairLanding.Login / RepairLanding.Challenge for a
       * login or challenge page, TimeoutCancellationException-free `null` when the request never came.
       */
      suspend fun watch(url: String, friendlyName: String, timeoutMs: Long): WatchedQuery?
      fun destroy()
  }
  sealed class RepairLanding : Exception() { object Login : RepairLanding(); object Challenge : RepairLanding() }
  ```
- Consumes: `WebEndpoints.landingOf`, the `PageClient` from `AndroidWebPage.kt` (reuse it for the main-frame HTTP error and render-gone rules), the same `igBridge` origin-locked listener pattern.

- [ ] **Step 1: Write `ig_watch.js`.** It is a document-start script that watches exactly one named request. It must never read or forward any token, cookie or other request.

```js
(function () {
  if (window.__igWatch) return;
  window.__igWatch = true;
  var NAME = 'PolarisProfileSavedTabContentQuery';
  function formOf(body) {
    try {
      if (typeof body === 'string') return new URLSearchParams(body);
      if (body instanceof URLSearchParams) return body;
      if (typeof FormData !== 'undefined' && body instanceof FormData) return new URLSearchParams(Array.from(body.entries()));
    } catch (e) {}
    return null;
  }
  function matches(url, method, body) {
    if (String(method).toUpperCase() !== 'POST') return null;
    var path;
    try { path = new URL(url, location.href).pathname; } catch (e) { return null; }
    if (path !== '/api/graphql' && path !== '/graphql/query') return null;
    var form = formOf(body);
    if (!form || form.get('fb_api_req_friendly_name') !== NAME) return null;
    return form.get('doc_id');
  }
  function report(docId, code, text) {
    window.igBridge.postMessage(JSON.stringify({ kind: 'watched', docId: docId, code: code, body: text }));
  }
  var origFetch = window.fetch;
  window.fetch = function (input, init) {
    var url = typeof input === 'string' ? input : (input && input.url);
    var method = (init && init.method) || (input && input.method) || 'GET';
    var docId = matches(url, method, init && init.body);
    var p = origFetch.apply(this, arguments);
    if (docId) p.then(function (r) { return r.clone().text().then(function (t) { report(docId, r.status, t); }); }, function () {});
    return p;
  };
  var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) { this.__w = { method: method, url: url }; return open.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function (body) {
    var w = this.__w, xhr = this;
    var docId = w && matches(w.url, w.method, body);
    if (docId) xhr.addEventListener('loadend', function () { report(docId, xhr.status, typeof xhr.responseText === 'string' ? xhr.responseText : null); });
    return send.apply(this, arguments);
  };
})();
```

- [ ] **Step 2: Write the failing emulator tests** (`AndroidRepairPageTest`, emulator only via `SmokeGuard`, local MockWebServer, origin `http://127.0.0.1:<port>`). Build `AndroidRepairPage(context, allowedOrigin = origin)`.
  - `theDesktopIdentityReachesTheServer`: the server sees `user-agent` containing `Macintosh` and not `Mobile`. If `WebViewFeature.USER_AGENT_METADATA` is supported, `sec-ch-ua-mobile: ?0` is checked too. Otherwise the page refuses to construct (see Step 3) and the test asserts that refusal instead.
  - `aFetchOfTheNamedQueryIsWatched`: a fake Saved page (served at `/saved/`) runs `fetch('/api/graphql', {method:'POST', body: new URLSearchParams({fb_api_req_friendly_name:'PolarisProfileSavedTabContentQuery', doc_id:'42', fb_dtsg: <fake>})})`. Then `watch()` returns `docId = "42"`, `code = 200` and the reply body. The message the app received (captured) does not contain the fake token.
  - `anXhrOfTheNamedQueryIsWatched`: the same, with `XMLHttpRequest` and a string body.
  - `aFormDataBodyIsWatched`: the same, with `FormData`.
  - `otherRequestsAreNeverReported`: the page POSTs another friendly name, and GETs `/api/v1/x`. Then `watch()` returns null at its (short) timeout, and the app received no message.
  - `aLoginLandingIsRepairLandingLogin`: the page redirects to `/accounts/login/` → `RepairLanding.Login`.
  - `a429PageIsPageHttpError429`: `/saved/` → 429 → `PageHttpError(429)`.
  - `destroyEndsAWatchAtOnce`: `destroy()` during `watch()` → it ends (throws) well under the timeout.

- [ ] **Step 3: Implement `AndroidRepairPage`.**
  - `WebView(context.applicationContext)`, JavaScript on, no `addJavascriptInterface`.
  - `settings.userAgentString = DESKTOP_UA` (a desktop Chrome UA on macOS, version from the device WebView's major: `WebViewCompat.getCurrentWebViewPackage(context)?.versionName?.substringBefore('.')`).
  - When `WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)` is true, call `WebSettingsCompat.setUserAgentMetadata(settings, UserAgentMetadata.Builder().setMobile(false).setPlatform("macOS").setPlatformVersion("15.0.0").setArchitecture("arm").setModel("").setBrandVersionList(<Chromium + Google Chrome, same major>).setFullVersion(<major>.0.0.0).build())`. Otherwise **throw on construction** (`IllegalStateException("no desktop mode")`), because a desktop UA with mobile client hints is a mismatch we never send.
  - `settings.useWideViewPort = true`, `settings.loadWithOverviewMode = true`. After creation, `webView.measure(exactly 1440, exactly 900)` and `webView.layout(0, 0, 1440, 900)`.
  - `WebViewCompat.addDocumentStartJavaScript(webView, igWatchScript, setOf(allowedOrigin))`. Throw on construction if `DOCUMENT_START_SCRIPT` is unsupported.
  - `addWebMessageListener("igBridge", setOf(allowedOrigin))` with the same main-frame/origin/string checks as `AndroidWebPage`. It accepts only JSON with `kind == "watched"` and a `docId` that is 1–30 digits; it completes a `CompletableDeferred<WatchedQuery>`.
  - `watch()`:
    - `load(url)` via the shared `PageClient` (an HTTP error page throws `PageHttpError`; a dead renderer fails the wait);
    - check `landingOf(path)` → `RepairLanding`;
    - then `withTimeoutOrNull(timeoutMs)` for the watched message.
  - `destroy()` fails any wait and destroys the WebView.

- [ ] **Step 4: Run.** `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest`. Expected: PASS. Show that each test fails on a mutant before it passes: no `setUserAgentMetadata`, no XHR wrapper, `kind` check removed, report every friendly name.

- [ ] **Step 5: JVM source pins** (`RepairPageGuardTest`):
  - `ig_watch.js`'s `NAME` equals `WebGraphQl.SAVED_COLLECTIONS.friendlyName`.
  - its only `postMessage` sends `{kind, docId, code, body}`, with `docId` from `form.get('doc_id')`.
  - it never mentions `fb_dtsg`, `lsd`, `cookie` or `sessionStorage`.
  - `AndroidRepairPage` has no `addJavascriptInterface`, and its listener keeps the three conditions (reuse `AndroidWebPageGuardTest`'s helpers).

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/assets/ig_watch.js app/src/main/kotlin/io/github/yuriimurha/reels/transport/RepairPage.kt app/src/main/kotlin/io/github/yuriimurha/reels/transport/AndroidRepairPage.kt <tests>
git commit -m "feat(transport): a desktop-mode repair page that watches the site's own collections query" -m "Pacing: not wired yet; when used (Task 5) it is one desktop page view, at most once per 24 h."
```

---

### Task 4: Collections parser, stale rule, doc-id store and the client

**Files:**
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/InstagramException.kt` (add `StaleQuery`)
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/InstagramClient.kt` (add `repairCollections`)
- Create: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/DocIdStore.kt`
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebParsers.kt` (`collectionsGraphQl`, `classifyGraphQl`)
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/web/WebInstagramClient.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/data/settings/SettingsStore.kt` (doc id, repair time, names-stale flag)
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/data/settings/SettingsDocIdStore.kt`
- Test: `WebParsersTest`, `WebInstagramClientTest` (`:instagram`), `SettingsStoreTest`, `SettingsDocIdStoreTest` (`:app`)

**Interfaces:**
- Consumes: Task 1 facts (STALE, CURSOR, AUTO); `WebGraphQl`, `InstagramTransport.graphql` (Task 2).
- Produces:
  ```kotlin
  // InstagramException (a SEALED class: subclasses must live in InstagramException.kt; fix every exhaustive `when`)
  class StaleQuery(val query: String) : InstagramException("stale query $query")   // never carries a doc id
  /** The repair could not run or did not find the query; sync falls back to the last names. */
  sealed class RepairUnavailable(reason: String) : InstagramException("collections repair unavailable: $reason")
  class RepairSkipped(reason: String) : RepairUnavailable(reason)   // the 24 h limit, or no stored handle
  class RepairFailed(reason: String) : RepairUnavailable(reason)    // no query seen, page error, stale repaired reply
  // DocIdStore.kt
  interface DocIdStore {
      suspend fun docId(query: GraphQlQuery): String
      suspend fun learned(query: GraphQlQuery, docId: String)
  }
  /** What a repair saw: the site's own reply for the query and the id it used. */
  class RepairedQuery(val docId: String, val reply: RawReply)
  fun interface QueryRepair { suspend fun repair(query: GraphQlQuery): RepairedQuery }   // throws when it can't
  // InstagramClient
  /** Learns the current collections query from the site itself (spec 2026-10-09 §3.3) and returns its first page. */
  suspend fun repairCollections(): Page<RemoteCollection> = throw InstagramException.Transient()   // default: fake backend
  // WebInstagramClient(transport, cookies, docIds: DocIdStore, repair: QueryRepair, reportsSavedCollectionIds = true)
  // SettingsStore
  suspend fun graphqlDocId(name: String): String?; suspend fun setGraphqlDocId(name: String, docId: String?)
  suspend fun collectionsRepairAt(): Long?; suspend fun setCollectionsRepairAt(at: Long)
  val collectionNamesStale: Flow<Boolean>; suspend fun setCollectionNamesStale(stale: Boolean)
  ```

- [ ] **Step 1: Write the failing parser tests** (`WebParsersTest`). The fixtures are scrubbed: names `Alpha`, `Beta`; ids `17900000000000001`…; covers `3100000000000000001`.
  - `collectionsGraphQlKeepsUserCollectionsAndSkipsAutomaticOnes`: a reply with an automatic node (per fact AUTO: `"collection_id":"ALL_MEDIA_AUTO_COLLECTION"`) and two user nodes returns two `RemoteCollection`s in order, with `cover_media.pk` as the cover.
  - `aLeadingForLoopGuardIsStripped`: the body starts with `for (;;);`.
  - `page2CursorComesFromPageInfo`: `has_next_page:true`, `end_cursor:"c1"` → `nextCursor == "c1"`. `has_next_page:false` → null. A missing `page_info` → `ShapeChanged("page_info")`.
  - `namesAreKeptAsIs`: `"Q\"uote 😀 שלום"` round-trips. An empty name becomes `""`; Task 5 turns it into a placeholder.
  - `classifyGraphQl`:
    - fact STALE's reply → `StaleQuery`;
    - a 429 → `RateLimited`;
    - `{"require_login":true}` → `LoginRequired`;
    - a redirect → `ChallengeRequired(null)`;
    - a 200 with `data.viewer.collections_unified_with_auto_collections` → null (parse it);
    - a 200 with `data` but the root missing and no `errors` → `ShapeChanged("data.viewer.collections_unified_with_auto_collections")`.

```kotlin
private fun node(id: String, name: String, cover: String? = null) =
    """{"node":{"__typename":"XDTSavedCollection","collection_id":"$id","collection_name":${JsonPrimitive(name)},""" +
        """"collection_media_count":1,"cover_media":${cover?.let { "{\"pk\":\"$it\"}" } ?: "null"}},"cursor":"x"}"""
private fun reply(vararg edges: String, next: String? = null) =
    """{"data":{"viewer":{"collections_unified_with_auto_collections":{"edges":[${edges.joinToString(",")}],""" +
        """"page_info":{"has_next_page":${next != null},"end_cursor":${next?.let { "\"$it\"" } ?: "null"}}}}}}"""
```

- [ ] **Step 2: Run.** `./gradlew :instagram:test --tests '*WebParsersTest*'`. Expected: FAIL (unresolved).

- [ ] **Step 3: Implement `WebParsers.collectionsGraphQl(json)` and `classifyGraphQl(reply)`.**
  - `classifyGraphQl` first applies `classifyReply`, except that an HTTP 400/404/500 non-JSON reply that `classifyReply` maps to `ShapeChanged("http.<code>")` becomes `StaleQuery` (fact STALE). It then parses the body after stripping a leading `for (;;);`. A body with `errors` and without the root path is `StaleQuery`.
  - `collectionsGraphQl` reads `data.viewer.collections_unified_with_auto_collections.edges[].node` and keeps nodes whose `collection_id` is all digits (fact AUTO). A node missing `collection_id` or `collection_name` is `ShapeChanged("edges[i].node.<field>")`, as today.

- [ ] **Step 4: Run.** Expected: PASS.

- [ ] **Step 5: Write the failing client tests** (`WebInstagramClientTest`, `OkHttpTransport` + MockWebServer, a fake `DocIdStore` and a fake `QueryRepair`):
  - `collectionsUsesTheStoredDocId`: the store answers `"555"` → the POST's form has `doc_id=555`.
  - `aStaleReplyThrowsStaleQueryAndNeverRepairsByItself`: `collections()` throws `StaleQuery`, and the repair is not called. Sync decides (Task 5).
  - `repairCollectionsLearnsTheIdAndParsesTheSitesReply`: the repair returns `RepairedQuery("777", <reply>)` → the store `learned("777")` and the page is parsed. No request goes to the server.
  - `aRepairWhoseReplyIsStaleIsAFailure`: the repaired reply is itself stale → `StaleQuery`, and nothing is learned.
  - `reportsSavedCollectionIdsIsTrue`.

- [ ] **Step 6: Implement.**
  - `WebInstagramClient.collections(cursor) = parse(transport.graphql(SAVED_COLLECTIONS, docIds.docId(SAVED_COLLECTIONS), WebGraphQl.savedCollectionsVariables(cursor)))`.
  - `repairCollections()` calls `repair.repair(SAVED_COLLECTIONS)`, parses its `reply` (a `StaleQuery`/error throws before learning), then `docIds.learned(...)`.
  - Set `SAVED_COLLECTION_IDS_CONFIRMED = true` and update its KDoc: "spike Q2 answered yes on 2026-10-09".
  - `WebEndpoints.collections` and the lab's `COLLECTIONS` URL are removed (the lab moves to GraphQL in Task 5). Fix every reference.

- [ ] **Step 7: SettingsStore + SettingsDocIdStore.** Keys:
  - `graphql_doc_<friendly name>` (String);
  - `collections_repair_at` (Long);
  - `collection_names_stale` (Boolean).

  `SettingsDocIdStore(settings)` answers the stored id or `query.builtInDocId`. Tests:
  - the default is the built-in id;
  - `learned` overrides it;
  - `setGraphqlDocId(name, null)` goes back to the built-in id;
  - the corruption fallback keeps working.

- [ ] **Step 8: Run.** `./gradlew check`. Expected: PASS.

- [ ] **Step 9: Commit.**

```bash
git add <the files above>
git commit -m "feat(instagram): collections through the website's GraphQL query, a stale-query rule and a learned doc id" -m "Pacing: the names query replaces the 404 collections call one for one; SAVED_COLLECTION_IDS_CONFIRMED = true drops the per-collection feed requests (fewer requests per sync)."
```

---

### Task 5: Sync, repair limit, fallback, placeholders, the lab and the Developer action

**Files:**
- Create: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/QueryRepairer.kt`
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/sync/SyncEngine.kt` (`fetchCollections`, `walkScope` placeholders)
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/data/db/CollectionDao.kt` (`liveCollectionsNow()`)
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/di/AppContainer.kt` (wiring)
- Modify: `instagram/src/main/kotlin/io/github/yuriimurha/reels/instagram/lab/AdapterLab.kt` (`COLLECTIONS` via GraphQL)
- Modify: `app/src/main/kotlin/io/github/yuriimurha/reels/ui/sync/DeveloperSection.kt`, `SyncViewModel.kt`, `SyncUiState.kt` (Forget button, notice)
- Test: `QueryRepairerTest`, `SyncEngineTest`, `AdapterLabTest`, `SyncViewModelTest`, `SyncScreenTest` (Compose)

**Interfaces:**
- Consumes:
  - `InstagramClient.collections/repairCollections`, `StaleQuery`, `QueryRepair`, `RepairedQuery` (Task 4);
  - `RepairPage`, `WatchedQuery`, `RepairLanding` (Task 3);
  - `SettingsStore` keys (Task 4).
- Produces: `class QueryRepairer(settings, handle: suspend () -> String?, createPage: () -> RepairPage, now: () -> Long, main: CoroutineDispatcher) : QueryRepair`.

- [ ] **Step 1: Write the failing `QueryRepairerTest`** (fake `RepairPage`, virtual time):
  - `refusesWithin24hOfTheLastAttempt`: `collections_repair_at = now - 1h` → it throws `RepairSkipped` and creates no page.
  - `recordsTheAttemptBeforeLoading`: `collections_repair_at` is set to `now` before `watch` runs, so even a crash mid-repair counts.
  - `loadsTheOwnSavedPageInDesktopMode`: `watch` gets `https://www.instagram.com/<handle>/saved/` (handle from the session; none → `RepairSkipped`, no page). The friendly name is `SAVED_COLLECTIONS.friendlyName` and the timeout `45_000`.
  - `aWatchedQueryBecomesARepairedQuery`: `WatchedQuery("777", 200, body)` → `RepairedQuery("777", RawReply(200, null, body))`.
  - `outcomes`:
    - `PageHttpError(429)` → `InstagramException.RateLimited` (so the Pacer arms the cooldown);
    - `RepairLanding.Login` → `LoginRequired`;
    - `RepairLanding.Challenge` → `ChallengeRequired(null)`;
    - `null` (never came) → `RepairFailed("no query")`;
    - any other error → `RepairFailed`.
  - `thePageIsAlwaysDestroyed`, on every outcome including cancellation.

  `RepairSkipped` and `RepairFailed` are the `InstagramException.RepairUnavailable` subclasses from Task 4.

- [ ] **Step 2: Implement `QueryRepairer`.** Use `withContext(main)` for the page, `try/finally { page.destroy() }`, and the order: limit check → handle → record attempt → create page → watch. It takes `log: ((String) -> Unit)?` (the container's debug sink) and logs exactly `repair: start`, `repair: learned new id` (on a watched query), or `repair: failed (<reason>)` with a reason from a fixed set (`limit`, `no handle`, `http <code>`, `login page`, `challenge page`, `no query`, `page error`). Never the id, the handle or a body; a test pins the lines.

- [ ] **Step 3: Write the failing `SyncEngineTest` cases** (fake client):
  - `aStaleQueryRepairsOnceThenSyncs`: `collections()` throws `StaleQuery` once; `repairCollections()` returns `[Alpha]` → collection rows are named, and the run is DONE. `repairCollections` goes through `pacer.sync` (one budget unit).
  - `aFailedRepairKeepsTheLastNamesAndRaisesTheNotice`: the DB already has `Alpha(id1)`; stale + `RepairFailed` → the run is DONE, Alpha keeps its name, `collection_names_stale = true`, and nothing is marked removed.
  - `aSkippedRepairBehavesLikeAFailedOne`.
  - `aRateLimitedRepairStopsTheRunLikeAnyRateLimit`: STOPPED_RATE_LIMIT, and the cooldown is armed.
  - `aGoodNamesFetchClearsTheNotice`.
  - `anUnnamedCollectionSeenOnItemsGetsAPlaceholder` (fallback mode): an item lists `id2`, which is not in the DB → a collection `id2` named `Collection 1` exists with the membership. A second unknown id gets `Collection 2`. A later good names fetch renames them.
  - `anEmptyNameBecomesAPlaceholder`.
  - `aSyncSendsNoPerCollectionFeedRequests`: count client calls. Exactly one `collections`, and `savedMedia` is only ever called with `collectionId == null`.
  - `forgetThenSyncRepairsExactlyOnce` (Review Focus 5): a stale query, one repair, the names.

- [ ] **Step 4: Implement in `SyncEngine.fetchCollections`.**

```kotlin
    private suspend fun fetchCollections(progress: Progress): Pair<List<CollectionEntity>, Boolean> {
        val remote = mutableListOf<RemoteCollection>()
        var cursor: String? = null
        try {
            do {
                val from = cursor
                val page = try {
                    call(progress) { client.collections(from) }
                } catch (e: InstagramException.StaleQuery) {
                    if (from != null) throw e        // a stale id on page 2 is a broken answer: fall back
                    call(progress) { client.repairCollections() }
                }
                remote += page.items
                cursor = page.nextCursor
            } while (cursor != null)
        } catch (e: InstagramException.StaleQuery) {
            return fallbackCollections() to true
        } catch (e: InstagramException.RepairUnavailable) {
            return fallbackCollections() to true
        }
        // (existing empty-list guard, upsert and markRemovedExcept, unchanged; empty names become placeholders)
        names.setCollectionNamesStale(false)
        return live to false
    }
```

`fallbackCollections()` returns `collectionDao.liveCollectionsNow()` and sets `collection_names_stale = true`. In fallback mode `walkScope` gets `placeholders = true`. In the strategy-A block, an id not in `knownCollections` then creates `CollectionEntity(id, "Collection ${n}", coverPk = null, position = <after the last>)` and adds it to the known set before writing memberships. `n` is one more than the number of live collections whose name matches `Collection <digits>` (a new DAO query `placeholderCount()` with `name GLOB 'Collection [0-9]*'`), so numbering continues across runs. `SyncEngine` logs `collections query stale` (debug sink, a new optional constructor lambda `log: ((String) -> Unit)? = null`) when it catches `StaleQuery` on page 1. The caller in `run()` takes the `Pair`: `(collections, fallback)`, and passes `fallback` to `walkScope` as `placeholders`. The engine gets the names-stale setter as a constructor lambda (`setNamesStale: suspend (Boolean) -> Unit = {}`), so the fake backend is unaffected.

- [ ] **Step 5: Wire it in `AppContainer`.**
  - `WebInstagramClient({ instagramTransport }, cookieStore, SettingsDocIdStore(settings), QueryRepairer(settings, handle = { settings.session.first().handle }, createPage = { AndroidRepairPage(context) }, now = System::currentTimeMillis, log = debugLog))`.
  - `SyncEngine(..., setNamesStale = settings::setCollectionNamesStale)`.
  - Mock mode builds none of it: extend `InstagramTransportWiringTest`/`BackendWiringGuardTest` (`AndroidRepairPage(` constructed only in `di/AppContainer.kt`, and never in the Fake branch).

- [ ] **Step 6: The lab.** `LabCall.COLLECTIONS` sends `transport.graphql(SAVED_COLLECTIONS, docIds.docId(..), savedCollectionsVariables(null))` and shows the shape (after stripping `for (;;);`). `idsOf` reads the first user collection id from the GraphQL edges. `AdapterLab` takes a `DocIdStore`. `AdapterLabTest`: one POST, stored id used, shape shown, ids read.

- [ ] **Step 7: The Developer action and the notice.**
  - `DeveloperSection` gains `OutlinedButton(onClick = onForgetQueryId) { Text("Forget collections query id") }`, enabled in real mode only.
  - `SyncViewModel.forgetQueryId()` sets the stored id to `"0"`, which is never a real id, so the next use is stale and repairs. It also clears `collections_repair_at`, so the repair isn't refused by the 24 h limit.
  - `SyncUiState` shows `Couldn't refresh collection names` while `collectionNamesStale` is true.
  - Tests: Compose (button present in Real, absent in Mock; notice shown/hidden), plus a ViewModel test (store and limit cleared).

- [ ] **Step 8: Run.** `./gradlew check`, then `ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest` (the smoke suite must stay green; Mock mode builds no repair page).

- [ ] **Step 9: Commit.**

```bash
git add <the files above>
git commit -m "feat(sync): collection names from the website's query, self-repair once a day, last names kept on failure" -m "Pacing: a sync sends fewer requests (one names query, no per-collection feeds). A repair is one desktop page view of Saved (the site's own ~30 requests, unpaced like the home load), at most once per 24 h and only after a rejected id; it runs inside the Pacer's gate."
```

---

### Task 6: Docs and the phone rollout steps

**Files:** `ARCHITECTURE.md`, `README.md`, `TODO.md`, `PROGRESS.md` (append only), the 2026-10-08 transport spec (an amendments line pointing to the 2026-10-09 spec).

- [ ] **Step 1: ARCHITECTURE.** Cover:
  - the GraphQL seam;
  - the allow-list;
  - tokens kept in the page;
  - the repair page (desktop mode, `ig_watch.js`, what it reports);
  - the doc-id store;
  - the 24 h limit;
  - the fallback and placeholders;
  - strategy A on;
  - the traffic statement (fewer requests per sync; repair = one desktop page view, ≤ 1/24 h);
  - the new tests.

  Remove the `api/v1/collections/list/` endpoint row.
- [ ] **Step 2: README.** Add a rollout box for this update:
  1. install;
  2. clear the log;
  3. one sync (expect `GRAPHQL PolarisProfileSavedTabContentQuery -> 200`);
  4. check the collection names on the Grid;
  5. **Forget collections query id**, then one sync (expect `collections query stale`, `repair: learned new id`, names intact);
  6. paste the log.
- [ ] **Step 3: TODO.**
  - Tick spike Q2 and the `SAVED_COLLECTION_IDS_CONFIRMED` flip.
  - Add a "Later" item: learn `x-ig-app-id`/`x-asbd-id` from the site by the same watch mechanism.
- [ ] **Step 4: PROGRESS.** One dated entry (2026-10-09): the phone findings, the design, and the tasks.
- [ ] **Step 5: Commit.** `docs: collection names, the repair page and the rollout steps`.
