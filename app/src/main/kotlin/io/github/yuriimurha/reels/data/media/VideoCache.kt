package io.github.yuriimurha.reels.data.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.File
import java.io.IOException

/** Removed items' cached videos go too (engine reconcile, Delete library). */
fun interface MediaEviction {
    fun evict(pks: List<String>)

    companion object {
        /**
         * Runs every one of [steps] for every pk, each on its own: a step that fails (a disk error, a key it refuses) skips no
         * other step of that pk and nothing of a later pk. What a failed step leaves only takes space, so it is not reported.
         */
        fun eachStep(vararg steps: (pk: String) -> Unit) = MediaEviction { pks ->
            for (pk in pks) {
                for (step in steps) {
                    try {
                        step(pk)
                    } catch (e: Exception) {
                        // Intentionally ignored: housekeeping after the reconcile; a stale cached file only takes space.
                    }
                }
            }
        }
    }
}

/**
 * Watched videos, kept on disk so a second watch needs no network (spec 8.3). Every entry is keyed by the media pk, never
 * by the link: a link changes each time it is refreshed, the video does not. Bounded to [maxBytes], least recently used first.
 *
 * One instance per directory per process (`SimpleCache` refuses a second): the app holds exactly one, in `AppContainer`.
 * Disk work: call [remove], [removeCarouselItems] and [clear] off the main thread.
 */
@UnstableApi
class VideoCache(dir: File, databaseProvider: DatabaseProvider, maxBytes: Long = MAX_BYTES) {
    val cache: Cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes), databaseProvider)

    /**
     * True when every byte of the video under [pk] is on disk: the content length is known AND the whole range from 0 to
     * that length is cached. Knowing the length is not enough: `CacheDataSource` records it when a read OPENS, so a video
     * the player only started (and stopped after a few KB) has a length too.
     */
    fun isFullyCached(pk: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(pk))
        return length != C.LENGTH_UNSET.toLong() && cache.isCached(pk, 0, length)
    }

    fun remove(pk: String) {
        cache.removeResource(pk)
    }

    /**
     * R6: removes the videos of [pk]'s carousel items, cached under `{pk}_{position}` (`CarouselRepository`), and nothing else:
     * not [pk]'s own video ([remove] does that). The prefix `{pk}_` names this post's items only because a pk is all digits
     * (`12_` cannot start post 122's keys), so a pk that is not all digits removes nothing.
     */
    fun removeCarouselItems(pk: String) {
        if (pk.isEmpty() || !pk.all { it in '0'..'9' }) return
        val prefix = "${pk}_"
        // A copy: removing a resource changes the key set while it is walked.
        for (key in cache.keys.toList()) if (key.startsWith(prefix)) cache.removeResource(key)
    }

    fun clear() {
        // A copy: removing a resource changes the key set while it is walked.
        for (key in cache.keys.toList()) cache.removeResource(key)
    }

    companion object {
        const val MAX_BYTES = 512L * 1024 * 1024
    }
}

/**
 * What the viewer's player reads through: the cache first, then the network. The upstream is [DefaultDataSource] so the
 * bundled clip (an `android.resource` URI, Mock mode) opens too; for http(s) it uses [DefaultHttpDataSource], which sends
 * no cookies (nothing installs a `CookieHandler`), with [userAgent], the WebView's own (null keeps the library default,
 * for Mock mode, which never reaches the network and so never loads the WebView).
 *
 * Redirects ARE followed. [DefaultHttpDataSource] has no switch to refuse them: `setAllowCrossProtocolRedirects(false)`,
 * already its default, only refuses a change between http and https. A CDN redirect therefore costs one more request, to
 * the host it names, still without cookies. Unlike thumbnails, the player's video is not on the Pacer's CDN lane.
 *
 * [onUpstream] wraps the network side and nothing else: the player's is the identity; a video start written ahead (R26,
 * [videoStartDataSourceFactory]) adds [OneTryUpstream].
 */
@UnstableApi
fun cachedDataSourceFactory(
    context: Context,
    cache: Cache,
    userAgent: String?,
    onUpstream: (DataSource.Factory) -> DataSource.Factory = { it },
): CacheDataSource.Factory {
    val http = DefaultHttpDataSource.Factory().apply { userAgent?.let(::setUserAgent) }
    return CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(onUpstream(DefaultDataSource.Factory(context, http)))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/**
 * R26: what a carousel's video start is written through ([CacheVideoStarts]): the player's own stack, built by
 * [cachedDataSourceFactory] over the same cache with the same user agent, cookieless, never the session. Its one addition
 * is [OneTryUpstream], because `CacheWriter` asks again, unbounded, when its bounded request fails.
 */
@UnstableApi
fun videoStartDataSourceFactory(context: Context, cache: Cache, userAgent: String?): CacheDataSource.Factory =
    cachedDataSourceFactory(context, cache, userAgent, onUpstream = OneTryUpstream::Factory)

/**
 * R26: one try per write of a video start. Media3's `CacheWriter` opens a bounded request first and, if that fails for any
 * reason, opens an unbounded one: for a refused link (403, 404, 410), the CDN's 429 or no connection, a second request to
 * the CDN that the write must not send. After the first [open] that fails, every later one fails the same way at once,
 * without reaching the network. Each data source the factory makes is one write's.
 */
@UnstableApi
class OneTryUpstream(private val upstream: DataSource) : DataSource by upstream {
    private var failure: IOException? = null

    override fun open(dataSpec: DataSpec): Long {
        failure?.let { throw it }
        return try {
            upstream.open(dataSpec)
        } catch (e: IOException) {
            failure = e
            throw e
        }
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = OneTryUpstream(upstream.createDataSource())
    }
}

/**
 * R26: the viewer's player starts a video once 1 s of it is buffered ([START_BUFFER_MS]) and, after a stall, 2 s
 * ([RESTART_BUFFER_MS]), for every streamed video, reels and carousel items alike; every other value is Media3's default
 * (local playback, Mock mode's bundled clip, keeps its own). Media3 1.11.1's streaming defaults are these same two values,
 * so this pins them against a library update rather than changing today's behaviour.
 */
@UnstableApi
fun viewerLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMsForStreaming(
        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
        START_BUFFER_MS,
        RESTART_BUFFER_MS,
    )
    .build()

/** R26: how much of a video the viewer's player buffers before it starts. */
const val START_BUFFER_MS = 1_000

/** R26: how much it buffers before it goes on after a stall. */
const val RESTART_BUFFER_MS = 2_000

/** What the viewer's player builds its media from: reads through [cachedDataSourceFactory], load errors per [VideoLoadErrorPolicy]. */
@UnstableApi
fun videoMediaSourceFactory(context: Context, cache: Cache, userAgent: String?): DefaultMediaSourceFactory =
    DefaultMediaSourceFactory(cachedDataSourceFactory(context, cache, userAgent))
        .setLoadErrorHandlingPolicy(VideoLoadErrorPolicy())

/**
 * R85: an HTTP error status (a 403 or 410 for a link that ran out, a 429, a 404) is never retried. Media3's default retries
 * every load error up to 3 times before the player reports it; for a refused link that is 3 more requests to the CDN, and
 * video is not on the Pacer's CDN lane. So the error reaches `onPlayerError` at once, where the viewer renews a 403/410 link
 * once. Anything else (a dropped connection, a timeout) keeps the default handling. It can only remove requests.
 */
@UnstableApi
class VideoLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        if (loadErrorInfo.exception.isHttpErrorStatus()) C.TIME_UNSET else super.getRetryDelayMsFor(loadErrorInfo)

    /** The error, or anything in its cause chain, is a response with an error status. */
    private fun Throwable.isHttpErrorStatus(): Boolean =
        generateSequence(this) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is HttpDataSource.InvalidResponseCodeException }

    private companion object {
        const val MAX_CAUSE_DEPTH = 12
    }
}
