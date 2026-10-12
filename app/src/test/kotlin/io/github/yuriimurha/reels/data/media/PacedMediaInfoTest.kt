package io.github.yuriimurha.reels.data.media

import io.github.yuriimurha.reels.data.media.PacedMediaInfo.Outcome
import io.github.yuriimurha.reels.data.media.PacedMediaInfo.Reason
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.sync.RunSession
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.time.Instant
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The one `mediaInfo` on the interactive lane, under the session gates (R76, R79): one test per outcome. Everything runs in
 * virtual time on the real Conservative Pacer; no Room, no network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PacedMediaInfoTest {
    private val client = StubClient()
    private val signals = RecordingSignals(current = 7)
    private val cooldowns = InMemoryCooldownStore()
    private var sessionReady = true

    private fun TestScope.pacer(log: InMemoryRequestLog = InMemoryRequestLog()) =
        Pacer(PacingPolicy.Conservative, log, cooldowns, Random(1), now = { START + testScheduler.currentTime })

    private fun info(pacer: Pacer) = PacedMediaInfo(client, pacer, signals, isSessionReady = { sessionReady })

    @Test
    fun anItemInstagramStillHasIsFresh() = runTest {
        val info = info(pacer())
        client.answer = { pk -> remote(pk) }

        assertEquals(Outcome.Fresh(remote("m1")), info.fetch("m1"))
        val afterFirst = testScheduler.currentTime
        assertEquals(Outcome.Fresh(remote("m2")), info.fetch("m2"))
        val waited = testScheduler.currentTime - afterFirst

        assertEquals(listOf("m1", "m2"), client.calls)
        // The interactive lane waits 2 s after the previous request; the Conservative sync lane at least 4 s.
        assertTrue(waited in PacingPolicy.Conservative.interactiveMinGapMs until PacingPolicy.Conservative.minGapMs, "waited $waited ms: not the interactive lane")
        assertEquals(emptyList(), signals.events)
    }

    @Test
    fun anItemInstagramNoLongerHasIsGone() = runTest {
        client.answer = { null }

        assertEquals(Outcome.Gone, info(pacer()).fetch("m1"))
        assertEquals(listOf("m1"), client.calls)
    }

    /** R76: without a usable session nothing is sent, nothing is logged against the budget, nothing is signalled. */
    @Test
    fun withoutASessionNothingIsSent() = runTest {
        val log = InMemoryRequestLog()
        sessionReady = false

        assertEquals(Outcome.Failed("Instagram session needs attention (Sync screen)", Reason.NO_SESSION), info(pacer(log)).fetch("m1"))
        assertEquals(emptyList(), client.calls)
        assertEquals(0, log.countSince(0))
        assertEquals(emptyList(), signals.events)
    }

    /**
     * R79: the fetch passed its own readiness check and queued behind another request; the session stopped being the one it
     * started under while it waited. The Pacer's in-gate check asks with the epoch the fetch STARTED under, and nothing goes out.
     */
    @Test
    fun aSessionThatChangesWhileTheRequestWaitsForTheGateSendsNothing() = runTest {
        val pacer = pacer()
        val info = info(pacer)
        val release = CompletableDeferred<Unit>()
        launch { pacer.interactive { release.await() } } // a request of the owner's holds the gate
        runCurrent()
        var outcome: Outcome? = null
        launch { outcome = info.fetch("m1") }
        runCurrent() // it has read the epoch and passed the readiness check; now it waits for the gate

        signals.current = 8 // a logout or a paste moved the session on
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(Outcome.Failed("Instagram session needs attention (Sync screen)", Reason.NO_SESSION), outcome)
        assertEquals(emptyList(), client.calls)
        assertEquals(listOf(7), signals.asked, "asked once, from inside the gate, with the epoch the fetch started under")
        assertEquals(emptyList(), signals.events)
    }

    @Test
    fun aRefusalSendsNothingAndSaysWhy() = runTest {
        cooldowns.onRateLimited(START)

        val outcome = info(pacer()).fetch("m1")

        assertEquals(Reason.REFUSED, (outcome as Outcome.Failed).reason)
        assertTrue("Cooling down" in outcome.message, outcome.message)
        assertEquals(emptyList(), client.calls)
    }

    @Test
    fun aFullDailyBudgetIsARefusalToo() = runTest {
        val log = InMemoryRequestLog(List(PacingPolicy.Conservative.dailyBudget) { START - 1_000L })

        val outcome = info(pacer(log)).fetch("m1")

        assertEquals(Reason.REFUSED, (outcome as Outcome.Failed).reason)
        assertTrue("24-hour" in outcome.message, outcome.message)
        assertEquals(emptyList(), client.calls)
    }

    @Test
    fun aTransientFailureOrANetworkErrorIsOffline() = runTest {
        val info = info(pacer())
        for (failure in listOf<Exception>(InstagramException.Transient(IOException("x")), IOException("no route"))) {
            client.answer = { throw failure }
            assertEquals(Outcome.Failed("Offline", Reason.OFFLINE), info.fetch("m1"), failure::class.simpleName)
        }
        assertEquals(emptyList(), signals.events)
    }

    @Test
    fun loginRequiredTellsTheSessionUnderTheStartingEpoch() = runTest {
        client.answer = { throw InstagramException.LoginRequired() }

        assertEquals(Outcome.Failed("Instagram session needs attention (Sync screen)", Reason.NEEDS_ATTENTION), info(pacer()).fetch("m1"))
        assertEquals(listOf("login:7"), signals.events)
    }

    @Test
    fun aChallengeTellsTheSessionAndNeverShowsItsUrl() = runTest {
        client.answer = { throw InstagramException.ChallengeRequired("https://example.test/challenge/1/") }

        val outcome = info(pacer()).fetch("m1") as Outcome.Failed

        assertEquals(Reason.NEEDS_ATTENTION, outcome.reason)
        assertEquals(listOf("challenge:7"), signals.events)
        assertEquals(listOf<String?>("https://example.test/challenge/1/"), signals.challengeUrls)
        assertTrue("https://" !in outcome.message)
    }

    @Test
    fun aSignalThatFailsDoesNotChangeTheOutcome() = runTest {
        client.answer = { throw InstagramException.LoginRequired() }
        signals.failWith = IllegalStateException("datastore is gone")

        assertEquals(Reason.NEEDS_ATTENTION, (info(pacer()).fetch("m1") as Outcome.Failed).reason)
    }

    @Test
    fun aRateLimitSaysSoAndThePacerArmsTheCooldown() = runTest {
        client.answer = { throw InstagramException.RateLimited() }

        assertEquals(Outcome.Failed("Instagram is limiting requests", Reason.RATE_LIMITED), info(pacer()).fetch("m1"))
        assertNotNull(cooldowns.activeUntil())
    }

    @Test
    fun anUnexpectedFailureKeepsNoneOfItsText() = runTest {
        val info = info(pacer())
        for (failure in listOf<Exception>(InstagramException.ShapeChanged("items[0]"), IllegalStateException("secret detail"))) {
            client.answer = { throw failure }
            val outcome = info.fetch("m1") as Outcome.Failed
            assertEquals(Reason.UNEXPECTED, outcome.reason)
            assertTrue("secret" !in outcome.message && "items[0]" !in outcome.message, outcome.message)
        }
    }

    /** A session layer that cannot even say its epoch (a jar that cannot be read) is a failure, never a crash, and sends nothing. */
    @Test
    fun anEpochThatCannotBeReadIsAFailureNotACrash() = runTest {
        signals.epochFailure = IllegalStateException("no WebView provider")

        assertEquals(Reason.UNEXPECTED, (info(pacer()).fetch("m1") as Outcome.Failed).reason)
        assertEquals(emptyList(), client.calls)
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        val requested = CompletableDeferred<Unit>()
        client.answer = { requested.complete(Unit); awaitCancellation() }
        var outcome: Any? = "unset"
        val info = info(pacer())
        val job = launch { outcome = info.fetch("m1") }
        runCurrent()
        assertTrue(requested.isCompleted, "precondition: the request is out")

        job.cancel()
        runCurrent()

        assertEquals("unset", outcome)
        assertTrue(job.isCancelled)
    }

    // --- fakes ---

    private class StubClient : InstagramClient {
        val calls = mutableListOf<String>()
        var answer: suspend (pk: String) -> RemoteMedia? = { null }
        override val reportsSavedCollectionIds = true

        override suspend fun currentUser(): Account = error("not used")

        override suspend fun collections(cursor: String?): Page<RemoteCollection> = error("not used")

        override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> = error("not used")

        override suspend fun mediaInfo(mediaPk: String): RemoteMedia? {
            calls += mediaPk
            return answer(mediaPk)
        }
    }

    private class RecordingSignals(var current: Int) : SessionSignals {
        val events = mutableListOf<String>()
        val challengeUrls = mutableListOf<String?>()
        val asked = mutableListOf<Int>()
        var failWith: Exception? = null
        var epochFailure: Exception? = null

        override fun epoch(): Int = epochFailure?.let { throw it } ?: current

        /** Like the session layer: work that started under any epoch but the current one is not usable. */
        override suspend fun runSession(epoch: Int): RunSession {
            asked += epoch
            return if (epoch == current) RunSession.USABLE else RunSession.NOT_USABLE
        }

        override suspend fun sessionOk(username: String, epoch: Int) {
            events += "ok:$epoch"
        }

        override suspend fun loginRequired(epoch: Int) {
            events += "login:$epoch"
            failWith?.let { throw it }
        }

        override suspend fun challengeRequired(challengeUrl: String?, epoch: Int) {
            events += "challenge:$epoch"
            challengeUrls += challengeUrl
        }
    }

    private fun remote(pk: String) = RemoteMedia(
        pk = pk, code = "C$pk", type = MediaType.REEL, author = "a", caption = null, takenAt = Instant.EPOCH,
        width = 1080, height = 1920, carouselCount = null, thumbnailUrl = "https://cdn.example.test/t/$pk.jpg",
        videoUrl = "https://cdn.example.test/v/$pk.mp4", videoUrlExpiresAt = null, savedCollectionIds = null,
    )

    private companion object {
        const val START = 1_800_000_000_000L
    }
}
