package io.github.yuriimurha.reels.data.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** What writing a video item's start (R26) came to. */
enum class StartWrite {
    /** The bytes asked for are in the cache now. */
    WRITTEN,

    /** They were there already: nothing was fetched. */
    CACHED,

    /**
     * The CDN refused the link as one that ran out or was revoked (403, 410): the same answer the player renews a link on
     * (R19, R27), so the item's own play goes to the visit's renewal instead of asking it again.
     */
    EXPIRED,

    /** Another 4xx but 429 (a 404, a 408, ...): passed quietly; the item's own play is the player's, as before R26 (R27). */
    REFUSED,

    /** The CDN said 429. */
    LIMITED,

    /** No connection, a timeout, a 5xx or any other failure to fetch. */
    OFFLINE,

    /** The video cache failed (a full disk, a cache that could not start), or the write would have asked for the whole video: stop (I1). */
    FAILED,
}

/**
 * R26: the first bytes of a carousel's video items, written into the video cache under the item's own key
 * (`{pk}_{position}`) before the owner swipes to them, so the player starts from disk. [CarouselRepository] decides which
 * items and when, and runs each write on the Pacer's CDN lane.
 */
interface VideoStarts {
    /** True when the first [bytes] under [key] (all of it, for a video known to be shorter) are on disk. Disk only: off the main thread. */
    fun isCached(key: String, bytes: Long): Boolean

    /**
     * Writes the first [bytes] of [uri] under [key]: one bounded request, never a second for the same write, and never one
     * for the whole video. Cancellable: the request that is out is closed at once (M5), and it returns (or throws) only once
     * the write has stopped, leaving what it wrote cached and nothing locked.
     */
    suspend fun write(key: String, uri: Uri, bytes: Long): StartWrite

    /** Drops what is cached under [key]. Disk only: off the main thread. */
    fun remove(key: String)

    companion object {
        /** Writes nothing: every start reads as there already. For a repository that never writes ahead (tests that do not look). */
        val NONE: VideoStarts = object : VideoStarts {
            override fun isCached(key: String, bytes: Long) = true

            override suspend fun write(key: String, uri: Uri, bytes: Long) = StartWrite.CACHED

            override fun remove(key: String) = Unit
        }
    }
}

/**
 * [VideoStarts] over the viewer's video cache with Media3's `CacheWriter`, through [dataSource]: the player's own cookieless
 * stack ([videoStartDataSourceFactory]), whose upstream tries each write once and never unbounded ([OneTryUpstream]), given
 * the write's [StartWriteAbort]. The request is bounded: it asks for the first [bytes] only (fewer when the video is known
 * to be shorter), and what is cached already is not asked for. `CacheWriter` blocks: it runs on [io], never the caller's
 * thread (the viewer's queue calls from the main thread).
 */
@UnstableApi
class CacheVideoStarts(
    private val cache: () -> Cache,
    private val dataSource: (abort: StartWriteAbort) -> CacheDataSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : VideoStarts {
    override fun isCached(key: String, bytes: Long): Boolean = lengthToWrite(key, bytes) == null

    override suspend fun write(key: String, uri: Uri, bytes: Long): StartWrite {
        val length = withContext(io) { lengthToWrite(key, bytes) } ?: return StartWrite.CACHED
        val spec = DataSpec.Builder().setUri(uri).setKey(key).setPosition(0).setLength(length).build()
        val abort = StartWriteAbort()
        val writer = CacheWriter(withContext(io) { dataSource(abort) }, spec, null, null)
        return try {
            coroutineScope {
                val writing = async(io) { writer.cache() }
                try {
                    writing.await()
                } catch (e: CancellationException) {
                    // CacheWriter stops only when told, and looks only between reads; the abort closes the request that is
                    // out, so a read or a response that stalls returns now (M5). Its source then closes, what it wrote stays
                    // cached, and nothing stays locked. This scope returns once it has stopped. The abort runs on its own
                    // thread: closing a connection can wait on the platform, and the caller may be the main thread.
                    writer.cancel()
                    Thread({ abort.abort() }, "video-start-abort").apply { isDaemon = true }.start()
                    throw e
                }
            }
            StartWrite.WRITTEN
        } catch (e: IOException) {
            // A cancelled write may end in CacheWriter's own InterruptedIOException: that is the cancellation, not a failure.
            currentCoroutineContext().ensureActive()
            e.asStartWrite()
        }
    }

    override fun remove(key: String) {
        cache().removeResource(key)
    }

    /** How many bytes from 0 to write under [key]: [bytes], or the video's length when it is known and shorter; null when they are all cached. */
    private fun lengthToWrite(key: String, bytes: Long): Long? {
        val cache = cache()
        val known = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        val wanted = if (known == C.LENGTH_UNSET.toLong()) bytes else minOf(bytes, known)
        return wanted.takeIf { it > 0 && !cache.isCached(key, 0, it) }
    }

    private fun IOException.asStartWrite(): StartWrite {
        val causes = generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        val status = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        return when {
            status == 403 || status == 410 -> StartWrite.EXPIRED
            status == 429 -> StartWrite.LIMITED
            status != null && status in 400..499 -> StartWrite.REFUSED
            causes.any { it is Cache.CacheException || it is UnboundedStartRefused } -> StartWrite.FAILED
            else -> StartWrite.OFFLINE
        }
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 12
    }
}
