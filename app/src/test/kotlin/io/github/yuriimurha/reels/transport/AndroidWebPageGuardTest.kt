package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.instagram.web.WebEndpoints
import io.github.yuriimurha.reels.testutil.KotlinSource
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
 * the rule set is exactly the allowed origin (never `*`), and a message reaches the transport only from the MAIN frame, from
 * exactly that origin, as a STRING. Each of the three conditions is the only thing that stops one kind of sender, so dropping
 * any of them, or widening the rule, fails here (the mutants below are made from the real source).
 */
class AndroidWebPageGuardTest {
    private val path = "src/main/kotlin/io/github/yuriimurha/reels/transport/AndroidWebPage.kt"

    private fun source(): String {
        val file = File(path)
        assertTrue(file.isFile, "unit tests must run from the app module directory, and $path must exist")
        return file.readText()
    }

    /** What the listener call lets through: its origin rules, and the terms of the `if` that guards the hand-over. */
    private data class Channel(val rules: String, val conditions: List<String>)

    private val call = "WebViewCompat.addWebMessageListener("

    private val expected = Channel(
        rules = "webView,BRIDGE,setOf(allowedOrigin)",
        conditions = listOf("isMainFrame", "sourceOrigin.toString() == allowedOrigin", "message.type == WebMessageCompat.TYPE_STRING"),
    )

    /**
     * The call's arguments and the `&&`-terms of the single `if` in its lambda; fails when the lambda hands a message to the
     * listener from anywhere else: before the `if`, in a second one, in an `else`, or after the `if`'s block. The `if` must be the
     * lambda's last statement, with a braced block that holds the one hand-over.
     */
    private fun channelOf(text: String): Channel {
        val shape = KotlinSource.skeleton(text)
        val code = KotlinSource.code(text)
        val calls = KotlinSource.callArguments(text, call)
        assertEquals(1, calls.size, "expected exactly one $call in AndroidWebPage.kt")
        val rules = calls.single().filterNot { it.isWhitespace() }

        // The trailing lambda: from the `{` after the call's closing parenthesis to its matching `}`.
        val open = shape.indexOf(call) + call.length - 1
        val close = matching(shape, open, '(', ')')
        var lambdaStart = close + 1
        while (shape[lambdaStart].isWhitespace()) lambdaStart++
        assertEquals('{', shape[lambdaStart], "the listener is the call's trailing lambda")
        val lambdaEnd = matching(shape, lambdaStart, '{', '}')
        val body = code.substring(lambdaStart + 1, lambdaEnd)
        val bodyShape = shape.substring(lambdaStart + 1, lambdaEnd)

        val ifs = Regex("""\bif\s*\(""").findAll(bodyShape).toList()
        assertEquals(1, ifs.size, "the listener has exactly one condition")
        val conditionOpen = ifs.single().range.last
        val conditionClose = matching(bodyShape, conditionOpen, '(', ')')
        val condition = body.substring(conditionOpen + 1, conditionClose)
        val before = body.substring(0, conditionOpen)
        assertTrue("listener" !in before, "nothing reaches the listener before the condition")
        // The block of the `if`: a `{` right after the condition, to its matching `}`; after it, nothing at all (no `else`, no
        // second hand-over), and inside it the one hand-over.
        var blockOpen = conditionClose + 1
        while (bodyShape[blockOpen].isWhitespace()) blockOpen++
        assertEquals('{', bodyShape[blockOpen], "the condition guards a braced block")
        val blockClose = matching(bodyShape, blockOpen, '{', '}')
        val block = body.substring(blockOpen + 1, blockClose)
        assertTrue(bodyShape.substring(blockClose + 1).isBlank(), "nothing follows the condition's block (no else, no second hand-over)")
        assertEquals(1, Regex("""listener\?\.invoke\(""").findAll(body).count(), "the listener is called exactly once in the lambda")
        assertTrue("listener?.invoke(" in block, "the transport's listener is called only inside the condition's block")
        return Channel(rules, condition.split("&&").map { it.trim().replace(Regex("""\s+"""), " ") })
    }

    /** The index of the bracket closing the one at [open] in [shape] (strings and comments already blanked). */
    private fun matching(shape: String, open: Int, up: Char, down: Char): Int {
        var depth = 0
        for (k in open until shape.length) {
            if (shape[k] == up) depth++
            if (shape[k] == down && --depth == 0) return k
        }
        error("unbalanced $up in AndroidWebPage.kt")
    }

    private fun assertChannelIsClosed(text: String) = assertEquals(expected, channelOf(text))

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
