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
import io.github.yuriimurha.reels.data.db.CarouselItemEntity
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.db.toEntity
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.media.CarouselImageStore
import io.github.yuriimurha.reels.data.media.CarouselRepository
import io.github.yuriimurha.reels.data.media.CdnRateLimited
import io.github.yuriimurha.reels.data.media.FakeVideoSourceResolver
import io.github.yuriimurha.reels.data.media.MediaFetcher
import io.github.yuriimurha.reels.data.media.PacedMediaInfo
import io.github.yuriimurha.reels.data.media.RealVideoSourceResolver
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.media.VideoCache
import io.github.yuriimurha.reels.data.media.VideoSource
import io.github.yuriimurha.reels.data.media.VideoSourceResolver
import io.github.yuriimurha.reels.data.media.VideoStarts
import io.github.yuriimurha.reels.data.media.StartWrite
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.Account
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.Page
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.instagram.fake.FakeInstagramClient
import io.github.yuriimurha.reels.instagram.fake.FakeLibrary
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.testutil.endViewModels
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
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
    private val viewModels = mutableListOf<ViewerViewModel>()

    private val main = StandardTestDispatcher()

    @Before
    fun setMain() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        // A carousel visit's work (its fill, its images) lives in the ViewModel's scope: end it, and wait until it has finished,
        // before the database goes and before Main is reset (endViewModels says why cancelling alone is not enough).
        endViewModels(viewModels, main)
        db.close()
        storeScope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(
        videoResolver: VideoSourceResolver = resolver,
        carousels: CarouselRepository = carouselRepository(),
    ): ViewerViewModel {
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        return ViewerViewModel(
            MediaSource.Collection(ALL_SAVED_ID),
            startIndex = 0,
            library = LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs"))),
            resolver = videoResolver,
            settings = settings,
            carousels = carousels,
            now = { now },
        ).also { viewModels += it }
    }

    // --- carousels: the real repository over the fake library, an in-memory Room and a stub CDN ---

    private val fake = FakeInstagramClient(FakeLibrary(itemCount = 200))

    /** What happens to the fake's `mediaInfo` reply on its way back (the request is already out): a test's way to hold it. */
    private var onReply: suspend (RemoteMedia?) -> RemoteMedia? = { it }
    private val client = object : InstagramClient by fake {
        override suspend fun mediaInfo(mediaPk: String): RemoteMedia? = onReply(fake.mediaInfo(mediaPk))
    }
    private val mediaInfoCalls get() = fake.calls.filter { it.startsWith("mediaInfo:") }

    /** A fake carousel of at least six items: 0, 1, 3, 4 images, 2 and 5 videos (every third child is one). */
    private val post: RemoteMedia = fake.library.allSaved().first { it.type == MediaType.CAROUSEL && (it.carouselCount ?: 0) >= 6 }
    private val carousel: MediaEntity = mediaEntity(post.pk, MediaType.CAROUSEL).copy(carouselCount = post.carouselCount)
    private val pk = post.pk

    /** The CDN: every image download is recorded; while [imageGate] is set, a download waits for it. */
    private val fetched: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val cancelledFetches: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var imageGate: CompletableDeferred<Unit>? = null
    private val fetcher = MediaFetcher { url ->
        fetched += url
        try {
            imageGate?.await()
        } catch (e: CancellationException) {
            cancelledFetches += url
            throw e
        }
        byteArrayOf(1, 2, 3)
    }
    private val videoCacheAsked: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var sessionReady = true

    /** What the video cache answers for every key: by default no video is fully cached. */
    private var videoCached = false

    /** Mock mode's clip (R3): every video item plays it, under its own `pk_position` key. */
    private val sampleClip = Uri.parse("android.resource://io.github.yuriimurha.reels/raw/sample_clip")

    /** The repository the container would build, here over the fake client and the real Conservative Pacer, in virtual time when [scope] is given. */
    private fun carouselRepository(
        scope: TestScope? = null,
        sampleClip: Uri? = null,
        fetcher: MediaFetcher = this.fetcher,
        starts: VideoStarts = VideoStarts.NONE,
    ): CarouselRepository {
        val clock = { START + (scope?.testScheduler?.currentTime ?: 0L) }
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore(), Random(1), now = clock)
        return CarouselRepository(
            db.carouselItemDao(), db.mediaDao(),
            PacedMediaInfo(client, pacer, SessionSignals.None, isSessionReady = { sessionReady }),
            fetcher, CarouselImageStore(File(tmp.root, "carousel")), pacer,
            isVideoCached = { key -> videoCacheAsked += key; videoCached },
            now = clock,
            sampleClip = sampleClip,
            starts = starts,
        )
    }

    /** The post, and (unless [withItems] is false) its items as an earlier sync stored them, video links still fresh. */
    private suspend fun storeCarousel(withItems: Boolean = true) {
        db.mediaDao().upsert(listOf(carousel))
        if (!withItems) return
        db.carouselItemDao().replace(
            pk,
            post.carouselItems!!.map {
                val stored = it.toEntity(pk).copy(imageUrl = "https://cdn.example.test/old/i${it.position}.jpg?oe=1")
                if (it.videoUrl == null) stored else stored.copy(videoUrl = "https://cdn.example.test/old/v${it.position}.mp4?oe=1", videoUrlExpiresAt = fresh)
            },
        )
    }

    /**
     * R23, R26: the pages whose picture the queue fetches ahead, in position order: every item but item 0, an image item's image
     * and a video item's poster alike (the fake's 1, 2, 3, 4, 5, ...).
     */
    private val ahead: List<Int> = post.carouselItems!!.filter { it.position > 0 }.map { it.position }

    /** The image items among [ahead] (the fake's 1, 3, 4, 6, ...); the others are its video items (2, 5, ...). */
    private val imagesAhead: List<Int> = post.carouselItems!!.filter { it.type == MediaType.IMAGE && it.position > 0 }.map { it.position }

    /** The video items past item 0 (the fake's 2, 5, ...): their posters are fetched ahead with the images (R26). */
    private val videosAhead: List<Int> = ahead - imagesAhead.toSet()

    /** True once the items are in and every one of [pages] shows its downloaded picture. */
    private fun CarouselUiState.shows(pages: List<Int>) = items.isNotEmpty() && pages.all { items.getOrNull(it)?.imagePath != null }

    /** Item [position]'s stored link, as [storeCarousel] stores it. */
    private fun old(position: Int) = "https://cdn.example.test/old/i$position.jpg?oe=1"

    /** Another fake carousel (a video at position 2 too), with its items stored: the post after [carousel] in some tests. */
    private val otherPost: RemoteMedia =
        fake.library.allSaved().first { it.type == MediaType.CAROUSEL && (it.carouselCount ?: 0) >= 3 && it.pk != post.pk }
    private val other: MediaEntity = mediaEntity(otherPost.pk, MediaType.CAROUSEL).copy(carouselCount = otherPost.carouselCount)

    private suspend fun storeOther() {
        db.mediaDao().upsert(listOf(other))
        db.carouselItemDao().replace(other.pk, otherPost.carouselItems!!.map { it.toEntity(other.pk) })
    }

    private fun item(position: Int, index: Int = 4) = SettledPage(index, carousel, position)

    /** Waits, in real time, for what the ViewModel does on Room's threads and in its own scope (virtual time runs meanwhile). */
    private suspend fun <T> eventually(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    private suspend fun ViewerViewModel.awaitCarousel(what: (CarouselUiState) -> Boolean): CarouselUiState =
        eventually { this.carousel.first { it != null && it.pk == pk && what(it.state) }!!.state }

    /** The player as the screen drives it: a settle stops it, a Play sets its item under its cache key, an Unavailable stops it. */
    private class PlayerHooks(val settled: MutableStateFlow<SettledPage>) {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val setMediaItems get() = events.filter { it.startsWith("set:") }.map { it.removePrefix("set:") }
        val uris = mutableListOf<Uri>()

        fun onSettled(page: SettledPage) {
            events += "stop"
        }

        fun onSource(page: SettledPage, source: VideoSource) {
            when (source) {
                is VideoSource.Play -> {
                    events += "set:${source.cacheKey}"
                    uris += source.uri
                }
                is VideoSource.Unavailable -> events += "stop"
            }
        }

        fun isStillSettled(unit: Playable) = settled.value.playable == unit
    }

    /** Follows [player]'s settled pages; [resolved] gets each page once its own resolve has returned (the prefetch hook). */
    private fun TestScope.following(
        viewModel: ViewerViewModel,
        player: PlayerHooks,
        resolved: MutableStateFlow<List<SettledPage>>,
        next: MediaEntity? = null,
    ): Job = launch {
        viewModel.followSettledPages(
            settled = player.settled,
            nextOf = { page ->
                resolved.update { it + page }
                next
            },
            isStillSettled = player::isStillSettled,
            onSettled = player::onSettled,
            onSource = player::onSource,
        )
    }

    /** The screen settles on a new post, [page], and waits until its first unit's resolve has returned (the prefetch hook ran). */
    private suspend fun settle(player: PlayerHooks, resolved: MutableStateFlow<List<SettledPage>>, page: SettledPage) {
        player.settled.value = page
        eventually { resolved.first { page in it } }
    }

    /**
     * The carousel's pager settles on [page]'s item, in the same post. Its settle runs at once (the player is stopped); an image
     * item resolves to nothing there and then (the fill is in), a video item is waited for until [played] (its key) is set.
     */
    private suspend fun TestScope.settleItem(player: PlayerHooks, page: SettledPage, played: String? = null) {
        val stops = player.events.count { it == "stop" }
        player.settled.value = page
        runCurrent()
        assertEquals(stops + 1, player.events.count { it == "stop" }, "the item settle stopped the player")
        if (played != null) eventually { while (player.events.lastOrNull() != "set:$played") delay(10) }
    }

    /** Review Focus 1, through the real repository in Mock mode: one setMediaItem per settled video item, an image stops the player. */
    @Test
    fun onlyTheSettledItemOfTheSettledPostPlays() = runTest {
        storeCarousel()
        val viewModel = viewModel(FakeVideoSourceResolver("io.github.yuriimurha.reels"), carouselRepository(this, sampleClip))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved)
        try {
            runCurrent() // the placeholder page the flow starts with
            settle(player, resolved, item(0))
            for (position in listOf(1, 2, 3, 5)) {
                viewModel.onItemSettled(pk, position) // what the carousel's pager reports
                settleItem(player, item(position), played = "${pk}_$position".takeIf { position % 3 == 2 })
            }

            assertEquals(
                listOf("stop", "stop", "stop", "stop", "set:${pk}_2", "stop", "stop", "set:${pk}_5"),
                player.events,
                "the first page (nothing), item 0, image 1, video 2 (set), image 3 (stopped, nothing set), video 5 (set)",
            )
            assertEquals(listOf(sampleClip, sampleClip), player.uris)
            assertEquals(5, viewModel.carousel.value!!.state.settled)
            assertEquals(emptyList(), mediaInfoCalls, "the stored items were used: no request")
        } finally {
            job.cancel()
        }
    }

    @Test
    fun leavingTheCarouselStopsItsVideo() = runTest {
        storeCarousel()
        val viewModel = viewModel(FakeVideoSourceResolver("io.github.yuriimurha.reels"), carouselRepository(this, sampleClip))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved)
        try {
            settle(player, resolved, item(0))
            viewModel.onItemSettled(pk, 2)
            settleItem(player, item(2), played = "${pk}_2")
            assertEquals("set:${pk}_2", player.events.last())

            settle(player, resolved, SettledPage(5, mediaEntity("i9", MediaType.IMAGE)))

            assertEquals("stop", player.events.last(), "the next post's settle stopped the item's video")
            assertEquals(listOf("${pk}_2"), player.setMediaItems, "and nothing else was set")
            assertNull(viewModel.carousel.value, "the carousel's visit ended with it")
        } finally {
            job.cancel()
        }
    }

    @Test
    fun returningToACarouselStartsAtItemZeroAndIsANewVisit() = runTest {
        storeCarousel()
        val viewModel = viewModel(carousels = carouselRepository(this))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        viewModel.onItemSettled(pk, 2)
        assertEquals(2, viewModel.carousel.value!!.state.settled)
        assertTrue(viewModel.recover(carousel, playbackFailure(403), position = 2) is VideoSource.Play)
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "the visit's one renewal")

        viewModel.onSettled(reel("m9", fresh))
        assertNull(viewModel.carousel.value)
        viewModel.onSettled(carousel)

        assertEquals(0, viewModel.carousel.value!!.state.settled, "shown again: item 0")
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        viewModel.onItemSettled(pk, 2)
        viewModel.recover(carousel, playbackFailure(403), position = 2)
        assertEquals(listOf("mediaInfo:$pk", "mediaInfo:$pk"), mediaInfoCalls, "a new visit has its own renewal")
    }

    @Test
    fun aRefusedCarouselVideoIsRenewedOnceThenUnavailable() = runTest {
        storeCarousel()
        val viewModel = viewModel(carousels = carouselRepository(this))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        viewModel.onItemSettled(pk, 2)

        val first = viewModel.recover(carousel, playbackFailure(403), position = 2)
        assertEquals(VideoSource.Play(Uri.parse("fake://video/$pk-2"), "${pk}_2"), first, "the renewed link, under the item's key")
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls)

        assertEquals(VideoSource.Unavailable("Can't load this item"), viewModel.recover(carousel, playbackFailure(403), position = 2))
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "no second request")
    }

    /**
     * The ViewModel's own rule, once per item per visit: the second refusal does not even ask the repository. Without a session
     * the repository looks for cached bytes (R9) on every forced call, which is how this test sees whether it was asked.
     */
    @Test
    fun aSecondRefusalOfTheSameItemDoesNotAskTheRepository() = runTest {
        storeCarousel()
        sessionReady = false
        val viewModel = viewModel(carousels = carouselRepository(this))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }

        assertEquals(
            VideoSource.Unavailable("Instagram session needs attention (Sync screen)"),
            viewModel.recover(carousel, playbackFailure(410), position = 2),
        )
        assertEquals(listOf("${pk}_2"), videoCacheAsked)

        assertEquals(VideoSource.Unavailable("Can't load this item"), viewModel.recover(carousel, playbackFailure(410), position = 2))
        assertEquals(listOf("${pk}_2"), videoCacheAsked, "the repository was not asked again")
        assertEquals(emptyList(), mediaInfoCalls)
    }

    @Test
    fun anyOtherErrorOnACarouselItemGivesUpWithoutAskingTheRepository() = runTest {
        storeCarousel()
        sessionReady = false
        val viewModel = viewModel(carousels = carouselRepository(this))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }

        assertEquals(VideoSource.Unavailable("Can't load this item"), viewModel.recover(carousel, playbackFailure(404), position = 2))
        assertEquals(emptyList(), videoCacheAsked)
        assertEquals(emptyList(), mediaInfoCalls)
    }

    /**
     * R16: offline, a video item the owner never watched through cannot play, and says so as an unviewed image does. The player
     * not reaching the CDN (no connection, or a timeout) is no link problem: nothing is renewed and nothing is sent.
     */
    @Test
    fun anUnreachableVideoItemThatIsNotCachedSaysItIsOffline() = runTest {
        storeCarousel()
        val viewModel = viewModel(carousels = carouselRepository(this))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        viewModel.onItemSettled(pk, 2)

        for (failure in listOf(networkFailure(), networkFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))) {
            assertEquals(VideoSource.Unavailable("Offline: this item isn't cached yet"), viewModel.recover(carousel, failure, position = 2))
        }
        assertEquals(listOf("${pk}_2", "${pk}_2"), videoCacheAsked, "the item's own cache key, each time")
        assertEquals(emptyList(), mediaInfoCalls, "no renewal")

        // Its renewal is untouched: a refused link still gets the visit's one renewal afterwards.
        assertTrue(viewModel.recover(carousel, playbackFailure(403), position = 2) is VideoSource.Play)
    }

    /** R16's other side: with all its bytes cached, a video item's failure is not the connection, and keeps the general copy. */
    @Test
    fun anUnreachableVideoItemWhoseBytesAreCachedCannotLoad() = runTest {
        storeCarousel()
        videoCached = true
        val viewModel = viewModel(carousels = carouselRepository(this))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }

        assertEquals(VideoSource.Unavailable("Can't load this item"), viewModel.recover(carousel, networkFailure(), position = 2))
        assertEquals(emptyList(), mediaInfoCalls)
    }

    /** R16 is for carousel items only: a reel that cannot reach the CDN keeps its own copy, and asks nothing. */
    @Test
    fun anUnreachableReelKeepsItsOwnCopy() = runTest {
        val viewModel = viewModel()
        val page = reel("m1", fresh)
        viewModel.onSettled(page)

        assertEquals(VideoSource.Unavailable("Can't play this video"), viewModel.recover(page, networkFailure()))
        assertEquals(emptyList(), videoCacheAsked)
    }

    /** A refused item's renewal must not queue behind the next post's prefetch in the interactive lane (R79, as for reels). */
    @Test
    fun aRefusedCarouselVideoCancelsThePrefetchForAnotherPost() = runTest {
        storeCarousel()
        val viewModel = viewModel(carousels = carouselRepository(this))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        resolver.hold("m2")
        viewModel.prefetch(reel("m2", expiresAt = expired))
        runCurrent()
        assertEquals(listOf("m2"), resolver.calls)

        viewModel.recover(carousel, playbackFailure(403), position = 2)
        runCurrent()

        assertEquals(listOf("m2"), resolver.cancelled)
        resolver.releaseAll()
    }

    /**
     * R8: one Visit per settled post, and its fill in a coroutine of the post's own: a re-settle of the same post (the list moved
     * under it) cancels the settle's wait, not the fill, and sends no second request. `(4, P)` then `(5, P)` is what the screen
     * reports for a list move: it never reports the post that slides into index 4 for an instant (C1, pinned by
     * `ViewerScreenCarouselTest.aPostSavedAboveTheSettledCarouselKeepsItsItemAndNeverPlaysTheNewPost`).
     */
    @Test
    fun theFillBelongsToThePostNotToTheSettleThatWaitsForIt() = runTest {
        storeCarousel(withItems = false)
        val reply = CompletableDeferred<Unit>()
        onReply = { reply.await(); it }
        val viewModel = viewModel(FakeVideoSourceResolver("io.github.yuriimurha.reels"), carouselRepository(this, sampleClip))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved)
        try {
            player.settled.value = item(0, index = 4)
            eventually { while (mediaInfoCalls.isEmpty()) delay(10) }
            assertEquals(true, viewModel.carousel.value!!.state.loading, "the cover shows, with the loading mark")
            player.settled.value = item(0, index = 5) // the same post, one place further down: its wait is cancelled
            runCurrent()

            reply.complete(Unit)
            val state = viewModel.awaitCarousel { it.items.isNotEmpty() }
            eventually { resolved.first { item(0, index = 5) in it } }

            assertEquals(post.carouselCount, state.items.size)
            assertEquals(false, state.loading)
            assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "one request for the post")
        } finally {
            reply.complete(Unit)
            job.cancel()
        }
    }

    /**
     * R8 for a video item: its link renewal (the visit's one request) runs in the post's scope, so swiping on while it is out
     * does not throw it away. Coming back to the item in the same visit plays the renewed link, with no second request.
     */
    @Test
    fun aVideoItemsRenewalOutlivesTheSwipeAway() = runTest {
        storeCarousel()
        db.carouselItemDao().replace(pk, db.carouselItemDao().items(pk).map { if (it.position == 2) it.copy(videoUrlExpiresAt = expired) else it })
        val reply = CompletableDeferred<Unit>()
        onReply = { reply.await(); it }
        val viewModel = viewModel(carousels = carouselRepository(this))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved)
        try {
            settle(player, resolved, item(0))
            viewModel.onItemSettled(pk, 2)
            player.settled.value = item(2)
            eventually { while (mediaInfoCalls.isEmpty()) delay(10) } // item 2's stale link is being renewed
            viewModel.onItemSettled(pk, 3)
            settleItem(player, item(3)) // swiped on before the answer
            reply.complete(Unit)
            eventually { while (db.carouselItemDao().items(pk)[2].videoUrl != "fake://video/$pk-2") delay(10) }

            viewModel.onItemSettled(pk, 2)
            settleItem(player, item(2), played = "${pk}_2")

            assertEquals("set:${pk}_2", player.events.last(), "the renewed link plays")
            assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "one request in the visit")
        } finally {
            reply.complete(Unit)
            job.cancel()
        }
    }

    /** R8 for images too: an item's download carries on after the owner swiped on, and lands in the state. */
    @Test
    fun anImageDownloadOutlivesTheSwipeAway() = runTest {
        storeCarousel()
        imageGate = CompletableDeferred()
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }

        viewModel.onItemSettled(pk, 1)
        eventually { while (fetched.isEmpty()) delay(10) }
        viewModel.onItemSettled(pk, 2)
        viewModel.onItemSettled(pk, 3)
        imageGate!!.complete(Unit)

        val state = viewModel.awaitCarousel { s -> s.items[1].imagePath != null && s.items[3].imagePath != null }
        assertTrue(state.items[1].imagePath!!.isNotEmpty())
        assertEquals(3, state.settled)
    }

    /** Leaving the post ends its visit: a download still waiting for the CDN lane (two at a time) is never sent. */
    @Test
    fun leavingThePostCancelsTheDownloadsItStillHasQueued() = runTest {
        storeCarousel()
        imageGate = CompletableDeferred()
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        for (position in listOf(1, 3)) viewModel.onItemSettled(pk, position)
        eventually { while (fetched.size < 2) delay(10) } // both lane permits are taken
        viewModel.onItemSettled(pk, 4)
        runCurrent()

        viewModel.onSettled(reel("m9", fresh))
        eventually { while (cancelledFetches.size < 2) delay(10) } // the two downloads out went with the visit
        imageGate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("https://cdn.example.test/old/i1.jpg?oe=1", "https://cdn.example.test/old/i3.jpg?oe=1"),
            fetched.toList().sorted(),
            "item 4's download was still queued: it went with the visit",
        )
    }

    /**
     * Item 0's image is the cover (the sync takes the cover from the first item, by the same rule): it is never downloaded.
     * Every other item's picture downloads once a visit, whether it settles or is fetched ahead (R23), however often it settles:
     * an image item's image, and a video item's poster (R26). A later visit finds the downloaded files on disk.
     */
    @Test
    fun imagesDownloadOnDemandOnceAVisitAndItemZeroUsesTheCover() = runTest {
        storeCarousel()
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }

        for (position in listOf(0, 1, 2, 1, 0, 1)) viewModel.onItemSettled(pk, position)
        val state = viewModel.awaitCarousel { it.shows(ahead) }

        assertEquals(ahead.map(::old).sorted(), fetched.toList().sorted(), "each item's picture once, a video item's poster too; item 0 never")
        assertNull(state.items[0].imagePath, "item 0 shows the cover")
        assertTrue(state.items[2].imagePath != null, "video item 2 has its poster (R26)")

        viewModel.onSettled(reel("m9", fresh))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        viewModel.onItemSettled(pk, 1)
        viewModel.awaitCarousel { it.shows(ahead) }
        assertEquals(ahead.size, fetched.size, "the next visit read them from disk")
    }

    @Test
    fun aCarouselWithoutACoverDownloadsItsFirstImage() = runTest {
        db.mediaDao().upsert(listOf(carousel.copy(thumbPath = null)))
        db.carouselItemDao().replace(pk, post.carouselItems!!.map { it.toEntity(pk) })
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))

        viewModel.onSettled(carousel.copy(thumbPath = null))

        viewModel.awaitCarousel { it.items.firstOrNull()?.imagePath != null }
        // The queue of R23 fetches the other pictures alongside; item 0 is the settled item's own download, and only once.
        assertEquals(1, fetched.count { it == "fake://thumb/$pk-0" }, "$fetched")
    }

    @Test
    fun anItemImageThatCannotLoadSaysSo() = runTest {
        storeCarousel()
        val failing = MediaFetcher { url -> fetched += url; null } // the CDN refuses it, and so does the renewed link
        val clock = { START + testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), InMemoryCooldownStore(), Random(1), now = clock)
        val repository = CarouselRepository(
            db.carouselItemDao(), db.mediaDao(), PacedMediaInfo(client, pacer, SessionSignals.None, isSessionReady = { true }),
            failing, CarouselImageStore(File(tmp.root, "carousel")), pacer, isVideoCached = { false }, now = clock,
        )
        val viewModel = viewModel(carousels = repository)
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }

        viewModel.onItemSettled(pk, 1)

        val state = viewModel.awaitCarousel { it.items[1].failure != null }
        assertEquals("Can't load this item", state.items[1].failure)
        assertNull(state.items[1].imagePath)
    }

    /** The fill failed: only the cover, "1/N" and why; nothing plays. */
    @Test
    fun aCarouselWhoseItemsCannotBeFilledShowsTheCoverAndSaysWhy() = runTest {
        storeCarousel(withItems = false)
        onReply = { null } // the post is gone from Instagram
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))

        viewModel.onSettled(carousel)

        val state = viewModel.awaitCarousel { !it.loading }
        assertEquals(CarouselUiState(coverOnlyMessage = "This item is no longer available on Instagram"), state)
        assertNull(viewModel.resolveVideo(carousel, position = 0))
    }

    @Test
    fun aCarouselItemResolvesThroughTheRepositoryAndAnImageItemToNothing() = runTest {
        storeCarousel()
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        viewModel.onSettled(carousel)

        assertNull(viewModel.resolveVideo(carousel, position = 1))
        assertEquals(VideoSource.Play(sampleClip, "${pk}_2"), viewModel.resolveVideo(carousel, position = 2))
        assertEquals(VideoSource.Play(sampleClip, "${pk}_5"), viewModel.resolveVideo(carousel, position = 5))
        assertEquals(emptyList(), resolver.calls, "a carousel item never goes through the reel resolver")
    }

    /** A carousel post is not a video: the reel resolver and its prefetch rule see it as today, and the next post is prefetched after the fill. */
    @Test
    fun theNextPostIsPrefetchedOnlyAfterTheCarouselsFill() = runTest {
        storeCarousel(withItems = false)
        val reply = CompletableDeferred<Unit>()
        onReply = { reply.await(); it }
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved, next = reel("m2", expiresAt = expired))
        try {
            player.settled.value = item(0)
            eventually { while (mediaInfoCalls.isEmpty()) delay(10) }
            runCurrent()
            assertEquals(emptyList(), resolver.calls, "the visible carousel's request goes first (R77)")

            reply.complete(Unit)
            eventually { resolved.first { item(0) in it } }
            runCurrent()
            assertEquals(listOf("m2"), resolver.calls)
        } finally {
            reply.complete(Unit)
            job.cancel()
        }
    }

    /**
     * Task 4 (parked): the owner swipes past the post's first item before its resolve returned (here item 0 waits for the fill).
     * That unit never reaches the prefetch hook, so the decision passes to the post's first unit whose resolve does return: the
     * next post's prefetch still goes out, once, after the fill (R11: still once a post).
     */
    @Test
    fun theNextPostIsPrefetchedWhenTheFirstItemWasSwipedPastBeforeItsResolveReturned() = runTest {
        storeCarousel(withItems = false)
        val reply = CompletableDeferred<Unit>()
        onReply = { reply.await(); it }
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved, next = reel("m2", expiresAt = expired))
        try {
            player.settled.value = item(0)
            eventually { while (mediaInfoCalls.isEmpty()) delay(10) }
            runCurrent()
            viewModel.onItemSettled(pk, 1)
            player.settled.value = item(1) // swiped on while item 0 still waits for the fill
            runCurrent()
            assertEquals(emptyList(), resolver.calls, "nothing is prefetched before the visible post's own request")

            reply.complete(Unit)
            eventually { resolved.first { item(1) in it } }
            runCurrent()
            assertEquals(listOf(item(1)), resolved.value, "item 0 never reached the hook; item 1, the first unit resolved, did")
            assertEquals(listOf("m2"), resolver.calls, "the next post's prefetch still went out, once")
            assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls)
        } finally {
            reply.complete(Unit)
            job.cancel()
        }
    }

    /** An item swipe is not a new post: it keeps the visit (no new request) and the prefetch the post started. */
    @Test
    fun anItemSwipeKeepsTheVisitAndTheNextPostsPrefetch() = runTest {
        storeCarousel()
        resolver.hold("m2")
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved, next = reel("m2", expiresAt = expired))
        try {
            settle(player, resolved, item(0))
            runCurrent()
            assertEquals(listOf("m2"), resolver.calls, "the next post's prefetch is out")

            viewModel.onItemSettled(pk, 1)
            settleItem(player, item(1))
            viewModel.onItemSettled(pk, 2)
            settleItem(player, item(2), played = "${pk}_2")

            assertEquals(emptyList(), resolver.cancelled, "the item swipes left it alone")
            assertEquals(listOf("m2"), resolver.calls, "and asked for nothing more")
            assertEquals("set:${pk}_2", player.events.last())
            assertEquals(emptyList(), mediaInfoCalls, "and the visit sent nothing")
        } finally {
            resolver.releaseAll()
            job.cancel()
        }
    }

    /**
     * R11: the next post's prefetch is decided once, on the post's first resolved unit. An item swipe is no new trigger: an owner
     * who dwells on a carousel until the next reel's link nears its end, then swipes an item, sends nothing for that reel.
     */
    @Test
    fun anItemSettleNeverPrefetchesTheNextPost() = runTest {
        storeCarousel()
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val next = reel("m2", expiresAt = now + RealVideoSourceResolver.FRESH_MARGIN_MS + 60_000)
        val job = following(viewModel, player, resolved, next)
        try {
            settle(player, resolved, item(0))
            runCurrent()
            assertEquals(emptyList(), resolver.calls, "at the post's settle the next link was still fresh: nothing to renew")

            now += 2 * 60_000 // the owner dwells: the next reel's link is now within the margin
            for (position in listOf(1, 2, 3)) {
                viewModel.onItemSettled(pk, position)
                settleItem(player, item(position), played = "${pk}_$position".takeIf { position == 2 })
                runCurrent()
            }
            // R23: the post's pictures were fetched ahead meanwhile, and that is no trigger either.
            viewModel.awaitCarousel { it.shows(ahead) }
            runCurrent()

            assertEquals(emptyList(), resolver.calls, "item swipes and the pictures fetched ahead sent no prefetch")
        } finally {
            job.cancel()
        }
    }

    // --- R8: one Visit a visit, whatever spends it ---

    /** The fill spent the visit's request: a 403 on a video item of the same visit sends no second one. */
    @Test
    fun aRefusedVideoItemAfterTheFillSendsNoSecondRequest() = runTest {
        storeCarousel(withItems = false)
        val viewModel = viewModel(carousels = carouselRepository(this))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "the fill")

        val answer = viewModel.recover(carousel, playbackFailure(403), position = 2)

        assertEquals(VideoSource.Unavailable("Can't load this item"), answer, "the fill's link is the one just refused")
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "no second request in the visit")
    }

    /** The fill spent the visit's request: a video item whose stored link has since gone stale plays the fill's link, no request. */
    @Test
    fun aStaleVideoItemAfterTheFillSendsNoSecondRequest() = runTest {
        storeCarousel(withItems = false)
        val viewModel = viewModel(carousels = carouselRepository(this))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        db.carouselItemDao().replace(pk, db.carouselItemDao().items(pk).map { if (it.position == 2) it.copy(videoUrlExpiresAt = expired) else it })

        val source = viewModel.resolveVideo(carousel, position = 2)

        assertEquals(VideoSource.Play(Uri.parse("fake://video/$pk-2"), "${pk}_2"), source)
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "no second request in the visit")
    }

    /**
     * The fill spent the visit's request: an image the CDN refuses is not renewed with a second one. And a link refused once is
     * never asked for again in the visit (R25): whichever of the queue and the item's own load meets it first, the other asks
     * the CDN nothing, and the queue stops there.
     */
    @Test
    fun aRefusedImageAfterTheFillSendsNoSecondRequest() = runTest {
        storeCarousel(withItems = false)
        val refusing = MediaFetcher { url ->
            fetched += url
            null
        }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = refusing))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }

        viewModel.onItemSettled(pk, 1)

        assertEquals("Can't load this item", viewModel.awaitCarousel { it.items[1].failure != null }.items[1].failure)
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "no second request in the visit")
        assertEquals(listOf("fake://thumb/$pk-1"), fetched.toList(), "item 1's link once in all, and nothing ahead after it")
    }

    // --- R23: a carousel's other pictures, fetched ahead one at a time; R25: one renewal at most; P1: what stops them ---

    /**
     * Opening a carousel fetches every item's picture after the cover, an image item's image and a video item's poster alike
     * (R26), in order, ONE download at a time, and asks Instagram nothing.
     */
    @Test
    fun openingACarouselFetchesItsOtherPicturesAheadOneAtATimeInOrder() = runTest {
        storeCarousel()
        val cdn = Cdn().apply { takes = 300 } // long enough that two downloads out together would overlap
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))

        viewModel.onSettled(carousel)

        val state = viewModel.awaitCarousel { it.shows(ahead) }
        assertEquals(ahead.map(::old), cdn.fetched.toList(), "every item's picture after the cover, in order, once")
        assertEquals(1, cdn.mostOut.get(), "one download at a time")
        assertNull(state.items[0].imagePath, "item 0 shows the cover: never fetched")
        assertTrue(videosAhead.isNotEmpty() && videosAhead.all { state.items[it].imagePath != null }, "every video item's poster (R26)")
        assertEquals(emptyList(), mediaInfoCalls)
    }

    /**
     * An item settle reorders what is left: the items after it first, then those before it. The download that is out carries on,
     * and nothing finished is fetched again; the settled item's own picture comes on the lane's other slot meanwhile.
     */
    @Test
    fun anItemSettleReordersWhatIsLeftWithoutCancellingTheDownloadThatIsOut() = runTest {
        storeCarousel()
        val cdn = Cdn().apply { hold(old(1)) }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        viewModel.onSettled(carousel)
        eventually { while (cdn.fetched.isEmpty()) delay(10) }
        assertEquals(listOf(old(1)), cdn.fetched.toList(), "the queue starts with the item after the settled one")

        viewModel.onItemSettled(pk, 4)
        viewModel.awaitCarousel { it.items[4].imagePath != null } // its own download, while item 1's is still out
        cdn.release(old(1))
        viewModel.awaitCarousel { it.shows(ahead) }

        val queued = listOf(1) + ahead.filter { it > 4 } + ahead.filter { it in 2 until 4 }.reversed()
        assertEquals(queued.map(::old), cdn.fetched.filter { it != old(4) }, "item 1, then the items after 4, then 3")
        assertEquals(1, cdn.fetched.count { it == old(4) }, "item 4 once, by its own load")
        assertEquals(emptyList(), cdn.cancelled.toList(), "item 1's download was not cancelled")
    }

    /** F3: settled on the last item while the queue's first download is out: what is left comes nearest first, every one. */
    @Test
    fun earlierItemsComeNearestFirst() = runTest {
        storeCarousel()
        val cdn = Cdn().apply { hold(old(1)) }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        try {
            viewModel.onSettled(carousel)
            eventually { while (cdn.fetched.isEmpty()) delay(10) }
            val last = post.carouselItems!!.maxOf { it.position }

            viewModel.onItemSettled(pk, last)
            cdn.release(old(1))
            viewModel.awaitCarousel { it.shows(ahead) }

            val earlier = ahead.filter { it in 2 until last }.reversed()
            assertTrue(earlier.size >= 2, "precondition: at least two earlier pictures left, $earlier")
            assertEquals((listOf(1) + earlier).map(::old), cdn.fetched.filter { it != old(last) })
        } finally {
            cdn.releaseAll()
        }
    }

    /** Leaving the post cancels the queue with the visit (R8): the download out is cancelled, nothing more is fetched or kept. */
    @Test
    fun leavingThePostCancelsThePicturesFetchedAhead() = runTest {
        storeCarousel()
        val cdn = Cdn().apply { hold(old(1)) }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        viewModel.onSettled(carousel)
        eventually { while (cdn.fetched.isEmpty()) delay(10) }

        viewModel.onSettled(reel("m9", fresh))
        eventually { while (cdn.cancelled.isEmpty()) delay(10) }
        cdn.releaseAll()
        advanceUntilIdle()

        assertEquals(listOf(old(1)), cdn.cancelled.toList())
        assertEquals(listOf(old(1)), cdn.fetched.toList(), "nothing after it")
        assertNull(CarouselImageStore(File(tmp.root, "carousel")).path(pk, 1), "and no file")
    }

    /**
     * R25: the stored links are old (as after a few days), so the first picture ahead is refused. That spends the visit's one
     * renewal as the post opens, and the queue goes on with the fresh links: every picture loads, with one request in all,
     * and no failure shows.
     */
    @Test
    fun aRefusedPictureAheadRenewsOnceAndTheQueueGoesOnWithTheFreshLinks() = runTest {
        storeCarousel()
        val cdn = Cdn { url -> if (url.startsWith("fake://")) byteArrayOf(1, 2, 3) else null } // only renewed links load
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))

        viewModel.onSettled(carousel)

        val state = viewModel.awaitCarousel { it.shows(ahead) }
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "the visit's one request, as the post opened")
        assertEquals(listOf(old(1)) + ahead.map { "fake://thumb/$pk-$it" }, cdn.fetched.toList(), "one refusal, then the fresh links")
        assertTrue(state.items.all { it.failure == null })

        viewModel.onItemSettled(pk, 1)
        runCurrent()
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "the item on screen shows its picture: nothing more")
    }

    /**
     * R25 with R20: the queue's renewal brings another shape, in which some items are videos, while the pager keeps the shape it
     * was shown with (every item an image here). The queue passes each such page once and goes on: every item that is an
     * image in the new shape loads, and no video's picture is fetched.
     */
    @Test
    fun aPageTheRenewalMadeAVideoIsPassedOnceAndTheQueueGoesOn() = runTest {
        db.mediaDao().upsert(listOf(carousel))
        db.carouselItemDao().replace(
            pk,
            post.carouselItems!!.map { it.toEntity(pk).copy(type = MediaType.IMAGE, imageUrl = old(it.position), videoUrl = null, videoUrlExpiresAt = null) },
        )
        val cdn = Cdn { url -> if (url.startsWith("fake://")) byteArrayOf(1, 2, 3) else null } // only renewed links load
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))

        viewModel.onSettled(carousel)

        viewModel.awaitCarousel { it.shows(imagesAhead) }
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls)
        val videos = post.carouselItems!!.filter { it.type == MediaType.VIDEO }.map { it.position }
        assertTrue(videos.isNotEmpty(), "precondition: the new shape has videos")
        assertTrue(cdn.fetched.none { url -> videos.any { url == "fake://thumb/$pk-$it" || url == old(it) } }, "no video's picture: ${cdn.fetched}")
    }

    /**
     * R25: the visit's renewal is spent already (here by the fill), so the first refusal ahead stops the queue; the item's own
     * load later asks the CDN nothing for that refused link, and Instagram nothing more.
     */
    @Test
    fun aSpentRenewalStopsThePicturesFetchedAhead() = runTest {
        storeCarousel(withItems = false)
        val cdn = Cdn { url -> if (url == "fake://thumb/$pk-3") null else byteArrayOf(1, 2, 3) }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        viewModel.onSettled(carousel)
        eventually { while ("fake://thumb/$pk-3" !in cdn.fetched) delay(10) }
        // A queue that went on would ask for the next picture within this (the lane's jitter is under a second).
        eventually { withTimeoutOrNull(1_000) { while (cdn.fetched.size == 3) delay(10) } }

        assertEquals(listOf("fake://thumb/$pk-1", "fake://thumb/$pk-2", "fake://thumb/$pk-3"), cdn.fetched.toList(), "nothing after the refusal")
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "the fill's, no other")

        viewModel.onItemSettled(pk, 3)

        assertEquals("Can't load this item", viewModel.awaitCarousel { it.items[3].failure != null }.items[3].failure)
        assertEquals(listOf("fake://thumb/$pk-1", "fake://thumb/$pk-2", "fake://thumb/$pk-3"), cdn.fetched.toList(), "item 3's refused link not asked again")
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls)
    }

    /**
     * The hard bound under concurrent refusals: the queue's first link is refused and the renewal it asked for is out when the
     * owner settles on item 3, whose link is refused too. Both share the visit's one renewal: one `mediaInfo` in all, and every
     * picture loads from the fresh links.
     */
    @Test
    fun theQueueAndTheItemOnScreenRefusedTogetherSendOneRequest() = runTest {
        storeCarousel()
        val reply = CompletableDeferred<Unit>()
        onReply = { reply.await(); it }
        val cdn = Cdn { url -> if (url.startsWith("fake://")) byteArrayOf(1, 2, 3) else null }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        try {
            viewModel.onSettled(carousel)
            eventually { while (mediaInfoCalls.isEmpty()) delay(10) } // the queue's renewal is out
            viewModel.onItemSettled(pk, 3)
            eventually { while (old(3) !in cdn.fetched) delay(10) } // item 3's own link is refused meanwhile
            reply.complete(Unit)

            val state = viewModel.awaitCarousel { it.shows(ahead) }
            assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls)
            assertEquals(1, cdn.fetched.count { it == old(3) })
            assertTrue(state.items.all { it.failure == null })
        } finally {
            reply.complete(Unit)
        }
    }

    /** P1: the CDN says 429 to the first picture ahead: nothing more is fetched ahead in the visit, and Instagram is not asked. */
    @Test
    fun aCdn429StopsThePicturesFetchedAhead() = runTest {
        storeCarousel()
        val cdn = Cdn { throw CdnRateLimited() }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        viewModel.onSettled(carousel)
        eventually { while (cdn.fetched.isEmpty()) delay(10) }
        eventually { withTimeoutOrNull(1_000) { while (cdn.fetched.size == 1) delay(10) } }

        assertEquals(listOf(old(1)), cdn.fetched.toList(), "no request after the 429")
        assertEquals(emptyList(), mediaInfoCalls)
        assertTrue(viewModel.carousel.value!!.state.items.all { it.failure == null }, "and nothing shows it")
    }

    /** P1: offline, the first picture ahead fails and the queue stops, instead of trying every other item one by one. */
    @Test
    fun beingOfflineStopsThePicturesFetchedAhead() = runTest {
        storeCarousel()
        val cdn = Cdn { throw IOException("no route") }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        viewModel.onSettled(carousel)
        eventually { while (cdn.fetched.isEmpty()) delay(10) }
        eventually { withTimeoutOrNull(1_000) { while (cdn.fetched.size == 1) delay(10) } }

        assertEquals(listOf(old(1)), cdn.fetched.toList(), "no request after the first failure")
        assertEquals(emptyList(), mediaInfoCalls)
    }

    /**
     * The owner settles on item 4 while the queue's first download is out, and item 4's own load fails (its link and the renewed
     * one are both refused), then swipes back to item 3, which puts item 4 first in what is left. The queue does not try item 4
     * again: the item says why, once a visit.
     */
    @Test
    fun anItemWhoseOwnLoadFailedIsNotFetchedAheadAgain() = runTest {
        storeCarousel()
        val cdn = Cdn { url -> if (url == old(4) || url == "fake://thumb/$pk-4") null else byteArrayOf(1, 2, 3) }.apply { hold(old(1)) }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        viewModel.onSettled(carousel)
        eventually { while (cdn.fetched.isEmpty()) delay(10) }

        viewModel.onItemSettled(pk, 4)
        assertEquals("Can't load this item", viewModel.awaitCarousel { it.items[4].failure != null }.items[4].failure)
        viewModel.onItemSettled(pk, 3)
        cdn.release(old(1))
        viewModel.awaitCarousel { it.shows(ahead - 4) }

        assertEquals(listOf(old(4), "fake://thumb/$pk-4"), cdn.fetched.filter { it == old(4) || it.endsWith("-4") }, "item 4's own tries only")
        assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "its own renewal, the visit's one request")
    }

    /**
     * F1, once a visit: item 4's own load fails (the CDN's 429, which renews nothing), and settling on it again in the same visit
     * does not download it again: the item says why until the post is shown again.
     */
    @Test
    fun aFailedItemSettledAgainIsNotDownloadedAgain() = runTest {
        storeCarousel()
        val cdn = Cdn { url -> if (url == old(4)) throw CdnRateLimited() else byteArrayOf(1, 2, 3) }.apply { hold(old(1)) }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        try {
            viewModel.onSettled(carousel)
            eventually { while (cdn.fetched.isEmpty()) delay(10) } // the queue's first download, held
            viewModel.onItemSettled(pk, 4)
            assertEquals("Can't load this item", viewModel.awaitCarousel { it.items[4].failure != null }.items[4].failure)

            viewModel.onItemSettled(pk, 3)
            viewModel.onItemSettled(pk, 4)
            runCurrent()
            eventually { withTimeoutOrNull(1_000) { while (cdn.fetched.count { it == old(4) } == 1) delay(10) } }

            assertEquals(1, cdn.fetched.count { it == old(4) }, "no new download for the failed item")
        } finally {
            cdn.releaseAll()
        }
    }

    /**
     * The queue has one download out, held: the item the owner settles on still loads, on the lane's other slot, without waiting
     * for it (two downloads ahead at once would take both slots and keep the item on screen black).
     */
    @Test
    fun theItemOnScreenIsNotStuckBehindThePicturesFetchedAhead() = runTest {
        storeCarousel()
        val cdn = Cdn().apply { ahead.filter { it != 4 }.forEach { hold(old(it)) } }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        try {
            viewModel.onSettled(carousel)
            eventually { while (cdn.fetched.isEmpty()) delay(10) }

            viewModel.onItemSettled(pk, 4)

            assertTrue(viewModel.awaitCarousel { it.items[4].imagePath != null }.items[4].imagePath!!.isNotEmpty())
            assertEquals(listOf(old(1), old(4)), cdn.fetched.toList(), "item 1's download is still out")
        } finally {
            cdn.releaseAll()
        }
    }

    /** F5: another post's item settle loads nothing in this visit, while this post's pictures are not all shown yet. */
    @Test
    fun anotherPostsItemSettleLoadsNothingHere() = runTest {
        storeCarousel()
        storeOther()
        val cdn = Cdn().apply { hold(old(1)) }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        try {
            viewModel.onSettled(carousel)
            eventually { while (cdn.fetched.isEmpty()) delay(10) }

            viewModel.onItemSettled(other.pk, 3)
            runCurrent()
            eventually { withTimeoutOrNull(1_000) { while (cdn.fetched.size < 2) delay(10) } }

            assertEquals(0, viewModel.carousel.value!!.state.settled)
            assertEquals(listOf(old(1)), cdn.fetched.toList(), "only the queue's own download")
        } finally {
            cdn.releaseAll()
        }
    }

    // --- R26: video items' posters with the pictures, then their starts, one at a time; never a request of their own ---

    private fun start(position: Int) = "${pk}_$position"

    /**
     * Opening a carousel fetches every picture first, its video items' posters among them, then writes the start of every video
     * item, in the same order: ONE download or write out at a time, and no request to Instagram.
     */
    @Test
    fun theQueueFetchesThePicturesAndPostersFirstThenTheVideoStartsOneAtATime() = runTest {
        storeCarousel()
        val cdn = Cdn().apply { takes = 300 } // long enough that two out together would overlap
        val starts = Starts(cdn).apply { takes = 300 }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))

        viewModel.onSettled(carousel)

        eventually { while (starts.written.size < videosAhead.size) delay(10) }
        eventually { withTimeoutOrNull(1_000) { while (starts.written.size == videosAhead.size) delay(10) } }
        assertTrue(videosAhead.size >= 2, "precondition: at least two video items, $videosAhead")
        assertEquals(videosAhead.map(::start), starts.written.toList(), "every video item's start, in order, once")
        assertEquals(ahead.size, starts.picturesBeforeFirstStart, "every picture and poster came before the first start")
        assertEquals(ahead.map(::old), cdn.fetched.toList())
        assertEquals(1, cdn.mostOut.get(), "one download or write at a time")
        assertEquals(emptyList(), mediaInfoCalls)
    }

    /**
     * The starts follow the settled item as the pictures do: the video items after it in order, then those before it, the
     * nearest first; never the settled one, which the player is reading.
     */
    @Test
    fun theVideoStartsFollowTheSettledItemAndPassIt() = runTest {
        storeCarousel()
        val cdn = Cdn().apply { hold(old(1)) }
        val starts = Starts(cdn)
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))
        try {
            viewModel.onSettled(carousel)
            eventually { while (cdn.fetched.isEmpty()) delay(10) }
            val settled = videosAhead[1]
            viewModel.onItemSettled(pk, settled)
            cdn.release(old(1))

            val expected = videosAhead.filter { it > settled } + videosAhead.filter { it < settled }.reversed()
            eventually { while (starts.written.size < expected.size) delay(10) }
            eventually { withTimeoutOrNull(1_000) { while (starts.written.size == expected.size) delay(10) } }
            assertEquals(expected.map(::start), starts.written.toList(), "after $settled in order, then before it; never $settled")
        } finally {
            cdn.releaseAll()
        }
    }

    /**
     * The owner settles on the video item whose start is being written: the write stops BEFORE the player is handed the item,
     * so the player never meets the write's lock on the cache, and the queue goes on with the next start.
     */
    @Test
    fun aStartTheOwnerSettlesOnGivesWayToThePlayerAndTheQueueGoesOn() = runTest {
        storeCarousel()
        val cdn = Cdn()
        val starts = Starts(cdn).apply {
            hold(start(2))
            stopTakesMs = 100 // a write stops only at its next read: the player must wait for that, not just for the cancel
        }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        starts.onCancelled = { player.events += "gave way:$it" }
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved)
        try {
            runCurrent()
            settle(player, resolved, item(0))
            eventually { while (start(2) !in starts.written) delay(10) }

            viewModel.onItemSettled(pk, 2)
            settleItem(player, item(2), played = start(2))

            val events = player.events.toList()
            assertTrue(events.indexOf("gave way:${start(2)}") in 0 until events.indexOf("set:${start(2)}"), "the write stopped first: $events")
            eventually { while (starts.written.size < videosAhead.size) delay(10) }
            assertEquals(videosAhead.map(::start), starts.written.toList(), "and the queue went on")
            assertEquals(listOf(start(2)), starts.cancelled.toList())
        } finally {
            starts.releaseAll()
            job.cancel()
        }
    }

    /**
     * M5: a start that does not stop at once (here 3 s, as a connection that stalls might) holds the player up for no more
     * than a moment: the item gets the player within about 300 ms, before the write has stopped, and the queue still takes
     * the next start only once it has.
     */
    @Test
    fun aStartThatIsSlowToStopHoldsThePlayerUpForAMomentOnly() = runTest {
        storeCarousel()
        val cdn = Cdn()
        val starts = Starts(cdn).apply {
            hold(start(2))
            stopTakesMs = 3_000
        }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        starts.onCancelled = { player.events += "gave way:$it" }
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved)
        try {
            runCurrent()
            settle(player, resolved, item(0))
            eventually { while (start(2) !in starts.written) delay(10) }

            viewModel.onItemSettled(pk, 2)
            val waited = measureTimeMillis { settleItem(player, item(2), played = start(2)) }

            assertTrue(waited < 1_500, "the player waited $waited ms for a write that would not stop")
            assertTrue("gave way:${start(2)}" !in player.events, "the player did not wait for the write to stop: ${player.events}")
            eventually { while (starts.written.size < videosAhead.size) delay(10) }
            assertEquals(listOf(start(2)), starts.cancelled.toList(), "the next start came only once that write had stopped")
        } finally {
            starts.releaseAll()
            job.cancel()
        }
    }

    /**
     * Item 0 has no picture to fetch (the cover is its picture), but a video item 0 has a start like any other: written ahead
     * once the owner is on another item, after the items after that one.
     */
    @Test
    fun aVideoItemZerosStartIsWrittenWhenTheOwnerIsElsewhere() = runTest {
        db.mediaDao().upsert(listOf(carousel))
        db.carouselItemDao().replace(
            pk,
            listOf(MediaType.VIDEO, MediaType.IMAGE, MediaType.VIDEO).mapIndexed { position, type ->
                CarouselItemEntity(
                    pk, position, type, 1080, 1350, old(position),
                    if (type == MediaType.VIDEO) "https://cdn.example.test/old/v$position.mp4?oe=1" else null,
                    if (type == MediaType.VIDEO) fresh else null,
                )
            },
        )
        val cdn = Cdn().apply { hold(old(1)) }
        val starts = Starts(cdn)
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))
        try {
            viewModel.onSettled(carousel)
            eventually { while (cdn.fetched.isEmpty()) delay(10) }
            viewModel.onItemSettled(pk, 1)
            cdn.release(old(1))

            eventually { while (starts.written.size < 2) delay(10) }
            assertEquals(listOf(start(2), start(0)), starts.written.toList(), "item 2 after the settled item, then item 0 before it")
            assertEquals(listOf(old(1), old(2)), cdn.fetched.toList(), "no picture fetched for item 0: the cover")
        } finally {
            cdn.releaseAll()
        }
    }

    /** Leaving the post cancels the start being written, with the visit (R8); nothing more is written. */
    @Test
    fun leavingThePostCancelsTheStartBeingWritten() = runTest {
        storeCarousel()
        val cdn = Cdn()
        val starts = Starts(cdn).apply { hold(start(2)) }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))
        viewModel.onSettled(carousel)
        eventually { while (starts.written.isEmpty()) delay(10) }

        viewModel.onSettled(reel("m9", fresh))
        eventually { while (starts.cancelled.isEmpty()) delay(10) }
        starts.releaseAll()
        advanceUntilIdle()

        assertEquals(listOf(start(2)), starts.cancelled.toList())
        assertEquals(listOf(start(2)), starts.written.toList(), "nothing after it")
    }

    /** P1: the CDN's 429, or no connection, on a start ends the queue for the visit; Instagram is not asked. */
    @Test
    fun aLimitedOrOfflineStartStopsTheQueue() = runTest {
        storeCarousel()
        assertTrue(videosAhead.size >= 2, "precondition: a start after the first, $videosAhead")
        for (outcome in listOf(StartWrite.LIMITED, StartWrite.OFFLINE)) {
            val cdn = Cdn()
            val starts = Starts(cdn) { outcome }
            val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))

            viewModel.onSettled(carousel)

            eventually { while (starts.written.isEmpty()) delay(10) }
            eventually { withTimeoutOrNull(1_000) { while (starts.written.size == 1) delay(10) } }
            assertEquals(listOf(start(videosAhead.first())), starts.written.toList(), "$outcome: no start after it")
        }
        assertEquals(emptyList(), mediaInfoCalls)
    }

    /** P1: a queue its pictures stopped (the CDN's 429) writes no start either. */
    @Test
    fun aQueueStoppedOnItsPicturesWritesNoStart() = runTest {
        storeCarousel()
        val cdn = Cdn { throw CdnRateLimited() }
        val starts = Starts(cdn)
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))

        viewModel.onSettled(carousel)

        eventually { while (cdn.fetched.isEmpty()) delay(10) }
        eventually { withTimeoutOrNull(1_000) { while (starts.written.isEmpty()) delay(10) } }
        assertEquals(emptyList(), starts.written.toList())
    }

    /**
     * A start the CDN refuses as running out (403, 410) is passed quietly and the queue goes on; nothing is renewed for it.
     * When the owner then settles on that item, it does not ask the refused link again: its own play takes the visit's one
     * renewal (R27: as after the player's own 403 or 410) and plays the new link. Settling on it again in the visit asks
     * nothing more, and the queue never writes it again.
     */
    @Test
    fun anExpiredStartIsPassedAndItsItemPlaysARenewedLinkWithTheVisitsOneRequest() = runTest {
        storeCarousel()
        val cdn = Cdn()
        val starts = Starts(cdn) { key -> if (key == start(2)) StartWrite.EXPIRED else StartWrite.WRITTEN }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved)
        try {
            runCurrent()
            settle(player, resolved, item(0))
            eventually { while (starts.written.size < videosAhead.size) delay(10) }
            assertEquals(videosAhead.map(::start), starts.written.toList(), "passed, and the queue went on")
            assertEquals(emptyList(), mediaInfoCalls, "no renewal for a start ahead")

            viewModel.onItemSettled(pk, 2)
            settleItem(player, item(2), played = start(2))
            assertEquals(Uri.parse("fake://video/$pk-2"), player.uris.last(), "a renewed link, never the refused one")
            assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls)

            viewModel.onItemSettled(pk, 1)
            settleItem(player, item(1))
            viewModel.onItemSettled(pk, 2)
            settleItem(player, item(2), played = start(2))
            assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "still the visit's one request")
            assertEquals(videosAhead.size, starts.written.size, "the refused start was never written again")
        } finally {
            job.cancel()
        }
    }

    /**
     * R27: a start refused with another 4xx (a 404) is passed, and the item's own play is the player's: its stored link, no
     * renewal. A pre-load never changes what a play costs.
     */
    @Test
    fun aStartRefusedOtherwiseLeavesThePlayToThePlayer() = runTest {
        storeCarousel()
        val cdn = Cdn()
        val starts = Starts(cdn) { key -> if (key == start(2)) StartWrite.REFUSED else StartWrite.WRITTEN }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved)
        try {
            runCurrent()
            settle(player, resolved, item(0))
            eventually { while (starts.written.size < videosAhead.size) delay(10) }

            viewModel.onItemSettled(pk, 2)
            settleItem(player, item(2), played = start(2))

            assertEquals(Uri.parse("https://cdn.example.test/old/v2.mp4?oe=1"), player.uris.last(), "the stored link")
            assertEquals(emptyList(), mediaInfoCalls, "no mediaInfo")
        } finally {
            job.cancel()
        }
    }

    /**
     * M4: a video item with no preview frame has no poster: the queue passes it without a renewal and goes on, so the video
     * starts are still written, and the visit sends no `mediaInfo`.
     */
    @Test
    fun aVideoItemWithNoPosterLinkStillGetsItsStartAndSpendsNoRequest() = runTest {
        storeCarousel()
        db.carouselItemDao().replace(pk, db.carouselItemDao().items(pk).map { if (it.position == 2) it.copy(imageUrl = "") else it })
        val cdn = Cdn()
        val starts = Starts(cdn)
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))

        viewModel.onSettled(carousel)

        eventually { while (starts.written.size < videosAhead.size) delay(10) }
        assertEquals(videosAhead.map(::start), starts.written.toList())
        assertEquals(emptyList(), mediaInfoCalls)
        assertTrue(cdn.fetched.none { it.endsWith("-2") || it == old(2) }, "no poster asked for item 2: ${cdn.fetched}")
    }

    /** The earlier video items' starts come nearest first, as the earlier pictures do. */
    @Test
    fun earlierStartsComeNearestFirst() = runTest {
        db.mediaDao().upsert(listOf(carousel))
        db.carouselItemDao().replace(
            pk,
            listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.VIDEO, MediaType.VIDEO, MediaType.IMAGE).mapIndexed { position, type ->
                CarouselItemEntity(
                    pk, position, type, 1080, 1350, old(position),
                    if (type == MediaType.VIDEO) "https://cdn.example.test/old/v$position.mp4?oe=1" else null,
                    if (type == MediaType.VIDEO) fresh else null,
                )
            },
        )
        val cdn = Cdn().apply { hold(old(1)) }
        val starts = Starts(cdn)
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))
        try {
            viewModel.onSettled(carousel)
            eventually { while (cdn.fetched.isEmpty()) delay(10) }
            viewModel.onItemSettled(pk, 4)
            cdn.release(old(1))

            eventually { while (starts.written.size < 3) delay(10) }
            assertEquals(listOf(start(3), start(2), start(1)), starts.written.toList(), "nearest first")
        } finally {
            cdn.releaseAll()
        }
    }

    /**
     * A video item's own load fetches its poster when the queue has not yet (its download out elsewhere); a poster that cannot
     * come (refused, and refused again after the visit's renewal) says nothing on the item: its video speaks for it.
     */
    @Test
    fun aVideoItemsOwnLoadFetchesItsPosterAndOneThatCannotComeSaysNothing() = runTest {
        storeCarousel()
        val second = videosAhead[1]
        val cdn = Cdn { url -> if (url == old(second) || url == "fake://thumb/$pk-$second") null else byteArrayOf(1, 2, 3) }.apply { hold(old(1)) }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn))
        try {
            viewModel.onSettled(carousel)
            eventually { while (cdn.fetched.isEmpty()) delay(10) } // the queue's first download, held

            viewModel.onItemSettled(pk, 2)
            assertTrue(viewModel.awaitCarousel { it.items[2].imagePath != null }.items[2].imagePath!!.isNotEmpty())
            assertEquals(1, cdn.fetched.count { it == old(2) }, "video item 2's poster, by its own load")

            viewModel.onItemSettled(pk, second)
            eventually { while ("fake://thumb/$pk-$second" !in cdn.fetched) delay(10) }
            eventually { withTimeoutOrNull(1_000) { while (viewModel.carousel.value!!.state.items[second].failure == null) delay(10) } }
            val state = viewModel.carousel.value!!.state
            assertNull(state.items[second].failure, "no failure for a poster")
            assertNull(state.items[second].imagePath)
        } finally {
            cdn.releaseAll()
        }
    }

    /**
     * The hard bound with every R26 path in play: old picture links (refused, then renewed), every start refused, each video
     * item played and a player refusal recovered. One `mediaInfo` in the visit.
     */
    @Test
    fun everyPathOfAVisitSendsOneMediaInfo() = runTest {
        storeCarousel()
        val cdn = Cdn { url -> if (url.startsWith("fake://")) byteArrayOf(1, 2, 3) else null } // only renewed links load
        val starts = Starts(cdn) { StartWrite.EXPIRED }
        val viewModel = viewModel(carousels = carouselRepository(this, fetcher = cdn, starts = starts))
        val player = PlayerHooks(MutableStateFlow(SettledPage(0, null)))
        val resolved = MutableStateFlow(emptyList<SettledPage>())
        val job = following(viewModel, player, resolved)
        try {
            runCurrent()
            settle(player, resolved, item(0))
            viewModel.awaitCarousel { it.shows(ahead) }
            eventually { while (starts.written.size < videosAhead.size) delay(10) }

            for (position in videosAhead) {
                val stops = player.events.count { it == "stop" }
                viewModel.onItemSettled(pk, position)
                settleItem(player, item(position))
                // The settle's stop, then the item's own: its refused link is not asked again, and the spent renewal brings no other.
                eventually { while (player.events.count { it == "stop" } < stops + 2) delay(10) }
            }
            viewModel.recover(carousel, playbackFailure(403), position = videosAhead.last())

            assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls)
        } finally {
            job.cancel()
        }
    }

    // --- nothing of one post reaches another ---

    /** A neighbouring carousel's pager (composed off screen) reports its page: the settled post's item and images are untouched. */
    @Test
    fun anItemSettleOfAnotherPostIsIgnored() = runTest {
        storeCarousel()
        storeOther()
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        viewModel.onItemSettled(pk, 2)
        // R23: the settled post fetches its own pictures ahead; let that finish, so what follows is the other post's doing alone.
        viewModel.awaitCarousel { it.shows(ahead) }
        val before = fetched.toList()

        viewModel.onItemSettled(other.pk, 0)
        viewModel.onItemSettled(other.pk, 1)
        runCurrent()

        assertEquals(pk, viewModel.carousel.value!!.pk)
        assertEquals(2, viewModel.carousel.value!!.state.settled)
        assertEquals(before, fetched.toList(), "no image fetched for a post nobody settled on")
        assertTrue(fetched.none { other.pk in it }, "$fetched")
    }

    /** A unit of a carousel that is not the settled post resolves to nothing and recovers nothing: never through this visit. */
    @Test
    fun aCarouselThatIsNotTheSettledPostGetsNothingFromTheVisit() = runTest {
        storeCarousel()
        storeOther()
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }

        assertNull(viewModel.resolveVideo(other, position = 2))
        assertEquals(VideoSource.Unavailable("Can't load this item"), viewModel.recover(other, playbackFailure(403), position = 2))
        assertEquals(emptyList(), mediaInfoCalls)
    }

    /** Review Focus 1: from carousel A on its item 2 straight to carousel B, B's visit starts at item 0, not at A's item. */
    @Test
    fun aNewVisitStartsAtItemZeroWhateverThePreviousCarouselWasOn() = runTest {
        storeCarousel()
        storeOther()
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        viewModel.onSettled(carousel)
        viewModel.awaitCarousel { it.items.isNotEmpty() }
        viewModel.onItemSettled(pk, 2)

        viewModel.onSettled(other)

        assertEquals(SettledCarousel(other.pk, CarouselUiState(loading = true)), viewModel.carousel.value)
    }

    /**
     * The same post settling again in place (the list moved under it; the screen reports that as the same post at its new index
     * only, C1) keeps its visit (no second request) but takes the newer row, which the visit's later calls use. Here the newer row has no cover, so item 0's own image is fetched once the fill is in;
     * with the first row kept, the cover rule would skip it.
     */
    @Test
    fun aReSettleInPlaceKeepsTheVisitAndTakesTheNewerRow() = runTest {
        db.mediaDao().upsert(listOf(carousel))
        val reply = CompletableDeferred<Unit>()
        onReply = { reply.await(); it }
        val viewModel = viewModel(carousels = carouselRepository(this, sampleClip))
        try {
            viewModel.onSettled(carousel)
            eventually { while (mediaInfoCalls.isEmpty()) delay(10) }

            viewModel.onSettled(carousel.copy(thumbPath = null)) // the same post, its row read again
            reply.complete(Unit)

            viewModel.awaitCarousel { it.items.firstOrNull()?.imagePath != null }
            // The queue of R23 never takes item 0, so only the settled item's own download, by the newer row's rule, fetched it.
            assertEquals(1, fetched.count { it == "fake://thumb/$pk-0" }, "$fetched")
            assertEquals(listOf("mediaInfo:$pk"), mediaInfoCalls, "the same visit")
        } finally {
            reply.complete(Unit)
        }
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
                onSource = { page, _ -> played += page.media!!.pk },
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
        fun isStillSettled(unit: Playable) = settled.value.media?.pk == unit.pk
    }

    private fun TestScope.playing(resolver: GatedResolver, screen: Screen): Job = launch {
        playSettledPages(
            settled = screen.settled,
            resolve = { media, _ -> resolver.resolve(media) },
            isStillSettled = screen::isStillSettled,
            onSettled = { screen.cleared += it.index },
            afterResolved = {},
            onSource = { page, source -> if (source is VideoSource.Play) screen.played += page.media!!.pk },
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

    /**
     * The CDN of the R23 tests: [answer] says what each download brings; one of a link in [hold] waits until it is released.
     * It records every download, every one cancelled, and the most that were out at once (each takes [takes] ms of virtual time).
     */
    private class Cdn(private val answer: (String) -> ByteArray? = { byteArrayOf(1, 2, 3) }) : MediaFetcher {
        val fetched: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var takes = 0L
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        /** Downloads out now, and (R26) video starts being written through [Starts] over this CDN. */
        val out = AtomicInteger()

        /** The most downloads (and video starts) that were out at once. */
        val mostOut = AtomicInteger()

        fun hold(url: String) {
            gates[url] = CompletableDeferred()
        }

        fun release(url: String) = gates[url]?.complete(Unit)

        /** So a test that fails half way cannot leave a download waiting for ever. */
        fun releaseAll() = gates.values.forEach { it.complete(Unit) }

        override suspend fun fetch(url: String): ByteArray? {
            fetched += url
            mostOut.accumulateAndGet(out.incrementAndGet(), ::maxOf)
            try {
                gates[url]?.await()
                if (takes > 0) delay(takes)
            } catch (e: CancellationException) {
                cancelled += url
                throw e
            } finally {
                out.decrementAndGet()
            }
            return answer(url)
        }
    }

    /**
     * R26: where the queue writes video starts, over the [cdn] of the pictures: every write is recorded (its key), and counted
     * as out on that CDN with its downloads, so [Cdn.mostOut] covers both. [picturesBeforeFirstStart] is how many pictures had
     * been fetched when the first start began. A start of a key in [hold] waits until it is released; each takes [takes] ms.
     */
    private class Starts(private val cdn: Cdn, private val answer: (String) -> StartWrite = { StartWrite.WRITTEN }) : VideoStarts {
        val written: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val cancelled: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var takes = 0L
        var picturesBeforeFirstStart: Int? = null

        /** Told the key of every write that is cancelled, once it has stopped. */
        var onCancelled: (String) -> Unit = {}

        /** How long a cancelled write takes to stop, in real time on an IO thread (as `CacheWriter` stops at its next read). */
        var stopTakesMs = 0L
        private val gates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun hold(key: String) {
            gates[key] = CompletableDeferred()
        }

        fun release(key: String) = gates[key]?.complete(Unit)

        /** So a test that fails half way cannot leave a write waiting for ever. */
        fun releaseAll() = gates.values.forEach { it.complete(Unit) }

        override fun isCached(key: String, bytes: Long) = false

        override suspend fun write(key: String, uri: Uri, bytes: Long): StartWrite {
            if (picturesBeforeFirstStart == null) picturesBeforeFirstStart = cdn.fetched.size
            written += key
            cdn.mostOut.accumulateAndGet(cdn.out.incrementAndGet(), ::maxOf)
            try {
                gates[key]?.await()
                if (takes > 0) delay(takes)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (stopTakesMs > 0) withContext(Dispatchers.IO) { Thread.sleep(stopTakesMs) }
                    cancelled += key
                    onCancelled(key)
                }
                throw e
            } finally {
                cdn.out.decrementAndGet()
            }
            return answer(key)
        }

        override fun remove(key: String) = Unit
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

    /** The player could not reach the CDN at all, as Media3 reports no connection ([code]) or a timeout. */
    private fun networkFailure(code: Int = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED) =
        PlaybackException("source error", IOException("no connection"), code)

    private companion object {
        const val START = 1_800_000_000_000L
    }
}
