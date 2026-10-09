package io.github.yuriimurha.reels.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.View
import android.webkit.WebView
import androidx.core.net.toUri
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.yuriimurha.reels.instagram.web.WebEndpoints
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException

/**
 * [RepairPage] over a second real WebView that is never shown. It is never the transport's [AndroidWebPage]: it has its own
 * WebView, its own bridge listener and its own script, and it exists only for one repair. Main thread only. Its cookies are
 * the app-wide WebView ones (the jar the login screen fills), so the site shows it the owner's own pages.
 *
 * **Desktop mode**, through the WebView's own settings, the way a person uses "Desktop site": a macOS Chrome user agent of
 * the WebView's own major version, desktop client hints (`mobile = false`, platform macOS, the same brands and version) and a
 * wide viewport laid out at [WIDTH] x [HEIGHT]. A WebView that cannot set the client hints (`USER_AGENT_METADATA`) gets no
 * repair page: it would send a desktop user agent with mobile client hints, a mismatch the app never sends.
 *
 * **What it watches.** Before any of the site's code runs, `ig_watch.js` ([WebViewCompat.addDocumentStartJavaScript], for
 * [allowedOrigin] only) wraps `fetch` and `XMLHttpRequest` to watch for exactly one request: a POST to the GraphQL path named
 * [WebGraphQl.SAVED_COLLECTIONS]. For that request alone it posts `{kind: 'watched', docId, code, body}`: the doc id from the
 * request's form, the reply's status and text. It never reads or posts a token, a cookie or any other request (pinned by
 * `RepairPageGuardTest`). The message comes back through `window.igBridge`, installed by [WebViewCompat.addWebMessageListener]
 * for [allowedOrigin] only and read under the same three conditions as [AndroidWebPage]'s: the main frame, exactly that
 * origin, a string. The trust boundary is that origin, as there (R109). Of those messages, only a watched report whose doc id
 * has the website's shape ([WebGraphQl.isDocId]) counts, and the first one ends the watch. `addJavascriptInterface` is never
 * used.
 *
 * **Failures.** A main-frame HTTP error page and a dead renderer are [PageClient]'s rules, shared with [AndroidWebPage]: the
 * first fails [watch] with [PageHttpError], the second with an [IOException], at once, while loading or waiting. A login or
 * challenge landing ([WebEndpoints.landingOf]) is a [RepairLanding]. The page goes nowhere but [allowedOrigin].
 *
 * Throws on construction when this WebView cannot do any of it (`USER_AGENT_METADATA`, `DOCUMENT_START_SCRIPT` or
 * `WEB_MESSAGE_LISTENER` unsupported, or its version unknown) or cannot be created at all.
 *
 * Exercised on the emulator against a local test server (`AndroidRepairPageTest`).
 */
class AndroidRepairPage(
    context: Context,
    private val allowedOrigin: String = WebEndpoints.HOME_URL.toUri().let { "${it.scheme}://${it.host}" },
) : RepairPage {
    private val origin: Uri = allowedOrigin.toUri()
    private val webView = WebView(context.applicationContext)
    private var loading: CompletableDeferred<String>? = null
    private var watching: CompletableDeferred<WatchedQuery>? = null
    private var destroyed = false

    /** Tests only: every message the bridge let through (the main frame, [allowedOrigin], a string), before it is read. */
    internal var heard: ((String) -> Unit)? = null

    init {
        try {
            enableScripting()
            enterDesktopMode(context)
            val script = context.applicationContext.assets.open(SCRIPT).bufferedReader().use { it.readText() }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // Installed before any load, so it is in place before the site's first script.
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(allowedOrigin))
            } else {
                throw IllegalStateException("this WebView cannot run a script before the page's own")
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf(allowedOrigin)) { _, message, sourceOrigin, isMainFrame, _ ->
                    // The data is read only once the sender is the main frame of the allowed origin, and only a string is one.
                    if (isMainFrame && sourceOrigin.toString() == allowedOrigin && message.type == WebMessageCompat.TYPE_STRING) {
                        message.data?.let { received(it) }
                    }
                }
            } else {
                throw IllegalStateException("this WebView cannot post messages to the app")
            }
            webView.webViewClient = PageClient(
                isAllowed = ::isAllowed,
                onLoaded = { url -> loading?.complete(url) },
                onLoadFailed = { error -> loading?.completeExceptionally(error) },
                onGone = {
                    val gone = IOException("render process gone")
                    loading?.completeExceptionally(gone)
                    watching?.completeExceptionally(gone)
                },
            )
        } catch (e: Exception) {
            webView.destroy()
            throw e
        }
    }

    /**
     * JavaScript and DOM storage, as on [AndroidWebPage]: the site's own code is what sends the watched request, and it needs
     * both. The page only ever shows [allowedOrigin] and has no JavaScript interface, only the origin-checked channel above.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun enableScripting() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
    }

    /** The desktop identity and layout (see the class comment), or an [IllegalStateException] when this WebView cannot have it. */
    private fun enterDesktopMode(context: Context) {
        val major = WebViewCompat.getCurrentWebViewPackage(context)?.versionName?.substringBefore('.')
            ?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?: throw IllegalStateException("no desktop mode: the WebView's version is unknown")
        val settings = webView.settings
        settings.userAgentString = desktopUserAgent(major)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder()
                    .setMobile(false)
                    .setPlatform("macOS")
                    .setPlatformVersion("15.0.0")
                    .setArchitecture("arm")
                    .setModel("")
                    .setBrandVersionList(
                        listOf("Chromium", "Google Chrome").map { brand ->
                            UserAgentMetadata.BrandVersion.Builder().setBrand(brand).setMajorVersion(major).setFullVersion("$major.0.0.0").build()
                        },
                    )
                    .setFullVersion("$major.0.0.0")
                    .build(),
            )
        } else {
            throw IllegalStateException("no desktop mode")
        }
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        webView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        webView.layout(0, 0, WIDTH, HEIGHT)
    }

    override suspend fun watch(url: String, friendlyName: String, timeoutMs: Long): WatchedQuery? {
        require(friendlyName == WebGraphQl.SAVED_COLLECTIONS.friendlyName) { "$SCRIPT watches ${WebGraphQl.SAVED_COLLECTIONS} only" }
        check(!destroyed) { "the page was destroyed" }
        check(watching == null) { "one watch at a time" }
        // Waited for before the load starts: the site may send its request, and have the reply, before its page has finished.
        val watched = CompletableDeferred<WatchedQuery>()
        watching = watched
        try {
            // The whole watch is bounded, the load included: a page that never finishes is a request that never came.
            return withTimeoutOrNull(timeoutMs) {
                landingAt(load(url))?.let { throw it }
                watched.await()
            }
        } finally {
            if (watching === watched) watching = null
        }
    }

    private suspend fun load(url: String): String {
        val done = CompletableDeferred<String>()
        loading = done
        webView.loadUrl(url)
        try {
            return done.await()
        } finally {
            if (loading === done) loading = null
        }
    }

    /** Where the page finished: a login or challenge page of the site is a [RepairLanding]; anything else is waited on. */
    private fun landingAt(url: String): RepairLanding? {
        val landed = url.toUri()
        // Off the site, its paths mean nothing (and the script does not run there, so the watch times out).
        if (!isAllowed(landed)) return null
        return when (WebEndpoints.landingOf(landed.path.orEmpty())) {
            WebEndpoints.Landing.LOGIN -> RepairLanding.Login
            WebEndpoints.Landing.CHALLENGE -> RepairLanding.Challenge
            null -> null
        }
    }

    /** One message the bridge let through: the watch ends on the first watched report of the right shape. */
    private fun received(raw: String) {
        heard?.invoke(raw)
        val awaited = watching ?: return
        watchedQueryOf(raw)?.let { awaited.complete(it) }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        // A watch in progress fails at once, whether it is loading or waiting for the query.
        val ended = IOException("page destroyed")
        loading?.completeExceptionally(ended)
        loading = null
        watching?.completeExceptionally(ended)
        watching = null
        heard = null
        try {
            webView.stopLoading()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(webView, BRIDGE)
        } finally {
            // Whatever the clean-up above says about a WebView whose renderer is gone, it is released.
            webView.destroy()
        }
    }

    /** Exactly the allowed origin: scheme, host and port. Nothing else loads, and only there do the site's landings count. */
    private fun isAllowed(url: Uri): Boolean = url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port

    private companion object {
        /** The object name the page posts to; `ig_watch.js` uses the same one. */
        const val BRIDGE = "igBridge"

        /** The watching script, in the app's assets. */
        const val SCRIPT = "ig_watch.js"

        /** What `ig_watch.js` calls its one report. */
        const val KIND = "watched"

        /** The desktop window the page is laid out in. */
        const val WIDTH = 1440
        const val HEIGHT = 900

        /** Chrome's own (reduced) user agent on macOS, of [major] version. */
        fun desktopUserAgent(major: String): String =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"

        /**
         * The query [raw] reports: a JSON object whose `kind` is [KIND], whose `docId` is a string of the website's shape
         * ([WebGraphQl.isDocId]) and whose `code` is a number. Anything else is null (not a report the page should have made).
         */
        fun watchedQueryOf(raw: String): WatchedQuery? {
            val message = try {
                Json.parseToJsonElement(raw) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null // SerializationException is one
            } ?: return null
            if (message.string("kind") != KIND) return null
            val docId = message.string("docId")?.takeIf(WebGraphQl::isDocId) ?: return null
            val code = (message["code"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return null
            return WatchedQuery(docId, code, message.string("body"))
        }

        /** The string at [key], or null when there is none or it is not a string. */
        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
