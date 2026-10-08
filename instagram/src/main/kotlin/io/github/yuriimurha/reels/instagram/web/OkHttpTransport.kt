package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [InstagramTransport] over the OkHttp client of [HttpClientFactory.create] (no redirects, no retries, the shared cookie
 * jar). One request per [get]. A connect failure is [InstagramException.Transient]; an HTTP error status is a reply.
 *
 * A 3xx is not followed and its body is not read: it is a [RawReply] with `redirected = true`, except that a bounce to
 * `/accounts/login` becomes the same `require_login` body Instagram itself sends, so the login-bounce rule of
 * [ErrorClassifier] still applies. A body that can't be read is `null`, whatever the status: [classifyReply] decides
 * what that means.
 */
class OkHttpTransport(
    private val http: OkHttpClient,
    private val base: HttpUrl = WebEndpoints.BASE,
) : InstagramTransport {
    override suspend fun get(pathAndQuery: String): RawReply {
        val url = base.resolve(pathAndQuery)
        // Only WebEndpoints builds the path, but an absolute or scheme-relative one must never leave the base host.
        require(url != null && url.scheme == base.scheme && url.host == base.host && url.port == base.port) {
            "not a path on the Instagram base"
        }
        val response = try {
            http.newCall(Request.Builder().url(url).get().build()).await()
        } catch (e: IOException) {
            throw InstagramException.Transient(e)
        }
        return response.use { it.toReply() }
    }

    private fun Response.toReply(): RawReply {
        val contentType = header("Content-Type")
        if (code in 300..399) {
            val bouncedToLogin = header("Location")?.contains("/accounts/login") == true
            return if (bouncedToLogin) RawReply(code, contentType, LOGIN_BOUNCE_BODY) else RawReply(code, contentType, null, redirected = true)
        }
        val text = try {
            body.string()
        } catch (_: IOException) {
            null
        }
        return RawReply(code, contentType, text)
    }

    private companion object {
        const val LOGIN_BOUNCE_BODY = "{\"require_login\":true}"
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
