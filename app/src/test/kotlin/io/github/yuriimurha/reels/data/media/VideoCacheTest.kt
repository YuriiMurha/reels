package io.github.yuriimurha.reels.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.cacheWholeFile
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@UnstableApi
@RunWith(AndroidJUnit4::class)
class VideoCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val videoCache by lazy { VideoCache(File(tmp.root, "video"), databaseProvider) }

    /** One SimpleCache per directory per process: an unreleased one would make the next test's cache refuse to open. */
    @After
    fun release() {
        videoCache.cache.release()
        databaseProvider.close()
    }

    private fun clip(name: String, size: Int = 20_000): File =
        File(tmp.root, name).apply { writeBytes(ByteArray(size) { (it % 251).toByte() }) }

    @Test
    fun aVideoWrittenInFullIsFullyCached() {
        assertFalse(videoCache.isFullyCached("42"), "nothing is cached yet")

        videoCache.cacheWholeFile("42", clip("a.mp4"))

        assertTrue(videoCache.isFullyCached("42"))
        assertFalse(videoCache.isFullyCached("43"), "the key is the media pk: another pk has nothing")
        assertEquals(20_000L, videoCache.cache.cacheSpace)
    }

    /** A bounded span: the content length is not even recorded, and the half that was written is not the video. */
    @Test
    fun aBoundedPartialSpanIsNotFullyCached() {
        val source = CacheDataSource.Factory()
            .setCache(videoCache.cache)
            .setUpstreamDataSourceFactory(FileDataSource.Factory())
            .createDataSource()
        val firstHalf = DataSpec.Builder().setUri(Uri.fromFile(clip("b.mp4"))).setKey("7").setLength(10_000).build()
        CacheWriter(source, firstHalf, ByteArray(4096), null).cache()

        assertEquals(10_000L, videoCache.cache.cacheSpace, "the half was written")
        assertFalse(videoCache.isFullyCached("7"))
    }

    /**
     * What a player does: it opens the whole video (no length given) and may stop part way, as when the owner swipes on.
     * `CacheDataSource` records the content length when the read OPENS, so the length is known although only 8 KB of the
     * 20 KB are on disk. Knowing the length is not the video being cached.
     */
    @Test
    fun aReadThatStopsHalfWayRecordsTheLengthButIsNotFullyCached() {
        val source = CacheDataSource.Factory()
            .setCache(videoCache.cache)
            .setUpstreamDataSourceFactory(FileDataSource.Factory())
            .createDataSource()
        val whole = DataSpec.Builder().setUri(Uri.fromFile(clip("c.mp4", 20_000))).setKey("9").setLength(C.LENGTH_UNSET.toLong()).build()
        source.open(whole)
        val buffer = ByteArray(2_048)
        var read = 0
        while (read < 8_192) read += source.read(buffer, 0, buffer.size)
        source.close()

        assertEquals(20_000L, ContentMetadata.getContentLength(videoCache.cache.getContentMetadata("9")), "the length was recorded at open")
        assertEquals(8_192L, videoCache.cache.cacheSpace, "only what was read is on disk")
        assertFalse(videoCache.isFullyCached("9"), "length known is not fully cached")
    }

    @Test
    fun removeDropsOneVideoAndKeepsTheOthers() {
        videoCache.cacheWholeFile("1", clip("a.mp4", 5_000))
        videoCache.cacheWholeFile("2", clip("b.mp4", 6_000))

        videoCache.remove("1")

        assertFalse(videoCache.isFullyCached("1"))
        assertTrue(videoCache.isFullyCached("2"))
        assertEquals(6_000L, videoCache.cache.cacheSpace, "the bytes of the removed video are gone from disk")
    }

    @Test
    fun removingSomethingNotCachedIsHarmless() {
        videoCache.remove("never-cached")
        assertEquals(0L, videoCache.cache.cacheSpace)
    }

    @Test
    fun clearDropsEveryVideo() {
        videoCache.cacheWholeFile("1", clip("a.mp4", 5_000))
        videoCache.cacheWholeFile("2", clip("b.mp4", 6_000))

        videoCache.clear()

        assertFalse(videoCache.isFullyCached("1"))
        assertFalse(videoCache.isFullyCached("2"))
        assertEquals(0L, videoCache.cache.cacheSpace)
        assertTrue(videoCache.cache.keys.isEmpty())
    }

    @Test
    fun theCacheHasABoundedSize() {
        assertEquals(512L * 1024 * 1024, VideoCache.MAX_BYTES)
    }
}
