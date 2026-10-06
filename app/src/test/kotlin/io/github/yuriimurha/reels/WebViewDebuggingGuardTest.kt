package io.github.yuriimurha.reels

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec 6.3: WebView remote debugging (chrome://inspect) is a debug-build tool and must never be switched on in release. */
class WebViewDebuggingGuardTest {
    @Test
    fun remoteDebuggingIsOnlyEverEnabledInsideADebugGuard() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty(), "unit tests must run from the app module directory")
        val calls = sources.flatMap { file ->
            file.readLines().filter { "setWebContentsDebuggingEnabled" in it }.map { file.name to it.trim() }
        }
        assertEquals(
            listOf("ReelsApp.kt" to "if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)"),
            calls,
            "the only call is a literal `true` under `if (BuildConfig.DEBUG)`; never pass a value computed from the build type",
        )
    }
}
