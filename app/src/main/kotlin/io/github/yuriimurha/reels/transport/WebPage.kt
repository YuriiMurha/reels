package io.github.yuriimurha.reels.transport

import java.io.IOException

/**
 * The hidden page [WebViewTransport] runs its calls in. Main-thread only: every method is called, and every listener is
 * invoked, on the main thread. The real one ([AndroidWebPage]) wraps a WebView; unit tests use a fake.
 */
interface WebPage {
    /**
     * Loads [url]; returns the URL the page finished on (after the site's own redirects). Throws [PageHttpError] when the
     * document itself was answered with an HTTP error status, and any other exception when the load failed.
     */
    suspend fun load(url: String): String

    /** Where the page is now (it can move after [load]: a site's own navigation, `pushState`), or null when it is gone. */
    fun currentUrl(): String?

    /** Runs [script] in the page; the result of a call arrives later through [onMessage]. */
    fun evaluate(script: String)

    /** Messages posted by the page from the allowed origin only. Set once, before [load]. */
    fun onMessage(listener: (String) -> Unit)

    /** Called once when the page can no longer be used (its renderer died). [currentUrl] is null from then on. */
    fun onGone(listener: () -> Unit)

    /** Releases the page. A [load] in progress fails. */
    fun destroy()
}

/** A page whose main document came back with HTTP [code] (>= 400): not a page to run calls in. */
class PageHttpError(val code: Int) : IOException("page load answered HTTP $code")
