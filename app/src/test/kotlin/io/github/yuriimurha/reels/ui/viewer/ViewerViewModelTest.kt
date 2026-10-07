package io.github.yuriimurha.reels.ui.viewer

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.media.RealVideoSourceResolver
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.media.VideoCache
import io.github.yuriimurha.reels.data.media.VideoSource
import io.github.yuriimurha.reels.data.media.VideoSourceResolver
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ViewerViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db = inMemoryDb()
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val resolver = RecordingResolver()
    private var now = START

    @Before
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
        storeScope.cancel()
    }

    private fun viewModel(videoResolver: VideoSourceResolver = resolver): ViewerViewModel {
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        return ViewerViewModel(
            MediaSource.Collection(ALL_SAVED_ID),
            startIndex = 0,
            library = LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs"))),
            resolver = videoResolver,
            settings = settings,
            now = { now },
        )
    }

    private fun reel(pk: String, expiresAt: Long?, url: String? = "https://video.example.test/$pk.mp4") =
        mediaEntity(pk, MediaType.REEL, videoUrl = url, videoUrlExpiresAt = expiresAt)

    private val expired get() = now - 1
    private val fresh get() = now + 3_600_000

    // --- prefetch ---

    @Test
    fun prefetchesTheNextExpiredLinkOnce() = runTest {
        val viewModel = viewModel()
        val page = reel("m1", fresh)
        val next = reel("m2", expiresAt = expired)

        viewModel.onSettled(page)
        viewModel.prefetch(next)
        runCurrent()
        viewModel.onSettled(page)
        viewModel.prefetch(next)
        runCurrent()

        assertEquals(listOf("m2"), resolver.calls, "two settles on the same page make one prefetch")
        assertEquals(listOf(false), resolver.forced, "a prefetch is an ordinary resolve, never a forced one")
    }

    @Test
    fun aNextLinkThatIsStillFreshIsLeftAlone() = runTest {
        val viewModel = viewModel()
        viewModel.prefetch(reel("m2", expiresAt = now + 3_600_000))
        runCurrent()
        assertEquals(emptyList(), resolver.calls)
    }

    @Test
    fun aNextLinkThatExpiresWithinTheMarginIsPrefetched() = runTest {
        val viewModel = viewModel()
        viewModel.prefetch(reel("m2", expiresAt = now + 9 * 60_000))
        runCurrent()
        assertEquals(listOf("m2"), resolver.calls)
    }

    @Test
    fun nothingIsPrefetchedForAnImageOrForTheLastItem() = runTest {
        val viewModel = viewModel()
        viewModel.prefetch(mediaEntity("i1", MediaType.IMAGE))
        viewModel.prefetch(null)
        runCurrent()
        assertEquals(emptyList(), resolver.calls)
    }

    @Test
    fun aNextLinkWithNoUrlOrNoExpiryIsPrefetched() = runTest {
        val viewModel = viewModel()
        viewModel.prefetch(reel("m2", expiresAt = null))
        runCurrent()
        viewModel.prefetch(reel("m3", expiresAt = null, url = null))
        runCurrent()
        assertEquals(listOf("m2", "m3"), resolver.calls)
    }

    @Test
    fun aNewSettleCancelsThePrefetchStillInFlight() = runTest {
        val viewModel = viewModel()
        resolver.hold("m2")
        viewModel.prefetch(reel("m2", expiresAt = expired))
        runCurrent()
        assertEquals(listOf("m2"), resolver.calls)

        viewModel.onSettled(reel("m5", fresh))
        viewModel.prefetch(reel("m6", expiresAt = expired))
        runCurrent()

        assertEquals(listOf("m2", "m6"), resolver.calls)
        assertEquals(listOf("m2"), resolver.cancelled, "only one prefetch is ever in flight")
        resolver.releaseAll()
    }

    /** R77: swiping to the item whose link is already being renewed must not ask Instagram a second time. */
    @Test
    fun settlingOnTheItemBeingPrefetchedReusesItsRequest() = runTest {
        val viewModel = viewModel()
        val gate = resolver.hold("m2")
        val next = reel("m2", expiresAt = expired)
        viewModel.prefetch(next)
        runCurrent()
        assertEquals(listOf("m2"), resolver.calls)

        viewModel.onSettled(next)
        val answer = async { viewModel.resolveVideo(next) }
        runCurrent()

        assertEquals(listOf("m2"), resolver.calls, "the settle is waiting for the prefetch, not sending its own request")
        assertEquals(emptyList(), resolver.cancelled, "and the prefetch was not cancelled by the settle on its own item")
        gate.complete(Unit)
        runCurrent()
        assertEquals(VideoSource.Play(Uri.parse("https://video.example.test/m2.mp4"), "m2"), answer.await())
        assertEquals(listOf("m2"), resolver.calls, "one request in all")
    }

    /**
     * The prefetch a settle is waiting for can be cancelled (a newer prefetch replaces it, or a refresh makes way). That is the
     * prefetch's cancellation, not the settle's: the settle must still get its source, from one more request.
     */
    @Test
    fun aPrefetchCancelledWhileASettleWaitsForItDoesNotFailTheSettle() = runTest {
        val viewModel = viewModel()
        val gate = resolver.hold("m2")
        val next = reel("m2", expiresAt = expired)
        viewModel.prefetch(next)
        runCurrent()
        viewModel.onSettled(next)
        val answer = async { viewModel.resolveVideo(next) }
        runCurrent()
        assertEquals(listOf("m2"), resolver.calls, "the settle is waiting for the prefetch")

        viewModel.prefetch(reel("m3", expiresAt = expired)) // a newer prefetch replaces (cancels) the one the settle waits for
        runCurrent()
        assertEquals(listOf("m2"), resolver.cancelled, "the prefetch the settle was waiting for is gone")
        gate.complete(Unit)
        runCurrent()

        assertEquals(VideoSource.Play(Uri.parse("https://video.example.test/m2.mp4"), "m2"), answer.await(), "the settle asked the resolver itself")
        assertEquals(listOf("m2", "m3", "m2"), resolver.calls, "one more request, as the cost of the cancelled prefetch")
    }

    /** The settle's OWN cancellation is still a cancellation: it must not turn into a fallback request. */
    @Test
    fun aCancelledSettleDoesNotFallBackToARequest() = runTest {
        val viewModel = viewModel()
        resolver.hold("m2")
        val next = reel("m2", expiresAt = expired)
        viewModel.prefetch(next)
        runCurrent()
        viewModel.onSettled(next)
        val answer = async { viewModel.resolveVideo(next) }
        runCurrent()

        answer.cancel()
        runCurrent()

        assertTrue(answer.isCancelled)
        assertEquals(listOf("m2"), resolver.calls, "no second request for a settle nobody waits for")
        resolver.releaseAll()
    }

    @Test
    fun afterThePrefetchIsDoneASettleAsksTheResolverAgain() = runTest {
        val viewModel = viewModel()
        val next = reel("m2", expiresAt = expired)
        viewModel.prefetch(next)
        runCurrent()

        viewModel.onSettled(next)
        viewModel.resolveVideo(next)

        assertEquals(listOf("m2", "m2"), resolver.calls, "nothing is in flight any more; the resolver itself sees the renewed row")
    }

    /** R77: the item the owner is looking at is requested before the one after it. */
    @Test
    fun theSettledItemIsRequestedBeforeTheNextOne() = runTest {
        val viewModel = viewModel()
        val m1 = reel("m1", expiresAt = expired)
        val m2 = reel("m2", expiresAt = expired)
        val gate = resolver.hold("m1")
        val settled = MutableStateFlow(SettledPage(0, null))
        val played = mutableListOf<String>()
        val job = launch {
            viewModel.followSettledPages(
                settled = settled,
                nextOf = { page -> m2.takeIf { page.media?.pk == "m1" } },
                isStillSettled = { settled.value.media?.pk == it.pk },
                onSettled = {},
                onSource = { media, _ -> played += media.pk },
            )
        }
        try {
            runCurrent()
            settled.value = SettledPage(1, m1)
            runCurrent()
            assertEquals(listOf("m1"), resolver.calls, "the visible item's request goes first, the next one waits for it")

            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("m1", "m2"), resolver.calls)
            assertEquals(listOf("m1"), played)
        } finally {
            resolver.releaseAll()
            job.cancel()
        }
    }

    @Test
    fun anImageDoesNotHoldBackThePrefetchOfTheNextVideo() = runTest {
        val viewModel = viewModel()
        val image = mediaEntity("i1", MediaType.IMAGE)
        val next = reel("m2", expiresAt = expired)
        val settled = MutableStateFlow(SettledPage(0, null))
        val job = launch {
            viewModel.followSettledPages(
                settled = settled,
                nextOf = { page -> next.takeIf { page.media?.pk == "i1" } },
                isStillSettled = { true },
                onSettled = {},
                onSource = { _, _ -> },
            )
        }
        try {
            runCurrent()
            settled.value = SettledPage(1, image)
            runCurrent()
            assertEquals(listOf("i1", "m2"), resolver.calls, "the image is resolved (to nothing), then the next video's link is renewed")
        } finally {
            job.cancel()
        }
    }

    @Test
    fun aSwipeBeforeTheSettledItemIsResolvedPrefetchesNothing() = runTest {
        val viewModel = viewModel()
        val m1 = reel("m1", expiresAt = expired)
        val m2 = reel("m2", expiresAt = expired)
        val m3 = reel("m3", expiresAt = expired)
        val m4 = reel("m4", expiresAt = expired)
        resolver.hold("m1")
        val settled = MutableStateFlow(SettledPage(0, null))
        val job = launch {
            viewModel.followSettledPages(
                settled = settled,
                nextOf = { page -> mapOf("m1" to m2, "m3" to m4)[page.media?.pk] },
                isStillSettled = { settled.value.media?.pk == it.pk },
                onSettled = {},
                onSource = { _, _ -> },
            )
        }
        try {
            runCurrent()
            settled.value = SettledPage(1, m1)
            runCurrent()
            settled.value = SettledPage(2, m3)
            runCurrent()

            assertEquals(listOf("m1", "m3", "m4"), resolver.calls, "m2 (the next of the page that was swiped past) was never requested")
        } finally {
            resolver.releaseAll()
            job.cancel()
        }
    }

    @Test
    fun aFailingPrefetchNeverCrashesTheViewer() = runTest {
        val viewModel = viewModel()
        resolver.failWith = IllegalStateException("boom")

        viewModel.prefetch(reel("m2", expiresAt = expired))
        runCurrent()

        assertEquals(listOf("m2"), resolver.calls)
        assertTrue(viewModel.viewModelScope.isActive, "an exception in the prefetch did not take the ViewModel's scope down")
    }

    /** R76: the prefetch goes through the resolver, whose readiness check keeps it from asking Instagram without a session. */
    @Test
    fun aPrefetchSendsNothingWhileTheSessionIsNotUsable() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val databaseProvider = StandaloneDatabaseProvider(context)
        val videoCache = VideoCache(File(tmp.root, "video"), databaseProvider)
        try {
            val client = CountingClient()
            val pacer = Pacer(PacingPolicy.Fast, InMemoryRequestLog(), InMemoryCooldownStore(), Random(1), now = { now })
            val real = RealVideoSourceResolver(client, pacer, db.mediaDao(), videoCache, SessionSignals.None, isSessionReady = { false }, now = { now })
            val next = reel("m2", expiresAt = expired)
            db.mediaDao().upsert(listOf(next))
            val viewModel = viewModel(real)

            viewModel.prefetch(next)
            val source = viewModel.resolveVideo(next) // the prefetch is still active, so its answer is reused: one refusal, locally

            assertEquals(VideoSource.Unavailable("Instagram session needs attention (Sync screen)"), source)
            assertEquals(emptyList(), client.calls, "no request without a usable session")
        } finally {
            videoCache.cache.release()
            databaseProvider.close()
        }
    }

    // --- one refresh after a 403 or 410 ---

    @Test
    fun aRefusedLinkIsRefreshedOnceAndThenGivenUp() = runTest {
        val viewModel = viewModel()
        val media = reel("m1", fresh)
        viewModel.onSettled(media)
        resolver.forcedAnswer = VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m1")

        val first = viewModel.recover(media, playbackFailure(403))
        val second = viewModel.recover(media, playbackFailure(403))

        assertEquals(VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m1"), first)
        assertEquals(listOf("m1"), resolver.calls, "one refresh")
        assertEquals(listOf(true), resolver.forced, "and it was forced, because the link only looked fresh")
        assertEquals(VideoSource.Unavailable(CANT_PLAY), second, "the second error on the same item gives up")
    }

    /** R79: the visible item's forced refresh must not queue behind the next item's prefetch in the interactive lane. */
    @Test
    fun aRefusedLinkCancelsThePrefetchForAnotherItemBeforeItsRefresh() = runTest {
        val viewModel = viewModel()
        val visible = reel("m1", fresh)
        resolver.hold("m2")
        resolver.forcedAnswer = VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m1")
        viewModel.onSettled(visible)
        val scopeJob = viewModel.viewModelScope.coroutineContext[Job]!!
        val before = scopeJob.children.toSet() // the scope has other children (the paging cache); the prefetch is the new one
        viewModel.prefetch(reel("m2", expiresAt = expired))
        runCurrent()
        assertEquals(listOf("m2"), resolver.calls, "the prefetch for the next item is out")
        val prefetchJob = scopeJob.children.single { it !in before }
        assertTrue(prefetchJob.isActive)
        var prefetchStillActiveWhenTheRefreshStarted: Boolean? = null
        resolver.onResolve = { _, forced -> if (forced) prefetchStillActiveWhenTheRefreshStarted = prefetchJob.isActive }

        val source = viewModel.recover(visible, playbackFailure(403))
        runCurrent()

        assertEquals(VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m1"), source)
        assertEquals(false, prefetchStillActiveWhenTheRefreshStarted, "the prefetch was already cancelled when the forced refresh was asked for")
        assertEquals(listOf("m2"), resolver.cancelled, "and it really ended")
        assertEquals(listOf("m2", "m1"), resolver.calls)
    }

    /**
     * A prefetch for the very item that failed is not "another item", so it is left alone (cancelling it would only throw away a
     * renewal of the same link). The forced refresh is still its own request: recover never reuses a prefetch's.
     */
    @Test
    fun aRefusedLinkLeavesAPrefetchForTheSameItemAlone() = runTest {
        val viewModel = viewModel()
        val media = reel("m2", expiresAt = expired)
        val gate = resolver.hold("m2")
        resolver.forcedAnswer = VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m2")
        viewModel.prefetch(media)
        runCurrent()

        val answer = async { viewModel.recover(media, playbackFailure(410)) }
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertEquals(VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m2"), answer.await())
        assertEquals(emptyList(), resolver.cancelled, "the item's own prefetch was not cancelled")
    }

    /** An error that is not a refused link refreshes nothing, so it has no reason to disturb the prefetch either. */
    @Test
    fun anErrorThatIsNotARefusedLinkLeavesThePrefetchAlone() = runTest {
        val viewModel = viewModel()
        val visible = reel("m1", fresh)
        val gate = resolver.hold("m2")
        viewModel.onSettled(visible)
        viewModel.prefetch(reel("m2", expiresAt = expired))
        runCurrent()

        assertEquals(VideoSource.Unavailable(CANT_PLAY), viewModel.recover(visible, playbackFailure(500)))
        runCurrent()

        assertEquals(emptyList(), resolver.cancelled)
        gate.complete(Unit)
    }

    @Test
    fun a410IsRefreshedToo() = runTest {
        val viewModel = viewModel()
        val media = reel("m1", fresh)
        viewModel.onSettled(media)
        resolver.forcedAnswer = VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m1")

        assertTrue(viewModel.recover(media, playbackFailure(410)) is VideoSource.Play)
        assertEquals(listOf("m1"), resolver.calls)
    }

    @Test
    fun anyOtherErrorGivesUpWithoutARequest() = runTest {
        val viewModel = viewModel()
        val media = reel("m1", fresh)
        viewModel.onSettled(media)

        for (error in listOf(playbackFailure(404), playbackFailure(500), PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED), IOException("reset"))) {
            assertEquals(VideoSource.Unavailable(CANT_PLAY), viewModel.recover(media, error), error.toString())
        }
        assertEquals(emptyList(), resolver.calls)
    }

    @Test
    fun theRefreshBudgetIsPerItem() = runTest {
        val viewModel = viewModel()
        val a = reel("m1", fresh)
        val b = reel("m2", fresh)
        viewModel.onSettled(a)
        resolver.forcedAnswer = VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "x")

        assertTrue(viewModel.recover(a, playbackFailure(403)) is VideoSource.Play)
        assertTrue(viewModel.recover(b, playbackFailure(403)) is VideoSource.Play, "another item has its own one refresh")
        assertEquals(listOf("m1", "m2"), resolver.calls)
    }

    @Test
    fun settlingOnTheItemAgainStartsItsBudgetOver() = runTest {
        val viewModel = viewModel()
        val media = reel("m1", fresh)
        resolver.forcedAnswer = VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m1")
        viewModel.onSettled(media)
        viewModel.recover(media, playbackFailure(403))

        viewModel.onSettled(reel("m2", fresh))
        viewModel.onSettled(media)

        assertTrue(viewModel.recover(media, playbackFailure(403)) is VideoSource.Play, "coming back to the item is a new visit")
    }

    @Test
    fun aRefreshThatFindsNothingKeepsItsOwnMessage() = runTest {
        val viewModel = viewModel()
        val media = reel("m1", fresh)
        viewModel.onSettled(media)
        resolver.forcedAnswer = VideoSource.Unavailable("This item is no longer available on Instagram")

        assertEquals(
            VideoSource.Unavailable("This item is no longer available on Instagram"),
            viewModel.recover(media, playbackFailure(410)),
        )
    }

    @Test
    fun theCauseChainIsSearchedForTheRefusal() {
        val refusal = invalidResponse(403)
        assertTrue(isExpiredLinkError(PlaybackException("source", refusal, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        assertTrue(isExpiredLinkError(RuntimeException("outer", IOException("middle", refusal))))
        assertTrue(isExpiredLinkError(refusal))
        assertTrue(!isExpiredLinkError(IOException("plain")))
        assertTrue(!isExpiredLinkError(PlaybackException("source", invalidResponse(404), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
    }

    @Test
    fun theMediaItemIsReadFromTheLinkAndCachedUnderThePk() {
        val item = VideoSource.Play(Uri.parse("https://video.example.test/new.mp4?token=2"), "42").toMediaItem()

        assertEquals(Uri.parse("https://video.example.test/new.mp4?token=2"), item.localConfiguration!!.uri)
        assertEquals("42", item.localConfiguration!!.customCacheKey, "the pk, not the link: a refreshed link must still hit the cached bytes")
    }

    // --- only the settled item plays ---

    private class GatedResolver(private val nonCancellable: Boolean = false) {
        val started = mutableListOf<String>()
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        fun gate(pk: String) = gates.getOrPut(pk) { CompletableDeferred() }

        suspend fun resolve(media: MediaEntity): VideoSource? {
            started += media.pk
            if (nonCancellable) withContext(NonCancellable) { gate(media.pk).await() } else gate(media.pk).await()
            return VideoSource.Play(Uri.parse("https://video.example.test/${media.pk}.mp4"), media.pk)
        }

        fun release(pk: String) = gate(pk).complete(Unit)

        /** So a test that fails half way cannot leave a resolve that ignores cancellation waiting for ever (the run would hang). */
        fun releaseAll() = gates.values.forEach { it.complete(Unit) }
    }

    private class Screen(val settled: MutableStateFlow<SettledPage>) {
        val played = mutableListOf<String>()
        val cleared = mutableListOf<Int>()
        fun isStillSettled(media: MediaEntity) = settled.value.media?.pk == media.pk
    }

    private fun TestScope.playing(resolver: GatedResolver, screen: Screen): Job = launch {
        playSettledPages(
            settled = screen.settled,
            resolve = resolver::resolve,
            isStillSettled = screen::isStillSettled,
            onSettled = { screen.cleared += it.index },
            afterResolved = {},
            onSource = { media, source -> if (source is VideoSource.Play) screen.played += media.pk },
        )
    }

    @Test
    fun viewerPlaysOnlyTheSettledItem() = runTest {
        val resolver = GatedResolver()
        val screen = Screen(MutableStateFlow(SettledPage(0, null)))
        val job = playing(resolver, screen)
        try {
            runCurrent()

            screen.settled.value = SettledPage(1, reel("m1", fresh))
            runCurrent()
            screen.settled.value = SettledPage(2, reel("m2", fresh))
            runCurrent()
            assertEquals(listOf("m1", "m2"), resolver.started, "the second page's resolve starts while the first is still out: collectLatest")

            resolver.release("m1")
            runCurrent()
            assertEquals(emptyList(), screen.played, "the first item's late answer never reaches the player")

            resolver.release("m2")
            runCurrent()
            assertEquals(listOf("m2"), screen.played)
            assertEquals(listOf(0, 1, 2), screen.cleared, "every settle first clears the player")
        } finally {
            resolver.releaseAll()
            job.cancel()
        }
    }

    /** A resolve that cannot be cancelled returns after the swipe; the re-check is what stops it. */
    @Test
    fun aResolveThatFinishesAfterTheSwipeIsNotPlayed() = runTest {
        val resolver = GatedResolver(nonCancellable = true)
        val screen = Screen(MutableStateFlow(SettledPage(0, null)))
        val job = playing(resolver, screen)
        try {
            runCurrent()

            screen.settled.value = SettledPage(1, reel("m1", fresh))
            runCurrent()
            screen.settled.value = SettledPage(2, reel("m2", fresh))
            runCurrent()
            resolver.release("m1")
            runCurrent()

            assertEquals(emptyList(), screen.played)
            resolver.release("m2")
            runCurrent()
            assertEquals(listOf("m2"), screen.played)
        } finally {
            resolver.releaseAll()
            job.cancel()
        }
    }

    @Test
    fun theRecheckAloneStopsAnAnswerForAnItemNoLongerSettled() = runTest {
        val media = reel("m1", fresh)
        val played = mutableListOf<String>()
        var settledPk: String? = "m1"

        playIfStillSettled(media, resolve = { VideoSource.Play(Uri.parse("https://video.example.test/m1.mp4"), "m1") }, isStillSettled = { settledPk == it.pk }) { played += it.toString() }
        assertEquals(1, played.size, "still settled: played")

        played.clear()
        playIfStillSettled(
            media,
            resolve = { settledPk = "m2"; VideoSource.Play(Uri.parse("https://video.example.test/m1.mp4"), "m1") },
            isStillSettled = { settledPk == it.pk },
        ) { played += it.toString() }
        assertEquals(emptyList(), played, "the page changed while the answer was on its way")
    }

    @Test
    fun anUnavailableAnswerIsHandedOverToo() = runTest {
        val got = mutableListOf<VideoSource>()
        playIfStillSettled(reel("m1", fresh), resolve = { VideoSource.Unavailable("Offline") }, isStillSettled = { true }) { got += it }
        assertEquals(listOf<VideoSource>(VideoSource.Unavailable("Offline")), got)
    }

    @Test
    fun aNonVideoGetsNoSource() = runTest {
        val got = mutableListOf<VideoSource>()
        playIfStillSettled(mediaEntity("i1", MediaType.IMAGE), resolve = { null }, isStillSettled = { true }) { got += it }
        assertEquals(emptyList(), got)
        assertNull(got.firstOrNull())
    }

    // --- fakes and helpers ---

    private class RecordingResolver : VideoSourceResolver {
        val calls = mutableListOf<String>()
        val forced = mutableListOf<Boolean>()
        val cancelled = mutableListOf<String>()
        var failWith: Exception? = null
        var forcedAnswer: VideoSource? = null

        /** Called as a resolve starts, before it waits for its gate. */
        var onResolve: ((media: MediaEntity, forced: Boolean) -> Unit)? = null
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** The resolve of [pk] waits until the returned gate is completed. */
        fun hold(pk: String): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gates[pk] = it }

        /** So a test that fails half way cannot leave a resolve waiting for ever. */
        fun releaseAll() = gates.values.forEach { it.complete(Unit) }

        override suspend fun resolve(media: MediaEntity, forceRefresh: Boolean): VideoSource? {
            calls += media.pk
            forced += forceRefresh
            onResolve?.invoke(media, forceRefresh)
            failWith?.let { throw it }
            gates[media.pk]?.let { gate ->
                try {
                    gate.await()
                } catch (e: CancellationException) {
                    cancelled += media.pk
                    throw e
                }
            }
            return if (forceRefresh) forcedAnswer else VideoSource.Play(Uri.parse("https://video.example.test/${media.pk}.mp4"), media.pk)
        }
    }

    private class CountingClient : InstagramClient {
        val calls = mutableListOf<String>()
        override val reportsSavedCollectionIds = true

        override suspend fun currentUser(): Account = error("not used")

        override suspend fun collections(cursor: String?): Page<RemoteCollection> = error("not used")

        override suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia> = error("not used")

        override suspend fun mediaInfo(mediaPk: String): RemoteMedia? {
            calls += mediaPk
            return null
        }
    }

    private fun invalidResponse(code: Int) = HttpDataSource.InvalidResponseCodeException(
        code, null, null, emptyMap(), DataSpec(Uri.parse("https://video.example.test/x.mp4")), ByteArray(0),
    )

    private fun playbackFailure(code: Int) =
        PlaybackException("source error", invalidResponse(code), PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)

    private companion object {
        const val START = 1_800_000_000_000L
    }
}
