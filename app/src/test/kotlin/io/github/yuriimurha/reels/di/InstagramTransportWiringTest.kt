package io.github.yuriimurha.reels.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.session.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Instagram transport is a hidden WebView on instagram.com. It must exist only because a real request needed it: Mock
 * mode (the fake backend) never builds it, and neither does anything that merely changes or asks about the session (a cold
 * logout, Delete library, the hooks themselves). Building it is the first step towards loading Instagram, so each of those is
 * run here against a container with no session, and the transport is asked whether it was made.
 */
@RunWith(AndroidJUnit4::class)
class InstagramTransportWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val containers = mutableListOf<AppContainer>()

    @After
    fun closeDatabases() {
        containers.forEach {
            it.requestLogDb.close()
            it.db.close()
        }
    }

    private fun container(useFake: Boolean): AppContainer {
        context.getSharedPreferences(BackendChoice.PREFS, Context.MODE_PRIVATE).edit().putBoolean(BackendChoice.KEY_USE_FAKE, useFake).commit()
        return AppContainer(context).also { containers += it }
    }

    /**
     * Everything a session change, a check or Delete library does to the transport's hooks, on a container whose cookie jar holds no
     * session (so `validate()` makes no request). Delete library builds the video cache, which must be released again.
     */
    private suspend fun AppContainer.runEveryHook() {
        try {
            assertEquals(SessionState.LoggedOut, session.validate())
            // R106: a stored expiry or challenge, and a login screen opened to fix one, close the page of a transport that exists.
            session.loginRequired(session.epoch())
            session.challengeRequired(null, session.epoch())
            session.closeHiddenPage()
            closeInstagramPage()
            session.logout()
            resetInstagramTransport()
            allowNewInstagramAttempts()
            library.deleteLibrary()
        } finally {
            videoCache.cache.release() // SimpleCache allows one per directory per process
        }
    }

    @Test
    fun mockModeNeverBuildsTheTransport() = runBlocking {
        val container = container(useFake = true)
        assertTrue(container.usesFake)
        assertTrue(container.backend is Backend.Fake)
        // Everything the screens build in Mock mode.
        container.adapterLab
        container.videoResolver
        container.syncEngine()
        assertFalse(container.instagramTransportCreated, "building the Mock mode graph built the transport")

        container.runEveryHook()

        assertFalse(container.instagramTransportCreated, "a hook built the transport in Mock mode")
    }

    /** The fake library's engine, started for real and stopped after its first requests: its run start must not reach the transport. */
    @Test
    fun aMockModeSyncRunNeverBuildsTheTransport() = runBlocking {
        val container = container(useFake = true)
        val runId = container.db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.RUNNING, startedAt = 0))
        val engine = container.syncEngine()

        val run = launch(Dispatchers.Default) { engine.run(runId) }
        try {
            withTimeout(20_000) {
                while ((container.db.syncDao().run(runId)?.requestsUsed ?: 0) < 2) delay(20)
            }
        } finally {
            run.cancelAndJoin()
        }

        assertTrue((container.db.syncDao().run(runId)?.requestsUsed ?: 0) >= 2, "precondition: the run really sent requests to the fake library")
        assertFalse(container.instagramTransportCreated, "a Mock mode run built the transport")
    }

    /** Building the real graph, and everything that only changes or asks about the session, makes no transport; a request would. */
    @Test
    fun theRealModeBuildsNoTransportUntilARequest() = runBlocking {
        val container = container(useFake = false)
        assertTrue(container.backend is Backend.Real)
        container.adapterLab
        container.syncEngine()
        assertFalse(container.instagramTransportCreated, "building the real graph built the transport")

        container.runEveryHook()

        assertFalse(container.instagramTransportCreated, "a hook, or a check with no session, built the transport")
    }

    /**
     * Once something did build the transport (not its page: that is made by the first call), the hooks run against it and finish
     * (no hang, no failure). That they reach it is not observable here: its state is private, and a call would load the site,
     * which no test may do. `BackendWiringGuardTest` pins what each hook calls.
     */
    @Test
    fun theHooksFinishOnATransportThatExists() = runBlocking {
        val container = container(useFake = false)
        container.instagramTransport
        assertTrue(container.instagramTransportCreated)

        withTimeout(20_000) { container.runEveryHook() }

        assertTrue(container.instagramTransportCreated)
    }
}
