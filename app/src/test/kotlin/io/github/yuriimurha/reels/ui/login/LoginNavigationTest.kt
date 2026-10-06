package io.github.yuriimurha.reels.ui.login

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 9.6: the login WebView loads https pages only and never hands a link to another app. */
@RunWith(AndroidJUnit4::class)
class LoginNavigationTest {
    private val loginPage = "https://www.instagram.com/accounts/login/"

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = true
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    /** True means the WebView does NOT load it (and, being handled here, no other app is started either). */
    private fun blocked(url: String): Boolean =
        InstagramOnlyClient().shouldOverrideUrlLoading(WebView(ApplicationProvider.getApplicationContext()), request(url))

    @Test
    fun httpsPagesLoadInsideTheWebView() {
        assertFalse(blocked("https://www.instagram.com/accounts/login/"))
        assertFalse(blocked("https://m.facebook.com/login/"))
    }

    @Test
    fun everythingElseIsDroppedNotHandedToAnotherApp() {
        for (url in listOf(
            "http://www.instagram.com/",
            "intent://instagram.com/#Intent;package=com.instagram.android;scheme=https;end",
            "market://details?id=com.instagram.android",
            "instagram://user?username=someone",
            "tel:+10000000",
            "file:///sdcard/Download/x.html",
            "javascript:alert(1)",
        )) {
            assertTrue(blocked(url), "$url must not load or leave the app")
        }
    }

    @Test
    fun theSchemeCheckIgnoresCase() {
        assertTrue(isHttps("HTTPS"))
        assertFalse(isHttps("http"))
        assertFalse(isHttps(null))
    }

    @Test
    fun theStartPageIsHttpsOrTheLoginPage() {
        assertEquals(loginPage, loginTarget(null))
        assertEquals("https://www.instagram.com/", loginTarget("https://www.instagram.com/"))
        assertEquals("HTTPS://www.instagram.com/challenge/x/", loginTarget("HTTPS://www.instagram.com/challenge/x/"))
        assertEquals(loginPage, loginTarget("http://www.instagram.com/"))
        assertEquals(loginPage, loginTarget("javascript:alert(1)"))
        assertEquals(loginPage, loginTarget("intent://instagram.com/#Intent;end"))
        assertEquals(loginPage, loginTarget(""))
    }

    @Test
    fun aChallengeUrlLoadsOnlyWhenItIsHttps() {
        assertEquals("https://www.instagram.com/challenge/x/", httpsUrlOrNull("https://www.instagram.com/challenge/x/"))
        assertNull(httpsUrlOrNull("http://www.instagram.com/challenge/x/"))
        assertNull(httpsUrlOrNull(null))
    }
}
