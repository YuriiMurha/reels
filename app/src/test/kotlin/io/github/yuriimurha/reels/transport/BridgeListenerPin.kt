package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.testutil.KotlinSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The source pin for a hidden page's one `addWebMessageListener` call, shared by [AndroidWebPageGuardTest] and
 * [RepairPageGuardTest] (each page has its own listener; neither shares the other's). Robolectric has no WebView provider, and
 * on the emulator the platform stops a frame of another origin before the listener ever sees it, so what keeps the channel
 * closed is read from the source: the rule set is exactly the allowed origin (never `*`), and a message is handed over only
 * when the platform credits it to the MAIN frame, from exactly that origin, as a STRING.
 */
internal object BridgeListenerPin {
    /** What the listener call lets through: its origin rules, and the terms of the `if` that guards the hand-over. */
    data class Channel(val rules: String, val conditions: List<String>)

    /** The only channel a page may open. */
    val CLOSED = Channel(
        rules = "webView,BRIDGE,setOf(allowedOrigin)",
        conditions = listOf("isMainFrame", "sourceOrigin.toString() == allowedOrigin", "message.type == WebMessageCompat.TYPE_STRING"),
    )

    private const val CALL = "WebViewCompat.addWebMessageListener("

    /**
     * The call's arguments and the `&&`-terms of the single `if` in its lambda, in [text] (the source of [file]); fails when the
     * lambda hands a message over ([handOver], a call such as `listener?.invoke(`) from anywhere else: before the `if`, in a
     * second one, in an `else`, or after the `if`'s block. The `if` must be the lambda's last statement, with a braced block that
     * holds the one hand-over.
     */
    fun channelOf(text: String, file: String, handOver: String): Channel {
        // The name of what the message is handed to: `listener` for `listener?.invoke(`, `received` for `received(`.
        val receiver = handOver.takeWhile { it.isLetterOrDigit() || it == '_' }
        val shape = KotlinSource.skeleton(text)
        val code = KotlinSource.code(text)
        val calls = KotlinSource.callArguments(text, CALL)
        assertEquals(1, calls.size, "expected exactly one $CALL in $file")
        val rules = calls.single().filterNot { it.isWhitespace() }

        // The trailing lambda: from the `{` after the call's closing parenthesis to its matching `}`.
        val open = shape.indexOf(CALL) + CALL.length - 1
        val close = matching(shape, open, '(', ')', file)
        var lambdaStart = close + 1
        while (shape[lambdaStart].isWhitespace()) lambdaStart++
        assertEquals('{', shape[lambdaStart], "the listener is the call's trailing lambda")
        val lambdaEnd = matching(shape, lambdaStart, '{', '}', file)
        val body = code.substring(lambdaStart + 1, lambdaEnd)
        val bodyShape = shape.substring(lambdaStart + 1, lambdaEnd)

        val ifs = Regex("""\bif\s*\(""").findAll(bodyShape).toList()
        assertEquals(1, ifs.size, "the listener has exactly one condition")
        val conditionOpen = ifs.single().range.last
        val conditionClose = matching(bodyShape, conditionOpen, '(', ')', file)
        val condition = body.substring(conditionOpen + 1, conditionClose)
        val before = body.substring(0, conditionOpen)
        assertTrue(receiver !in before, "nothing reaches $receiver before the condition")
        // The block of the `if`: a `{` right after the condition, to its matching `}`; after it, nothing at all (no `else`, no
        // second hand-over), and inside it the one hand-over.
        var blockOpen = conditionClose + 1
        while (bodyShape[blockOpen].isWhitespace()) blockOpen++
        assertEquals('{', bodyShape[blockOpen], "the condition guards a braced block")
        val blockClose = matching(bodyShape, blockOpen, '{', '}', file)
        val block = body.substring(blockOpen + 1, blockClose)
        assertTrue(bodyShape.substring(blockClose + 1).isBlank(), "nothing follows the condition's block (no else, no second hand-over)")
        assertEquals(1, Regex(Regex.escape(handOver)).findAll(body).count(), "$receiver is called exactly once in the lambda")
        assertTrue(handOver in block, "$receiver is called only inside the condition's block")
        return Channel(rules, condition.split("&&").map { it.trim().replace(Regex("""\s+"""), " ") })
    }

    /** The index of the bracket closing the one at [open] in [shape] (strings and comments already blanked). */
    private fun matching(shape: String, open: Int, up: Char, down: Char, file: String): Int {
        var depth = 0
        for (k in open until shape.length) {
            if (shape[k] == up) depth++
            if (shape[k] == down && --depth == 0) return k
        }
        error("unbalanced $up in $file")
    }
}
