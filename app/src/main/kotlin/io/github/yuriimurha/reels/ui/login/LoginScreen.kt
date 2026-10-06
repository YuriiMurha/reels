package io.github.yuriimurha.reels.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
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
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.ui.LocalAppContainer
import kotlinx.coroutines.delay

private const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

/** Instagram's own login page in a WebView (spec 9.6). The session lands in CookieManager, shared with the API client. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(startUrl: String?, onDone: () -> Unit, onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val viewModel = viewModel { LoginViewModel(container.session) }
    val status by viewModel.status.collectAsStateWithLifecycle()
    var webView by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.onCookiesMaybeReady()
            delay(1_000)
        }
    }
    LaunchedEffect(status) {
        val done = status as? LoginViewModel.Status.Done ?: return@LaunchedEffect
        when (val state = done.state) {
            is SessionState.Valid -> onDone()
            is SessionState.Challenge -> httpsUrlOrNull(state.challengeUrl)?.let { webView?.loadUrl(it) }
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
                LoginViewModel.Status.Waiting -> Unit
                LoginViewModel.Status.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is LoginViewModel.Status.Failed -> RetryBar(current.message, viewModel::retry)
                is LoginViewModel.Status.Done -> when (current.state) {
                    is SessionState.Challenge -> RetryBar("Instagram wants verification. Finish it below, then check again.", viewModel::retry)
                    is SessionState.Expired, SessionState.LoggedOut ->
                        RetryBar("That session isn't valid yet. Finish logging in, then check again.", viewModel::retry)
                    is SessionState.Valid -> Unit
                }
            }
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = InstagramOnlyClient()
                        loadUrl(loginTarget(startUrl))
                    }.also { webView = it }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Where the WebView starts: the requested page when it is https, otherwise Instagram's login page. */
internal fun loginTarget(startUrl: String?): String = httpsUrlOrNull(startUrl) ?: LOGIN_URL

/** [url] when it is an https URL (the scheme is case-insensitive), else null: nothing else is ever loaded. */
internal fun httpsUrlOrNull(url: String?): String? = url?.takeIf { it.startsWith("https://", ignoreCase = true) }

/** Only https pages load (case-insensitive scheme). Anything else is dropped, not handed to another app. */
internal fun isHttps(scheme: String?): Boolean = scheme.equals("https", ignoreCase = true)

/** Keeps every page inside the WebView and never hands a link to another app (such as the Instagram app on another account). */
internal class InstagramOnlyClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        !isHttps(request.url.scheme)
}

@Composable
private fun RetryBar(message: String, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onRetry) { Text("Check again") }
    }
}
