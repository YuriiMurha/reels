package io.github.yuriimurha.reels.instagram.web

import okhttp3.Interceptor
import okhttp3.Response

/**
 * A network interceptor that stops a response from writing cookies for a session that is no longer the current one.
 *
 * A request can be in flight when the owner logs out, pastes another sessionid, or logs in again. Its answer then
 * arrives for a session that is gone, and OkHttp's cookie bridge (which runs after the network interceptors) would store
 * every `Set-Cookie` in it: the old sessionid, back in the jar the logout just emptied.
 *
 * "Before" is the `sessionid` the request actually carries, read from its `Cookie` header, not from the jar: OkHttp loads
 * that header (Bridge) before it connects (DNS, TCP, TLS), and network interceptors run after the connection is up, so a
 * logout during the handshake has already emptied the jar while the request still holds the old session. "After" is the
 * jar's `sessionid` when the answer is back. If the two differ, the session changed during the flight, and the response
 * is returned with every `Set-Cookie` header removed; the caller still gets the body and status. Anything else, including
 * a response that legitimately sets a new `sessionid` for an unchanged session, passes.
 *
 * This narrows the race, it does not close it: a change between this interceptor's read of the jar and the bridge's store
 * a few instructions later is not seen.
 *
 * Register it FIRST among the network interceptors: then it is the outermost one, and sees the response last, just before
 * the bridge stores its cookies. It is not needed on the CDN client, which has no cookie jar.
 */
internal class SessionGuard(private val cookies: CookieStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val sent = request.header("Cookie")?.let(::sessionIdOf)
        val response = chain.proceed(request)
        val now = cookies.cookieValue(request.url.toString(), SESSION_COOKIE)
        return if (sent == now) response else response.newBuilder().removeHeader("Set-Cookie").build()
    }

    private fun sessionIdOf(cookieHeader: String): String? =
        cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val SESSION_COOKIE = "sessionid"
    }
}
