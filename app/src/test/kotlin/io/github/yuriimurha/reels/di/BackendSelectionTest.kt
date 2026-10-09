package io.github.yuriimurha.reels.di

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.fake.FakeFailures
import io.github.yuriimurha.reels.instagram.fake.FakeInstagramClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Which backend the process runs and where its data lives (P1, P2). These run on the debug unit-test variant, where
 * `BuildConfig.DEBUG` is true; the release rule is tested on [BackendChoice] directly.
 */
@RunWith(AndroidJUnit4::class)
class BackendSelectionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val containers = mutableListOf<AppContainer>()

    private fun prefs(): SharedPreferences = context.getSharedPreferences(BackendChoice.PREFS, Context.MODE_PRIVATE)

    /** [useFake] null leaves the preference unset, as on a fresh install. */
    private fun container(useFake: Boolean?): AppContainer {
        prefs().edit().apply { if (useFake == null) clear() else putBoolean(BackendChoice.KEY_USE_FAKE, useFake) }.commit()
        return AppContainer(context).also { containers += it }
    }

    @After
    fun closeDatabases() {
        containers.forEach {
            it.requestLogDb.close()
            it.db.close()
        }
    }

    @Test
    fun releaseNeverUsesFake() {
        prefs().edit().putBoolean(BackendChoice.KEY_USE_FAKE, true).commit()
        assertFalse(BackendChoice(prefs(), debugBuild = false).useFake)
    }

    @Test
    fun debugDefaultsToFake() {
        prefs().edit().clear().commit()
        assertTrue(BackendChoice(prefs(), debugBuild = true).useFake, "a fresh debug install, and every emulator, starts on the fake library")
    }

    @Test
    fun debugFollowsTheStoredChoice() {
        val choice = BackendChoice(prefs(), debugBuild = true)
        choice.setUseFake(false)
        assertFalse(choice.useFake)
        assertFalse(BackendChoice(prefs(), debugBuild = true).useFake, "stored, not just remembered by the instance")
        choice.setUseFake(true)
        assertTrue(choice.useFake)
    }

    @Test
    fun theProcessReadsTheChoiceOnceAndKeepsIt() {
        val container = container(useFake = true)
        assertTrue(container.usesFake)
        container.backendChoice.setUseFake(false)
        assertTrue(container.usesFake, "the mode in effect only changes with a restart")
    }

    @Test
    fun realBackendReusesTheInstagramPacer() {
        val container = container(useFake = false)
        val backend = container.backend
        assertTrue(backend is Backend.Real, "Mock mode off means the real backend")
        assertSame(container.instagramPacer, backend.pacer, "real traffic has ONE pacer: the process's Conservative instagramPacer")

        // The transport and the CDN client are lazy: building the container and the backend must not load a WebView.
        assertFalse(container.instagramTransportCreated, "the transport was built before any request")
        assertFalse(lazyIsInitialised(container, "cdnHttp"), "the CDN client was built before any download")
    }

    @Test
    fun theFakeBackendKeepsItsOwnFastPacerAndNeverTheRealOne() {
        val container = container(useFake = true)
        assertTrue(container.backend is Backend.Fake)
        assertNotSame(container.instagramPacer, container.backend.pacer)
    }

    @Test
    fun backendsUseSeparateLibrariesAndShareTheRequestLog() = runBlocking {
        val fake = container(useFake = true)
        assertNotSame(fake.requestLogDb, fake.db, "the fake library is not in library.db")

        // The real request log (the 24 h budget) is in library.db even in Mock mode: lab calls and session checks count.
        fake.instagramPacer.interactive { }
        assertEquals(1, fake.requestLogDb.apiRequestDao().countSince(0))
        assertEquals(0, fake.db.apiRequestDao().countSince(0), "reels.db has no request log of its own that counts")
        assertTrue(context.getDatabasePath("library.db").exists())
        assertTrue(context.getDatabasePath("reels.db").exists())
    }

    @Test
    fun theRealLibraryAndTheRequestLogShareLibraryDb() = runBlocking {
        val real = container(useFake = false)
        assertSame(real.requestLogDb, real.db)
        real.instagramPacer.interactive { }
        assertEquals(1, real.db.apiRequestDao().countSince(0))
        assertTrue(context.getDatabasePath("library.db").exists())
        assertFalse(context.getDatabasePath("reels.db").exists(), "the real mode never creates the fake library")
    }

    @Test
    fun thumbnailsLiveBesideTheirLibrary() {
        val bytes = byteArrayOf(1, 2, 3)
        container(useFake = true).thumbnails.write("p1", bytes)
        assertTrue(File(context.filesDir, "thumbs/p1.jpg").exists(), "the fake library keeps filesDir/thumbs")

        container(useFake = false).thumbnails.write("p2", bytes)
        assertTrue(File(context.filesDir, "library-thumbs/p2.jpg").exists(), "the real library uses filesDir/library-thumbs")
        assertFalse(File(context.filesDir, "thumbs/p2.jpg").exists())
    }

    /**
     * R85: cached videos live beside their library, like thumbnails. A shared directory would let Mock mode's clip, cached
     * under a fake pk, answer for a real item with the same pk, and Delete library in one mode empty the other's cache.
     */
    private fun videosLiveIn(useFake: Boolean, own: String, other: String) {
        listOf(own, other).forEach { File(context.cacheDir, it).deleteRecursively() } // whatever an earlier test left
        val container = container(useFake)
        try {
            // Built here. SimpleCache creates its directory on its own init thread; any of its (synchronized) calls waits for that.
            container.videoCache.cache.keys
            assertTrue(File(context.cacheDir, own).isDirectory, "the ${if (useFake) "fake" else "real"} library caches videos in cacheDir/$own")
            assertFalse(File(context.cacheDir, other).exists(), "and never in cacheDir/$other")
        } finally {
            container.videoCache.cache.release()
        }
    }

    @Test
    fun theFakeLibrarysVideosLiveInTheirOwnCache() = videosLiveIn(useFake = true, own = "fake-video", other = "video")

    @Test
    fun theRealLibrarysVideosKeepTheirCache() = videosLiveIn(useFake = false, own = "video", other = "fake-video")

    /**
     * R84: the container's library and its account go together: the account is kept under this library's kind, and Delete
     * library forgets it. One container per test: two would open two DataStores on the one settings file.
     */
    private fun deleteLibraryForgetsTheAccountOf(useFake: Boolean) = runBlocking {
        val container = container(useFake)
        val (own, other) = if (useFake) "fake" to "real" else "real" to "fake"
        try {
            container.libraryAccount.remember("7")
            assertEquals("7", container.settings.libraryAccountPk(own), "kept under this library's kind")
            assertEquals(null, container.settings.libraryAccountPk(other), "and not the other library's")

            container.library.deleteLibrary()

            assertEquals(null, container.libraryAccount.pk())
            assertEquals(null, container.settings.libraryAccountPk(own))
        } finally {
            container.videoCache.cache.release() // Delete library built it; SimpleCache allows one per directory per process
        }
    }

    @Test
    fun deleteLibraryForgetsTheRealLibrarysAccount() = deleteLibraryForgetsTheAccountOf(useFake = false)

    @Test
    fun deleteLibraryForgetsTheFakeLibrarysAccount() = deleteLibraryForgetsTheAccountOf(useFake = true)

    /**
     * C-M5: "Couldn't refresh collection names" is the real library's. Deleting the real library clears it with the names it
     * spoke of; deleting Mock mode's fake library leaves it alone.
     */
    private fun deleteLibraryClearsTheNamesNoticeOf(useFake: Boolean) = runBlocking {
        val container = container(useFake)
        try {
            container.settings.setCollectionNamesStale(true)
            container.library.deleteLibrary()
            assertEquals(useFake, container.settings.collectionNamesStale.first(), if (useFake) "the real library's notice stays" else "cleared")
        } finally {
            container.videoCache.cache.release()
        }
    }

    @Test
    fun deletingTheRealLibraryClearsTheNamesNotice() = deleteLibraryClearsTheNamesNoticeOf(useFake = false)

    @Test
    fun deletingTheFakeLibraryLeavesTheRealNamesNotice() = deleteLibraryClearsTheNamesNoticeOf(useFake = true)

    /**
     * R18/R21: Forget's flag is the real library's. A Mock mode sync with the flag set (the real library left it) asks the fake
     * names query as always, never repairs, and leaves the flag for the real library.
     */
    @Test
    fun mockModeNeverReadsTheForcedRepairFlag() = runBlocking {
        val container = container(useFake = true)
        container.settings.forgetCollectionsQueryId()
        val fake = container.backend.client as FakeInstagramClient
        // Stop the run at its first feed page: only the session check and the names query matter here.
        fake.failures = FakeFailures { call -> if (call == 3) InstagramException.ShapeChanged("stop") else null }
        val run = container.db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.RUNNING, startedAt = 1L))

        container.syncEngine().run(run)

        assertEquals(listOf("currentUser", "collections:null", "saved:all:null"), fake.calls, "the names query was asked, as always")
        assertTrue(container.settings.collectionsForceRepair(), "the real library's flag is left alone")
    }

    private fun lazyIsInitialised(container: AppContainer, property: String): Boolean =
        (AppContainer::class.java.getDeclaredField("$property\$delegate").apply { isAccessible = true }.get(container) as Lazy<*>)
            .isInitialized()
}
