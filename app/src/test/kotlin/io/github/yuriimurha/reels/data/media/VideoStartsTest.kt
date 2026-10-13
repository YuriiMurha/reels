package io.github.yuriimurha.reels.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.cacheWholeFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * R26: a carousel video item's start, written into the video cache by Media3's `CacheWriter` through the player's own stack
 * ([videoStartDataSourceFactory]), over MockWebServer (plain http: a test-only convenience). What only a run can show: the
 * bounded request, the cache key, one request per write whatever the CDN answers, and a cancelled write's cache.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoStartsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val videoCache by lazy { VideoCache(File(tmp.root, "video"), databaseProvider) }
    private val server = MockWebServer()

    /** A video of three megabytes, so a start is a third of it. */
    private val video = ByteArray(3 * MB) { (it % 251).toByte() }

    private val starts by lazy {
        CacheVideoStarts({ videoCache.cache }, { videoStartDataSourceFactory(context, videoCache.cache, "TestWebView/1.0").createDataSource() })
    }

    /** The `Range` header of every request, in order. */
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())

    /** What the CDN answers: by default the asked range of [video], as a 206. */
    private var answer: (RecordedRequest) -> MockResponse = ::partial

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                ranges += request.headers["Range"]
                return answer(request)
            }
        }
        server.start()
    }

    @After
    fun stop() {
        server.close()
        videoCache.cache.release()
        databaseProvider.close()
    }

    /** A CDN that honours `Range: bytes=a-b` (or none) with the bytes of [video]; [throttled] sends 64 KB every 100 ms. */
    private fun partial(request: RecordedRequest, throttled: Boolean = false): MockResponse {
        val range = request.headers["Range"]?.removePrefix("bytes=")?.split('-')
        val from = range?.getOrNull(0)?.toInt() ?: 0
        val to = range?.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toInt()?.coerceAtMost(video.size - 1) ?: (video.size - 1)
        val builder = MockResponse.Builder()
            .code(if (range == null) 200 else 206)
            .body(Buffer().write(video, from, to - from + 1))
        if (range != null) builder.addHeader("Content-Range", "bytes $from-$to/${video.size}")
        if (throttled) builder.throttleBody(64 * 1024L, 100, TimeUnit.MILLISECONDS)
        return builder.build()
    }

    private val url get() = Uri.parse(server.url("/v.mp4?oe=1").toString())

    /** The bytes cached under [key] from 0, read from the cache alone (the server is not asked). */
    private fun cachedStart(key: String, length: Int): ByteArray {
        val before = server.requestCount
        val spec = DataSpec.Builder().setUri(url).setKey(key).setLength(length.toLong()).build()
        val bytes = DataSourceInputStream(cachedDataSourceFactory(context, videoCache.cache, null).createDataSource(), spec).use { it.readBytes() }
        assertEquals(before, server.requestCount, "read from the cache alone")
        return bytes
    }

    @Test
    fun aStartIsTheFirstMegabyteUnderTheItemsKeyAskedForInOneBoundedCookielessRequest() = runBlocking {
        assertEquals(StartWrite.WRITTEN, starts.write("42_2", url, VIDEO_START_BYTES))

        assertEquals(listOf("bytes=0-${MB - 1}"), ranges.toList(), "one request, for the first megabyte only")
        val request = server.takeRequest()
        assertNull(request.headers["Cookie"], "a CDN request must never carry the session")
        assertEquals("TestWebView/1.0", request.headers["User-Agent"], "the player's own user agent")
        assertEquals(MB.toLong(), videoCache.cache.getCachedBytes("42_2", 0, Long.MAX_VALUE), "exactly the start, under the item's key")
        assertEquals(0L, videoCache.cache.getCachedBytes("42", 0, Long.MAX_VALUE), "nothing under the post's key")
        assertContentEquals(video.copyOf(MB), cachedStart("42_2", MB))
        assertTrue(starts.isCached("42_2", VIDEO_START_BYTES))
    }

    @Test
    fun aStartThatIsCachedFetchesNothing() = runBlocking {
        assertEquals(StartWrite.WRITTEN, starts.write("42_2", url, VIDEO_START_BYTES))
        assertEquals(StartWrite.CACHED, starts.write("42_2", url, VIDEO_START_BYTES))
        assertEquals(1, server.requestCount, "the second write asked for nothing")

        // A short video watched in full (its length known): every byte there is all of its start.
        videoCache.cacheWholeFile("43_2", File(tmp.root, "short.mp4").apply { writeBytes(ByteArray(3_000) { it.toByte() }) })
        assertTrue(starts.isCached("43_2", VIDEO_START_BYTES))
        assertEquals(StartWrite.CACHED, starts.write("43_2", url, VIDEO_START_BYTES))
        assertEquals(1, server.requestCount)
    }

    /** The player watched the first part: only the rest of the megabyte is asked for. */
    @Test
    fun aPartlyCachedStartAsksOnlyForTheRest() = runBlocking {
        val watched = 300 * 1024
        DataSourceInputStream(
            cachedDataSourceFactory(context, videoCache.cache, null).createDataSource(),
            DataSpec.Builder().setUri(url).setKey("42_2").setLength(watched.toLong()).build(),
        ).use { it.readBytes() }
        ranges.clear()

        assertEquals(StartWrite.WRITTEN, starts.write("42_2", url, VIDEO_START_BYTES))

        assertEquals(listOf("bytes=$watched-${MB - 1}"), ranges.toList())
        assertContentEquals(video.copyOf(MB), cachedStart("42_2", MB))
    }

    /**
     * One request a write, whatever the CDN answers: `CacheWriter` asks again, unbounded, after a bounded request fails, and
     * [OneTryUpstream] stops that second request. A refused link reads REFUSED, a 429 LIMITED, anything else OFFLINE.
     */
    @Test
    fun aFailedStartIsOneRequestAndSaysWhy() = runBlocking {
        val expected = mapOf(403 to StartWrite.REFUSED, 404 to StartWrite.REFUSED, 410 to StartWrite.REFUSED, 429 to StartWrite.LIMITED, 500 to StartWrite.OFFLINE)
        for ((code, outcome) in expected) {
            answer = { MockResponse.Builder().code(code).build() }
            val before = server.requestCount

            assertEquals(outcome, starts.write("50_$code", url, VIDEO_START_BYTES), "HTTP $code")

            assertEquals(before + 1, server.requestCount, "HTTP $code: one request, never a second for the same write")
            assertEquals(0L, videoCache.cache.getCachedBytes("50_$code", 0, Long.MAX_VALUE), "HTTP $code: nothing cached")
        }
    }

    @Test
    fun noConnectionIsOffline() = runBlocking {
        val unreachable = url
        server.close()

        assertEquals(StartWrite.OFFLINE, starts.write("42_2", unreachable, VIDEO_START_BYTES))
    }

    /**
     * Leaving the post cancels the write mid-way. It returns only once `CacheWriter` has stopped: what came is cached under the
     * key, intact, and nothing is left locked, so the next write (or the player) goes on from there, asking only for the rest.
     */
    @Test
    fun aCancelledStartLeavesTheCacheConsistent() = runBlocking {
        answer = { partial(it, throttled = true) }
        val writing = async(Dispatchers.Default) { starts.write("42_2", url, VIDEO_START_BYTES) }
        withTimeout(10_000) { while (server.requestCount == 0) delay(10) }
        delay(500) // a few hundred kilobytes, not the megabyte

        withTimeout(10_000) { writing.cancelAndJoin() }

        val kept = videoCache.cache.getCachedBytes("42_2", 0, Long.MAX_VALUE)
        assertTrue(kept in 1 until MB, "what came is kept, not the whole start: $kept bytes")
        assertContentEquals(video.copyOf(kept.toInt()), cachedStart("42_2", kept.toInt()), "and it is the video's own start")

        answer = ::partial
        ranges.clear()
        assertEquals(StartWrite.WRITTEN, withTimeout(10_000) { starts.write("42_2", url, VIDEO_START_BYTES) }, "nothing left locked")
        assertEquals(listOf("bytes=$kept-${MB - 1}"), ranges.toList(), "only the rest is asked for")
        assertContentEquals(video.copyOf(MB), cachedStart("42_2", MB))
    }

    @Test
    fun removeDropsTheKeyAndNothingElse() = runBlocking {
        starts.write("42_2", url, VIDEO_START_BYTES)
        starts.write("42_5", url, VIDEO_START_BYTES)

        starts.remove("42_2")

        assertEquals(0L, videoCache.cache.getCachedBytes("42_2", 0, Long.MAX_VALUE))
        assertEquals(MB.toLong(), videoCache.cache.getCachedBytes("42_5", 0, Long.MAX_VALUE))
    }

    private companion object {
        const val MB = 1 shl 20
    }
}
