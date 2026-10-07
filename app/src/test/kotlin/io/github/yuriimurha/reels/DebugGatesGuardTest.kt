package io.github.yuriimurha.reels

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec 6.3: the Adapter lab and the Developer section send real requests and exist in debug builds only. These are
 * source pins in the style of [WebViewDebuggingGuardTest]: they read the code, so a refactor that drops a gate (or moves
 * the call out of its block) fails here instead of shipping a lab in a release build.
 */
class DebugGatesGuardTest {
    private val gate = "if (BuildConfig.DEBUG)"

    @Test
    fun theDeveloperSectionIsOnlyEverComposedInsideADebugGate() {
        assertEveryOccurrenceIsGated("ui/sync/SyncScreen.kt", "DeveloperSection(")
    }

    @Test
    fun theLabRouteIsOnlyEverRegisteredInsideADebugGate() {
        assertEveryOccurrenceIsGated("ui/ReelsNavHost.kt", "composable<AdapterLabRoute>")
    }

    @Test
    fun theLabIsOnlyReachedFromTheContainerAndTheLabPackage() {
        val users = sources()
            .filter { "adapterLab" in code(it.text) }
            .map { it.path }
        assertTrue("di/AppContainer.kt" in users, "the container no longer builds the lab: update this pin")
        assertTrue("ui/lab/AdapterLabScreen.kt" in users, "the lab screen no longer uses the lab: update this pin")
        val strays = users.filterNot { it == "di/AppContainer.kt" || it.startsWith("ui/lab/") }
        assertEquals(emptyList(), strays, "adapterLab may only be referenced from di/AppContainer.kt and ui/lab/")
    }

    /**
     * Lab calls are real traffic, so they go through the process's one Conservative pacer. In Mock mode `backend.pacer`
     * is the fast fake one, which would let lab taps skip the real gap, budget and cooldown.
     */
    @Test
    fun theLabScreenPacesThroughTheInstagramPacerNeverTheBackendsPacer() {
        val screen = code(source("ui/lab/AdapterLabScreen.kt").text)
        assertTrue("container.instagramPacer" in screen, "AdapterLabScreen must pace with container.instagramPacer")
        assertFalse(
            Regex("""\bbackend\b""").containsMatchIn(screen),
            "AdapterLabScreen must not touch the backend at all (backend.pacer is the fast fake pacer in Mock mode)",
        )
    }

    // The gate is the `{` that directly follows `if (BuildConfig.DEBUG)`. Walking back from the call, a `}` means we are
    // leaving a sibling block, so its `{` is skipped; a `{` at depth 0 opens a block that encloses the call, and the call
    // is gated when one of those blocks is the gate. An `else` branch of the gate does not count: its `{` follows `else`.
    private fun assertEveryOccurrenceIsGated(path: String, call: String) {
        val text = source(path).text
        val offsets = Regex(Regex.escape(call)).findAll(text).map { it.range.first }.toList()
        assertTrue(offsets.isNotEmpty(), "$path no longer contains `$call`: update this pin")
        for (offset in offsets) {
            val line = text.substring(0, offset).count { it == '\n' } + 1
            assertTrue(
                isInsideGate(text.substring(0, offset)),
                "`$call` at $path:$line must sit inside a `$gate { ... }` block",
            )
        }
    }

    private fun isInsideGate(textBeforeCall: String): Boolean {
        val before = code(textBeforeCall)
        var closed = 0
        for (i in before.indices.reversed()) {
            when (before[i]) {
                '}' -> closed++
                '{' -> if (closed > 0) {
                    closed--
                } else if (before.substring(0, i).trimEnd().endsWith(gate)) {
                    return true
                }
            }
        }
        return false
    }

    /** Comments blanked out (same length), so a brace or a gate named in a comment can't count. */
    private fun code(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/|//[^\n]*""")) { match -> match.value.map { if (it == '\n') '\n' else ' ' }.joinToString("") }

    private class Source(val path: String, val text: String)

    private fun sources(): List<Source> {
        val root = File("src/main/kotlin/io/github/yuriimurha/reels")
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "unit tests must run from the app module directory")
        return files.map { Source(it.relativeTo(root).invariantSeparatorsPath, it.readText()) }
    }

    private fun source(path: String): Source =
        sources().singleOrNull { it.path == path } ?: error("$path is missing: update this pin")
}
