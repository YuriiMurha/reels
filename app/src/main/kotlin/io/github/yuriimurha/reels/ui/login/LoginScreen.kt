package io.github.yuriimurha.reels.ui.login

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yuriimurha.reels.instagram.web.WebEndpoints
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.ui.LocalAppContainer
import kotlinx.coroutines.delay

private const val FINISHED_VERIFYING = "Finished verifying on Instagram?"

/**
 * Instagram's own login page in a WebView (spec 9.6). The session lands in CookieManager, shared with the API client.
 * [purpose] says what the screen may spend an Instagram request on; see [LoginPurpose].
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    startUrl: String?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    purpose: LoginPurpose = LoginPurpose.LOGIN,
    // A parameter only so tests can give the screen a fake session; the app always uses the default.
    viewModel: LoginViewModel = LocalAppContainer.current.let { container -> viewModel { LoginViewModel(container.session, purpose) } },
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.onCookiesMaybeReady()
            delay(1_000)
        }
    }
    LaunchedEffect(status) {
        if (status == LoginViewModel.Status.CsrfReady) {
            onDone()
            return@LaunchedEffect
        }
        val done = status as? LoginViewModel.Status.Done ?: return@LaunchedEffect
        when (val state = done.state) {
            is SessionState.Valid -> onDone()
            is SessionState.Challenge -> {
                // Without a usable URL Instagram's home redirects to the checkpoint. Don't reload the page already shown.
                val target = challengeTarget(state.challengeUrl)
                webView?.let { if (needsLoad(it.url, target)) it.loadUrl(target) }
            }
            SessionState.LoggedOut, is SessionState.Expired -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log in to Instagram") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (val current = status) {
                LoginViewModel.Status.Waiting -> when (purpose) {
                    LoginPurpose.CSRF -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    // A challenge screen is seeded with the session it was opened for, so it never checks by itself: the
                    // owner says when verification is finished (Instagram usually keeps the same sessionid).
                    LoginPurpose.CHALLENGE -> RetryBar(FINISHED_VERIFYING, viewModel::retry)
                    LoginPurpose.LOGIN, LoginPurpose.RELOGIN -> Unit
                }
                LoginViewModel.Status.CsrfReady -> Unit
                LoginViewModel.Status.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is LoginViewModel.Status.Failed -> RetryBar(current.message, viewModel::retry)
                is LoginViewModel.Status.Done -> when (current.state) {
                    // After a Challenge result nothing validates by itself any more (see LoginViewModel): the same manual
                    // control as a challenge screen, because Instagram may issue a new sessionid while it is being finished.
                    is SessionState.Challenge -> RetryBar(FINISHED_VERIFYING, viewModel::retry)
                    is SessionState.Expired, SessionState.LoggedOut ->
                        RetryBar("That session isn't valid yet. Finish logging in, then check again.", viewModel::retry)
                    is SessionState.Valid -> Unit
                }
            }
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        // Compose adds the view with a plain addView(view). Without LayoutParams it gets WRAP_CONTENT, and a
                        // WebView with a WRAP_CONTENT height lays the page out at zero height: Instagram's height:100%
                        // containers collapse and only the fixed backdrop paints.
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = InstagramOnlyClient(onRendererGone = { dead ->
                            // Its renderer died: this view is unusable and the screen has nothing to show. Close, never crash.
                            webView = null
                            dead.destroy()
                            onBack()
                        })
                        loadUrl(startPage(purpose, startUrl))
                    }.also { webView = it }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Where the WebView starts: the requested page when it is an allowed one, otherwise Instagram's login page. */
internal fun loginTarget(startUrl: String?): String = allowedUrlOrNull(startUrl) ?: WebEndpoints.LOGIN_URL

/**
 * Where the WebView starts for [purpose]. A challenge without a usable URL opens Instagram's home, which redirects to
 * the checkpoint, like a Challenge result does; the other purposes fall back to the login page.
 */
internal fun startPage(purpose: LoginPurpose, startUrl: String?): String = when (purpose) {
    LoginPurpose.CHALLENGE -> challengeTarget(startUrl)
    LoginPurpose.LOGIN, LoginPurpose.RELOGIN, LoginPurpose.CSRF -> loginTarget(startUrl)
}

/** What to load for a Challenge result: its URL when allowed, else Instagram's home, which redirects to the checkpoint. */
internal fun challengeTarget(challengeUrl: String?): String = allowedUrlOrNull(challengeUrl) ?: WebEndpoints.HOME_URL

/** False when the WebView already shows [target]: reloading it would only throw away what the owner has done there. */
internal fun needsLoad(currentUrl: String?, target: String): Boolean = currentUrl != target

/**
 * The login flow may only visit https pages on Instagram's own domains (and Facebook and Meta, which its login and
 * verification use). The list and the host matching live in `:instagram` ([WebEndpoints.isLoginPage]).
 */
internal fun isAllowedPage(scheme: String?, host: String?): Boolean = WebEndpoints.isLoginPage(scheme, host)

internal fun isAllowedPage(url: Uri): Boolean = isAllowedPage(url.scheme, url.host)

/** [url] when it is an allowed page, else null: nothing else is ever loaded. */
internal fun allowedUrlOrNull(url: String?): String? = url?.takeIf { it.isNotBlank() && isAllowedPage(Uri.parse(it)) }

/**
 * Keeps every page inside the WebView on Instagram's own domains, and never hands a link to another app (such as the
 * Instagram app on another account). Returning true means "handled here": the navigation simply doesn't happen.
 */
// onRenderProcessGone IS overridden below. androidx.webkit's lint check also flags the `WebViewClient()` constructor call in the
// supertype list of every subclass, override or not, so the one remaining warning is a false positive.
@SuppressLint("MissingOnRenderProcessGone")
internal class InstagramOnlyClient(
    /** Told, on the main thread, that [WebView]'s renderer is gone; the view can no longer be used and must be destroyed. */
    private val onRendererGone: (WebView) -> Unit = {},
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !isAllowedPage(request.url)

    /**
     * A renderer that crashes or is killed by the system (memory) takes the whole app down unless the client handles it, by
     * returning true. The view is useless from then on, so the owner is told to close it, never left to crash.
     */
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        onRendererGone(view)
        return true
    }
}

@Composable
private fun RetryBar(message: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRetry) { Text("Check again") }
    }
}
