package io.github.yuriimurha.reels.data.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
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
     * True when every byte of the video under [pk] is on disk. The length is known only once a read of the whole video went
     * through the cache, so a partial span, however large, never counts.
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
 */
@UnstableApi
fun cachedDataSourceFactory(context: Context, cache: Cache, userAgent: String?): DataSource.Factory {
    val http = DefaultHttpDataSource.Factory().apply { userAgent?.let(::setUserAgent) }
    return CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, http))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
