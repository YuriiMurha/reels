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
    @Test
    fun noSourceFileEverCallsAddJavascriptInterface() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "unit tests must run from the app module directory")
        val users = sources
            .filter { "addJavascriptInterface" in KotlinSource.code(it.readText()) }
            .map { it.invariantSeparatorsPath.substringAfter("src/main/kotlin/") }
        assertEquals(emptyList(), users)
    }
}
