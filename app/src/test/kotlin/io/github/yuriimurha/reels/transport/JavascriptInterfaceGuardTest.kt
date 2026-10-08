package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.testutil.KotlinSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page-to-app channel is `WebViewCompat.addWebMessageListener`, which only accepts messages from the origins it is
 * given. `addJavascriptInterface` exposes an object to every page and frame the WebView ever shows, so it is never used.
 */
class JavascriptInterfaceGuardTest {
    /** Every file of the app's `src/main`: Kotlin and Java with their comments blanked (a doc may say why it is never used), the rest as it is. */
    @Test
    fun noFileInSrcMainEverCallsAddJavascriptInterface() {
        val files = File("src/main").walkTopDown().filter { it.isFile }.toList()
        assertTrue(files.any { it.extension == "kt" } && files.any { it.name == "ig_fetch.js" }, "unit tests must run from the app module directory")
        val users = files
            .filter { file ->
                val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return@filter false // a binary resource
                "addJavascriptInterface" in if (file.extension == "kt" || file.extension == "java") KotlinSource.code(text) else text
            }
            .map { it.invariantSeparatorsPath.substringAfter("src/main/") }
        assertEquals(emptyList(), users)
    }
}
