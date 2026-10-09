package io.github.yuriimurha.reels.transport

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJsPromptResult
import org.robolectric.shadows.ShadowJsResult
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R16: what [HiddenPageChromeClient] answers, override by override (a dialog's result through Robolectric's `ShadowJsResult`,
 * which records a cancel). The emulator test (`HiddenPageConsoleTest`) shows the console part end to end: a page's console
 * marker never reaches logcat.
 */
@RunWith(AndroidJUnit4::class)
class HiddenPageChromeClientTest {
    private val client = HiddenPageChromeClient()

    /** A console message is handled (true), so the WebView never logs it. */
    @Test
    fun aConsoleMessageIsHandledAndDropped() {
        for (level in ConsoleMessage.MessageLevel.entries) {
            assertTrue(client.onConsoleMessage(ConsoleMessage("a marker", "http://127.0.0.1/", 1, level)), "$level")
        }
        assertTrue(client.onConsoleMessage(null))
    }

    /** `alert`, `confirm`, `prompt` and `beforeunload`: each is handled (true) and its result cancelled at once, never left open. */
    @Test
    fun everyJavascriptDialogIsCancelled() {
        val dialogs = listOf<(JsPromptResult) -> Boolean>(
            { client.onJsAlert(null, "http://127.0.0.1/", "hi", it) },
            { client.onJsConfirm(null, "http://127.0.0.1/", "ok?", it) },
            { client.onJsBeforeUnload(null, "http://127.0.0.1/", "leave?", it) },
            { client.onJsPrompt(null, "http://127.0.0.1/", "name?", "default", it) },
        )
        for ((index, dialog) in dialogs.withIndex()) {
            val result = ShadowJsPromptResult.newInstance()
            assertTrue(dialog(result), "dialog $index: handled here")
            assertTrue(Shadow.extract<ShadowJsResult>(result).wasCancelled(), "dialog $index: cancelled")
        }
    }

    @Test
    fun geolocationIsDeniedAndNotRemembered() {
        val answers = mutableListOf<Triple<String?, Boolean, Boolean>>()
        client.onGeolocationPermissionsShowPrompt("http://127.0.0.1") { origin, allow, retain -> answers += Triple(origin, allow, retain) }
        assertEquals(listOf(Triple<String?, Boolean, Boolean>("http://127.0.0.1", false, false)), answers)
    }

    @Test
    fun everyOtherPermissionIsDenied() {
        var denied = 0
        var granted = 0
        val request = object : PermissionRequest() {
            override fun getOrigin(): Uri = Uri.parse("http://127.0.0.1/")

            override fun getResources(): Array<String> = arrayOf(RESOURCE_VIDEO_CAPTURE, RESOURCE_AUDIO_CAPTURE)

            override fun grant(resources: Array<out String>?) {
                granted++
            }

            override fun deny() {
                denied++
            }
        }
        client.onPermissionRequest(request)
        assertEquals(1, denied)
        assertEquals(0, granted)
    }

    @Test
    fun aFileChooserGetsNoFile() {
        val answers = mutableListOf<Array<Uri>?>()
        val callback = ValueCallback<Array<Uri>> { answers += it }
        assertTrue(client.onShowFileChooser(null, callback, null), "handled here")
        assertEquals(1, answers.size)
        assertNull(answers.single())
    }

}
