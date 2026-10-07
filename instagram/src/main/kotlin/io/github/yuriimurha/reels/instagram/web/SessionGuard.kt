package io.github.yuriimurha.reels.instagram.web

import okhttp3.Interceptor
import okhttp3.Response

/**
 * A network interceptor that stops a response from writing cookies for a session that is no longer the current one.
 *
 * A request can be in flight when the owner logs out, pastes another sessionid, or logs in again. Its answer then
 * arrives for a session that is gone, and OkHttp's cookie bridge (which runs after the network interceptors) would store
 * every `Set-Cookie` in it: the old sessionid, back in the jar the logout just emptied. So the guard reads the jar's
 * `sessionid` before the request goes out and again when the answer is back. If the two differ, the session changed during
 * the flight, and the response is returned with every `Set-Cookie` header removed; the caller still gets the body and
 * status. Anything else, including a response that legitimately sets a new `sessionid` for an unchanged session, passes.
 *
 * Register it FIRST among the network interceptors: then it is the outermost one, and sees the response last, just before
 * the bridge stores its cookies. It is not needed on the CDN client, which has no cookie jar.
 */
internal class SessionGuard(private val cookies: CookieStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val site = request.url.toString()
        val before = cookies.cookieValue(site, SESSION_COOKIE)
        val response = chain.proceed(request)
        val after = cookies.cookieValue(site, SESSION_COOKIE)
        return if (before == after) response else response.newBuilder().removeHeader("Set-Cookie").build()
    }

    private companion object {
        const val SESSION_COOKIE = "sessionid"
    }
}
