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

    /** The CDN refused the link (a 4xx other than 429): it will not play as it is. */
    REFUSED,

    /** The CDN said 429. */
    LIMITED,

    /** No connection, a timeout, a 5xx or any other failure to fetch. */
    OFFLINE,
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
     * Writes the first [bytes] of [uri] under [key]: one request, never a second for the same write. Cancellable: it returns
     * (or throws) only once the write has stopped, leaving what it wrote cached and nothing locked.
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
 * stack ([videoStartDataSourceFactory]), whose upstream tries each write once ([OneTryUpstream]). The request is bounded: it
 * asks for the first [bytes] only (fewer when the video is known to be shorter), and what is cached already is not asked for.
 */
@UnstableApi
class CacheVideoStarts(
    private val cache: () -> Cache,
    private val dataSource: () -> CacheDataSource,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : VideoStarts {
    override fun isCached(key: String, bytes: Long): Boolean = lengthToWrite(key, bytes) == null

    override suspend fun write(key: String, uri: Uri, bytes: Long): StartWrite {
        val length = withContext(io) { lengthToWrite(key, bytes) } ?: return StartWrite.CACHED
        val spec = DataSpec.Builder().setUri(uri).setKey(key).setPosition(0).setLength(length).build()
        val writer = CacheWriter(withContext(io) { dataSource() }, spec, null, null)
        return try {
            coroutineScope {
                val writing = async(io) { writer.cache() }
                try {
                    writing.await()
                } catch (e: CancellationException) {
                    // CacheWriter stops only when told, at its next read: its source then closes, what it wrote stays cached,
                    // and nothing stays locked. This scope returns once it has stopped, so a caller that waits for the
                    // cancelled write finds the cache free for the player.
                    writer.cancel()
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
        val status = generateSequence<Throwable>(this) { it.cause }.take(MAX_CAUSE_DEPTH)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
        return when {
            status == 429 -> StartWrite.LIMITED
            status != null && status in 400..499 -> StartWrite.REFUSED
            else -> StartWrite.OFFLINE
        }
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 12
    }
}
