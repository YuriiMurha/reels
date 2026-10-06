package io.github.yuriimurha.reels.di

import android.content.Context
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.SyncEngine
import io.github.yuriimurha.reels.sync.WorkManagerSyncScheduler
import java.io.File

/** Hand-wired dependencies, one instance per process (spec 4.1). */
class AppContainer(context: Context) {
    val settings: SettingsStore by lazy { SettingsStore.create(context) }
    val db: ReelsDatabase by lazy { ReelsDatabase.build(context) }
    val thumbnails: ThumbnailStore by lazy { ThumbnailStore(File(context.filesDir, "thumbs")) }
    val backend: Backend by lazy { Backend.Fake() }
    val library: LibraryRepository by lazy { LibraryRepository(db) }
    val syncController: SyncController by lazy { SyncController(db, WorkManagerSyncScheduler(context)) }

    fun syncEngine(): SyncEngine = SyncEngine(backend.client, backend.pacer, db, backend.fetcher, thumbnails)
}
