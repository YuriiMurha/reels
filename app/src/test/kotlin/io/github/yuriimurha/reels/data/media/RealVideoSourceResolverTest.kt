package io.github.yuriimurha.reels.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.testutil.cacheWholeFile
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.Instant
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
        return RealVideoSourceResolver(client, pacer, db.mediaDao(), videoCache, signals, now = clock)
    }

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

    @Test
    fun theRefreshRunsOnTheInteractiveLane() = runTest {
        // Cooling down refuses an interactive request before it is sent: proof that the refresh goes through the Pacer.
        cooldowns.onRateLimited(START)
        val media = stored(expiresAt = START - 1)

        resolver().resolve(media)

        assertEquals(emptyList(), client.calls, "the Pacer refused, so the client was never called")
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

        assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m1"), resolver.resolve(cached), "a fully cached video plays from the cache")
        val message = assertIs<VideoSource.Unavailable>(resolver.resolve(uncached)).message
        assertTrue("Cooling down" in message, "the message says why: $message")
        assertTrue(message.startsWith("Video can't load right now: "), message)
        assertIs<VideoSource.Unavailable>(resolver.resolve(cachedWithoutUrl), "no stored url, nothing to hand the player")
        assertEquals(emptyList(), client.calls, "a refusal sends nothing")
    }

    @Test
    fun aBudgetRefusalFallsBackTheSameWay() = runTest {
        val clock = { START + testScheduler.currentTime }
        val log = InMemoryRequestLog(List(PacingPolicy.Fast.dailyBudget) { START - 1_000L })
        val resolver = RealVideoSourceResolver(client, Pacer(PacingPolicy.Fast, log, cooldowns, Random(1), now = clock), db.mediaDao(), videoCache, signals, clock)
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
            assertEquals(VideoSource.Play(Uri.parse(OLD_URL), "m1"), resolver.resolve(cached), "${failure::class.simpleName}: cached plays")
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

        runCurrent()
        assertTrue(requested.isCompleted, "the request is out")
        job.cancel()
        runCurrent()

        assertEquals("unset", outcome, "cancellation ended the resolve; it did not turn into an Unavailable")
        assertTrue(job.isCancelled)
    }

    // --- fakes ---

    private class StubClient : InstagramClient {
        val calls = mutableListOf<String>()
        var answer: suspend () -> RemoteMedia? = { null }
        override val reportsSavedCollectionIds = true

        override suspend fun currentUser(): Account = error("not used by the resolver")

        override suspend fun collections(cursor: String?): Page<RemoteCollection> = error("not used by the resolver")

        override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> = error("not used by the resolver")

        override suspend fun mediaInfo(mediaPk: String): RemoteMedia? {
            calls += mediaPk
            return answer()
        }
    }

    private class RecordingSignals(private val epoch: Int) : SessionSignals {
        val events = mutableListOf<String>()
        val challengeUrls = mutableListOf<String?>()
        var failWith: Exception? = null

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
