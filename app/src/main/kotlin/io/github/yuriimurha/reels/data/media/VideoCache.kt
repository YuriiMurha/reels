package io.github.yuriimurha.reels.data.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.io.File

/** Removed items' cached videos go too (engine reconcile, Delete library). */
fun interface MediaEviction {
    fun evict(pks: List<String>)
}

/**
 * Watched videos, kept on disk so a second watch needs no network (spec 8.3). Every entry is keyed by the media pk, never
 * by the link: a link changes each time it is refreshed, the video does not. Bounded to [maxBytes], least recently used first.
 *
 * One instance per directory per process (`SimpleCache` refuses a second): the app holds exactly one, in `AppContainer`.
 * Disk work: call [remove] and [clear] off the main thread.
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
 * the host it names, still without cookies. Unlike thumbnails, video is not on the Pacer's CDN lane.
 */
@UnstableApi
fun cachedDataSourceFactory(context: Context, cache: Cache, userAgent: String?): DataSource.Factory {
    val http = DefaultHttpDataSource.Factory().apply { userAgent?.let(::setUserAgent) }
    return CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, http))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

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
