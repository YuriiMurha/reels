package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.testutil.KotlinSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Source pins for the repair page: its script `ig_watch.js` and the channel `AndroidRepairPage` reads it through (in the
 * style of [AndroidWebPageGuardTest] and `WebViewTransportTest`'s script pins). The emulator test (`AndroidRepairPageTest`)
 * shows the page at work against a local server; these pins say what it can never do, whatever a fake page does:
 * - the script is exactly the text below, built from `:instagram`'s names (the watched query, `WebGraphQl.QUERY_PATHS`, the
 *   two form fields), so any change to it is a deliberate change of this test too;
 * - whatever that text becomes, its one message to the app is `{kind, docId, code, body}`, posted by `report()` alone, and it
 *   never names a token, the cookie jar, the session storage or the console (WebView logs `console` to logcat);
 * - the page has no JavaScript interface, its own WebView (never the transport's page or listener), the watching script and
 *   the bridge for the allowed origin only, and the bridge's listener keeps the three conditions both pages share
 *   ([BridgeListenerPin]);
 * - it spells out no Instagram origin of its own: its default is `WebEndpoints.HOME_URL`'s.
 * Each pin fails for the mutants below, made from the real sources, so a pass means something.
 */
class RepairPageGuardTest {
    private fun read(path: String): String {
        val file = File(path)
        assertTrue(file.isFile, "unit tests must run from the app module directory, and $path must exist")
        return file.readText()
    }

    private fun script(): String = read(SCRIPT_PATH)

    private fun page(): String = read(PAGE_PATH)

    // --- ig_watch.js -------------------------------------------------------------------------------------------------------

    @Test
    fun theScriptIsExactlyTheOneTheTestsKnow() {
        assertScriptIsTheKnownOne(script())
    }

    @Test
    fun theScriptsOnlyMessageIsTheWatchedQuerysIdStatusAndReply() {
        assertScriptPostsOnlyTheReport(script())
    }

    @Test
    fun theScriptNeverMentionsATokenTheCookiesTheSessionStorageOrTheConsole() {
        assertScriptNamesNoSecret(script())
    }

    /** The whole-text pin is tied to each `:instagram` name: the known text made with any other one is not the script. */
    @Test
    fun theKnownScriptIsBuiltFromTheAdaptersNames() {
        val real = script()
        for ((what, other) in mapOf(
            "another query" to knownScript(name = "PolarisSomeOtherQuery"),
            "another path" to knownScript(paths = listOf(WebGraphQl.PATH, "graphql/other")),
            "one path fewer" to knownScript(paths = listOf(WebGraphQl.PATH)),
            "another friendly-name field" to knownScript(friendlyNameField = "fb_api_req_other"),
            "another doc-id field" to knownScript(docIdField = "query_id"),
        )) {
            assertNotEquals(other, real, what)
        }
    }

    /**
     * The script pins themselves: every mutant fails the whole-text pin; and a mutant that leaks through the report or names a
     * secret also fails the structural pin aimed at it, checked alone, so those hold even if the known text were edited along.
     */
    @Test
    fun eachWayOfWideningOrLeakingTheScriptFailsItsPins() {
        val real = script()
        val name = WebGraphQl.SAVED_COLLECTIONS.friendlyName
        val watching = mapOf(
            "another query" to real.replace("var NAME = '$name';", "var NAME = 'PolarisSomeOtherQuery';"),
            "every friendly name reported" to real.replace("form.get('fb_api_req_friendly_name') === NAME ? form.get('doc_id') : null", "form.get('doc_id')"),
            "a GET watched" to real.replace("String(method).toUpperCase() === 'POST' && ", ""),
            "any path watched" to real.replace("PATHS.indexOf(new URL(String(url), location.href).pathname) >= 0", "true"),
            "a third path watched" to real.replace("'/graphql/query'];", "'/graphql/query', '/api/v1/x'];"),
            "the doc id from another field" to real.replace("? form.get('doc_id') : null", "? form.get('variables') : null"),
            "any status reported" to real.replace("if (!docId || !(code >= 100 && code <= 599)) return;", "if (!docId) return;"),
            "a Request's body not read" to real.replace("(request ? request.clone().text() : Promise.resolve(null))", "Promise.resolve(null)"),
            "the request's body as the reply" to real.replace("report(both[0], both[1].status, t);", "report(both[0], both[1].status, t + String(init.body));"),
            "the XHR's request body as the reply" to real.replace("report(id, code, text);", "report(id, code, String(body));"),
            "responseText whatever the type" to real.replace("    try {\n      if (xhr.responseType", "    return xhr.responseText;\n    try {\n      if (xhr.responseType"),
            "a throw into the site" to real.replace("    } catch (e) {}\n    var p = origFetch", "    } finally {}\n    var p = origFetch"),
        )
        val reporting = mapOf(
            "a field more" to real.replace("code: code, body: text }", "code: code, body: text, url: location.href }"),
            "a field fewer" to real.replace(", body: text }", " }"),
            "the body is something else" to real.replace("body: text }", "body: document.title }"),
            "another kind" to real.replace("kind: 'watched'", "kind: 'seen'"),
            "a second message" to real.replace("    return send.apply(this, arguments);", "    window.igBridge.postMessage(String(body));\n    return send.apply(this, arguments);"),
            "the bridge kept under another name" to real.replace("var origFetch = window.fetch;", "var b = window.igBridge; var origFetch = window.fetch;"),
            "the post through a computed member" to real.replace("window.igBridge.postMessage(", "window.igBridge['postMessage']("),
            "a post outside report()" to real.replace(
                "    if (!docId || !(code >= 100 && code <= 599)) return;\n    window.igBridge.postMessage(JSON.stringify({ kind: 'watched', docId: docId, code: code, body: text }));\n",
                "    if (!docId || !(code >= 100 && code <= 599)) return;\n",
            ).replace(
                "          docId.then(function (id) { report(id, code, text); })",
                "          docId.then(function (id) { window.igBridge.postMessage(JSON.stringify({ kind: 'watched', docId: id, code: code, body: text })); })",
            ),
        )
        val naming = mapOf(
            "the page's token read" to real.replace("? form.get('doc_id') : null", "? form.get('doc_id') + form.get('fb_dtsg') : null"),
            "the site's token module read" to real.replace("var origFetch = window.fetch;", "var d = require('DTSGInitialData'); var origFetch = window.fetch;"),
            "the lsd read" to real.replace("var form = new URLSearchParams(text);", "var form = new URLSearchParams(text); var l = form.get('lsd');"),
            "the cookie jar read" to real.replace("var origFetch = window.fetch;", "var c = document.cookie; var origFetch = window.fetch;"),
            "a cookie header read" to real.replace("var code = xhr.status,", "var c = xhr.getResponseHeader('Set-Cookie'), code = xhr.status,"),
            "the session storage read" to real.replace("var origFetch = window.fetch;", "var s = sessionStorage.getItem('www-claim-v2'); var origFetch = window.fetch;"),
            "the reply logged" to real.replace("report(both[0], both[1].status, t);", "report(both[0], both[1].status, t); console.log(t);"),
        )
        for ((what, mutant) in watching + reporting + naming) {
            assertTrue(mutant != real, "the mutant '$what' did not change the script")
            assertFailsWith<AssertionError>(what) { assertScriptIsTheKnownOne(mutant) }
        }
        for ((pin, mutants) in listOf(::assertScriptPostsOnlyTheReport to reporting, ::assertScriptNamesNoSecret to naming)) {
            for ((what, mutant) in mutants) {
                assertFailsWith<AssertionError>(what) { pin(mutant) }
            }
        }
    }

    private fun assertScriptIsTheKnownOne(script: String) {
        assertEquals(knownScript(), script, "ig_watch.js is exactly the script the tests know")
    }

    /**
     * The one message to the app is `{kind: 'watched', docId, code, body}`, posted by `report()` alone, with that function's
     * own arguments: the bridge and `postMessage` are named once, inside `report()`.
     */
    private fun assertScriptPostsOnlyTheReport(script: String) {
        assertEquals(1, Regex("""\bpostMessage\b""").findAll(script).count(), "postMessage is named once (no computed member)")
        assertEquals(1, Regex("""\bigBridge\b""").findAll(script).count(), "the bridge is named once (never kept under another name)")
        val posts = Regex("""window\.igBridge\.postMessage\(JSON\.stringify\(\{(.*?)\}\)\);""").findAll(block(script, "function report(docId, code, text) {")).toList()
        assertEquals(1, posts.size, "report() posts the one message")
        assertEquals(
            listOf("kind" to "'watched'", "docId" to "docId", "code" to "code", "body" to "text"),
            fieldsOf(posts.single().groupValues[1]),
            "the message's fields",
        )
    }

    /**
     * No token, no cookie jar, no session storage (the site's claim): the script never names them, so it cannot read them.
     * And no `console`, which a WebView writes to logcat.
     */
    private fun assertScriptNamesNoSecret(script: String) {
        for (word in listOf("dtsg", "lsd", "cookie", "sessionStorage", "console")) {
            assertFalse(script.contains(word, ignoreCase = true), "the script never mentions `$word`")
        }
    }

    // --- AndroidRepairPage.kt -------------------------------------------------------------------------------------------------

    @Test
    fun thePageHasNoJavascriptInterface() {
        assertNoJavascriptInterface(page())
    }

    @Test
    fun thePagesBridgeIsOnlyForTheAllowedOriginAndOnlyTheMainFrameStringsGetThrough() {
        assertChannelIsClosed(page())
    }

    @Test
    fun theWatchingScriptRunsInTheAllowedOriginOnly() {
        assertScriptIsForTheAllowedOriginOnly(page())
    }

    @Test
    fun thePageIsItsOwnWebViewNeverTheTransportsPage() {
        assertOwnPage(page())
    }

    @Test
    fun thePageSpellsOutNoInstagramOriginOfItsOwn() {
        assertOriginIsTheAdaptersOwn(page())
    }

    /** The page pins themselves, on mutants made from the real source: each fails the pin it is aimed at, checked alone. */
    @Test
    fun eachWayOfOpeningThePageFailsItsPin() {
        val real = page()
        val handOver = "                        message.data?.let { received(it) }\n                    }\n"
        val listener = "addWebMessageListener(webView, BRIDGE, setOf(allowedOrigin))"
        val webView = "    private val webView = WebView(context.applicationContext)"
        val interfaces = mapOf(
            "a JavaScript interface" to real.replace("enableScripting()\n", "enableScripting()\n            webView.addJavascriptInterface(Any(), \"x\")\n"),
        )
        val channels = mapOf(
            "the bridge's rule widened to every origin" to real.replace(listener, "addWebMessageListener(webView, BRIDGE, setOf(\"*\"))"),
            "the bridge's rule widened to a pattern" to real.replace(listener, "addWebMessageListener(webView, BRIDGE, setOf(\"https://*\"))"),
            "no main-frame check" to real.replace("isMainFrame && ", ""),
            "no origin check" to real.replace("sourceOrigin.toString() == allowedOrigin && ", ""),
            "no string check" to real.replace(" && message.type == WebMessageCompat.TYPE_STRING", ""),
            "a term made optional" to real.replace("isMainFrame && sourceOrigin", "isMainFrame || sourceOrigin"),
            "the guard removed altogether" to real.replace(
                "if (isMainFrame && sourceOrigin.toString() == allowedOrigin && message.type == WebMessageCompat.TYPE_STRING) {",
                "run {",
            ),
            "handed over before the guard" to real.replace("// The data is read only once", "received(\"\")\n                    // The data is read only once"),
            "handed over in an else branch" to real.replace(handOver, "                        message.data?.let { received(it) }\n                    } else {\n$handOver"),
            "handed over again after the guard" to real.replace(handOver, handOver + "                    message.data?.let { received(it) }\n"),
        )
        val scripts = mapOf(
            "the script's rule widened to every origin" to
                real.replace("addDocumentStartJavaScript(webView, script, setOf(allowedOrigin))", "addDocumentStartJavaScript(webView, script, setOf(\"*\"))"),
            "a second script" to real.replace("            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {", "            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(\"*\"))\n            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {"),
        )
        val pages = mapOf(
            "the transport's page" to real.replace(") : RepairPage {", ") : RepairPage, WebPage {"),
            "the transport's page inside" to real.replace(webView, "$webView\n    private val shared = AndroidWebPage(context)"),
            "the transport inside" to real.replace(webView, "$webView\n    private val shared: WebViewTransport? = null"),
            "a second WebView" to real.replace(webView, "$webView\n    private val other = WebView(context)"),
        )
        val origins = mapOf(
            "an origin of its own" to real.replace(
                "private val allowedOrigin: String = WebEndpoints.HOME_URL.toUri().let { \"\${it.scheme}://\${it.host}\" },",
                "private val allowedOrigin: String = \"https://www.instagram.com\",",
            ),
            "an origin from another URL" to real.replace("WebEndpoints.HOME_URL.toUri().let", "WebEndpoints.LOGIN_URL.toUri().let"),
        )
        for ((pin, mutants) in listOf(
            ::assertNoJavascriptInterface to interfaces,
            ::assertChannelIsClosed to channels,
            ::assertScriptIsForTheAllowedOriginOnly to scripts,
            ::assertOwnPage to pages,
            ::assertOriginIsTheAdaptersOwn to origins,
        )) {
            for ((what, mutant) in mutants) {
                assertTrue(mutant != real, "the mutant '$what' did not change the source")
                assertFailsWith<AssertionError>(what) { pin(mutant) }
            }
        }
    }

    private fun assertNoJavascriptInterface(source: String) {
        assertFalse("addJavascriptInterface" in KotlinSource.code(source), "AndroidRepairPage never calls addJavascriptInterface")
    }

    /** The same pin as [AndroidWebPageGuardTest]'s, for this page's own listener, which hands a message to `received()`. */
    private fun assertChannelIsClosed(source: String) {
        assertEquals(BridgeListenerPin.CLOSED, BridgeListenerPin.channelOf(source, "AndroidRepairPage.kt", handOver = "received("))
    }

    /** The watching script is installed once, for exactly the allowed origin (never `*`). */
    private fun assertScriptIsForTheAllowedOriginOnly(source: String) {
        val calls = KotlinSource.callArguments(source, "WebViewCompat.addDocumentStartJavaScript(")
        assertEquals(listOf("webView,script,setOf(allowedOrigin)"), calls.map { call -> call.filterNot { it.isWhitespace() } })
    }

    /**
     * Its own WebView, never the transport's page: one `WebView(` constructed, the class is a [RepairPage] and nothing else, and
     * it never names [WebPage], [AndroidWebPage] or [WebViewTransport], so it cannot be one or reach one's listener.
     */
    private fun assertOwnPage(source: String) {
        val code = KotlinSource.code(source)
        assertEquals(1, Regex("""\bWebView\(""").findAll(code).count(), "one WebView, its own")
        assertTrue(Regex("""^class AndroidRepairPage\($""", RegexOption.MULTILINE).containsMatchIn(code), "the class header")
        assertTrue(Regex("""^\) : RepairPage \{$""", RegexOption.MULTILINE).containsMatchIn(code), "a RepairPage and nothing else")
        for (other in listOf("WebPage", "AndroidWebPage", "WebViewTransport")) {
            assertFalse(Regex("""\b$other\b""").containsMatchIn(code), "AndroidRepairPage never names $other")
        }
    }

    /** No string of the page names Instagram: the allowed origin's default is `WebEndpoints.HOME_URL`'s scheme and host. */
    private fun assertOriginIsTheAdaptersOwn(source: String) {
        val code = KotlinSource.code(source)
        assertFalse(Regex(""""[^"\n]*instagram[^"\n]*"""", RegexOption.IGNORE_CASE).containsMatchIn(code), "a string of the page names Instagram")
        val defaults = Regex("""\ballowedOrigin\s*:\s*String\s*=\s*(.*),\s*$""", RegexOption.MULTILINE).findAll(code).map { it.groupValues[1] }.toList()
        assertEquals(listOf("WebEndpoints.HOME_URL.toUri().let { \"\${it.scheme}://\${it.host}\" }"), defaults, "the default origin")
    }

    // --- Plumbing ------------------------------------------------------------------------------------------------------------

    /** The text between the `{` that ends [opening] and its matching `}` (quoted strings skipped). */
    private fun block(script: String, opening: String): String {
        val start = script.indexOf(opening)
        assertTrue(start >= 0, "the script has no `$opening`")
        var depth = 0
        var quote: Char? = null
        var k = start + opening.length - 1
        while (k < script.length) {
            val c = script[k]
            when {
                quote != null -> if (c == '\\') k++ else if (c == quote) quote = null
                c == '\'' || c == '"' -> quote = c
                c == '{' -> depth++
                c == '}' -> if (--depth == 0) return script.substring(start + opening.length, k)
            }
            k++
        }
        error("unbalanced `$opening` in the script")
    }

    /** `key: value` pairs of a flat object literal, in order. */
    private fun fieldsOf(literal: String): List<Pair<String, String>> =
        literal.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { field ->
            val colon = field.indexOf(':')
            assertTrue(colon > 0, "not a key: value field: $field")
            field.substring(0, colon).trim() to field.substring(colon + 1).trim()
        }

    private companion object {
        const val SCRIPT_PATH = "src/main/assets/ig_watch.js"
        const val PAGE_PATH = "src/main/kotlin/io/github/yuriimurha/reels/transport/AndroidRepairPage.kt"

        /**
         * ig_watch.js, exactly, made from `:instagram`'s names: the watched query's friendly [name], the [paths] the site may
         * post it to, and the form fields that carry the query's name and its doc id. Any change to the script must change this
         * text too.
         */
        fun knownScript(
            name: String = WebGraphQl.SAVED_COLLECTIONS.friendlyName,
            paths: List<String> = WebGraphQl.QUERY_PATHS,
            friendlyNameField: String = WebGraphQl.Field.FRIENDLY_NAME,
            docIdField: String = WebGraphQl.Field.DOC_ID,
        ): String = """
            (function () {
              if (window.__igWatch) return;
              window.__igWatch = true;
              // The one query watched, and the paths the site may post it to.
              var NAME = '$name';
              var PATHS = [${paths.joinToString(", ") { "'/$it'" }}];
              // Whether a request is a POST to one of the paths (its URL a string or a URL object, relative or not).
              function watchedPost(url, method) {
                return String(method).toUpperCase() === 'POST' && PATHS.indexOf(new URL(String(url), location.href).pathname) >= 0;
              }
              // A request body as text, through a promise: null for none, or for a kind the site never sends a query as.
              function textOf(body) {
                if (typeof body === 'string') return Promise.resolve(body);
                if (body instanceof URLSearchParams) return Promise.resolve(body.toString());
                if (body instanceof FormData) return Promise.resolve(new URLSearchParams(Array.from(body.entries())).toString());
                if (body instanceof Blob || body instanceof ArrayBuffer || ArrayBuffer.isView(body)) return new Blob([body]).text();
                return Promise.resolve(null);
              }
              // The doc id of a request whose form names the query, else null.
              function docIdOf(text) {
                if (text === null) return null;
                var form = new URLSearchParams(text);
                return form.get('$friendlyNameField') === NAME ? form.get('$docIdField') : null;
              }
              // The one message to the app, for a watched request that got a reply: its doc id, the status and the text (or null).
              function report(docId, code, text) {
                if (!docId || !(code >= 100 && code <= 599)) return;
                window.igBridge.postMessage(JSON.stringify({ kind: 'watched', docId: docId, code: code, body: text }));
              }
              // Nothing below may throw into the site: each step of the watch is inside a try or a promise with a catch.
              var origFetch = window.fetch;
              window.fetch = function (input, init) {
                var docId = null;
                try {
                  var request = input instanceof Request ? input : null;
                  var method = (init && init.method) || (request ? request.method : 'GET');
                  if (watchedPost(request ? request.url : input, method)) {
                    // A Request's own body is read from a copy, before the fetch below uses it up.
                    var text = init && init.body != null ? textOf(init.body) : (request ? request.clone().text() : Promise.resolve(null));
                    docId = text.then(docIdOf).catch(function () { return null; });
                  }
                } catch (e) {}
                var p = origFetch.apply(this, arguments);
                if (docId) {
                  try {
                    // The reply is copied as soon as it arrives, before the site reads it; a fetch that fails reports nothing.
                    Promise.all([docId, p.then(function (r) { return r.clone(); })])
                      .then(function (both) { if (both[0]) return both[1].text().then(function (t) { report(both[0], both[1].status, t); }); })
                      .catch(function () {});
                  } catch (e) {}
                }
                return p;
              };
              // The reply's text by its type: as sent for text, re-serialised for JSON the browser parsed, else null.
              function replyOf(xhr) {
                try {
                  if (xhr.responseType === '' || xhr.responseType === 'text') return xhr.responseText;
                  if (xhr.responseType === 'json' && xhr.response !== null) return JSON.stringify(xhr.response);
                } catch (e) {}
                return null;
              }
              var open = XMLHttpRequest.prototype.open, send = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function (method, url) {
                try { this.__igWatched = watchedPost(url, method); } catch (e) { this.__igWatched = false; }
                return open.apply(this, arguments);
              };
              XMLHttpRequest.prototype.send = function (body) {
                var xhr = this;
                try {
                  if (xhr.__igWatched) {
                    var docId = textOf(body).then(docIdOf).catch(function () { return null; });
                    // A request that got no reply ends with status 0, which report() refuses.
                    xhr.addEventListener('loadend', function () {
                      var code = xhr.status, text = replyOf(xhr);
                      docId.then(function (id) { report(id, code, text); }).catch(function () {});
                    }, { once: true });
                  }
                } catch (e) {}
                return send.apply(this, arguments);
              };
            })();
        """.trimIndent() + "\n"
    }
}
