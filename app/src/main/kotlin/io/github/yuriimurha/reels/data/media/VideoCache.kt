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
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList

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
 * [onUpstream] wraps the network side and nothing else: the [DefaultHttpDataSource] itself, under [DefaultDataSource] (whose
 * close forgets the source it opened, so a close from outside that came before the connection would never reach it: N-M2).
 * The player's is the identity, so its retries (R85: a dropped connection, a timeout) open the network again; a video start
 * written ahead (R26, [videoStartDataSourceFactory]) adds [OneTryUpstream].
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
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, onUpstream(http)))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}

/**
 * R26: what a carousel's video start is written through ([CacheVideoStarts]): the player's own stack, built by
 * [cachedDataSourceFactory] over the same cache with the same user agent, cookieless, never the session, with two
 * differences, both because Media3's `CacheWriter` opens an UNBOUNDED request (the whole video) whenever its bounded one
 * fails:
 * - its upstream is [OneTryUpstream]: one try, never an unbounded request, and an abort for a cancelled write ([abort]);
 * - a cache error is not ignored (no `FLAG_IGNORE_CACHE_ON_ERROR`): the player streams past a failing cache, a start must
 *   fail instead (I1), or a full disk would turn every start into a whole-video download that caches nothing.
 */
@UnstableApi
fun videoStartDataSourceFactory(
    context: Context,
    cache: Cache,
    userAgent: String?,
    abort: StartWriteAbort,
): CacheDataSource.Factory =
    cachedDataSourceFactory(context, cache, userAgent, onUpstream = { OneTryUpstream.Factory(it, abort) }).setFlags(0)

/** R26: a video start that would have asked for the whole video (`CacheWriter`'s unbounded fallback): refused, never sent (I1). */
class UnboundedStartRefused : IOException("a video start asks for its first bytes only")

/**
 * R26, M5: the requests of one start write, so that cancelling the write closes the one that is out at once instead of
 * when `CacheWriter` next looks (its only check is between reads, and a read or a response can stall for the connection's
 * 8 s timeouts). A request still in its TCP or TLS handshake cannot be cut: it is closed once the handshake is done (N-M2).
 * [abort] may wait while the platform closes the connection: call it off the main thread.
 */
@UnstableApi
class StartWriteAbort {
    private val upstreams = CopyOnWriteArrayList<OneTryUpstream>()

    @Volatile
    var aborted = false
        private set

    internal fun register(upstream: OneTryUpstream) {
        upstreams += upstream
        if (aborted) upstream.abort()
    }

    /** Closes every request of the write now, and refuses any later one. */
    fun abort() {
        aborted = true
        for (upstream in upstreams) upstream.abort()
    }
}

/**
 * R26: one write's network side. Media3's `CacheWriter` opens a bounded request first and, if that fails for any reason (a
 * refused link, the CDN's 429, no connection, a timeout, a failing cache), opens an unbounded one: a second request to the
 * CDN, and for the whole video. So:
 * - an unbounded request is refused outright ([UnboundedStartRefused]), never sent: a start only ever asks for a known length;
 * - after the first [open] that fails, every later one fails the same way at once, without reaching the network;
 * - [abort] (a cancelled write) closes the request that is out from the cancelling side, so a call blocked on the response
 *   returns at once, and refuses any later one. A failure after an abort reads as an interruption, never as the CDN's answer.
 * - An open the abort came during always closes its connection itself once it returns (N-M2): during the TCP or TLS
 *   handshake the data source has no connection yet for the abort's close to reach, and the platform's blocking connect
 *   cannot be cut from another thread, so the request still goes out once the handshake is done; it is then closed at once.
 *
 * It wraps the HTTP data source itself ([cachedDataSourceFactory]), so its close is the connection's.
 */
@UnstableApi
class OneTryUpstream(private val upstream: DataSource) : DataSource by upstream {
    private var failure: IOException? = null

    @Volatile
    private var aborted = false
    private val lock = Any()

    /**
     * True from [open] until the upstream is closed, by [close] or by [abort]: whichever comes first closes it, once (an
     * aborted open closes it once more, [closeAfterAbort]). Guarded by [lock].
     */
    private var openNow = false

    override fun open(dataSpec: DataSpec): Long {
        failure?.let { throw it }
        if (aborted) throw aborted()
        if (dataSpec.length == C.LENGTH_UNSET.toLong()) throw UnboundedStartRefused().also { failure = it }
        synchronized(lock) { openNow = true }
        val length = try {
            upstream.open(dataSpec)
        } catch (e: Exception) {
            val failed = if (aborted) aborted() else e as? IOException ?: throw e
            failure = failed
            throw failed
        }
        if (aborted) {
            // The abort came while the request was being made. Its own close may have come before there was a connection to
            // close (a handshake), so the connection the open made is closed now, whether or not the abort closed anything.
            closeAfterAbort()
            throw aborted().also { failure = it }
        }
        return length
    }

    /**
     * Closes the upstream after an aborted open that returned, always: closing it again is harmless, missing it leaves a
     * connection open. (An open that failed closed its own connection, and the caller closes it again.)
     */
    private fun closeAfterAbort() {
        synchronized(lock) { openNow = false }
        runCatching { upstream.close() }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        upstream.read(buffer, offset, length)
    } catch (e: Exception) {
        if (aborted) throw aborted() else throw e
    }

    override fun close() {
        if (!takeOpen()) return
        try {
            upstream.close()
        } catch (e: Exception) {
            if (!aborted) throw e
        }
    }

    /** From any thread. */
    fun abort() {
        aborted = true
        if (!takeOpen()) return
        try {
            upstream.close()
        } catch (e: Exception) {
            // Intentionally ignored: the write is cancelled, and its own thread reads the abort as an interruption.
        }
    }

    private fun takeOpen(): Boolean = synchronized(lock) { openNow.also { openNow = false } }

    private fun aborted() = InterruptedIOException("video start cancelled")

    class Factory(private val upstream: DataSource.Factory, private val abort: StartWriteAbort) : DataSource.Factory {
        override fun createDataSource(): DataSource = OneTryUpstream(upstream.createDataSource()).also(abort::register)
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
