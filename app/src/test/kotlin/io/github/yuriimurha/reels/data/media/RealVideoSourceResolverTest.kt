package io.github.yuriimurha.reels.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.Account
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.SessionProbe
import io.github.yuriimurha.reels.session.RecordingCookieStore
import io.github.yuriimurha.reels.session.SessionRepository
import io.github.yuriimurha.reels.session.toStored
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.sync.RunSession
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.testutil.cacheWholeFile
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RealVideoSourceResolverTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDb()
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val videoCache by lazy { VideoCache(File(tmp.root, "video"), databaseProvider) }
    private val cooldowns = InMemoryCooldownStore()
    private val client = StubClient()
    private val signals = RecordingSignals(epoch = 7)

    /** What the container's readiness check reads: the stored session. Only a valid one may be asked on. */
    private var sessionState: SessionState = SessionState.Valid("tester")

    @After
    fun close() {
        videoCache.cache.release()
        databaseProvider.close()
        db.close()
    }

    /** The Pacer, the resolver and the test scheduler agree on the time: [START] plus whatever the test has waited. */
    private fun TestScope.resolver(): RealVideoSourceResolver {
        val clock = { START + testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), cooldowns, Random(1), now = clock)
        return RealVideoSourceResolver(client, pacer, db.mediaDao(), videoCache, signals, ::sessionIsValid, now = clock)
    }

    private suspend fun sessionIsValid() = sessionState is SessionState.Valid

    private suspend fun stored(pk: String = "m1", videoUrl: String? = OLD_URL, expiresAt: Long? = START + 3_600_000): MediaEntity =
        mediaEntity(pk, MediaType.REEL, videoUrl = videoUrl, videoUrlExpiresAt = expiresAt).also { db.mediaDao().upsert(listOf(it)) }

    private fun cacheFully(pk: String) {
        val file = File(tmp.root, "$pk.mp4").apply { writeBytes(ByteArray(3_000) { it.toByte() }) }
        videoCache.cacheWholeFile(pk, file)
    }

    private suspend fun row(pk: String = "m1") = db.mediaDao().byPks(listOf(pk)).single()

    @Test
    fun resolverRefreshesOnlyExpiredLinks() = runTest {
        val resolver = resolver()

        // A link good for an hour: no request at all.
        val fresh = stored(expiresAt = START + 3_600_000)
        assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m1"), resolver.resolve(fresh))
        assertEquals(emptyList(), client.calls, "a fresh link costs no request")

        // Just inside the margin is still refreshed; just outside is not.
        val edge = stored(expiresAt = START + RealVideoSourceResolver.FRESH_MARGIN_MS)
        client.answer = { remote("m1", NEW_URL, START + 7_200_000) }
        assertEquals(VideoSource.Play(Uri.parse(NEW_URL), "m1"), resolver.resolve(edge), "exactly the margin away is not 'more than' the margin")
        assertEquals(listOf("m1"), client.calls)
        client.calls.clear()
        val outside = stored(expiresAt = START + RealVideoSourceResolver.FRESH_MARGIN_MS + 1)
        assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m1"), resolver.resolve(outside))
        assertEquals(emptyList(), client.calls)

        // An expired link: one request, the row is rewritten, and the new url plays under the pk.
        val expired = stored(expiresAt = START - 1)
        val newExpiry = START + 7_200_000
        client.answer = { remote("m1", NEW_URL, newExpiry) }
        val source = resolver.resolve(expired)
        assertEquals(VideoSource.Play(Uri.parse(NEW_URL), "m1"), source)
        assertEquals("m1", (source as VideoSource.Play).cacheKey, "the cache key is the pk, so the new link still hits the cached bytes")
        assertEquals(listOf("m1"), client.calls)
        assertEquals(NEW_URL, row().videoUrl)
        assertEquals(newExpiry, row().videoUrlExpiresAt)

        // A link whose expiry is unknown cannot be called fresh.
        client.calls.clear()
        resolver.resolve(stored(expiresAt = null))
        assertEquals(listOf("m1"), client.calls)

        // forceRefresh asks again even when the link looks fresh (one retry after a 403 or 410).
        client.calls.clear()
        val forced = resolver.resolve(stored(expiresAt = START + 3_600_000), forceRefresh = true)
        assertEquals(listOf("m1"), client.calls)
        assertEquals(VideoSource.Play(Uri.parse(NEW_URL), "m1"), forced)
    }

    /**
     * The interactive lane waits at least 2 s after the previous request; the sync lane (the fast fake policy) 20 to 60 ms.
     * So two refreshes in a row tell the lanes apart, which a refusal does not (both lanes refuse a cooldown).
     */
    @Test
    fun theRefreshRunsOnTheInteractiveLane() = runTest {
        val resolver = resolver()
        val first = stored("m1", expiresAt = START - 1)
        val second = stored("m2", expiresAt = START - 1)
        client.answer = { pk -> remote(pk, NEW_URL, START + 7_200_000) }

        resolver.resolve(first)
        val afterFirst = testScheduler.currentTime
        resolver.resolve(second)
        val waited = testScheduler.currentTime - afterFirst

        assertEquals(listOf("m1", "m2"), client.calls)
        assertTrue(waited >= PacingPolicy.Fast.interactiveMinGapMs, "the second refresh waited only $waited ms: that is the sync lane's gap, not the interactive lane's 2 s")
    }

    @Test
    fun aRefusalComesBeforeAnyRequest() = runTest {
        cooldowns.onRateLimited(START)

        resolver().resolve(stored(expiresAt = START - 1))

        assertEquals(emptyList(), client.calls, "the Pacer refused, so the client was never called")
    }

    /** The paging snapshot a caller holds can be older than the row: a prefetch may have renewed the link since. */
    @Test
    fun aStaleSnapshotWhoseRowWasRefreshedSendsNoRequest() = runTest {
        val snapshot = stored(expiresAt = START - 1)
        db.mediaDao().setVideoLink("m1", NEW_URL, START + 7_200_000)

        val source = resolver().resolve(snapshot)

        assertEquals(VideoSource.Play(Uri.parse(NEW_URL), "m1"), source, "the row's link, not the snapshot's")
        assertEquals(emptyList(), client.calls)
    }

    /** R78: the bytes are on disk, so the link that names them is beside the point. */
    @Test
    fun anExpiredButFullyCachedVideoPlaysWithoutARequest() = runTest {
        val resolver = resolver()
        cacheFully("m1")
        val cached = stored("m1", expiresAt = START - 1)
        val partial = stored("m2", expiresAt = START - 1)
        client.answer = { pk -> remote(pk, NEW_URL, START + 7_200_000) }

        assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m1"), resolver.resolve(cached))
        assertEquals(emptyList(), client.calls, "a fully cached video asks Instagram for nothing")
        assertEquals(VideoSource.Play(Uri.parse(NEW_URL), "m2"), resolver.resolve(partial), "an uncached one still refreshes")
        assertEquals(listOf("m2"), client.calls)
        assertEquals(OLD_URL, row("m1").videoUrl, "and the cached item's stored link was left alone")
    }

    /** A forced refresh (the player was refused) asks even for a cached video: the cached bytes did not play. */
    @Test
    fun aForcedRefreshAsksEvenForACachedVideo() = runTest {
        cacheFully("m1")
        val cached = stored("m1", expiresAt = START + 3_600_000)
        client.answer = { pk -> remote(pk, NEW_URL, START + 7_200_000) }

        assertEquals(VideoSource.Play(Uri.parse(NEW_URL), "m1"), resolver().resolve(cached, forceRefresh = true))
        assertEquals(listOf("m1"), client.calls)
    }

    /** R78: a link with no expiry of its own must not cost a request on every settle. */
    @Test
    fun aRefreshWithoutAnExpiryStoresOneHourAndTheNextSettleSendsNothing() = runTest {
        val resolver = resolver()
        val expired = stored(expiresAt = START - 1)
        client.answer = { pk -> remote(pk, NEW_URL, expiresAt = null) }

        assertEquals(VideoSource.Play(Uri.parse(NEW_URL), "m1"), resolver.resolve(expired))
        assertEquals(START + 3_600_000, row().videoUrlExpiresAt, "fetch time plus one hour")

        assertEquals(VideoSource.Play(Uri.parse(NEW_URL), "m1"), resolver.resolve(row()))
        assertEquals(listOf("m1"), client.calls, "the next settle used the stored link")
    }

    /** R76 / spec 6.4: after a challenge, a login failure or a logout, nothing automatic may be sent to Instagram. */
    @Test
    fun withoutAUsableSessionNothingIsRequested() = runTest {
        val resolver = resolver()
        cacheFully("m1")
        val cached = stored("m1", expiresAt = START - 1)
        val uncached = stored("m2", expiresAt = START - 1)
        val fresh = stored("m3", expiresAt = START + 3_600_000)
        client.answer = { pk -> remote(pk, NEW_URL, START + 7_200_000) }

        val unusable = listOf(
            SessionState.LoggedOut,
            SessionState.Expired("tester"),
            SessionState.Challenge("https://example.test/challenge/1/", "tester"),
        )
        for (state in unusable) {
            sessionState = state
            assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m1"), resolver.resolve(cached), "$state: cached plays")
            assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m3"), resolver.resolve(fresh), "$state: a fresh link plays")
            val needsAttention = VideoSource.Unavailable("Instagram session needs attention (Sync screen)")
            assertEquals(needsAttention, resolver.resolve(uncached), "$state: an expired uncached one says so")
            assertEquals(needsAttention, resolver.resolve(uncached, forceRefresh = true), "$state: a forced refresh sends nothing either")
            assertEquals(emptyList(), client.calls, "$state: no request")
        }
        assertEquals(emptyList(), signals.events, "and nothing is signalled to the session layer")

        sessionState = SessionState.Valid("tester")
        assertEquals(VideoSource.Play(Uri.parse(NEW_URL), "m2"), resolver.resolve(uncached), "a valid session asks again")
        assertEquals(listOf("m2"), client.calls)
    }

    /**
     * R79: the interactive lane can wait seconds for the gate (a request in flight holds it, then the 2 s gap), and the session
     * can turn bad meanwhile (a challenge). The resolver checked before it queued, so the Pacer re-checks from inside the gate:
     * nothing may go out. Runs [media]'s resolve while another interactive request holds the gate, turns the session to
     * [afterwards] once the resolve is queued behind it, and only then lets the gate go. (A latch, not a delay: while the test
     * waits for a real I/O thread the virtual clock runs on by itself, so a delay could end before the resolve has queued.)
     */
    private suspend fun TestScope.resolveWhileTheGateIsBusy(
        media: MediaEntity,
        afterwards: SessionState,
        forceRefresh: Boolean = false,
        signalsAfterwards: RunSession = RunSession.USABLE,
    ): VideoSource? {
        val clock = { START + testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), cooldowns, Random(1), now = clock)
        val asked = CompletableDeferred<Unit>()
        val resolver = RealVideoSourceResolver(
            client, pacer, db.mediaDao(), videoCache, signals,
            isSessionReady = { sessionIsValid().also { asked.complete(Unit) } },
            now = clock,
        )
        val release = CompletableDeferred<Unit>()
        launch { pacer.interactive { release.await() } } // a request of the owner's holds the gate
        runCurrent()
        var outcome: VideoSource? = null
        val job = launch { outcome = resolver.resolve(media, forceRefresh) }

        // The resolver reads the row first (a real I/O thread). Once it has passed its own readiness check it queues for the gate.
        withContext(Dispatchers.Default) { withTimeout(5_000) { asked.await() } }
        sessionState = afterwards
        signals.usable = signalsAfterwards
        release.complete(Unit)
        advanceUntilIdle() // the holder finishes, then the queued resolve takes the gate and waits out the 2 s gap
        // What follows touches Room again (a real I/O thread), so the end is awaited in real time.
        withContext(Dispatchers.Default) { withTimeout(5_000) { job.join() } }
        return outcome
    }

    @Test
    fun aSessionThatTurnsBadWhileTheRequestWaitsForTheGateSendsNothing() = runTest {
        val media = stored(expiresAt = START - 1)
        client.answer = { pk -> remote(pk, NEW_URL, START + 7_200_000) }

        val source = resolveWhileTheGateIsBusy(media, afterwards = SessionState.Challenge("https://example.test/challenge/1/", "tester"))

        assertEquals(emptyList(), client.calls, "the session changed while the request waited: mediaInfo must not be called")
        assertEquals(VideoSource.Unavailable("Instagram session needs attention (Sync screen)"), source)
        assertEquals(OLD_URL, row().videoUrl, "nothing was stored")
        assertEquals(emptyList(), signals.events, "and nothing is signalled to the session layer: the resolver itself declined")
    }

    /** I1: the stored state is still Valid, but the epoch or the account behind it changed while the request waited. */
    @Test
    fun aSessionThatIsNoLongerTheOneTheRequestStartedUnderSendsNothing() = runTest {
        val media = stored(expiresAt = START - 1)
        client.answer = { pk -> remote(pk, NEW_URL, START + 7_200_000) }

        val source = resolveWhileTheGateIsBusy(media, afterwards = SessionState.Valid("tester"), signalsAfterwards = RunSession.NOT_USABLE)

        assertEquals(emptyList(), client.calls, "mediaInfo must not be called under another session")
        assertEquals(VideoSource.Unavailable("Instagram session needs attention (Sync screen)"), source)
        assertEquals(OLD_URL, row().videoUrl, "nothing was stored")
        assertEquals(listOf(7), signals.asked, "it was asked, from inside the gate, with the epoch the resolve started under")
    }

    /**
     * M1: with the real session layer over a jar that cannot be read, `epoch()` used to throw at the top of `resolve`, outside
     * its try: the viewer crashed. Now the resolve sends nothing and answers "Can't load this video right now".
     */
    @Test
    fun aJarThatCannotBeReadDoesNotCrashTheViewerAndSendsNothing() = runTest {
        val jar = RecordingCookieStore().apply { readFailure = IllegalStateException("no WebView provider") }
        val storeScope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
            settings.setSession(SessionState.Valid("tester").toStored())
            val clock = { START + testScheduler.currentTime }
            val pacer = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), cooldowns, Random(1), now = clock)
            val repository = SessionRepository(jar, object : SessionProbe { override suspend fun currentUser() = error("never asked") }, pacer, settings)
            val resolver = RealVideoSourceResolver(client, pacer, db.mediaDao(), videoCache, repository, ::sessionIsValid, now = clock)
            val media = stored(expiresAt = START - 1)

            val source = withContext(Dispatchers.Default) { withTimeout(5_000) { resolver.resolve(media) } }

            assertEquals(VideoSource.Unavailable("Can't load this video right now"), source)
            assertEquals(emptyList(), client.calls, "mediaInfo was never called")
        } finally {
            storeScope.cancel()
        }
    }

    @Test
    fun aSessionThatTurnsBadWhileWaitingStillPlaysACachedVideo() = runTest {
        cacheFully("m1")
        val media = stored("m1", expiresAt = START + 3_600_000)
        client.answer = { pk -> remote(pk, NEW_URL, START + 7_200_000) }

        val source = resolveWhileTheGateIsBusy(media, afterwards = SessionState.Expired("tester"), forceRefresh = true)

        assertEquals(emptyList(), client.calls)
        assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m1"), source, "the bytes are on disk, so they play")
    }

    /** `SimpleCache` synchronises with its own file commits: asking it about a video must never happen on the main thread. */
    @Test
    fun theCacheIsOnlyCheckedOnTheInjectedIoDispatcher() = runTest {
        val io = RecordingDispatcher(StandardTestDispatcher(testScheduler))
        val clock = { START + testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), cooldowns, Random(1), now = clock)
        val resolver = RealVideoSourceResolver(client, pacer, db.mediaDao(), videoCache, signals, ::sessionIsValid, clock, io)
        cacheFully("m1")
        val cached = stored("m1", expiresAt = START - 1)
        val uncached = stored("m2", expiresAt = START - 1)
        val fresh = stored("m3", expiresAt = START + 3_600_000)
        client.answer = { pk -> remote(pk, NEW_URL, START + 7_200_000) }

        assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m1"), resolver.resolve(cached))
        val afterCached = io.dispatches
        assertTrue(afterCached > 0, "a cached video was looked up through the IO dispatcher")

        assertEquals(VideoSource.Play(Uri.parse(NEW_URL), "m2"), resolver.resolve(uncached))
        assertTrue(io.dispatches > afterCached, "an uncached one was looked up through it too, before the request")

        val before = io.dispatches
        assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m3"), resolver.resolve(fresh))
        assertEquals(before, io.dispatches, "a fresh link needs no cache check at all")
    }

    private class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun notFoundIsUnavailable() = runTest {
        val media = stored(expiresAt = START - 1)
        client.answer = { null }

        val source = resolver().resolve(media)

        assertEquals(VideoSource.Unavailable("This item is no longer available on Instagram"), source)
        assertEquals(OLD_URL, row().videoUrl, "nothing is deleted or rewritten because Instagram no longer has it")
    }

    @Test
    fun aResultWithoutAVideoLinkIsUnavailableToo() = runTest {
        val media = stored(expiresAt = START - 1)
        client.answer = { remote("m1", videoUrl = null, expiresAt = null) }

        assertEquals(VideoSource.Unavailable("This item is no longer available on Instagram"), resolver().resolve(media))
        assertEquals(OLD_URL, row().videoUrl)
    }

    @Test
    fun refusalFallsBackToCacheOrMessage() = runTest {
        cooldowns.onRateLimited(START)
        val resolver = resolver()
        cacheFully("m1")
        val cached = stored("m1", expiresAt = START - 1)
        val uncached = stored("m2", expiresAt = START - 1)
        val cachedWithoutUrl = stored("m3", videoUrl = null, expiresAt = null).also { cacheFully("m3") }

        assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m1"), resolver.resolve(cached, forceRefresh = true), "a fully cached video plays from the cache")
        val message = assertIs<VideoSource.Unavailable>(resolver.resolve(uncached)).message
        assertTrue("Cooling down" in message, "the message says why: $message")
        assertTrue(message.startsWith("Video can't load right now: "), message)
        assertIs<VideoSource.Unavailable>(resolver.resolve(cachedWithoutUrl, forceRefresh = true), "no stored url, nothing to hand the player")
        assertEquals(emptyList(), client.calls, "a refusal sends nothing")
    }

    @Test
    fun aBudgetRefusalFallsBackTheSameWay() = runTest {
        val clock = { START + testScheduler.currentTime }
        val log = InMemoryRequestLog(List(PacingPolicy.Fast.dailyBudget) { START - 1_000L })
        val resolver = RealVideoSourceResolver(client, Pacer(PacingPolicy.Fast, log, cooldowns, Random(1), now = clock), db.mediaDao(), videoCache, signals, ::sessionIsValid, clock)
        val media = stored(expiresAt = START - 1)

        val message = assertIs<VideoSource.Unavailable>(resolver.resolve(media)).message

        assertTrue("24-hour" in message, message)
        assertEquals(emptyList(), client.calls)
    }

    @Test
    fun offlineFallsBackToCacheOrMessage() = runTest {
        val resolver = resolver()
        cacheFully("m1")
        val cached = stored("m1", expiresAt = START - 1)
        val uncached = stored("m2", expiresAt = START - 1)

        for (failure in listOf<Exception>(InstagramException.Transient(IOException("x")), IOException("no route"))) {
            client.answer = { throw failure }
            assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m1"), resolver.resolve(cached, forceRefresh = true), "${failure::class.simpleName}: cached plays")
            assertEquals(
                VideoSource.Unavailable("Offline: this video isn't cached yet"),
                resolver.resolve(uncached),
                "${failure::class.simpleName}: uncached says offline",
            )
        }
    }

    @Test
    fun challengeSignalsTheSession() = runTest {
        val media = stored(expiresAt = START - 1)
        client.answer = { throw InstagramException.ChallengeRequired("https://example.test/challenge/1/") }

        val source = resolver().resolve(media)

        assertEquals(VideoSource.Unavailable("Instagram session needs attention (Sync screen)"), source)
        assertEquals(listOf("challenge:7"), signals.events, "the signal carries the epoch the refresh started under")
        assertTrue(signals.challengeUrls.single()!!.startsWith("https://"), "the session layer gets the url; the viewer never shows it")
        assertTrue("https://" !in (source as VideoSource.Unavailable).message)
    }

    @Test
    fun loginRequiredSignalsTheSession() = runTest {
        val media = stored(expiresAt = START - 1)
        client.answer = { throw InstagramException.LoginRequired() }

        assertEquals(VideoSource.Unavailable("Instagram session needs attention (Sync screen)"), resolver().resolve(media))
        assertEquals(listOf("login:7"), signals.events)
    }

    @Test
    fun aSignalThatFailsDoesNotCrashTheViewer() = runTest {
        val media = stored(expiresAt = START - 1)
        client.answer = { throw InstagramException.LoginRequired() }
        signals.failWith = IllegalStateException("datastore is gone")

        assertEquals(VideoSource.Unavailable("Instagram session needs attention (Sync screen)"), resolver().resolve(media))
    }

    @Test
    fun rateLimitedSaysSoAndTheCooldownIsArmed() = runTest {
        val media = stored(expiresAt = START - 1)
        client.answer = { throw InstagramException.RateLimited() }

        assertEquals(VideoSource.Unavailable("Instagram is limiting requests"), resolver().resolve(media))
        assertNotNull(cooldowns.activeUntil(), "the Pacer armed the cooldown; the resolver adds nothing to it")
    }

    @Test
    fun anAdapterOrUnexpectedFailureIsUnavailableNotACrash() = runTest {
        val media = stored(expiresAt = START - 1)
        val resolver = resolver()

        for (failure in listOf<Exception>(InstagramException.ShapeChanged("items[0]"), IllegalStateException("secret detail"))) {
            client.answer = { throw failure }
            val message = assertIs<VideoSource.Unavailable>(resolver.resolve(media)).message
            assertTrue("secret detail" !in message, "an unexpected failure's own text never reaches the screen: $message")
        }
    }

    @Test
    fun nonVideoIsNull() = runTest {
        val resolver = resolver()

        assertNull(resolver.resolve(mediaEntity("i1", MediaType.IMAGE, videoUrl = OLD_URL, videoUrlExpiresAt = START - 1)))
        assertNull(resolver.resolve(mediaEntity("c1", MediaType.CAROUSEL), forceRefresh = true))
        assertEquals(emptyList(), client.calls)
    }

    @Test
    fun aCancelledResolveIsNotSwallowed() = runTest {
        val media = stored(expiresAt = START - 1)
        val requested = CompletableDeferred<Unit>()
        client.answer = { requested.complete(Unit); awaitCancellation() }
        var outcome: Any? = "unset"
        val job = launch { outcome = resolver().resolve(media) }

        // The resolver reads the row first (a real I/O thread), so the request is out a moment later, in real time.
        withContext(Dispatchers.Default) { withTimeout(5_000) { requested.await() } }
        job.cancel()
        runCurrent()

        assertEquals("unset", outcome, "cancellation ended the resolve; it did not turn into an Unavailable")
        assertTrue(job.isCancelled)
    }

    // --- fakes ---

    private class StubClient : InstagramClient {
        val calls = mutableListOf<String>()
        var answer: suspend (pk: String) -> RemoteMedia? = { null }
        override val reportsSavedCollectionIds = true

        override suspend fun currentUser(): Account = error("not used by the resolver")

        override suspend fun collections(cursor: String?): Page<RemoteCollection> = error("not used by the resolver")

        override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> = error("not used by the resolver")

        override suspend fun mediaInfo(mediaPk: String): RemoteMedia? {
            calls += mediaPk
            return answer(mediaPk)
        }
    }

    private class RecordingSignals(private val epoch: Int) : SessionSignals {
        val events = mutableListOf<String>()
        val challengeUrls = mutableListOf<String?>()
        var failWith: Exception? = null

        /** What the in-gate check answers, and the epochs it was asked with. */
        var usable = RunSession.USABLE
        val asked = mutableListOf<Int>()

        override suspend fun runSession(epoch: Int): RunSession {
            asked += epoch
            return usable
        }

        override fun epoch(): Int = epoch

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

    private fun remote(pk: String, videoUrl: String?, expiresAt: Long?) = RemoteMedia(
        pk = pk, code = "C$pk", type = MediaType.REEL, author = "a", caption = null, takenAt = Instant.EPOCH,
        width = 1080, height = 1920, carouselCount = null, thumbnailUrl = "https://example.test/t/$pk.jpg",
        videoUrl = videoUrl, videoUrlExpiresAt = expiresAt?.let(Instant::ofEpochMilli), savedCollectionIds = null,
    )

    private companion object {
        const val START = 1_800_000_000_000L
        const val OLD_URL = "https://video.example.test/old.mp4?oe=1"
        const val NEW_URL = "https://video.example.test/new.mp4?oe=2"
    }
}
