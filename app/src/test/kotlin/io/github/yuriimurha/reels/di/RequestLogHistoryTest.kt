package io.github.yuriimurha.reels.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ApiRequestEntity
import io.github.yuriimurha.reels.data.db.ReelsDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R68: before Task 7 the real request log lived in `reels.db`; it now lives in `library.db`, which starts empty. Without
 * a one-time copy, an upgrade forgets the last 24 hours of real requests, so the budget and the gap seed start over.
 */
@RunWith(AndroidJUnit4::class)
class RequestLogHistoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val containers = mutableListOf<AppContainer>()
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L

    private fun seedOldDatabase(vararg times: Long) = runBlocking {
        val old = ReelsDatabase.build(context, "reels.db")
        times.forEach { old.apiRequestDao().insert(ApiRequestEntity(at = it)) }
        old.close()
    }

    private fun container(useFake: Boolean): AppContainer {
        context.getSharedPreferences(BackendChoice.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(BackendChoice.KEY_USE_FAKE, useFake).commit()
        return AppContainer(context).also { containers += it }
    }

    @After
    fun closeDatabases() {
        containers.forEach { it.requestLogDb.close() }
    }

    @Test
    fun theLastDayOfRequestsIsCopiedOnceInMockMode() = runBlocking {
        seedOldDatabase(now - 1 * hour, now - 5 * hour, now - 23 * hour, now - 30 * hour)

        val first = container(useFake = true)
        assertEquals(3, first.requestLogDb.apiRequestDao().countSince(0), "the three recent rows, not the 30 h old one")
        assertEquals(3, first.instagramPacer.status().requestsLast24h, "the budget remembers them")
        assertEquals(now - 1 * hour, first.requestLogDb.apiRequestDao().latest(), "so does the gap seed")
        first.requestLogDb.close()

        // A restart: the flag says it was done, so a second container must not copy again.
        val second = container(useFake = true)
        assertEquals(3, second.requestLogDb.apiRequestDao().countSince(0))
    }

    @Test
    fun theCopyIsTheSameInRealMode() = runBlocking {
        seedOldDatabase(now - 2 * hour, now - 40 * hour)

        assertEquals(1, container(useFake = false).requestLogDb.apiRequestDao().countSince(0))
    }

    @Test
    fun withNoOldDatabaseNothingIsCopiedAndNoneIsCreated() = runBlocking {
        val container = container(useFake = false)
        assertEquals(0, container.requestLogDb.apiRequestDao().countSince(0))

        assertFalse(context.getDatabasePath("reels.db").exists(), "the copy must not create the old database")
        assertTrue(context.getDatabasePath("library.db").exists())
    }

    @Test
    fun theCopyIsOnceEvenIfAnOldDatabaseAppearsLater() = runBlocking {
        val first = container(useFake = false)
        assertEquals(0, first.requestLogDb.apiRequestDao().countSince(0))
        first.requestLogDb.close()

        seedOldDatabase(now - 1 * hour)
        assertEquals(0, container(useFake = false).requestLogDb.apiRequestDao().countSince(0))
    }
}
