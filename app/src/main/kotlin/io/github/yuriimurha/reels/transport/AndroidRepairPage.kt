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
import kotlin.math.roundToInt

/**
 * [RepairPage] over a second real WebView that is never shown. It is never the transport's [AndroidWebPage]: it has its own
 * WebView, its own bridge listener and its own script, and it exists only for one repair. Main thread only. Its cookies are
 * the app-wide WebView ones (the jar the login screen fills), so the site shows it the owner's own pages.
 *
 * **Desktop mode** (R10), through the WebView's own settings: Chrome on Android with "Desktop site" on, as [DesktopSite] has
 * it (a Linux desktop Chrome user agent at the WebView's major version; client hints for platform Linux, x86, 64-bit, no
 * model, not mobile, form factor Desktop, Chrome's brands with its GREASE brand, the WebView's full version), and a wide
 * viewport laid out as a [WIDTH] x [HEIGHT] CSS-pixel window (R9). A WebView that cannot set those client hints
 * (`USER_AGENT_METADATA`, `USER_AGENT_METADATA_FORM_FACTORS`) gets no repair page: it would send a desktop user agent with
 * mobile client hints, a mismatch the app never sends.
 *
 * **What it watches.** Before any of the site's code runs, `ig_watch.js` ([WebViewCompat.addDocumentStartJavaScript], for
 * [allowedOrigin] only) wraps `fetch` and `XMLHttpRequest` to watch for exactly one request: a POST to one of
 * [WebGraphQl.QUERY_PATHS] whose form names [WebGraphQl.SAVED_COLLECTIONS]. For that request alone, once it got a reply, it
 * posts `{kind: 'watched', docId, code, body}`: the doc id from the request's form, the reply's status and text. It never reads
 * or posts a token, a cookie or any other request (pinned by `RepairPageGuardTest`). The message comes back through
 * `window.igBridge`, installed by [WebViewCompat.addWebMessageListener] for [allowedOrigin] only and read under the same three
 * conditions as [AndroidWebPage]'s: the main frame, exactly that origin, a string. The trust boundary is that origin, as there
 * (R109). Of those messages, only a watched report whose doc id has the website's shape ([WebGraphQl.isDocId]) counts.
 * `addJavascriptInterface` is never used.
 *
 * **How a watch ends**, whichever comes first: the watched report (at once, even before the page has finished loading); a
 * main-frame HTTP error page, the first load's or a later one's ([PageHttpError], [PageClient]'s rule shared with
 * [AndroidWebPage]); a main-frame page that finishes on a login or challenge path ([RepairLanding], [WebEndpoints.landingOf]);
 * a dead renderer or [destroy] ([IOException]); or the timeout, when the page is checked once more for a login or challenge
 * path it moved to by itself (`pushState`), else `null`. A page whose renderer died stays dead: a later watch fails at once.
 * The page goes nowhere but [allowedOrigin].
 *
 * Throws on construction when this WebView cannot do any of it (`USER_AGENT_METADATA`, `USER_AGENT_METADATA_FORM_FACTORS`,
 * `DOCUMENT_START_SCRIPT` or `WEB_MESSAGE_LISTENER` unsupported, or its version unknown) or cannot be created at all.
 *
 * Exercised on the emulator against a local test server (`AndroidRepairPageTest`).
 */
class AndroidRepairPage(
    context: Context,
    private val allowedOrigin: String = WebEndpoints.HOME_URL.toUri().let { "${it.scheme}://${it.host}" },
    /** Tests only (production passes none): every message the bridge let through, before it is read. */
    private val onRawMessage: ((String) -> Unit)? = null,
) : RepairPage {
    private val origin: Uri = allowedOrigin.toUri()
    private val webView = WebView(context.applicationContext)
    private var watching: CompletableDeferred<WatchedQuery>? = null
    private var destroyed = false
    private var gone = false

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
                // Every main-frame page that finishes, the first and any later one, is checked for a login or challenge path.
                onLoaded = { url -> landingAt(url)?.let { watching?.completeExceptionally(it) } },
                onLoadFailed = { error -> watching?.completeExceptionally(error) },
                onGone = {
                    gone = true
                    watching?.completeExceptionally(IOException("render process gone"))
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

    /** The desktop identity and window (see the class comment), or an [IllegalStateException] when this WebView cannot have it. */
    private fun enterDesktopMode(context: Context) {
        val site = DesktopSite.of(WebViewCompat.getCurrentWebViewPackage(context)?.versionName)
            ?: throw IllegalStateException("no desktop mode: the WebView's version is unknown")
        val settings = webView.settings
        settings.userAgentString = site.userAgent
        // Both features, or no desktop mode. Without the form factors the WebView sends "Mobile" for them.
        val metadata = if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)) {
            UserAgentMetadata.Builder()
                .setMobile(false)
                .setPlatform(DesktopSite.PLATFORM)
                .setPlatformVersion(DesktopSite.PLATFORM_VERSION)
                .setArchitecture(DesktopSite.ARCHITECTURE)
                .setBitness(DesktopSite.BITNESS)
                .setModel(DesktopSite.MODEL)
                .setFormFactors(listOf(UserAgentMetadata.FORM_FACTOR_DESKTOP))
                .setBrandVersionList(
                    site.brands.map { brand ->
                        UserAgentMetadata.BrandVersion.Builder()
                            .setBrand(brand.name)
                            .setMajorVersion(brand.majorVersion)
                            .setFullVersion(brand.fullVersion)
                            .build()
                    },
                )
                .setFullVersion(site.fullVersion)
                .build()
        } else {
            throw IllegalStateException("no desktop mode")
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            WebSettingsCompat.setUserAgentMetadata(settings, metadata)
        } else {
            throw IllegalStateException("no desktop mode")
        }
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        // A desktop window in CSS pixels (R9): the view is laid out in device pixels, CSS pixels times the density.
        val density = context.resources.displayMetrics.density
        val width = (WIDTH * density).roundToInt()
        val height = (HEIGHT * density).roundToInt()
        webView.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        webView.layout(0, 0, width, height)
    }

    override suspend fun watch(url: String, friendlyName: String, timeoutMs: Long): WatchedQuery? {
        require(friendlyName == WebGraphQl.SAVED_COLLECTIONS.friendlyName) { "$SCRIPT watches ${WebGraphQl.SAVED_COLLECTIONS} only" }
        require(isAllowed(url.toUri())) { "the repair page loads its own origin only" }
        check(!destroyed) { "the page was destroyed" }
        if (gone) throw IOException("render process gone")
        check(watching == null) { "one watch at a time" }
        // Waited for before the load starts: the site may send its request, and have the reply, before its page has finished.
        val outcome = CompletableDeferred<WatchedQuery>()
        watching = outcome
        try {
            webView.loadUrl(url)
            // The whole watch is bounded, the load included: a page that never finishes is a request that never came.
            val watched = withTimeoutOrNull(timeoutMs) { outcome.await() }
            if (watched == null) {
                // A page that moved itself to a login or challenge path (pushState) is that landing, not a missing request.
                landingAt(webView.url.orEmpty())?.let { throw it }
            }
            return watched
        } finally {
            if (watching === outcome) watching = null
        }
    }

    /** Where the page is: a login or challenge page of the site is a [RepairLanding]; any other page, or one off the site, is not. */
    private fun landingAt(url: String): RepairLanding? {
        val landed = url.toUri()
        // Off the site, its paths mean nothing (and the script does not run there).
        if (!isAllowed(landed)) return null
        return when (WebEndpoints.landingOf(landed.path.orEmpty())) {
            WebEndpoints.Landing.LOGIN -> RepairLanding.Login
            WebEndpoints.Landing.CHALLENGE -> RepairLanding.Challenge
            null -> null
        }
    }

    /** One message the bridge let through: the watch ends on the first watched report of the right shape. */
    private fun received(raw: String) {
        onRawMessage?.invoke(raw)
        val awaited = watching ?: return
        watchedQueryOf(raw)?.let { awaited.complete(it) }
    }

    override fun destroy() {
        if (destroyed) return
        destroyed = true
        // A watch in progress fails at once, whether its page is loading or it waits for the query.
        watching?.completeExceptionally(IOException("page destroyed"))
        watching = null
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

        /** The desktop window the page is laid out in, in CSS pixels (R9). */
        const val WIDTH = 1440
        const val HEIGHT = 900

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
