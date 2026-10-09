package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.testutil.KotlinSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Source pins for the repair page: its script `ig_watch.js` and the channel `AndroidRepairPage` reads it through (in the
 * style of [AndroidWebPageGuardTest] and `WebViewTransportTest`'s script pins). The emulator test (`AndroidRepairPageTest`)
 * shows the page at work against a local server; these pins say what it can never do, whatever a fake page does:
 * - the script watches one query, `WebGraphQl.SAVED_COLLECTIONS`, POSTed to one of `WebGraphQl.QUERY_PATHS`, and reports only
 *   that request's doc id (from its form), the reply's status and the reply's text, in its one message to the app;
 * - it never names a token, the cookie jar, the session storage or the console (WebView logs `console` to logcat);
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
    fun theScriptWatchesOnlyTheSavedCollectionsQuery() {
        assertScriptWatchesOnlyTheQuery(script())
    }

    @Test
    fun theScriptsOnlyMessageIsTheWatchedQuerysIdStatusAndReply() {
        assertScriptReportsOnlyTheWatchedQuery(script())
    }

    @Test
    fun theScriptNeverMentionsATokenTheCookiesTheSessionStorageOrTheConsole() {
        assertScriptNamesNoSecret(script())
    }

    /**
     * The script pins themselves: each way of widening what is watched, of leaking through the report, or of naming a secret
     * fails the pin it is aimed at, checked alone (so each pin means something on its own).
     */
    @Test
    fun eachWayOfWideningOrLeakingTheScriptFailsItsPin() {
        val real = script()
        val name = WebGraphQl.SAVED_COLLECTIONS.friendlyName
        val watching = mapOf(
            "another query" to real.replace("var NAME = '$name';", "var NAME = 'PolarisSomeOtherQuery';"),
            "every friendly name reported" to real.replace(" || form.get('fb_api_req_friendly_name') !== NAME", ""),
            "NAME used elsewhere" to real.replace("var form = formOf(body);", "var form = formOf(body); var n = NAME;"),
            "a GET watched" to real.replace("    if (String(method).toUpperCase() !== 'POST') return null;\n", ""),
            "any method watched" to real.replace("!== 'POST'", "=== 'TRACE'"),
            "any path watched" to real.replace("    if (path !== '/api/graphql' && path !== '/graphql/query') return null;\n", ""),
            "a third path watched" to real.replace("path !== '/graphql/query')", "path !== '/graphql/query' && path !== '/api/v1/x')"),
            "one path forgotten" to real.replace(" && path !== '/graphql/query'", ""),
            "the doc id from another field" to real.replace("return form.get('doc_id');", "return form.get('variables');"),
        )
        val reporting = mapOf(
            "the doc id from the URL" to real.replace("var docId = matches(url, method, init && init.body);", "var docId = url;"),
            "the XHR's doc id from its URL" to real.replace("var docId = w && matches(w.url, w.method, body);", "var docId = w && w.url;"),
            "a report whatever matched" to real.replace("if (docId) p.then(", "p.then("),
            "the request's body as the reply" to real.replace("report(docId, r.status, t);", "report(docId, r.status, String(init.body));"),
            "the XHR's request body as the reply" to real.replace("report(docId, xhr.status, typeof", "report(docId, xhr.status, body || typeof"),
            "a field more" to real.replace("code: code, body: text }", "code: code, body: text, url: location.href }"),
            "a field fewer" to real.replace(", body: text }", " }"),
            "the body is something else" to real.replace("body: text }", "body: document.title }"),
            "another kind" to real.replace("kind: 'watched'", "kind: 'seen'"),
            "a second message" to real.replace(
                "    return send.apply(this, arguments);",
                "    window.igBridge.postMessage(String(body));\n    return send.apply(this, arguments);",
            ),
            "the bridge kept under another name" to real.replace("var origFetch = window.fetch;", "var b = window.igBridge; var origFetch = window.fetch;"),
            "the post through a computed member" to real.replace("window.igBridge.postMessage(", "window.igBridge['postMessage']("),
            "a third report" to real.replace("    return send.apply(this, arguments);", "    report(docId, 0, null);\n    return send.apply(this, arguments);"),
        )
        // Secrets and logcat.
        val naming = mapOf(
            "the page's token read" to real.replace("return form.get('doc_id');", "return form.get('doc_id') + form.get('fb_dtsg');"),
            "the site's token module read" to real.replace("var origFetch = window.fetch;", "var d = require('DTSGInitialData'); var origFetch = window.fetch;"),
            "the lsd read" to real.replace("var form = formOf(body);", "var form = formOf(body); var l = form.get('lsd');"),
            "the cookie jar read" to real.replace("var origFetch = window.fetch;", "var c = document.cookie; var origFetch = window.fetch;"),
            "a cookie header read" to real.replace("var w = this.__w, xhr = this;", "var w = this.__w, xhr = this; var c = xhr.getResponseHeader('Set-Cookie');"),
            "the session storage read" to real.replace("var origFetch = window.fetch;", "var s = sessionStorage.getItem('www-claim-v2'); var origFetch = window.fetch;"),
            "the reply logged" to real.replace("report(docId, r.status, t);", "report(docId, r.status, t); console.log(t);"),
        )
        for ((pin, mutants) in listOf(
            ::assertScriptWatchesOnlyTheQuery to watching,
            ::assertScriptReportsOnlyTheWatchedQuery to reporting,
            ::assertScriptNamesNoSecret to naming,
        )) {
            for ((what, mutant) in mutants) {
                assertTrue(mutant != real, "the mutant '$what' did not change the script")
                assertFailsWith<AssertionError>(what) { pin(mutant) }
            }
        }
    }

    /**
     * The script watches `WebGraphQl.SAVED_COLLECTIONS` alone: its `NAME`, declared once and read only by the check, and
     * `matches()`, which is exactly the text the tests know (a POST, to one of `WebGraphQl.QUERY_PATHS`, whose form names that
     * query; it gives back the form's doc id and nothing else).
     */
    private fun assertScriptWatchesOnlyTheQuery(script: String) {
        val names = Regex("""var NAME = '([^']*)';""").findAll(script).map { it.groupValues[1] }.toList()
        assertEquals(listOf(WebGraphQl.SAVED_COLLECTIONS.friendlyName), names, "the script watches WebGraphQl.SAVED_COLLECTIONS")
        assertEquals(2, Regex("""\bNAME\b""").findAll(script).count(), "NAME is declared and checked, nothing else")
        assertEquals(codeOf(MATCHES_BODY), codeOf(block(script, "function matches(url, method, body) {")), "matches() is exactly the check the tests know")
    }

    /**
     * The one message to the app is `{kind: 'watched', docId, code, body}`, posted by `report()` alone; `report()` is called
     * twice, once per wrapper, each time only for a request `matches()` gave a doc id for, with that doc id, the reply's status
     * and the reply's own text.
     */
    private fun assertScriptReportsOnlyTheWatchedQuery(script: String) {
        assertEquals(1, Regex("""\bpostMessage\b""").findAll(script).count(), "postMessage is named once (no computed member)")
        assertEquals(1, Regex("""\bigBridge\b""").findAll(script).count(), "the bridge is named once (never kept under another name)")
        val post = Regex("""^window\.igBridge\.postMessage\(JSON\.stringify\(\{(.*)\}\)\);$""")
            .find(codeOf(block(script, "function report(docId, code, text) {")))
        assertNotNull(post, "report() is the one post and nothing else")
        assertEquals(
            listOf("kind" to "'watched'", "docId" to "docId", "code" to "code", "body" to "text"),
            fieldsOf(post.groupValues[1]),
            "the message's fields",
        )
        // The doc id is matches()'s answer (the form's doc_id) and nothing else, for both wrappers.
        assertEquals(
            listOf("matches(url, method, init && init.body)", "w && matches(w.url, w.method, body)"),
            Regex("""\bdocId\s*=(?!=)\s*([^;]*);""").findAll(script).map { it.groupValues[1].trim() }.toList(),
            "docId is set from matches() only",
        )
        val code = codeOf(script)
        assertEquals(2, Regex("""(?<!function )\breport\(""").findAll(code).count(), "report() is called twice, once per wrapper")
        for (statement in REPORTS) {
            assertEquals(1, code.split(codeOf(statement)).size - 1, "the wrapper reports exactly so: $statement")
        }
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

    /** [script]'s code as one line: every run of white space one space (the script has no comments). */
    private fun codeOf(script: String): String = script.replace(Regex("""\s+"""), " ").trim()

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
         * The body of `matches()` in ig_watch.js, exactly (compared with white space collapsed): a POST, to one of the website's
         * query paths, whose form names the watched query; the answer is the form's doc id. Any change to what the script
         * watches must change this text too.
         */
        val MATCHES_BODY = """
            if (String(method).toUpperCase() !== 'POST') return null;
            var path;
            try { path = new URL(url, location.href).pathname; } catch (e) { return null; }
            if (${WebGraphQl.QUERY_PATHS.joinToString(" && ") { "path !== '/$it'" }}) return null;
            var form = formOf(body);
            if (!form || form.get('${WebGraphQl.Field.FRIENDLY_NAME}') !== NAME) return null;
            return form.get('${WebGraphQl.Field.DOC_ID}');
        """

        /** How each wrapper reports: only for a request with a doc id, with the reply's status and its own text (or none). */
        val REPORTS = listOf(
            "if (docId) p.then(function (r) { return r.clone().text().then(function (t) { report(docId, r.status, t); }); }, function () {});",
            "if (docId) xhr.addEventListener('loadend', function () { report(docId, xhr.status, typeof xhr.responseText === 'string' ? xhr.responseText : null); });",
        )
    }
}
