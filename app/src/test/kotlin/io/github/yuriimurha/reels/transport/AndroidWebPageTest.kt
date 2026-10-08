package io.github.yuriimurha.reels.transport

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Robolectric has no JavaScript and no WebView provider, so this covers construction only. Page loads, messages and the
 * origin check run on the emulator against a local test server.
 */
@RunWith(AndroidJUnit4::class)
class AndroidWebPageTest {
    @Test
    fun aWebViewThatCannotPostMessagesFailsToConstruct() {
        // Robolectric's WebView does not support WEB_MESSAGE_LISTENER. The page must refuse to exist (WebViewTransport turns
        // that into Transient) rather than load instagram.com with no way to hear it, or fall back to addJavascriptInterface.
        val error = assertFailsWith<IllegalStateException> { AndroidWebPage(ApplicationProvider.getApplicationContext<Context>()) }
        assertEquals("this WebView cannot post messages to the app", error.message)
    }
}
