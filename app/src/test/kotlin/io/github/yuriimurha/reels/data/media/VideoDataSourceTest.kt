package io.github.yuriimurha.reels.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.R
import io.github.yuriimurha.reels.ui.viewer.isExpiredLinkError
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The data source stack the viewer's player is built with, over MockWebServer (plain http: a test-only convenience, the
 * stack itself is the player's). Pins what only a run can show: the cache key, the missing cookies, the user agent.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoDataSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val videoCache by lazy { VideoCache(File(tmp.root, "video"), databaseProvider) }
    private val server = MockWebServer()
    private val body = ByteArray(30_000) { (it % 241).toByte() }

    private val factory by lazy { cachedDataSourceFactory(context, videoCache.cache, "TestWebView/1.0") }

    @Before
    fun start() = server.start()

    @After
    fun stop() {
        server.close()
        videoCache.cache.release()
        databaseProvider.close()
    }

    private fun respondWith(bytes: ByteArray = body) =
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(bytes)).build())

    private fun read(url: String, key: String): ByteArray {
        val spec = DataSpec.Builder().setUri(url).setKey(key).build()
        return DataSourceInputStream(factory.createDataSource(), spec).use { it.readBytes() }
    }

    @Test
    fun aRefreshedLinkStillHitsTheCachedBytes() {
        respondWith()
        assertContentEquals(body, read(server.url("/v.mp4?token=old").toString(), key = "42"))
        assertEquals(1, server.requestCount)
        assertTrue(videoCache.isFullyCached("42"))

        // The same video under a new link (the refresh changed the query): same pk, so the cache answers and the CDN is not asked.
        assertContentEquals(body, read(server.url("/v.mp4?token=new").toString(), key = "42"))
        assertEquals(1, server.requestCount, "the second read came from the cache")
    }

    @Test
    fun anotherPkIsAnotherVideo() {
        respondWith()
        read(server.url("/v.mp4").toString(), key = "42")
        respondWith(byteArrayOf(9, 9, 9))

        assertContentEquals(byteArrayOf(9, 9, 9), read(server.url("/v.mp4").toString(), key = "43"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun videoRequestsCarryNoCookiesAndTheWebViewUserAgent() {
        respondWith()
        read(server.url("/v.mp4").toString(), key = "42")

        val request = server.takeRequest()
        assertNull(request.headers["Cookie"], "a CDN request must never carry the session")
        assertEquals("TestWebView/1.0", request.headers["User-Agent"])
        assertNull(java.net.CookieHandler.getDefault(), "nothing in the process installed a cookie handler the video requests would use")
    }

    /** What the player sees when a stale link is refused, and what the viewer then turns into a refresh. */
    @Test
    fun aRefusedLinkIsTheErrorTheViewerRefreshesOn() {
        for (code in listOf(403, 410)) {
            server.enqueue(MockResponse.Builder().code(code).build())
            val failure = assertFailsWith<HttpDataSource.InvalidResponseCodeException>("HTTP $code") {
                read(server.url("/v.mp4?token=stale$code").toString(), key = "50$code")
            }
            assertEquals(code, failure.responseCode)
            assertTrue(isExpiredLinkError(failure))
        }
        server.enqueue(MockResponse.Builder().code(404).build())
        val notFound = assertFailsWith<HttpDataSource.InvalidResponseCodeException> { read(server.url("/gone.mp4").toString(), key = "51") }
        assertTrue(!isExpiredLinkError(notFound), "a 404 is not a stale link")
    }

    private fun loadError(exception: IOException, errorCount: Int) = LoadErrorHandlingPolicy.LoadErrorInfo(
        LoadEventInfo(LoadEventInfo.getNewId(), DataSpec(Uri.parse(server.url("/v.mp4").toString())), 0L),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        exception,
        errorCount,
    )

    /**
     * R85: Media3's default policy retries an HTTP error up to 3 times before the player reports it, and video isn't on the
     * Pacer's CDN lane. A refused link (403, 410, a 429) goes straight to `onPlayerError`, where the viewer renews it once.
     */
    @Test
    fun anHttpErrorStatusIsNeverRetried() {
        for (code in listOf(403, 410, 429, 404)) {
            server.enqueue(MockResponse.Builder().code(code).build())
            val refused = assertFailsWith<HttpDataSource.InvalidResponseCodeException> { read(server.url("/v.mp4?c=$code").toString(), key = "6$code") }
            for (errorCount in 1..3) {
                assertEquals(C.TIME_UNSET, VideoLoadErrorPolicy().getRetryDelayMsFor(loadError(refused, errorCount)), "HTTP $code, error $errorCount")
            }
            val wrapped = IOException("read failed", refused)
            assertEquals(C.TIME_UNSET, VideoLoadErrorPolicy().getRetryDelayMsFor(loadError(wrapped, 1)), "HTTP $code as a cause")
        }
        assertEquals(4, server.requestCount, "one request per refused link")
    }

    /** Anything else (a dropped connection, a timeout) keeps Media3's default handling. */
    @Test
    fun otherLoadErrorsKeepTheDefaultRetries() {
        for (errorCount in 1..4) {
            val dropped = loadError(IOException("connection reset"), errorCount)
            assertEquals(DefaultLoadErrorHandlingPolicy().getRetryDelayMsFor(dropped), VideoLoadErrorPolicy().getRetryDelayMsFor(dropped))
        }
        assertTrue(VideoLoadErrorPolicy().getRetryDelayMsFor(loadError(IOException("connection reset"), 2)) != C.TIME_UNSET, "still retried")
        assertEquals(DefaultLoadErrorHandlingPolicy().getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA), VideoLoadErrorPolicy().getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    /** Mock mode: the bundled clip is an android.resource URI, which the cache's upstream must open too. */
    @Test
    fun theBundledClipStillPlaysThroughTheCache() {
        val uri = Uri.parse("android.resource://${context.packageName}/${R.raw.sample_clip}")
        val expected = File("src/main/res/raw/sample_clip.mp4").readBytes()

        val first = DataSourceInputStream(factory.createDataSource(), DataSpec.Builder().setUri(uri).setKey("clip").build()).use { it.readBytes() }

        assertContentEquals(expected, first)
        assertEquals(0, server.requestCount)
    }
}
