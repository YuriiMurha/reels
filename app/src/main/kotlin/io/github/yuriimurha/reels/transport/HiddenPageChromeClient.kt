package io.github.yuriimurha.reels.transport

import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * The [WebChromeClient] of both hidden pages, [AndroidWebPage] and [AndroidRepairPage] (R16). Without one, a WebView writes the
 * site's own `console` messages to the system log, release builds too: the repair page's URL with the owner's handle, signed
 * CDN URLs. This client keeps every console message out of it, and answers everything else the site could ask of a page
 * nobody sees exactly as a WebView without a client does (no dialog shown, no permission given), only on purpose:
 * - a console message is handled here and dropped (never the default, which logs it);
 * - `alert`, `confirm`, `prompt` and `beforeunload` are cancelled at once;
 * - geolocation and every other permission (camera, microphone, protected media) are denied;
 * - a file chooser is answered with no file.
 *
 * Nothing else is overridden: a page that asks for full screen (`onShowCustomView`) or a new window (`onCreateWindow`) keeps the
 * default, which shows and opens nothing. `AndroidWebPageGuardTest` and `RepairPageGuardTest` pin the class and its use on both
 * pages; `HiddenPageChromeClientTest` runs each override; the emulator test shows a page's console marker never reaching logcat.
 */
internal class HiddenPageChromeClient : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true

    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        result.cancel()
        return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        result.cancel()
        return true
    }

    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
        result.cancel()
        return true
    }

    override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
        result.cancel()
        return true
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams?,
    ): Boolean {
        filePathCallback.onReceiveValue(null)
        return true
    }
}
