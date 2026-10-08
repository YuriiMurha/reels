package io.github.yuriimurha.reels.instagram.web

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WebHeadersTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    @Test
    fun theAppIdAndTheAsbdIdArePinned() {
        assertEquals("1217981644879628", WebHeaders.APP_ID)
        assertEquals("359341", WebHeaders.ASBD_ID)
    }

    @Test
    fun theInterceptorSendsBothIds() {
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        OkHttpClient.Builder().addInterceptor(WebHeaders.interceptor("UA", InMemoryCookieStore())).build()
            .newCall(Request.Builder().url(server.url("/api/v1/x/")).build()).execute().close()

        val recorded = server.takeRequest()
        assertEquals("1217981644879628", recorded.headers["X-IG-App-ID"])
        assertEquals("359341", recorded.headers["X-ASBD-ID"])
        assertEquals("XMLHttpRequest", recorded.headers["X-Requested-With"])
    }
}
