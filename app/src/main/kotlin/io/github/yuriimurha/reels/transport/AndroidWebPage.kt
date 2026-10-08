package io.github.yuriimurha.reels.transport

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.yuriimurha.reels.instagram.web.WebEndpoints
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException

/**
 * [WebPage] over a real WebView that is never shown. Main thread only. Its cookies are the app-wide WebView ones, the same
 * jar the login screen fills.
 *
 * The page talks back through `window.igBridge.postMessage(string)`, installed by
 * [WebViewCompat.addWebMessageListener] for [allowedOrigin] only (tests pass a local one). `addJavascriptInterface` is
 * never used: it would expose an object to every page and frame the WebView ever shows. A message counts only from the main
 * frame of exactly that origin, and only as a string.
 *
 * A main-frame document answered with an HTTP error (a 429 page, a 503) fails [load] with [PageHttpError], and a dead
 * renderer ([WebViewClient.onRenderProcessGone]) marks the page gone: [currentUrl] is null and [onGone] fires.
 *
 * Throws on construction when this WebView can't post messages (`WEB_MESSAGE_LISTENER` unsupported) or can't be created at
 * all (no WebView provider); [WebViewTransport] turns either into `Transient`.
 *
 * Exercised on the emulator against a local test server; the JVM tests use a fake [WebPage].
 */
class AndroidWebPage(
    context: Context,
    private val allowedOrigin: String = "https://www.instagram.com",
) : WebPage {
    private val origin: Uri = allowedOrigin.toUri()
    private val webView = WebView(context.applicationContext)
    private var listener: ((String) -> Unit)? = null
    private var goneListener: (() -> Unit)? = null
    private var loading: CompletableDeferred<String>? = null
    private var gone = false

    init {
        try {
            enableScripting()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                // Installed before any load, so the page has window.igBridge from its first script on.
                WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf(allowedOrigin)) { _, message, sourceOrigin, isMainFrame, _ ->
                    // The data is read only once the sender is the main frame of the allowed origin, and only a string is one.
                    if (isMainFrame && sourceOrigin.toString() == allowedOrigin && message.type == WebMessageCompat.TYPE_STRING) {
                        message.data?.let { listener?.invoke(it) }
                    }
                }
            } else {
                throw IllegalStateException("this WebView cannot post messages to the app")
            }
            webView.webViewClient = PageClient()
        } catch (e: Exception) {
            webView.destroy()
            throw e
        }
    }

    /**
     * JavaScript is the point of this page: `ig_fetch.js` runs in it. It only ever shows Instagram's own pages (navigation is
     * limited by [isAllowed]) and has no JavaScript interface, only the origin-checked message channel above.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun enableScripting() {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
    }

    override suspend fun load(url: String): String {
        val done = CompletableDeferred<String>()
        loading = done
        webView.loadUrl(url)
        return done.await()
    }

    override fun currentUrl(): String? = if (gone) null else webView.url

    override fun evaluate(script: String) = webView.evaluateJavascript(script, null)

    override fun onMessage(listener: (String) -> Unit) {
        this.listener = listener
    }

    override fun onGone(listener: () -> Unit) {
        goneListener = listener
    }

    override fun destroy() {
        // A load in progress fails (the transport is not waiting for it any more, but this must not leave it hanging).
        loading?.completeExceptionally(IOException("page destroyed"))
        loading = null
        listener = null
        goneListener = null
        try {
            webView.stopLoading()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(webView, BRIDGE)
        } finally {
            // Whatever the clean-up above says about a WebView whose renderer is gone, it is released.
            webView.destroy()
        }
    }

    /** Instagram's own domains (and the login flow's Facebook and Meta ones), plus the test origin. Nothing else loads. */
    private fun isAllowed(url: Uri): Boolean =
        WebEndpoints.isLoginPage(url.scheme, url.host) ||
            (url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port)

    // onRenderProcessGone IS overridden below. androidx.webkit's lint check also flags the `WebViewClient()` constructor call in
    // the supertype list of every subclass, override or not, so the one remaining warning is a false positive.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !isAllowed(request.url)

        override fun onPageFinished(view: WebView, url: String?) {
            loading?.complete(url.orEmpty())
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) loading?.completeExceptionally(IOException("page load failed (error ${error.errorCode})"))
        }

        /** The home document itself was refused (429, 503, ...): the load fails with the status, whatever the page shows. */
        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame && errorResponse.statusCode >= 400) loading?.completeExceptionally(PageHttpError(errorResponse.statusCode))
        }

        /**
         * The renderer died (out of memory, killed in the background). Returning false would crash the app. This page is
         * dead either way: it is marked gone, a load in progress fails, and the transport is told so it can fail a call
         * in flight at once and drop the page.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            gone = true
            loading?.completeExceptionally(IOException("render process gone"))
            goneListener?.invoke()
            return true
        }
    }

    private companion object {
        /** The object name the page posts to; `ig_fetch.js` uses the same one. */
        const val BRIDGE = "igBridge"
    }
}
