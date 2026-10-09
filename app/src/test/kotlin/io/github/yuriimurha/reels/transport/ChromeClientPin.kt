package io.github.yuriimurha.reels.transport

import io.github.yuriimurha.reels.testutil.KotlinSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R16, the source pin shared by [AndroidWebPageGuardTest] and [RepairPageGuardTest]: both hidden pages install the one
 * [HiddenPageChromeClient], and that class overrides exactly what keeps the site's console out of the system log and answers
 * its dialogs and permission requests on purpose ([HiddenPageChromeClientTest] runs each override). Robolectric has no WebView
 * provider, so a page's own installation is read from its source.
 */
internal object ChromeClientPin {
    const val CLIENT_PATH = "src/main/kotlin/io/github/yuriimurha/reels/transport/HiddenPageChromeClient.kt"

    /** Every override the client has, and only these: never `onShowCustomView`, `onHideCustomView` or `onCreateWindow`. */
    val OVERRIDES = setOf(
        "onConsoleMessage", "onJsAlert", "onJsConfirm", "onJsPrompt", "onJsBeforeUnload", "onGeolocationPermissionsShowPrompt",
        "onPermissionRequest", "onShowFileChooser",
    )

    fun clientSource(): String {
        val file = File(CLIENT_PATH)
        assertTrue(file.isFile, "unit tests must run from the app module directory, and $CLIENT_PATH must exist")
        return file.readText()
    }

    /**
     * The client in [source]: a [android.webkit.WebChromeClient], exactly [OVERRIDES], none calling `super` (whose console
     * default logs the message); the console override is `= true`, each dialog cancels its result and returns true, geolocation
     * is denied and not retained, a permission request denied, a file chooser answered with no file.
     */
    fun assertQuietClient(source: String) {
        val code = KotlinSource.code(source)
        assertTrue(Regex("""^internal class HiddenPageChromeClient : WebChromeClient\(\) \{$""", RegexOption.MULTILINE).containsMatchIn(code), "the class header")
        val overrides = Regex("""\boverride fun (\w+)\(""").findAll(code).map { it.groupValues[1] }.toList()
        assertEquals(OVERRIDES.sorted(), overrides.sorted(), "exactly these overrides, each once")
        assertFalse(Regex("""\bsuper\b""").containsMatchIn(code), "no override calls super")
        assertTrue(Regex("""override fun onConsoleMessage\(consoleMessage: ConsoleMessage\?\): Boolean = true$""", RegexOption.MULTILINE).containsMatchIn(code), "the console is handled")
        for (dialog in listOf("onJsAlert", "onJsConfirm", "onJsPrompt", "onJsBeforeUnload")) {
            assertEquals("result.cancel() return true", bodyOf(code, dialog), "$dialog cancels")
        }
        assertEquals("callback.invoke(origin, false, false)", bodyOf(code, "onGeolocationPermissionsShowPrompt"))
        assertEquals("request.deny()", bodyOf(code, "onPermissionRequest"))
        assertEquals("filePathCallback.onReceiveValue(null) return true", bodyOf(code, "onShowFileChooser"))
    }

    /**
     * A page's [source] installs one [HiddenPageChromeClient] on its own WebView, in its `init`, before the `webViewClient` (so
     * before any load), and sets no other chrome client.
     */
    fun assertInstalled(source: String, file: String) {
        val code = KotlinSource.code(source)
        val installs = Regex("""\bwebChromeClient\s*=\s*(.*)$""", RegexOption.MULTILINE).findAll(code).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf("HiddenPageChromeClient()"), installs, "$file sets one chrome client, the hidden pages' own")
        assertEquals(1, Regex("""\bwebView\.webChromeClient\s*=""").findAll(code).count(), "$file sets it on its own WebView")
        assertFalse(Regex("""\bsetWebChromeClient\b|WebChromeClient\(""").containsMatchIn(code), "$file makes no other chrome client")
        val install = code.indexOf("webView.webChromeClient")
        val init = code.indexOf("init {")
        assertTrue(init in 0 until install, "$file installs it in its init")
        assertTrue(install < code.indexOf("webView.webViewClient"), "$file installs it before its WebViewClient, before any load")
    }

    /** The statements of [function]'s block in [code], joined by one space. */
    private fun bodyOf(code: String, function: String): String {
        val start = code.indexOf("override fun $function(")
        assertTrue(start >= 0, "no $function")
        // The signature ends its line with the block's `{` (one line or several).
        val open = code.indexOf("{\n", start)
        assertTrue(open > start && "override fun" !in code.substring(start + 1, open), "$function has a block of its own")
        var depth = 0
        for (k in open until code.length) {
            if (code[k] == '{') depth++
            if (code[k] == '}' && --depth == 0) {
                return code.substring(open + 1, k).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            }
        }
        error("unbalanced block in $function")
    }

    /** The class pin's own mutants, made from the real source: each must fail [assertQuietClient]. */
    fun clientMutants(real: String): Map<String, String> = mapOf(
        "the console logged by default" to real.replace("override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true", "override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = false"),
        "the console handed to super" to real.replace("override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true", "override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = super.onConsoleMessage(consoleMessage)"),
        "no console override" to real.replace("    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true\n", ""),
        "an alert confirmed" to real.replaceFirst("        result.cancel()\n", "        result.confirm()\n"),
        "an alert left to the default" to real.replaceFirst("        result.cancel()\n        return true\n", "        return false\n"),
        "geolocation allowed" to real.replace("callback.invoke(origin, false, false)", "callback.invoke(origin, true, false)"),
        "geolocation remembered" to real.replace("callback.invoke(origin, false, false)", "callback.invoke(origin, false, true)"),
        "a permission granted" to real.replace("request.deny()", "request.grant(request.resources)"),
        "a file chooser left open" to real.replace("        filePathCallback.onReceiveValue(null)\n", ""),
        "full screen allowed" to real.replace(
            "    override fun onPermissionRequest(",
            "    override fun onShowCustomView(view: android.view.View?, callback: CustomViewCallback?) = Unit\n\n    override fun onPermissionRequest(",
        ),
        "a new window allowed" to real.replace(
            "    override fun onPermissionRequest(",
            "    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean = true\n\n    override fun onPermissionRequest(",
        ),
    )

    /** Where both pages' `init` ends its `try`, after the WebViewClient is set. */
    private const val END_OF_INIT = "        } catch (e: Exception) {\n            webView.destroy()"

    /** A page pin's own mutants, made from the page's real source (whose WebViewClient is set as `webView.webViewClient = `). */
    fun pageMutants(real: String): Map<String, String> {
        val install = "            webView.webChromeClient = HiddenPageChromeClient()\n"
        return mapOf(
            "no chrome client" to real.replace(install, ""),
            "the platform's default client" to real.replace(install, "            webView.webChromeClient = android.webkit.WebChromeClient()\n"),
            "a second client after it" to real.replace(install, install + "            webView.webChromeClient = null\n"),
            "installed after the WebViewClient" to real.replace(install, "").replace(END_OF_INIT, install + END_OF_INIT),
            "installed outside the init" to real.replace(install, "").replace("    override fun destroy() {\n", "    override fun destroy() {\n    $install"),
        )
    }
}
