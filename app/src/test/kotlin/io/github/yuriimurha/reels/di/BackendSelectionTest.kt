package io.github.yuriimurha.reels.di

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

        // The clients are lazy: building the container and the backend must not load a WebView (the user agent comes from it).
        assertFalse(lazyIsInitialised(container, "instagramHttp"), "the API client was built before any request")
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

    private fun lazyIsInitialised(container: AppContainer, property: String): Boolean =
        (AppContainer::class.java.getDeclaredField("$property\$delegate").apply { isAccessible = true }.get(container) as Lazy<*>)
            .isInitialized()
}
