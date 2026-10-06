package io.github.yuriimurha.reels.ui.login

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.di.AppContainer
import io.github.yuriimurha.reels.ui.LocalAppContainer
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class LoginScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun findWebView(view: View): WebView? = when {
        view is WebView -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWebView(view.getChildAt(it)) }
        else -> null
    }

    /**
     * Compose adds the factory's view with a plain addView(view), so a view without LayoutParams gets WRAP_CONTENT.
     * A WebView whose height is WRAP_CONTENT lays the page out at zero height, and Instagram's height:100% containers
     * collapse (only the fixed backdrop paints).
     */
    @Test
    fun theWebViewFillsTheScreenSoThePageIsLaidOutAtFullHeight() {
        val container = AppContainer(ApplicationProvider.getApplicationContext())
        compose.setContent {
            ReelsTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    LoginScreen(startUrl = null, onDone = {}, onBack = {})
                }
            }
        }
        compose.waitForIdle()
        val webView = findWebView(compose.activity.window.decorView)
        assertNotNull(webView, "the login screen shows no WebView")
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView.layoutParams.height)
    }
}
