package io.github.yuriimurha.reels.transport

import android.content.Context
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Robolectric has no JavaScript and no WebView provider, so this covers construction, and the page's [PageClient] driven by
 * hand (a top-level class for that reason). Page loads, messages and the origin check run on the emulator against a local
 * test server.
 */
@RunWith(AndroidJUnit4::class)
class AndroidWebPageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun aWebViewThatCannotPostMessagesFailsToConstruct() {
        // Robolectric's WebView does not support WEB_MESSAGE_LISTENER. The page must refuse to exist (WebViewTransport turns
        // that into Transient) rather than load instagram.com with no way to hear it, or fall back to addJavascriptInterface.
        val error = assertFailsWith<IllegalStateException> { AndroidWebPage(context) }
        assertEquals("this WebView cannot post messages to the app", error.message)
    }

    // --- PageClient ------------------------------------------------------------------------------------------------------------

    /** What the client said, in order. */
    private val events = mutableListOf<String>()
    private val failures = mutableListOf<IOException>()

    private val client = PageClient(
        isAllowed = { url -> url.host == "www.instagram.com" },
        onLoaded = { url -> events += "loaded $url" },
        onLoadFailed = { error ->
            failures += error
            events += "failed"
        },
        onGone = { events += "gone" },
    )

    private val view by lazy { WebView(context) }

    private fun request(mainFrame: Boolean, url: String = "https://www.instagram.com/") = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)

        override fun isForMainFrame(): Boolean = mainFrame

        override fun isRedirect(): Boolean = false

        override fun hasGesture(): Boolean = false

        override fun getMethod(): String = "GET"

        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun response(status: Int) = WebResourceResponse("text/html", "utf-8", status, "Status $status", emptyMap(), "".byteInputStream())

    private fun rendererGone(crashed: Boolean) = object : RenderProcessGoneDetail() {
        override fun didCrash(): Boolean = crashed

        override fun rendererPriorityAtExit(): Int = 0
    }

    /** P01: returning false from onRenderProcessGone lets the system kill the whole app. The page is dead; the app lives on. */
    @Test
    fun aDeadRendererIsTheDeathOfThePageNotOfTheApp() {
        for (crashed in listOf(true, false)) {
            events.clear()
            assertTrue(client.onRenderProcessGone(view, rendererGone(crashed)), "crashed=$crashed: handled, never fatal")
            assertEquals(listOf("gone"), events, "crashed=$crashed")
        }
    }

    /** P02: a home document answered 429 is a rate limit: the load fails with it, so no API request goes into the limit. */
    @Test
    fun aMainFrameHttpErrorFailsTheLoadWithItsStatus() {
        for (status in listOf(429, 404, 400, 403, 500, 503)) {
            failures.clear()
            client.onReceivedHttpError(view, request(mainFrame = true), response(status))
            assertEquals(status, assertIs<PageHttpError>(failures.single(), "$status").code)
        }
    }

    @Test
    fun aMainFrameThatIsNoErrorDoesNotFailTheLoad() {
        // (WebResourceResponse refuses a 3xx status outright.)
        for (status in listOf(200, 204, 299)) client.onReceivedHttpError(view, request(mainFrame = true), response(status))
        assertEquals(emptyList(), events)
    }

    /** A subframe's or a sub-resource's error status (a missing favicon, a tracker's 429) is not the page's. */
    @Test
    fun aSubframeOrSubresourceHttpErrorIsIgnored() {
        for (status in listOf(404, 429, 503)) client.onReceivedHttpError(view, request(mainFrame = false), response(status))
        assertEquals(emptyList(), events)
    }

    @Test
    fun theFinishedLoadReportsTheUrlItEndedOn() {
        client.onPageFinished(view, "https://www.instagram.com/accounts/login/")
        client.onPageFinished(view, null)
        assertEquals(listOf("loaded https://www.instagram.com/accounts/login/", "loaded "), events)
    }

    @Test
    fun aNavigationOutsideTheAllowedPagesIsRefused() {
        assertFalse(client.shouldOverrideUrlLoading(view, request(mainFrame = true, url = "https://www.instagram.com/explore/")))
        assertTrue(client.shouldOverrideUrlLoading(view, request(mainFrame = true, url = "https://example.invalid/")))
    }
}
