package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.testutil.KotlinSource
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page-to-app channel is `WebViewCompat.addWebMessageListener`, which only accepts messages from the origins it is
 * given. `addJavascriptInterface` exposes an object to every page and frame the WebView ever shows, so it is never used.
 */
class JavascriptInterfaceGuardTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Every file of every source set that goes into an APK (`src/main`, and `src/debug`, `src/release` or any flavour's once
     * they exist: every `src/<set>` but the test ones). A debug-only WebView helper ships to the phone too.
     */
    @Test
    fun noFileOfAnAppSourceSetEverCallsAddJavascriptInterface() {
        val src = File("src")
        assertTrue(File(src, "main/assets/ig_fetch.js").isFile, "unit tests must run from the app module directory")
        assertEquals(emptyList(), usersOf(src))
    }

    /** The scan itself (T7): a call in a debug or release source set is found, one in a test source set is not the app's. */
    @Test
    fun theScanCoversEverySourceSetButTheTestOnes() {
        val src = tmp.newFolder("src")
        val call = "fun f(w: android.webkit.WebView) { w.addJavascriptInterface(Any(), \"x\") }"
        for (set in listOf("main", "debug", "release", "free", "test", "androidTest", "testDebug", "androidTestRelease")) {
            File(src, "$set/kotlin/X.kt").apply { parentFile.mkdirs() }.writeText(call)
        }
        File(src, "debug/assets/page.js").apply { parentFile.mkdirs() }.writeText("window.addJavascriptInterface;")
        // A documentation comment may say why it is never used.
        File(src, "release/kotlin/Doc.kt").writeText("/** Never addJavascriptInterface: see the guard. */\nclass Doc")

        assertEquals(
            listOf("debug/assets/page.js", "debug/kotlin/X.kt", "free/kotlin/X.kt", "main/kotlin/X.kt", "release/kotlin/X.kt"),
            usersOf(src),
        )
    }

    /**
     * The files under [src]'s app source sets that call `addJavascriptInterface`, as `<set>/<path>`: Kotlin and Java with their
     * comments blanked, the rest as it is.
     */
    private fun usersOf(src: File): List<String> =
        src.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
            .flatMap { set -> set.walkTopDown().filter { it.isFile }.toList() }
            .filter { file ->
                // readText(UTF_8) decodes bytes that are not text with replacement characters instead of failing, so a binary resource is
                // scanned too (not skipped); only a file that cannot be read at all is left out.
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@filter false
                "addJavascriptInterface" in if (file.extension == "kt" || file.extension == "java") KotlinSource.code(text) else text
            }
            .map { it.relativeTo(src).invariantSeparatorsPath }
            .sorted()
}
