package io.github.yuriimurha.reels.testutil

/**
 * Just enough Kotlin lexing for the source-pin tests (in the style of `FastPolicyGuardTest`): comments blanked, string
 * contents blanked, and the argument text of a call. A lexer, not regexes: a `//` inside a string literal (a URL) is not a
 * comment, and a quote inside a comment does not open a string.
 */
object KotlinSource {
    /** [text] with every comment blanked (same length, newlines kept). String and character literals are left alone. */
    fun code(text: String): String = blank(text, blankStrings = false)

    /** [code] with the insides of string and character literals blanked too, so a brace or paren in a string can't count. */
    fun skeleton(text: String): String = blank(text, blankStrings = true)

    /**
     * The text between the parentheses of every call that starts with [call] (for example `"HttpMediaFetcher("`) in
     * [text], matched by depth, ignoring comments and string contents. [call] must end with `(`.
     */
    fun callArguments(text: String, call: String): List<String> {
        require(call.endsWith("(")) { "call must end with an opening parenthesis" }
        val shape = skeleton(text)
        val code = code(text)
        val results = mutableListOf<String>()
        var from = shape.indexOf(call)
        while (from >= 0) {
            val open = from + call.length - 1
            var depth = 0
            var close = -1
            for (k in open until shape.length) {
                if (shape[k] == '(') depth++
                if (shape[k] == ')' && --depth == 0) { close = k; break }
            }
            check(close > open) { "unbalanced parentheses after $call" }
            results += code.substring(open + 1, close)
            from = shape.indexOf(call, close)
        }
        return results
    }

    /**
     * Handles `"..."` with escapes, `"""..."""`, `'x'` and non-nested `/* */`. It does not parse `${...}` templates (a quote
     * inside one would confuse it), which none of the scanned files have.
     */
    private fun blank(text: String, blankStrings: Boolean): String {
        val out = StringBuilder(text)
        fun blankRange(from: Int, to: Int) {
            for (k in from until minOf(to, text.length)) if (text[k] != '\n') out[k] = ' '
        }
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    val end = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    if (blankStrings) blankRange(i + 3, end - 3)
                    i = end
                }
                text[i] == '"' || text[i] == '\'' -> {
                    val quote = text[i]
                    var j = i + 1
                    while (j < text.length && text[j] != quote && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                    if (blankStrings) blankRange(i + 1, j)
                    i = j + 1
                }
                text.startsWith("//", i) -> {
                    val end = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                    blankRange(i, end)
                    i = end
                }
                text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    blankRange(i, end)
                    i = end
                }
                else -> i++
            }
        }
        return out.toString()
    }
}
