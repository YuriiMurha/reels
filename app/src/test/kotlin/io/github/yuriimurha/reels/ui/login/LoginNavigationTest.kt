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

/** Spec 9.6: the login WebView stays on Instagram's own pages and never hands a link to another app. */
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
    fun httpsPagesOnInstagramAndItsLoginPartnersLoadInsideTheWebView() {
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
    fun onlyInstagramFacebookAndMetaPagesLoad() {
        for (url in listOf(
            "https://www.instagram.com/accounts/login/",
            "https://instagram.com/",
            "https://l.instagram.com/?u=x",
            "https://m.facebook.com/",
            "https://facebook.com/login/",
            "https://www.meta.com/",
            "HTTPS://WWW.INSTAGRAM.COM/",
        )) {
            assertFalse(blocked(url), "$url is Instagram's own login flow and must load")
        }
        for (url in listOf(
            "https://example.com/",
            "https://evilinstagram.com/",
            "https://instagram.com.evil.example/",
            "https://www.instagram.com@evil.example/",
            "https://notfacebook.com/",
            "https://facebook.com.evil.example/",
            "https://metaa.com/",
            "http://www.instagram.com/",
        )) {
            assertTrue(blocked(url), "$url must not load in the login WebView")
        }
    }

    @Test
    fun theHostCheckIsCaseInsensitiveAndHasADotBoundary() {
        assertTrue(isAllowedPage("HTTPS", "WWW.Instagram.COM"))
        assertTrue(isAllowedPage("https", "instagram.com"))
        assertFalse(isAllowedPage("https", "evilinstagram.com"))
        assertFalse(isAllowedPage("https", "instagram.com."))
        assertFalse(isAllowedPage("http", "www.instagram.com"))
        assertFalse(isAllowedPage("https", null))
        assertFalse(isAllowedPage(null, "www.instagram.com"))
    }

    @Test
    fun theStartPageIsAnAllowedPageOrTheLoginPage() {
        assertEquals(loginPage, loginTarget(null))
        assertEquals("https://www.instagram.com/", loginTarget("https://www.instagram.com/"))
        assertEquals("HTTPS://www.instagram.com/challenge/x/", loginTarget("HTTPS://www.instagram.com/challenge/x/"))
        assertEquals("https://m.facebook.com/", loginTarget("https://m.facebook.com/"))
        assertEquals(loginPage, loginTarget("https://example.com/"))
        assertEquals(loginPage, loginTarget("https://evilinstagram.com/"))
        assertEquals(loginPage, loginTarget("http://www.instagram.com/"))
        assertEquals(loginPage, loginTarget("javascript:alert(1)"))
        assertEquals(loginPage, loginTarget("intent://instagram.com/#Intent;end"))
        assertEquals(loginPage, loginTarget(""))
    }

    @Test
    fun theStartPageDependsOnThePurpose() {
        val home = "https://www.instagram.com/"
        assertEquals(loginPage, startPage(LoginPurpose.LOGIN, null))
        assertEquals(loginPage, startPage(LoginPurpose.LOGIN, "https://example.com/"))
        assertEquals(loginPage, startPage(LoginPurpose.RELOGIN, null))
        assertEquals(home, startPage(LoginPurpose.CSRF, home))
        assertEquals(home, startPage(LoginPurpose.CHALLENGE, null), "no challenge URL: the home page redirects to the checkpoint")
        assertEquals(home, startPage(LoginPurpose.CHALLENGE, "https://example.com/challenge/x/"))
        assertEquals("https://www.instagram.com/challenge/x/", startPage(LoginPurpose.CHALLENGE, "https://www.instagram.com/challenge/x/"))
    }

    @Test
    fun aChallengeUrlLoadsOnlyWhenItIsAnAllowedPage() {
        assertEquals("https://www.instagram.com/challenge/x/", allowedUrlOrNull("https://www.instagram.com/challenge/x/"))
        assertNull(allowedUrlOrNull("http://www.instagram.com/challenge/x/"))
        assertNull(allowedUrlOrNull("https://example.com/challenge/x/"))
        assertNull(allowedUrlOrNull(null))
    }

    @Test
    fun aChallengeWithoutAUsableUrlSendsTheWebViewToInstagramHome() {
        assertEquals("https://www.instagram.com/", challengeTarget(null))
        assertEquals("https://www.instagram.com/", challengeTarget("https://example.com/challenge/x/"))
        assertEquals("https://www.instagram.com/challenge/x/", challengeTarget("https://www.instagram.com/challenge/x/"))
    }

    @Test
    fun theWebViewIsNotReloadedOntoThePageItAlreadyShows() {
        assertFalse(needsLoad("https://www.instagram.com/challenge/x/", "https://www.instagram.com/challenge/x/"))
        assertTrue(needsLoad("https://www.instagram.com/accounts/login/", "https://www.instagram.com/challenge/x/"))
        assertTrue(needsLoad(null, "https://www.instagram.com/"))
    }
}
