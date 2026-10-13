package io.github.yuriimurha.reels.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.cacheWholeFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
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

    private val starts by lazy { startsOver(videoCache.cache) }

    /** The writer as the container builds it, over [cache]: the start stack with the write's own abort. */
    private fun startsOver(cache: Cache) =
        CacheVideoStarts({ cache }, { abort -> videoStartDataSourceFactory(context, cache, "TestWebView/1.0", abort).createDataSource() })

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
     * [OneTryUpstream] stops that second request. A link running out (403, 410) reads EXPIRED, any other 4xx REFUSED (R27),
     * a 429 LIMITED, anything else OFFLINE.
     */
    @Test
    fun aFailedStartIsOneRequestAndSaysWhy() = runBlocking {
        val expected = mapOf(
            403 to StartWrite.EXPIRED, 410 to StartWrite.EXPIRED, 404 to StartWrite.REFUSED, 408 to StartWrite.REFUSED,
            400 to StartWrite.REFUSED, 429 to StartWrite.LIMITED, 500 to StartWrite.OFFLINE,
        )
        for ((code, outcome) in expected) {
            answer = { MockResponse.Builder().code(code).build() }
            val before = server.requestCount

            assertEquals(outcome, starts.write("50_$code", url, VIDEO_START_BYTES), "HTTP $code")

            assertEquals(before + 1, server.requestCount, "HTTP $code: one request, never a second for the same write")
            assertEquals(0L, videoCache.cache.getCachedBytes("50_$code", 0, Long.MAX_VALUE), "HTTP $code: nothing cached")
        }
    }

    /**
     * T-I3: a response that never comes (the read times out after `DefaultHttpDataSource`'s 8 s) is one request too: the
     * writer's second, unbounded try never goes out.
     */
    @Test
    fun aTimedOutStartIsOneRequest() = runBlocking {
        answer = { MockResponse.Builder().onResponseStart(SocketEffect.Stall).build() }

        assertEquals(StartWrite.OFFLINE, starts.write("42_2", url, VIDEO_START_BYTES))

        assertEquals(listOf("bytes=0-${MB - 1}"), ranges.toList(), "one bounded request, never a second for the same write")
    }

    /**
     * T-I3: a connection the CDN drops is never followed by the writer's unbounded try. Dropped before it answers, the
     * platform's HTTP stack may send the same bounded request once more on its own (the JVM's does, as Android's may, for the
     * player too: below the data source, nothing a write can switch off); dropped part way through the body, it is one request.
     */
    @Test
    fun aDroppedConnectionIsNeverFollowedByAnUnboundedRequest() = runBlocking {
        answer = { MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build() }
        assertEquals(StartWrite.OFFLINE, starts.write("42_2", url, VIDEO_START_BYTES))
        assertTrue(ranges.size in 1..2 && ranges.all { it == "bytes=0-${MB - 1}" }, "dropped before the answer: bounded, the platform's one resend at most: $ranges")

        ranges.clear()
        answer = { partial(it).newBuilder().onResponseBody(SocketEffect.ShutdownConnection).build() }
        starts.write("42_3", url, VIDEO_START_BYTES)
        assertEquals(listOf("bytes=0-${MB - 1}"), ranges.toList(), "dropped part way: one request")
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

    /**
     * I1: a cache that fails (a full disk: its metadata store throws, as SQLite's full-disk error does through Media3) makes a
     * start FAILED, which stops the queue. The one bounded request may already be out; the second, unbounded one for the whole
     * video never is, and nothing is read past the failure.
     */
    @Test
    fun aFailingCacheStopsTheStartWithNoRequestForTheWholeVideo() = runBlocking {
        val read = AtomicLong()

        val outcome = CacheVideoStarts({ videoCache.cache }, { abort ->
            videoStartDataSourceFactory(context, MetadataStoreFails(videoCache.cache), "TestWebView/1.0", abort).createDataSource()
                .also { it.addTransferListener(counting(read)) }
        }).write("42_2", url, VIDEO_START_BYTES)

        assertEquals(StartWrite.FAILED, outcome)
        assertEquals(listOf("bytes=0-${MB - 1}"), ranges.toList(), "the bounded request at most; never one without a range")
        assertEquals(0L, read.get(), "no video bytes read")
        assertEquals(0L, videoCache.cache.getCachedBytes("42_2", 0, Long.MAX_VALUE))
    }

    /** I1: a cache that cannot be used at all (it failed to start) fails the start before any request. */
    @Test
    fun anUnusableCacheStopsTheStartBeforeAnyRequest() = runBlocking {
        assertEquals(StartWrite.FAILED, startsOver(CacheUnusable(videoCache.cache)).write("42_2", url, VIDEO_START_BYTES))
        assertEquals(0, server.requestCount)
    }

    /**
     * M5: a write cancelled while its request waits for the CDN's answer (here 3 s) returns at once: the abort closes the
     * request, instead of waiting for the answer or the 8 s timeout.
     */
    @Test
    fun aCancelledStartWaitingForItsAnswerStopsAtOnce() = runBlocking {
        answer = { partial(it).newBuilder().headersDelay(3, TimeUnit.SECONDS).build() }
        val writing = async(Dispatchers.Default) { starts.write("42_2", url, VIDEO_START_BYTES) }
        withTimeout(10_000) { while (server.requestCount == 0) delay(10) }
        delay(100)

        val waited = measureTimeMillis { withTimeout(10_000) { writing.cancelAndJoin() } }

        assertTrue(waited < 1_000, "the cancel waited $waited ms for a request it should have closed")
        assertEquals(0L, videoCache.cache.getCachedBytes("42_2", 0, Long.MAX_VALUE))
        answer = ::partial
        assertEquals(StartWrite.WRITTEN, withTimeout(10_000) { starts.write("42_2", url, VIDEO_START_BYTES) }, "nothing left locked")
    }

    /** T-I1: the write's blocking `CacheWriter` runs on its io dispatcher, never on the caller's thread (the viewer's main). */
    @Test
    fun theWriteRunsOffTheCallersThread() = runBlocking {
        val opened = Collections.synchronizedList(mutableListOf<String>())
        val io = Executors.newSingleThreadExecutor { Thread(it, "start-io") }.asCoroutineDispatcher()
        val caller = Executors.newSingleThreadExecutor { Thread(it, "start-caller") }.asCoroutineDispatcher()
        val source = File(tmp.root, "v.mp4").apply { writeBytes(video) }
        val writer = CacheVideoStarts({ videoCache.cache }, { _ ->
            CacheDataSource.Factory().setCache(videoCache.cache).setUpstreamDataSourceFactory {
                val file = FileDataSource()
                object : DataSource by file {
                    override fun open(dataSpec: DataSpec): Long {
                        opened += Thread.currentThread().name
                        return file.open(dataSpec)
                    }
                }
            }.createDataSource()
        }, io)
        try {
            assertEquals(StartWrite.WRITTEN, withContext(caller) { writer.write("42_2", Uri.fromFile(source), VIDEO_START_BYTES) })
            assertTrue(opened.isNotEmpty() && opened.all { it.startsWith("start-io") }, "opened on: $opened")
        } finally {
            io.close()
            caller.close()
        }
    }

    /**
     * T-I2: the one-try rule is the start's only. The PLAYER's stack opens again after a failure (Media3's own retry, R85), and
     * the player's factory adds nothing to it.
     */
    @Test
    fun thePlayersStackOpensAgainAfterAFailure() {
        var first = true
        answer = {
            if (first) {
                first = false
                MockResponse.Builder().code(500).build()
            } else {
                MockResponse.Builder().code(200).body(Buffer().write(video, 0, 1000)).build()
            }
        }
        val source = cachedDataSourceFactory(context, videoCache.cache, null).createDataSource()
        val spec = DataSpec.Builder().setUri(url).setKey("42_9").build()
        assertTrue(runCatching { source.open(spec) }.isFailure, "precondition: the first open fails")
        runCatching { source.close() }

        source.open(spec)
        source.close()

        assertEquals(2, server.requestCount, "the player's second try reached the CDN")
        val code = File("src/main/kotlin/io/github/yuriimurha/reels/data/media/VideoCache.kt").readText()
        val factory = Regex("""fun videoMediaSourceFactory\([^)]*\)[^=]*=([^\n]*\n){1,3}""").find(code)?.value.orEmpty()
        assertTrue(
            Regex("""DefaultMediaSourceFactory\(\s*cachedDataSourceFactory\(\s*context\s*,\s*cache\s*,\s*userAgent\s*\)\s*\)""").containsMatchIn(factory),
            "the player's factory: $factory",
        )
    }

    @Test
    fun removeDropsTheKeyAndNothingElse() = runBlocking {
        starts.write("42_2", url, VIDEO_START_BYTES)
        starts.write("42_5", url, VIDEO_START_BYTES)

        starts.remove("42_2")

        assertEquals(0L, videoCache.cache.getCachedBytes("42_2", 0, Long.MAX_VALUE))
        assertEquals(MB.toLong(), videoCache.cache.getCachedBytes("42_5", 0, Long.MAX_VALUE))
    }

    /** A TransferListener that adds up the bytes read from the network into [read]. */
    private fun counting(read: AtomicLong) = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (isNetwork) read.addAndGet(bytesTransferred.toLong())
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
    }

    /** The cache's metadata store fails, as on a full disk (SQLite's full-disk error, wrapped by Media3 as a CacheException). */
    private class MetadataStoreFails(private val cache: Cache) : Cache by cache {
        override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) {
            throw Cache.CacheException("metadata store failed (disk full)")
        }
    }

    /** A cache that could not start: every span it is asked for throws. */
    private class CacheUnusable(private val cache: Cache) : Cache by cache {
        override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? =
            throw Cache.CacheException("cache not initialized")
    }

    private companion object {
        const val MB = 1 shl 20
    }
}
