package io.github.yuriimurha.reels.data.media

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** The CDN said 429. Not an API rate limit: no cooldown, but no more CDN requests this run (Task 8). */
class CdnRateLimited : IOException("CDN rate limit")

/**
 * Downloads media from Instagram's CDN. It never talks to the Instagram API and never carries a cookie: [client] has no
 * jar at all, and the CDN hosts are not Instagram's. 200 gives the bytes; 403, 404 and 410 mean the file is gone (null);
 * 429 is [CdnRateLimited]; any other status is an [IOException] that names only the code, never the URL (CDN links are
 * signed). A blank, unparseable or non-https URL is null with no request at all. [requireHttps] is `false` only in tests,
 * so MockWebServer (http) can be used.
 *
 * The caller paces downloads through `Pacer.cdn` (2 at a time, jittered). [http] is called on the first real request, not
 * before: its user agent comes from the WebView provider.
 */
class HttpMediaFetcher(http: () -> OkHttpClient, private val requireHttps: Boolean = true) : MediaFetcher {
    private val http by lazy(http)

    override suspend fun fetch(url: String): ByteArray? {
        val target = url.takeIf { it.isNotBlank() }?.toHttpUrlOrNull() ?: return null
        if (requireHttps && !target.isHttps) return null
        val request = Request.Builder().url(target).get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    // On OkHttp's own thread, so the body is read here, and call.cancel() (a cancelled coroutine) cuts it short.
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resumeWith(runCatching { response.use(::bytesOrNull) })
                    }
                },
            )
        }
    }

    private fun bytesOrNull(response: Response): ByteArray? = when (response.code) {
        200 -> response.body.bytes()
        403, 404, 410 -> null
        429 -> throw CdnRateLimited()
        else -> throw IOException("CDN returned HTTP ${response.code}")
    }

    companion object {
        /**
         * The CDN client: no cookie jar at all, no silent re-sends, 15 s connect / 30 s read, the WebView user agent. An
         * https URL is never bounced to cleartext http. [userAgent] is reduced to printable ASCII so the header can't throw.
         */
        fun client(userAgent: String): OkHttpClient {
            val safeUserAgent = userAgent.filter { it in ' '..'~' }
            return OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .retryOnConnectionFailure(false)
                .followSslRedirects(false)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", safeUserAgent).build()) }
                .build()
        }
    }
}
