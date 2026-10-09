package io.github.yuriimurha.reels.transport

import android.content.Context
import android.os.Process
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuriimurha.reels.NOT_AN_EMULATOR
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.isEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * R16 on the emulator, against a LOCAL server only: a hidden page's own `console.log` never reaches the system log. A WebView
 * without a chrome client writes the site's console to logcat (tag `chromium`) under this process, release builds too, so the
 * test first shows exactly that with a plain WebView (the control: logcat is readable here and the page's console does land
 * there), then loads the same kind of page in the transport's page and in the repair page and finds their markers nowhere in
 * this process's log. Markers are random and built from parts, and the test never logs one itself.
 */
@RunWith(AndroidJUnit4::class)
class HiddenPageConsoleTest {
    @get:Rule
    val emulatorOnly = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue(NOT_AN_EMULATOR, isEmulator())
                base.evaluate()
            }
        }
    }

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val sites = mutableListOf<LocalSite>()
    private val cleanups = mutableListOf<() -> Unit>()

    @After
    fun tearDown() {
        onMain { cleanups.forEach { it() } }
        sites.forEach { it.close() }
    }

    @Test
    fun theHiddenPagesConsoleNeverReachesTheSystemLog() {
        // The control: a WebView with no chrome client logs its page's console here.
        val control = marker()
        val controlSite = site(control)
        onMain {
            val plain = WebView(context.applicationContext)
            cleanups += { plain.destroy() }
            plain.settings.javaScriptEnabled = true
            plain.loadUrl(controlSite.origin + "/")
        }
        assertTrue(
            "the control: a page's console without a chrome client reaches logcat, so its absence below means something",
            waitFor(LOG_WAIT_MS) { control in processLog() },
        )

        // The transport's page: its console marker is logged by the page, which then tells the app it did.
        val hidden = marker()
        val hiddenSite = site(hidden)
        val heard = CopyOnWriteArrayList<String>()
        onMain {
            val page = AndroidWebPage(context, allowedOrigin = hiddenSite.origin)
            cleanups += { page.destroy() }
            page.onMessage { heard += it }
            page.load(hiddenSite.origin + "/")
        }
        assertTrue("the page ran its console call", waitFor(LOG_WAIT_MS) { DONE in heard })

        // The repair page: same, through its test hook for raw bridge messages (it watches for nothing here, so it times out).
        val repair = marker()
        val repairSite = site(repair)
        val repairHeard = CopyOnWriteArrayList<String>()
        onMain {
            val page = AndroidRepairPage(context, allowedOrigin = repairSite.origin, onRawMessage = { repairHeard += it })
            cleanups += { page.destroy() }
            page.watch(repairSite.origin + "/", WebGraphQl.SAVED_COLLECTIONS.friendlyName, WATCH_MS)
        }
        assertTrue("the repair page ran its console call", DONE in repairHeard)

        // A line this test writes after both pages logged: once it is in the log, so is everything they wrote before it.
        val sentinel = marker()
        android.util.Log.i(TAG, sentinel)
        assertTrue("the log caught up", waitFor(LOG_WAIT_MS) { sentinel in processLog() })
        val log = processLog()
        assertFalse("the transport's page wrote its console to the system log", hidden in log)
        assertFalse("the repair page wrote its console to the system log", repair in log)
    }

    // --- Plumbing ------------------------------------------------------------------------------------------------------------

    /** A page that logs [marker] to its console at every level, then tells the app it did (the bridge exists on both pages). */
    private fun site(marker: String): LocalSite = LocalSite().also { site ->
        require(site.origin.startsWith("http://127.0.0.1:")) { "a local server only: ${site.origin}" }
        sites += site
        val page = "<html><body>console<script>" +
            "console.log('$marker'); console.info('$marker'); console.warn('$marker'); console.error('$marker');" +
            "if (window.igBridge) window.igBridge.postMessage('$DONE');" +
            "</script></body></html>"
        site.route("/") { MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=utf-8").body(page).build() }
    }

    /** This process's whole log as logcat prints it now. */
    private fun processLog(): String {
        val logcat = ProcessBuilder("logcat", "-d", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
        return logcat.inputStream.bufferedReader().use { it.readText() }.also { logcat.waitFor(10, TimeUnit.SECONDS) }
    }

    /** A value plainly not a real one, built from parts and different on every run. */
    private fun marker(): String = listOf("console", "marker", UUID.randomUUID().toString().take(8)).joinToString("-")

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun <T> onMain(block: suspend CoroutineScope.() -> T): T = runBlocking { withContext(Dispatchers.Main, block) }

    private companion object {
        const val TAG = "HiddenPageConsoleTest"
        const val DONE = "console-done"
        const val LOG_WAIT_MS = 10_000L
        const val WATCH_MS = 3_000L
    }
}
