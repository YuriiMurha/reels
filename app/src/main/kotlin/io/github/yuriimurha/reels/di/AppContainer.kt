package io.github.yuriimurha.reels.di

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import io.github.yuriimurha.reels.BuildConfig
import io.github.yuriimurha.reels.data.db.LegacyRequestLogCopy
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.media.FakeVideoSourceResolver
import io.github.yuriimurha.reels.data.media.HttpMediaFetcher
import io.github.yuriimurha.reels.data.media.MediaEviction
import io.github.yuriimurha.reels.data.media.RealVideoSourceResolver
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.media.VideoCache
import io.github.yuriimurha.reels.data.media.VideoSourceResolver
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.lab.AdapterLab
import io.github.yuriimurha.reels.instagram.web.CookieStore
import io.github.yuriimurha.reels.instagram.web.HttpClientFactory
import io.github.yuriimurha.reels.instagram.web.WebInstagramClient
import io.github.yuriimurha.reels.instagram.web.WebSessionProbe
import io.github.yuriimurha.reels.session.AndroidCookieStore
import io.github.yuriimurha.reels.session.LazySessionProbe
import io.github.yuriimurha.reels.session.SessionRepository
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.SyncEngine
import io.github.yuriimurha.reels.sync.SyncWorker
import io.github.yuriimurha.reels.sync.WorkManagerSyncScheduler
import io.github.yuriimurha.reels.sync.pacing.DataStoreCooldownStore
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.sync.pacing.RoomRequestLog
import okhttp3.OkHttpClient
import java.io.File

/**
 * Hand-wired dependencies, one instance per process (spec 4.1). Opts in to Media3's unstable API: the video cache and its
 * data sources are built here.
 */
@OptIn(UnstableApi::class)
class AppContainer(context: Context) {
    val settings: SettingsStore by lazy { SettingsStore.create(context) }

    /** Which library this process runs on (P1). Changing it takes effect on the next start. */
    private val backendPrefs = context.getSharedPreferences(BackendChoice.PREFS, Context.MODE_PRIVATE)
    val backendChoice = BackendChoice(backendPrefs, BuildConfig.DEBUG)

    /** Read once per process, so the library, its thumbnails and the backend can never disagree within one run. */
    val usesFake: Boolean = backendChoice.useFake

    /**
     * `library.db`: the real library, and the `api_request` log behind the real 24 h budget even in Mock mode, so session
     * checks and lab calls made in Mock mode still count (P2). The first time it is opened, the last 24 h of requests are
     * copied out of the old `reels.db`, where that log lived before (R68).
     */
    val requestLogDb: ReelsDatabase by lazy { ReelsDatabase.build(context, "library.db", LegacyRequestLogCopy(context, backendPrefs)) }

    /** The library the screens and the sync engine use: the fake one keeps `reels.db`, the real one is [requestLogDb]. */
    val db: ReelsDatabase by lazy { if (usesFake) ReelsDatabase.build(context, "reels.db") else requestLogDb }

    val thumbnails: ThumbnailStore by lazy { ThumbnailStore(File(context.filesDir, if (usesFake) "thumbs" else "library-thumbs")) }
    val library: LibraryRepository by lazy { LibraryRepository(db, thumbnails, clearVideoCache = { videoCache.clear() }) }

    /** Queued work names a run id only, so it also carries which library it belongs to (R67). */
    private val syncScheduler: WorkManagerSyncScheduler by lazy { WorkManagerSyncScheduler(context, SyncWorker.kindOf(usesFake)) }

    val syncController: SyncController by lazy { SyncController(db, syncScheduler) }

    /**
     * Watched videos, in the cache directory (the OS may reclaim it; a video is only a download away). One instance per
     * process: a second `SimpleCache` on the same directory throws. Built on first use.
     */
    val videoCache: VideoCache by lazy { VideoCache(File(context.cacheDir, "video"), StandaloneDatabaseProvider(context)) }

    /** The WebView's own user agent for video requests, read once and only when a video is first played. */
    val videoUserAgent: String by lazy { WebSettings.getDefaultUserAgent(context) }

    /**
     * Mock mode plays the bundled clip. The real resolver renews links on the Pacer's interactive lane (the one
     * Conservative Pacer, so a link refresh counts against the same budget as sync) and keeps the bytes in [videoCache].
     */
    val videoResolver: VideoSourceResolver by lazy {
        if (usesFake) {
            FakeVideoSourceResolver(context.packageName)
        } else {
            RealVideoSourceResolver(backend.client, instagramPacer, db.mediaDao(), videoCache, session)
        }
    }

    val cookieStore: CookieStore by lazy { AndroidCookieStore() }

    /** Paces every request to real Instagram; one per process for real traffic (spec 7.3). Logs to [requestLogDb] in both modes. */
    val instagramPacer: Pacer by lazy {
        Pacer(PacingPolicy.Conservative, RoomRequestLog(requestLogDb.apiRequestDao()), DataStoreCooldownStore(settings))
    }

    private val instagramHttp: OkHttpClient by lazy {
        HttpClientFactory.create(
            cookies = cookieStore,
            userAgent = WebSettings.getDefaultUserAgent(context),
            logger = if (BuildConfig.DEBUG) { line -> Log.d("InstagramHttp", line) } else null,
        )
    }

    /**
     * The CDN's own client (P6): no cookie jar, never shared with the API client. Lazy like [instagramHttp], because its
     * user agent comes from the WebView provider, which must not load just because the container was built.
     */
    private val cdnHttp: OkHttpClient by lazy { HttpMediaFetcher.client(WebSettings.getDefaultUserAgent(context)) }

    /** Fake library, or real Instagram (P1). Building it builds no HTTP client: both are lazy. */
    val backend: Backend by lazy {
        if (usesFake) {
            Backend.Fake()
        } else {
            Backend.Real(WebInstagramClient({ instagramHttp }, cookieStore), HttpMediaFetcher({ cdnHttp }), instagramPacer)
        }
    }

    /** The Developer section's Mock mode switch; [restart] is [ProcessRestart.restart] in the app. */
    fun mockModeSwitch(restart: () -> Unit) =
        MockModeSwitch(usesFake, backendChoice, cancelSync = { syncScheduler.cancelAndAwait() }, restart = restart)

    /** The debug Adapter lab. Built without the HTTP client: that is only built when a lab call reaches the network. */
    val adapterLab: AdapterLab by lazy { AdapterLab({ instagramHttp }, cookieStore) }

    /**
     * Building this loads no WebView: the HTTP client (whose user agent comes from the WebView provider) is only built
     * when the first request needs the probe, and the cookie store reaches CookieManager per call, not at construction.
     */
    val session: SessionRepository by lazy {
        SessionRepository(
            cookies = cookieStore,
            probe = LazySessionProbe { WebSessionProbe(instagramHttp, cookieStore) },
            pacer = instagramPacer,
            settings = settings,
        )
    }

    fun syncEngine(): SyncEngine {
        // Exhaustive on purpose: a new backend forces a decision about session signals (the real one's carry the session epoch).
        val signals: SessionSignals = when (backend) {
            is Backend.Fake -> SessionSignals.None
            is Backend.Real -> session
        }
        return SyncEngine(
            backend.client, backend.pacer, db, backend.fetcher, thumbnails, signals,
            eviction = MediaEviction { pks -> pks.forEach { videoCache.remove(it) } },
        )
    }
}
