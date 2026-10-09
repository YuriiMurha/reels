package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.instagram.web.WebEndpoints
import java.io.File
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Source pins (in the style of [JavascriptInterfaceGuardTest]) for the one place a page can talk to the app: the
 * `addWebMessageListener` call in `AndroidWebPage.kt`. Robolectric has no WebView provider, and on the emulator the platform
 * stops a frame of another origin before the listener ever sees it, so what keeps the channel closed is read from the source:
 * the rule set is exactly the allowed origin (never `*`), and a message reaches the transport only when the platform credits it
 * to the MAIN frame, from exactly that origin, as a STRING. Each of the three conditions is the only thing that stops one kind
 * of sender, so dropping any of them, or widening the rule, fails here (the mutants below are made from the real source). The
 * main-frame condition stops a same-origin subframe's own bridge only: its `parent.igBridge` is credited to the main frame
 * (R109, pinned on the emulator), so the trust boundary is the origin.
 */
class AndroidWebPageGuardTest {
    private val path = "src/main/kotlin/io/github/yuriimurha/reels/transport/AndroidWebPage.kt"

    private fun source(): String {
        val file = File(path)
        assertTrue(file.isFile, "unit tests must run from the app module directory, and $path must exist")
        return file.readText()
    }

    /** The listener's channel in [text], read by the pin both hidden pages share. */
    private fun channelOf(text: String) = BridgeListenerPin.channelOf(text, "AndroidWebPage.kt", handOver = "listener?.invoke(")

    private fun assertChannelIsClosed(text: String) = assertEquals(BridgeListenerPin.CLOSED, channelOf(text))

    /**
     * P3: the page's default origin, the one origin its bridge accepts in the app (`AppContainer` passes none: pinned by
     * `BackendWiringGuardTest`), is exactly the scheme and host of `WebEndpoints.HOME_URL`, the page the transport loads. A
     * trailing slash, another host or plain http would never match the sender's origin, and every call would time out.
     */
    private fun assertDefaultOriginIsTheHomePages(text: String) {
        val defaults = Regex("""\ballowedOrigin\s*:\s*String\s*=\s*"([^"]*)"""").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(1, defaults.size, "one default for allowedOrigin: $defaults")
        val home = URI(WebEndpoints.HOME_URL)
        assertEquals("${home.scheme}://${home.host}", defaults.single())
    }

    @Test
    fun theDefaultOriginIsTheHomePagesOrigin() {
        assertDefaultOriginIsTheHomePages(source())
    }

    @Test
    fun eachWayOfMissingTheHomePagesOriginFailsThePin() {
        val real = source()
        for ((what, mutant) in mapOf(
            "A04: a trailing slash" to real.replace("= \"https://www.instagram.com\"", "= \"https://www.instagram.com/\""),
            "another host" to real.replace("= \"https://www.instagram.com\"", "= \"https://instagram.com\""),
            "plain http" to real.replace("= \"https://www.instagram.com\"", "= \"http://www.instagram.com\""),
        )) {
            assertTrue(mutant != real, "the mutant '$what' did not change the source")
            assertFailsWith<AssertionError>(what) { assertDefaultOriginIsTheHomePages(mutant) }
        }
    }

    @Test
    fun theBridgeIsOnlyForTheAllowedOriginAndOnlyTheMainFrameStringsGetThrough() {
        assertChannelIsClosed(source())
    }

    /** R16: the hidden pages' one chrome client keeps the site's console out of logcat and answers its dialogs on purpose. */
    @Test
    fun theHiddenPagesChromeClientIsQuiet() {
        ChromeClientPin.assertQuietClient(ChromeClientPin.clientSource())
    }

    /** R16: this page installs it, in its init, before any load. */
    @Test
    fun thePageInstallsTheQuietChromeClient() {
        ChromeClientPin.assertInstalled(source(), "AndroidWebPage.kt")
    }

    /** The two pins above fail for each way of making the console loud again, on mutants of the real sources. */
    @Test
    fun eachWayOfMakingThePageLoudFailsItsPin() {
        val client = ChromeClientPin.clientSource()
        for ((what, mutant) in ChromeClientPin.clientMutants(client)) {
            assertTrue(mutant != client, "the mutant '$what' did not change the source")
            assertFailsWith<AssertionError>(what) { ChromeClientPin.assertQuietClient(mutant) }
        }
        val page = source()
        for ((what, mutant) in ChromeClientPin.pageMutants(page)) {
            assertTrue(mutant != page, "the mutant '$what' did not change the source")
            assertFailsWith<AssertionError>(what) { ChromeClientPin.assertInstalled(mutant, "AndroidWebPage.kt") }
        }
    }

    /** The scan itself: the guard really fails for each way of opening the channel, so a pass above means something. */
    @Test
    fun eachWayOfOpeningTheChannelFailsThePin() {
        val real = source()
        val mutants = mapOf(
            "rule widened to every origin" to real.replace("setOf(allowedOrigin)", "setOf(\"*\")"),
            "rule widened to a pattern" to real.replace("setOf(allowedOrigin)", "setOf(\"https://*\")"),
            "no main-frame check" to real.replace("isMainFrame && ", ""),
            "no origin check" to real.replace("sourceOrigin.toString() == allowedOrigin && ", ""),
            "no string check" to real.replace(" && message.type == WebMessageCompat.TYPE_STRING", ""),
            "a term made optional" to real.replace("isMainFrame && sourceOrigin", "isMainFrame || sourceOrigin"),
            "origin compared with something else" to real.replace("sourceOrigin.toString() == allowedOrigin", "sourceOrigin.toString() != allowedOrigin"),
            "the guard removed altogether" to real.replace(
                "if (isMainFrame && sourceOrigin.toString() == allowedOrigin && message.type == WebMessageCompat.TYPE_STRING) {",
                "run {",
            ),
            "handed over before the guard" to real.replace("// The data is read only once", "listener?.invoke(\"\")\n                    // The data is read only once"),
            "handed over in an else branch" to real.replace(
                "                        message.data?.let { listener?.invoke(it) }\n                    }\n",
                "                        message.data?.let { listener?.invoke(it) }\n                    } else {\n                        message.data?.let { listener?.invoke(it) }\n                    }\n",
            ),
            "handed over a second time after the guard" to real.replace(
                "                        message.data?.let { listener?.invoke(it) }\n                    }\n",
                "                        message.data?.let { listener?.invoke(it) }\n                    }\n                    message.data?.let { listener?.invoke(it) }\n",
            ),
            "a guard without a block" to real.replace(
                "TYPE_STRING) {\n                        message.data?.let { listener?.invoke(it) }\n                    }\n",
                "TYPE_STRING) message.data?.let { listener?.invoke(it) }\n",
            ),
        )
        for ((what, mutant) in mutants) {
            assertTrue(mutant != real, "the mutant '$what' did not change the source")
            assertFailsWith<AssertionError>(what) { assertChannelIsClosed(mutant) }
        }
    }
}
