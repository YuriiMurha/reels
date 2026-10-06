package io.github.yuriimurha.reels.di

import android.content.Context
import android.util.Log
import android.webkit.WebSettings
import io.github.yuriimurha.reels.BuildConfig
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.media.FakeVideoSourceResolver
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.media.VideoSourceResolver
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.web.CookieStore
import io.github.yuriimurha.reels.instagram.web.HttpClientFactory
import io.github.yuriimurha.reels.instagram.web.WebSessionProbe
import io.github.yuriimurha.reels.session.AndroidCookieStore
import io.github.yuriimurha.reels.session.LazySessionProbe
import io.github.yuriimurha.reels.session.SessionRepository
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.SyncEngine
import io.github.yuriimurha.reels.sync.WorkManagerSyncScheduler
import io.github.yuriimurha.reels.sync.pacing.DataStoreCooldownStore
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.sync.pacing.RoomRequestLog
import okhttp3.OkHttpClient
import java.io.File

/** Hand-wired dependencies, one instance per process (spec 4.1). */
class AppContainer(context: Context) {
    val settings: SettingsStore by lazy { SettingsStore.create(context) }
    val db: ReelsDatabase by lazy { ReelsDatabase.build(context) }
    val thumbnails: ThumbnailStore by lazy { ThumbnailStore(File(context.filesDir, "thumbs")) }
    val backend: Backend by lazy { Backend.Fake() }
    val library: LibraryRepository by lazy { LibraryRepository(db, thumbnails) }
    val syncController: SyncController by lazy { SyncController(db, WorkManagerSyncScheduler(context)) }

    /** M5 replaces this with the real resolver (link refresh on the interactive lane, pk-keyed cache). */
    val videoResolver: VideoSourceResolver by lazy { FakeVideoSourceResolver(context.packageName) }

    val cookieStore: CookieStore by lazy { AndroidCookieStore() }

    /** Paces every request to real Instagram; one per process for real traffic (spec 7.3). */
    val instagramPacer: Pacer by lazy {
        Pacer(PacingPolicy.Conservative, RoomRequestLog(db.apiRequestDao()), DataStoreCooldownStore(settings))
    }

    private val instagramHttp: OkHttpClient by lazy {
        HttpClientFactory.create(
            cookies = cookieStore,
            userAgent = WebSettings.getDefaultUserAgent(context),
            logger = if (BuildConfig.DEBUG) { line -> Log.d("InstagramHttp", line) } else null,
        )
    }

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
        // Exhaustive on purpose: adding Backend.Real (M4) forces a decision about session signals.
        val signals: SessionSignals = when (backend) {
            is Backend.Fake -> SessionSignals.None
        }
        return SyncEngine(backend.client, backend.pacer, db, backend.fetcher, thumbnails, signals)
    }
}
