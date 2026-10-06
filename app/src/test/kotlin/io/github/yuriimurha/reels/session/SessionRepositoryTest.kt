package io.github.yuriimurha.reels.session

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.instagram.web.InMemoryCookieStore
import io.github.yuriimurha.reels.instagram.web.cookieValue
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val cookies = InMemoryCookieStore()
    private val probe = FakeProbe()
    private val log = InMemoryRequestLog()

    @After
    fun tearDown() = storeScope.cancel()

    private fun TestScope.repository(): SessionRepository {
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        val pacer = Pacer(PacingPolicy.Conservative, log, InMemoryCooldownStore(), Random(1), now = { testScheduler.currentTime })
        return SessionRepository(cookies, probe, pacer, settings)
    }

    @Test
    fun validationIsPacedAndStoresTheHandle() = runTest {
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.validate())
        assertEquals(SessionState.Valid("tester"), repository.state.first())
        assertEquals(1, log.countSince(-1), "a session check is an Instagram request and goes through the Pacer")
    }

    @Test
    fun loginRequiredKeepsTheLastHandle() = runTest {
        val repository = repository()
        repository.validate()
        probe.next = { throw InstagramException.LoginRequired() }
        assertEquals(SessionState.Expired("tester"), repository.validate())
    }

    @Test
    fun challengeKeepsItsUrl() = runTest {
        val repository = repository()
        probe.next = { throw InstagramException.ChallengeRequired("https://www.instagram.com/challenge/x/") }
        assertEquals(SessionState.Challenge("https://www.instagram.com/challenge/x/", null), repository.validate())
    }

    @Test
    fun pasteRejectsGarbageWithoutRequest() = runTest {
        val repository = repository()
        assertNull(repository.pasteSessionId("hello there"))
        assertEquals(0, probe.calls)
        assertEquals(0, log.countSince(-1))
        assertFalse(repository.hasSessionCookies())
    }

    @Test
    fun pasteWritesBothCookiesThenValidates() = runTest {
        val repository = repository()
        assertEquals(SessionState.Valid("tester"), repository.pasteSessionId(" sessionid=\"42%3Aab\"; "))
        assertEquals("42%3Aab", cookies.cookieValue(SessionRepository.INSTAGRAM, "sessionid"))
        assertEquals("42", cookies.cookieValue(SessionRepository.INSTAGRAM, "ds_user_id"))
        assertEquals(1, probe.calls)
    }

    @Test
    fun logoutForgetsTheCookiesAndTheHandle() = runTest {
        val repository = repository()
        repository.pasteSessionId("42%3Aab")
        repository.logout()
        assertFalse(repository.hasSessionCookies())
        assertEquals(SessionState.LoggedOut, repository.state.first())
    }

    @Test
    fun engineSignalsUpdateTheState() = runTest {
        val repository = repository()
        repository.validate()
        repository.challengeRequired("https://www.instagram.com/challenge/z/")
        assertEquals(SessionState.Challenge("https://www.instagram.com/challenge/z/", "tester"), repository.state.first())
        repository.loginRequired()
        assertEquals(SessionState.Expired("tester"), repository.state.first())
    }

    @Test
    fun sessionCookiesNeedBothValues() = runTest {
        val repository = repository()
        cookies.setCookie(SessionRepository.INSTAGRAM, "sessionid=s1")
        assertFalse(repository.hasSessionCookies())
        cookies.setCookie(SessionRepository.INSTAGRAM, "ds_user_id=42")
        assertTrue(repository.hasSessionCookies())
        assertEquals("s1", repository.currentSessionId())
    }

    private class FakeProbe : SessionProbe {
        var calls = 0
        var next: () -> Account = { Account("42", "tester") }

        override suspend fun currentUser(): Account {
            calls++
            return next()
        }
    }
}
