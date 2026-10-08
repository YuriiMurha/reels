package io.github.yuriimurha.reels.transport

/**
 * The hidden page [WebViewTransport] runs its calls in. Main-thread only: every method is called, and every listener is
 * invoked, on the main thread. The real one ([AndroidWebPage]) wraps a WebView; unit tests use a fake.
 */
interface WebPage {
    /** Loads [url]; returns the URL the page finished on (after the site's own redirects), or throws on failure. */
    suspend fun load(url: String): String

    /** Runs [script] in the page; the result of a call arrives later through [onMessage]. */
    fun evaluate(script: String)

    /** Messages posted by the page from the allowed origin only. Set once, before [load]. */
    fun onMessage(listener: (String) -> Unit)

    /** Releases the page. A [load] in progress fails. */
    fun destroy()
}
