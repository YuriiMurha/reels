package io.github.yuriimurha.reels.instagram.web

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SessionGuard] through the real client from [HttpClientFactory.create] and a real cookie bridge. The requests go to
 * `www.instagram.com` (resolved to the mock server), because OkHttp refuses a cookie whose Domain does not match the
 * request's host: with a plain `localhost` URL a `Domain=.instagram.com` cookie would never be stored, guard or no guard,
 * and these tests could not tell the two apart.
 */
class SessionGuardTest {
    private val server = MockWebServer()
    private val site = WebSessionCookies.ORIGIN

    // Built from parts: nothing here is a session, but nothing here should look like one either.
    private val sessionId = "session" + "id"
    private val csrf = "csrf" + "token"

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    /**
     * The factory's client, with `www.instagram.com` resolved to the mock server by [dns] and no proxy, so a JVM-wide proxy
     * setting can never forward these requests (fake cookie included) to the real host.
     */
    private fun clientFor(
        cookies: CookieStore,
        logger: ((String) -> Unit)? = null,
        dns: () -> Unit = {},
    ): OkHttpClient =
        HttpClientFactory.create(cookies, userAgent = "UA", logger = logger).newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns {
                dns()
                listOf(InetAddress.getByName("127.0.0.1"))
            }
            .build()

    private fun get(client: OkHttpClient) {
        val url = "http://www.instagram.com:${server.port}/api/v1/x/"
        client.newCall(Request.Builder().url(url).build()).execute().close()
    }

    private fun serve(whileServing: () -> Unit = {}, setCookie: String) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                whileServing()
                return MockResponse.Builder().code(200).addHeader("Set-Cookie", setCookie).body("{}").build()
            }
        }
    }

    @Test
    fun setCookieFromARequestThatOutlivedItsSessionIsDropped() {
        val cookies = InMemoryCookieStore().apply { setCookie(site, "$sessionId=s1") }
        // The owner logs out while the request is in flight; the answer still carries a session cookie for the old login.
        serve(whileServing = { cookies.clearAll() }, setCookie = WebSessionCookies.sessionCookie("s2"))

        get(clientFor(cookies))

        assertNull(cookies.cookieValue(site, sessionId), "a response that outlived its session must not resurrect it")
    }

    @Test
    fun aSessionReplacedWhileTheRequestWasInFlightIsNotOverwrittenByTheOldAnswer() {
        val cookies = InMemoryCookieStore().apply { setCookie(site, "$sessionId=s1") }
        // A paste (or another login) replaces the session mid-request; the old session's answer must not win.
        serve(whileServing = { cookies.setCookie(site, "$sessionId=s3") }, setCookie = WebSessionCookies.sessionCookie("s2"))

        get(clientFor(cookies))

        assertEquals("s3", cookies.cookieValue(site, sessionId))
    }

    @Test
    fun everySetCookieOfAStaleResponseIsDroppedNotOnlyTheSessionOne() {
        val cookies = InMemoryCookieStore().apply { setCookie(site, "$sessionId=s1") }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                cookies.clearAll()
                return MockResponse.Builder().code(200)
                    .addHeader("Set-Cookie", WebSessionCookies.sessionCookie("s2"))
                    .addHeader("Set-Cookie", "$csrf=c2; Domain=.instagram.com; Path=/; Secure")
                    .addHeader("set-cookie", "rur=r2; Domain=.instagram.com; Path=/")
                    .body("{}").build()
            }
        }

        get(clientFor(cookies))

        assertNull(cookies.cookieValue(site, sessionId))
        assertNull(cookies.cookieValue(site, csrf), "a cookie from a stale response is stale too")
        assertNull(cookies.cookieValue(site, "rur"), "header names are case-insensitive")
    }

    /**
     * OkHttp loads the Cookie header (Bridge) BEFORE it connects (DNS, TCP, TLS), and network interceptors run after that. A
     * logout during the handshake empties the jar while the request already carries the old sessionid, so a guard that read
     * "before" from the jar at that point would see nothing and nothing, and wave the old answer through.
     */
    @Test
    fun aLogoutDuringConnectionSetupDoesNotLetTheOldAnswerRestoreTheSession() {
        val cookies = InMemoryCookieStore().apply { setCookie(site, "$sessionId=s1") }
        serve(setCookie = WebSessionCookies.sessionCookie("s2"))

        get(clientFor(cookies, dns = { cookies.clearAll() }))

        assertEquals("$sessionId=s1", server.takeRequest().headers["Cookie"], "the request really went out with the old session")
        assertNull(cookies.cookieValue(site, sessionId), "the old session's answer must not put a session back into the emptied jar")
    }

    @Test
    fun aSessionReplacedDuringConnectionSetupIsNotOverwrittenByTheOldAnswer() {
        val cookies = InMemoryCookieStore().apply { setCookie(site, "$sessionId=s1") }
        serve(setCookie = WebSessionCookies.sessionCookie("s2"))

        get(clientFor(cookies, dns = { cookies.setCookie(site, "$sessionId=s3") }))

        assertEquals("s3", cookies.cookieValue(site, sessionId))
    }

    @Test
    fun aRequestThatCarriedNoSessionAndFoundNoneAfterwardsKeepsItsCookies() {
        val cookies = InMemoryCookieStore()
        serve(setCookie = "$csrf=c2; Domain=.instagram.com; Path=/; Secure")

        get(clientFor(cookies))

        assertEquals("c2", cookies.cookieValue(site, csrf))
    }

    /** The guard is the first (outermost) network interceptor: the wire log, which sits closer to the socket, sees what really arrived. */
    @Test
    fun debugLoggingStillSeesTheSetCookieOfAStaleResponse() {
        val cookies = InMemoryCookieStore().apply { setCookie(site, "$sessionId=s1") }
        serve(whileServing = { cookies.clearAll() }, setCookie = WebSessionCookies.sessionCookie("s2"))
        val lines = mutableListOf<String>()

        get(clientFor(cookies, logger = { lines += it }))

        assertTrue("Set-Cookie: \u2588\u2588" in lines, "the log is closer to the wire than the guard, so it still shows the header (redacted): $lines")
        assertNull(cookies.cookieValue(site, sessionId))
    }

    @Test
    fun setCookieIsKeptWhenTheSessionDidNotChange() {
        val cookies = InMemoryCookieStore().apply { setCookie(site, "$sessionId=s1") }
        serve(setCookie = "$csrf=c2; Domain=.instagram.com; Path=/; Secure")

        get(clientFor(cookies))

        assertEquals("c2", cookies.cookieValue(site, csrf))
        assertEquals("s1", cookies.cookieValue(site, sessionId))
    }

    @Test
    fun aLoginThatStartedWithNoSessionKeepsTheCookiesItIsGiven() {
        val cookies = InMemoryCookieStore()
        serve(setCookie = WebSessionCookies.sessionCookie("s2"))

        get(clientFor(cookies))

        assertEquals("s2", cookies.cookieValue(site, sessionId), "no session changed during the flight: nothing to guard")
    }
}
