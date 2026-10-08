package io.github.yuriimurha.reels.transport

import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
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
    private var loading: CompletableDeferred<String>? = null

    init {
        try {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                throw IllegalStateException("this WebView cannot post messages to the app")
            }
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            // Installed before any load, so the page has window.igBridge from its first script on.
            WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf(allowedOrigin)) { _, message, sourceOrigin, isMainFrame, _ ->
                val data = message.data
                if (data != null && isMainFrame && sourceOrigin.toString() == allowedOrigin) listener?.invoke(data)
            }
            webView.webViewClient = PageClient()
        } catch (e: Exception) {
            webView.destroy()
            throw e
        }
    }

    override suspend fun load(url: String): String {
        val done = CompletableDeferred<String>()
        loading = done
        webView.loadUrl(url)
        return done.await()
    }

    override fun evaluate(script: String) = webView.evaluateJavascript(script, null)

    override fun onMessage(listener: (String) -> Unit) {
        this.listener = listener
    }

    override fun destroy() {
        // A load in progress fails (the transport is not waiting for it any more, but this must not leave it hanging).
        loading?.completeExceptionally(IOException("page destroyed"))
        loading = null
        listener = null
        webView.stopLoading()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(webView, BRIDGE)
        webView.destroy()
    }

    /** Instagram's own domains (and the login flow's Facebook and Meta ones), plus the test origin. Nothing else loads. */
    private fun isAllowed(url: Uri): Boolean =
        WebEndpoints.isLoginPage(url.scheme, url.host) ||
            (url.scheme == origin.scheme && url.host == origin.host && url.port == origin.port)

    private inner class PageClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !isAllowed(request.url)

        override fun onPageFinished(view: WebView, url: String?) {
            loading?.complete(url.orEmpty())
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) loading?.completeExceptionally(IOException("page load failed (error ${error.errorCode})"))
        }

        /**
         * The renderer died (out of memory, killed in the background). Returning false would crash the app; this page is
         * dead either way. A call in flight times out and the transport drops the page; the next call makes a new one.
         */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            loading?.completeExceptionally(IOException("render process gone"))
            return true
        }
    }

    private companion object {
        /** The object name the page posts to; `ig_fetch.js` uses the same one. */
        const val BRIDGE = "igBridge"
    }
}
