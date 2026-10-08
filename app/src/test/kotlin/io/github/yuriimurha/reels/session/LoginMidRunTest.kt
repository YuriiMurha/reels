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

    private class Setup(val repository: SessionRepository, val engine: SyncEngine, val startRun: suspend () -> Long, val status: suspend (Long) -> SyncRunEntity)

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
