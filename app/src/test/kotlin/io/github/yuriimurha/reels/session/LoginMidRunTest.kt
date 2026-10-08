package io.github.yuriimurha.reels.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.media.MediaFetcher
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.fake.FakeInstagramClient
import io.github.yuriimurha.reels.instagram.fake.FakeLibrary
import io.github.yuriimurha.reels.sync.SyncEngine
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.testutil.InMemoryLibraryAccount
import io.github.yuriimurha.reels.testutil.inMemoryDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H1, end to end: a real [SyncEngine] gated by the real [SessionRepository] (as `AppContainer` wires it), with the owner
 * logging in again in the WebView while the run is in progress. Fakes only; nothing here reaches Instagram.
 */
@RunWith(AndroidJUnit4::class)
class LoginMidRunTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val db = inMemoryDb()
    private val cookies = RecordingCookieStore()
    private val account = InMemoryLibraryAccount()
    private val fetcher = MediaFetcher { byteArrayOf(1, 2, 3) }
    private val thumbs by lazy { ThumbnailStore(File(tmp.root, "thumbs")) }

    @After
    fun tearDown() {
        storeScope.cancel()
        db.close()
    }

    /** What the session check (Check now, the login screen) asks Instagram; a different account once the owner logs in again. */
    private var checkedAs = Account("1", "test_account")

    private fun signIn(sessionId: String, userId: String) {
        cookies.setCookie(SessionRepository.INSTAGRAM, "sessionid=$sessionId")
        cookies.setCookie(SessionRepository.INSTAGRAM, "ds_user_id=$userId")
    }

    /**
     * The sync's client, with [duringCall] run while request number [onCall] is in flight, where a login or a check would land.
     * It touches only [repository] and [cookies], never the engine's Pacer: its request holds that gate, as in the app.
     */
    private fun hooked(
        onCall: Int,
        duringCall: suspend () -> Unit,
    ): Pair<FakeInstagramClient, InstagramClient> {
        val fake = FakeInstagramClient(FakeLibrary(itemCount = 50, collectionCount = 3))
        return fake to object : InstagramClient by fake {
            override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> {
                if (fake.calls.size + 1 == onCall) duringCall()
                return fake.savedMedia(collectionId, cursor)
            }
        }
    }

    private class Setup(
        val repository: SessionRepository,
        val engine: SyncEngine,
        val settings: SettingsStore,
        val startRun: suspend () -> Long,
        val status: suspend (Long) -> SyncRunEntity,
    )

    private fun TestScope.setup(client: InstagramClient): Setup {
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        // The session layer's own Pacer: the engine's request holds its gate while the hooked "owner" validates, so they can't share one here.
        val sessionPacer = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), InMemoryCooldownStore(), Random(2), now = { testScheduler.currentTime })
        val probe = object : SessionProbe {
            override suspend fun currentUser(): Account = checkedAs
        }
        val repository = SessionRepository(cookies, probe, sessionPacer, settings)
        val syncPacer = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), InMemoryCooldownStore(), Random(1), now = { testScheduler.currentTime })
        val engine = SyncEngine(
            client, syncPacer, db, fetcher, thumbs, repository, Random(1), now = { testScheduler.currentTime },
            sessionUsable = repository::runSession, libraryAccount = account,
        )
        return Setup(
            repository,
            engine,
            settings,
            startRun = {
                delay(1)
                db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.RUNNING, startedAt = testScheduler.currentTime))
            },
            status = { id -> db.syncDao().run(id)!! },
        )
    }

    @Test
    fun aLoginAsAnotherAccountMidRunStopsTheRunBeforeItsNextRequest() = runTest {
        signIn("s1", "1")
        lateinit var repository: SessionRepository
        val (fake, client) = hooked(onCall = 3) {
            // A break or a backoff, and the owner logs in again in the WebView, as another account; the login screen validates.
            signIn("s2", "2")
            checkedAs = Account("2", "other_account")
            assertEquals(SessionState.Valid("other_account"), repository.validate())
        }
        val s = setup(client)
        repository = s.repository
        assertEquals(SessionState.Valid("test_account"), s.repository.validate(), "the owner checked the session before syncing")

        val id = s.startRun()
        s.engine.run(id)

        val run = s.status(id)
        assertEquals(3, fake.calls.size, "nothing more is sent under the new login: ${fake.calls}")
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals("Session expired", run.lastError)
        assertEquals(SessionState.Valid("other_account"), s.repository.state.first(), "and the new login is what is stored")
    }

    /**
     * I1, the reviewer's probe: the cookies switch to another account while request 3 is out and NOTHING validates (the login
     * screen polls only once a second, and the owner may have left it). Before the fix the run went on to DONE and the
     * next pages were sent as the other account.
     */
    @Test
    fun aLoginAsAnotherAccountWithNoCheckStillStopsTheRunBeforeItsNextRequest() = runTest {
        signIn("s1", "1")
        val (fake, client) = hooked(onCall = 3) { signIn("s2", "2") }
        val s = setup(client)
        assertEquals(SessionState.Valid("test_account"), s.repository.validate())

        val id = s.startRun()
        s.engine.run(id)

        val run = s.status(id)
        assertEquals(3, fake.calls.size, "nothing more is sent under the other account: ${fake.calls}")
        assertEquals(SyncStatus.STOPPED_LOGIN, run.status)
        assertEquals("Session expired", run.lastError)
    }

    /** The same sync, but the sessionid is re-issued for the SAME account: no false stop. */
    @Test
    fun aReissuedSessionIdOfTheSameAccountMidRunDoesNotStopIt() = runTest {
        signIn("s1", "1")
        val (fake, client) = hooked(onCall = 3) { signIn("s1-reissued", "1") }
        val s = setup(client)
        assertEquals(SessionState.Valid("test_account"), s.repository.validate())

        val id = s.startRun()
        s.engine.run(id)

        assertEquals(SyncStatus.DONE, s.status(id).status)
        assertTrue(fake.calls.size > 3, "the run went on past the re-issue: ${fake.calls}")
    }

    /**
     * M1: a process that starts with its jar unreadable (`CookieManager` without a WebView provider) and a stored Valid session.
     * The run is left PAUSED with the usual "Unexpected error", with nothing sent; before, `epoch()` threw outside the engine's
     * try and the row stayed RUNNING.
     */
    @Test
    fun aJarThatCannotBeReadEndsTheRunPausedAndSendsNothing() = runTest {
        cookies.readFailure = IllegalStateException("no WebView provider")
        val (fake, client) = hooked(onCall = 3) { }
        val s = setup(client)
        s.settings.setSession(SessionState.Valid("test_account").toStored())

        val id = s.startRun()
        s.engine.run(id)

        val run = s.status(id)
        assertEquals(SyncStatus.PAUSED, run.status)
        assertEquals("Unexpected error: IllegalStateException", run.lastError)
        assertEquals(emptyList(), fake.calls, "not even the session check was sent")
    }

    @Test
    fun aCheckNowOfTheSameSessionMidRunDoesNotStopIt() = runTest {
        signIn("s1", "1")
        lateinit var repository: SessionRepository
        val (fake, client) = hooked(onCall = 3) {
            assertEquals(SessionState.Valid("test_account"), repository.validate())
        }
        val s = setup(client)
        repository = s.repository
        assertEquals(SessionState.Valid("test_account"), s.repository.validate())

        val id = s.startRun()
        s.engine.run(id)

        val run = s.status(id)
        assertEquals(SyncStatus.DONE, run.status)
        assertTrue(fake.calls.size > 3, "the run went on past the check: ${fake.calls}")
    }
}
