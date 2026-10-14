package io.github.yuriimurha.reels.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.CarouselItemEntity
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.LibraryDeletion
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.instagram.InstagramClient
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.RemoteCarouselItem
import io.github.yuriimurha.reels.instagram.RemoteMedia
import io.github.yuriimurha.reels.instagram.fake.FakeFailures
import io.github.yuriimurha.reels.instagram.fake.FakeInstagramClient
import io.github.yuriimurha.reels.instagram.fake.FakeLibrary
import io.github.yuriimurha.reels.sync.SessionSignals
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.testutil.cacheWholeFile
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A carousel's items on demand (spec 2026-10-12 §3.3): the stored ones as they are, else one `mediaInfo` on the interactive
 * lane at most once per visit, for the lazy fill and the link renewal alike. The fake client, the real Conservative Pacer in
 * virtual time, an in-memory Room.
 */
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CarouselRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDb()
    private val fake = FakeInstagramClient(FakeLibrary(itemCount = 200))

    /** What happens to the fake's reply on its way back (the request is already out): a test's way to act mid-request. */
    private var onReply: suspend (RemoteMedia?) -> RemoteMedia? = { it }
    private val client = object : InstagramClient by fake {
        override suspend fun mediaInfo(mediaPk: String): RemoteMedia? = onReply(fake.mediaInfo(mediaPk))
    }
    private val fetcher = StubFetcher()

    /** R26: where video starts are written; it records each write and answers what the test says. */
    private val starts = RecordingStarts()
    private val images by lazy { CarouselImageStore(File(tmp.root, "carousel")) }
    private var sessionReady = true

    private var databaseProvider: StandaloneDatabaseProvider? = null
    private var builtCache: VideoCache? = null
    private val videoCache: VideoCache
        get() = builtCache ?: VideoCache(File(tmp.root, "video"), StandaloneDatabaseProvider(context).also { databaseProvider = it })
            .also { builtCache = it }
    private val cacheKeysAsked = Collections.synchronizedList(mutableListOf<String>())

    /** A carousel of the fake library with a video at position 2 (every third item is one). */
    private val post: RemoteMedia = fake.library.allSaved().first { it.type == MediaType.CAROUSEL && (it.carouselCount ?: 0) >= 3 }
    private val pk = post.pk
    private val media: MediaEntity = mediaEntity(pk, MediaType.CAROUSEL)

    @After
    fun close() {
        builtCache?.cache?.release()
        databaseProvider?.close()
        db.close()
    }

    /** The repository, the Pacer and the test scheduler agree on the time: [START] plus whatever the test has waited. */
    private fun TestScope.repository(
        sampleClip: Uri? = null,
        cooldowns: InMemoryCooldownStore = InMemoryCooldownStore(),
        log: ((String) -> Unit)? = null,
        starts: VideoStarts = this@CarouselRepositoryTest.starts,
    ): CarouselRepository {
        val clock = { START + testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), cooldowns, Random(1), now = clock)
        return CarouselRepository(
            db.carouselItemDao(), db.mediaDao(),
            PacedMediaInfo(client, pacer, SessionSignals.None, isSessionReady = { sessionReady }),
            fetcher, images, pacer,
            now = clock,
            isVideoCached = { key -> cacheKeysAsked += key; videoCache.isFullyCached(key) },
            sampleClip = sampleClip,
            log = log,
            // The container's R20 steps, over this test's own cache and image store.
            itemEviction = MediaEviction.eachStep({ videoCache.removeCarouselItems(it) }, { images.deleteFor(it) }),
            starts = starts,
        )
    }

    private suspend fun storePost() = db.mediaDao().upsert(listOf(media))

    /**
     * The post and three items with links from an earlier sync: two images, then a video whose link expires at [videoExpiresAt].
     * Their child pks are [childPks]: unknown by default, as a row stored before version 3 (R28).
     */
    private suspend fun storeOldItems(
        videoExpiresAt: Long? = START + 3_600_000,
        firstImageUrl: String = oldImage(0),
        videoUrl: String? = OLD_VIDEO,
        childPks: List<String?> = listOf(null, null, null),
    ) {
        storePost()
        db.carouselItemDao().replace(
            pk,
            listOf(
                CarouselItemEntity(pk, 0, MediaType.IMAGE, 1080, 1350, firstImageUrl, null, null, childPks[0]),
                CarouselItemEntity(pk, 1, MediaType.IMAGE, 1080, 1350, oldImage(1), null, null, childPks[1]),
                CarouselItemEntity(pk, 2, MediaType.VIDEO, 1080, 1920, oldImage(2), videoUrl, videoExpiresAt, childPks[2]),
            ),
        )
    }

    /** R28: the child pks the fake sends for the post's first three items: what an earlier sync stored since version 3. */
    private val fakeChildPks: List<String?> get() = post.carouselItems!!.take(3).map { it.childPk }

    private suspend fun rows() = db.carouselItemDao().items(pk)

    private val mediaInfoCalls get() = fake.calls.filter { it.startsWith("mediaInfo:") }

    private fun newImage(position: Int) = "fake://thumb/$pk-$position"

    private fun newVideo(position: Int) = "fake://video/$pk-$position"

    // --- items: stored, or the lazy fill ---

    @Test
    fun storedItemsAreReturnedWithoutARequest() = runTest {
        storeOldItems()

        val result = repository().items(media, CarouselRepository.Visit())

        assertEquals(ItemsResult.Items(rows()), result)
        assertEquals(3, (result as ItemsResult.Items).items.size)
        assertEquals(emptyList(), fake.calls)
    }

    @Test
    fun aCarouselWithoutItemsFillsThemWithOneRequest() = runTest {
        storePost()
        val repository = repository()

        val result = repository.items(media, CarouselRepository.Visit())

        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        val items = assertIs<ItemsResult.Items>(result).items
        assertEquals(rows(), items, "what was returned is what was stored")
        assertEquals(List(post.carouselCount!!) { it }, items.map { it.position })
        assertEquals(List(post.carouselCount!!) { newImage(it) }, items.map { it.imageUrl })
        val video = items[2]
        assertEquals(MediaType.VIDEO, video.type)
        assertEquals(newVideo(2), video.videoUrl)
        // R78's rule, as for reel links: a renewed link that names no expiry is good for an hour, so it is not asked for again at once.
        assertTrue(video.videoUrlExpiresAt!! - START in RealVideoSourceResolver.ASSUMED_LIFETIME_MS..RealVideoSourceResolver.ASSUMED_LIFETIME_MS + 60_000)
        assertNull(items[0].videoUrlExpiresAt, "an image has no video link to date")

        assertEquals(ItemsResult.Items(items), repository.items(media, CarouselRepository.Visit()), "the next visit finds them stored")
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
    }

    /** Review Focus 2: one `mediaInfo` per visit, whatever came of it. A new visit may ask again. */
    @Test
    fun aSecondItemsCallInTheSameVisitSendsNothing() = runTest {
        storePost()
        val repository = repository()
        val visit = CarouselRepository.Visit()
        fake.failures = FakeFailures { InstagramException.Transient() }

        assertEquals(ItemsResult.CoverOnly(OFFLINE), repository.items(media, visit))
        fake.failures = FakeFailures { null }
        assertEquals(ItemsResult.CoverOnly(OFFLINE), repository.items(media, visit), "the visit spent its one request")
        assertEquals(ItemImage.Failed(OFFLINE), repository.image(media, 0, visit))
        assertEquals(VideoSource.Unavailable(OFFLINE), repository.video(media, 2, visit))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls, "nothing more was sent in this visit")
        assertEquals(emptyList(), fetcher.fetched)

        assertIs<ItemsResult.Items>(repository.items(media, CarouselRepository.Visit()), "a new visit asks again")
        assertEquals(2, mediaInfoCalls.size)
    }

    /** R76: no usable session, no request; the post and its cover are left as they were. */
    @Test
    fun withoutASessionNothingIsSentAndTheCoverStays() = runTest {
        storePost()
        sessionReady = false

        assertEquals(ItemsResult.CoverOnly(NEEDS_ATTENTION), repository().items(media, CarouselRepository.Visit()))

        assertEquals(emptyList(), fake.calls)
        assertEquals(listOf(media), db.mediaDao().byPks(listOf(pk)), "the post and its cover are untouched")
        assertEquals(emptyList(), rows())
    }

    @Test
    fun offlineFillKeepsTheCover() = runTest {
        storePost()
        fake.failures = FakeFailures { InstagramException.Transient() }

        assertEquals(ItemsResult.CoverOnly(OFFLINE), repository().items(media, CarouselRepository.Visit()))

        assertEquals(listOf(media), db.mediaDao().byPks(listOf(pk)))
        assertEquals(emptyList(), rows())
    }

    /** The Pacer refused (cooling down after a rate limit): nothing is sent, and the cover says why, in the item's words. */
    @Test
    fun aRefusedFillSaysWhyAndKeepsTheCover() = runTest {
        storePost()
        val cooldowns = InMemoryCooldownStore().apply { onRateLimited(START) }

        assertEquals(
            ItemsResult.CoverOnly("Can't load this item right now: Cooling down after a rate limit"),
            repository(cooldowns = cooldowns).items(media, CarouselRepository.Visit()),
        )
        assertEquals(emptyList(), fake.calls)
        assertEquals(emptyList(), rows())
    }

    /** R4: a reply whose items are unknown (an unreadable child) stores nothing and keeps the cover. */
    @Test
    fun aReplyWithoutItemsKeepsTheCover() = runTest {
        storePost()
        onReply = { it?.copy(carouselItems = null) }

        assertEquals(ItemsResult.CoverOnly(CANT_LOAD), repository().items(media, CarouselRepository.Visit()))

        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertEquals(emptyList(), rows())
    }

    /** R2 on the fill path: an empty `carousel_media` says nothing about the items, so the post stays cover-only, never empty. */
    @Test
    fun anEmptyFillLeavesThePostCoverOnly() = runTest {
        storePost()
        onReply = { it?.copy(carouselItems = emptyList()) }

        assertEquals(ItemsResult.CoverOnly(CANT_LOAD), repository().items(media, CarouselRepository.Visit()))

        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertEquals(emptyList(), rows())
    }

    /**
     * T2, R2 on the renewal path: an empty reply says nothing about the items, so the stored ones stay. Replaced by nothing, every
     * later visit of the post would spend a `mediaInfo` on a fill.
     */
    @Test
    fun anEmptyRenewalKeepsTheStoredItems() = runTest {
        storeOldItems()
        onReply = { it?.copy(carouselItems = emptyList()) }

        assertEquals(ItemImage.Failed(CANT_LOAD), repository().image(media, 0, CarouselRepository.Visit()))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertEquals(3, rows().size, "the stored items stay")

        assertIs<ItemsResult.Items>(repository().items(media, CarouselRepository.Visit()))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls, "the next visit needs no fill")
    }

    /**
     * T4: the debug log (spec §4, Privacy) gets a line for each fill, renewal and failed image, and none names a link, a host
     * or a pk.
     */
    @Test
    fun theLogNamesNoLinkHostOrPk() = runTest {
        val lines = mutableListOf<String>()
        storePost()
        repository(log = { lines += it }).items(media, CarouselRepository.Visit()) // the fill
        storeOldItems()
        repository(log = { lines += it }).image(media, 0, CarouselRepository.Visit()) // refused, renewed, refused again
        fetcher.answer = { throw java.io.IOException("offline") }
        repository(log = { lines += it }).image(media, 1, CarouselRepository.Visit()) // offline
        fetcher.answer = { null }
        repository(log = { lines += it }).prefetchImage(media, 1, CarouselRepository.Visit()) // R23: refused ahead

        for (
            kind in listOf(
                "carousel fill:", "carousel renew:", "carousel image failed (refused)", "carousel image failed (offline)",
                "carousel prefetch failed (refused)",
            )
        ) {
            assertTrue(lines.any { it.startsWith(kind) }, "a line '$kind...': $lines")
        }
        val links = listOf("://", "cdn", "example", "fake", ".jpg", ".mp4")
        assertTrue(lines.none { line -> pk in line || links.any { it in line } }, "no link, host or pk: $lines")
    }

    @Test
    fun aGoneCarouselSaysSo() = runTest {
        storePost()
        fake.library.unsave(pk)

        assertEquals(ItemsResult.CoverOnly(GONE), repository().items(media, CarouselRepository.Visit()))
        assertEquals(listOf(media), db.mediaDao().byPks(listOf(pk)), "a failed fill never deletes the post or its cover")

        // An item whose link was refused says so too, after its one renewal.
        storeOldItems()
        val visit = CarouselRepository.Visit()
        assertEquals(ItemImage.Failed(GONE), repository().image(media, 0, visit))
        assertEquals(2, mediaInfoCalls.size)
        assertEquals(oldImage(0), rows()[0].imageUrl, "and its stored items stay")
    }

    /** A reel (its link is the video resolver's) or an image post has no items: no call of the repository sends anything for it. */
    @Test
    fun aPostThatIsNotACarouselHasNoItemsToAskFor() = runTest {
        val repository = repository()
        // The fake library has a carousel under this pk, so a request would bring items back: each call is checked on its own.
        fun nothingSent(what: String) {
            assertEquals(emptyList(), fake.calls, "$what sent a request")
            assertEquals(emptyList(), fetcher.fetched, "$what downloaded")
        }
        for (type in listOf(MediaType.REEL, MediaType.VIDEO, MediaType.IMAGE)) {
            val post = mediaEntity(pk, type)
            db.mediaDao().upsert(listOf(post))

            assertEquals(ItemsResult.CoverOnly(null), repository.items(post, CarouselRepository.Visit()), "$type")
            nothingSent("items of a $type")
            assertEquals(ItemImage.Failed(CANT_LOAD), repository.image(post, 0, CarouselRepository.Visit()), "$type")
            nothingSent("image of a $type")
            assertEquals(VideoSource.Unavailable(CANT_LOAD), repository.video(post, 2, CarouselRepository.Visit()), "$type")
            assertEquals(VideoSource.Unavailable(CANT_LOAD), repository.video(post, 2, CarouselRepository.Visit(), forceRefresh = true), "$type")
            nothingSent("video of a $type")
        }
    }

    /** A post no longer in the library (Delete library ran while it was open) has nowhere to keep items: nothing is asked. */
    @Test
    fun aPostNoLongerInTheLibrarySendsNothing() = runTest {
        val repository = repository()

        assertEquals(ItemsResult.CoverOnly(CANT_LOAD), repository.items(media, CarouselRepository.Visit()))
        assertEquals(ItemImage.Failed(CANT_LOAD), repository.image(media, 0, CarouselRepository.Visit()))
        assertEquals(VideoSource.Unavailable(CANT_LOAD), repository.video(media, 2, CarouselRepository.Visit()))

        assertEquals(emptyList(), fake.calls)
        assertEquals(emptyList(), fetcher.fetched)
    }

    /**
     * The post's row went while its request was out (Delete library): storing its items would break the foreign key. That is
     * a failure the viewer shows, never a crash, for the lazy fill and for the renewal alike.
     */
    @Test
    fun aPostDeletedWhileItsRequestWasOutGivesTheCoverNotACrash() = runTest {
        storePost()
        onReply = { reply -> db.mediaDao().deleteAll(); reply }

        assertEquals(ItemsResult.CoverOnly(CANT_LOAD), repository().items(media, CarouselRepository.Visit()))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertEquals(emptyList(), rows())

        storeOldItems()
        assertEquals(ItemImage.Failed(CANT_LOAD), repository().image(media, 0, CarouselRepository.Visit()))
        assertEquals(2, mediaInfoCalls.size)
        assertEquals(emptyList(), rows())
    }

    /**
     * A visit whose one request was cancelled on the way (the viewer left the item) has still spent it: the request may have
     * gone out, so the visit sends no other.
     */
    @Test
    fun aCancelledRequestStillSpendsTheVisit() = runTest {
        storePost()
        val repository = repository()
        val visit = CarouselRepository.Visit()
        val requested = CompletableDeferred<Unit>()
        onReply = { requested.complete(Unit); awaitCancellation() }

        assertEquals(UNSET, cancelOnceStarted(requested) { repository.items(media, visit) }, "the cancelled call delivered nothing")
        onReply = { it }

        assertEquals(ItemsResult.CoverOnly(CANT_LOAD), repository.items(media, visit))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertIs<ItemsResult.Items>(repository.items(media, CarouselRepository.Visit()), "a new visit asks again")
    }

    /**
     * Each call ends a cancelled caller with the cancellation, never with a value: a post-scoped coroutine the viewer cancelled
     * must not write "Can't load this item" into its state. Cancelled in the request (items, video) and in the download (image).
     */
    @Test
    fun aCancelledCallEndsInCancellationNotAFailure() = runTest {
        storeOldItems(videoExpiresAt = START - 1)
        val repository = repository()

        val downloading = CompletableDeferred<Unit>()
        fetcher.answer = { downloading.complete(Unit); awaitCancellation() }
        assertEquals(UNSET, cancelOnceStarted(downloading) { repository.image(media, 0, CarouselRepository.Visit()) }, "image")

        val renewing = CompletableDeferred<Unit>()
        onReply = { renewing.complete(Unit); awaitCancellation() }
        assertEquals(UNSET, cancelOnceStarted(renewing) { repository.video(media, 2, CarouselRepository.Visit()) }, "video")

        val prefetching = CompletableDeferred<Unit>()
        fetcher.answer = { prefetching.complete(Unit); awaitCancellation() }
        assertEquals(UNSET, cancelOnceStarted(prefetching) { repository.prefetchImage(media, 1, CarouselRepository.Visit()) }, "prefetch")

        db.carouselItemDao().deleteFor(pk)
        val filling = CompletableDeferred<Unit>()
        onReply = { filling.complete(Unit); awaitCancellation() }
        assertEquals(UNSET, cancelOnceStarted(filling) { repository.items(media, CarouselRepository.Visit()) }, "items")
    }

    /**
     * Runs [call] until [started], cancels it there and waits for it to end. Returns what it delivered: [UNSET] when it ended in
     * the cancellation, the value it returned when it swallowed it. Waits in real time: the call reads Room on a real I/O thread.
     */
    private suspend fun TestScope.cancelOnceStarted(started: CompletableDeferred<Unit>, call: suspend () -> Any?): Any? {
        var delivered: Any? = UNSET
        val job = launch { delivered = call() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { started.await() } }
        job.cancel()
        withContext(Dispatchers.Default) { withTimeout(5_000) { job.join() } }
        return delivered
    }

    // --- images ---

    @Test
    fun anImageIsDownloadedOnceAndThenServedFromTheStore() = runTest {
        storeOldItems()
        fetcher.answer = { url -> url.toByteArray() }
        val repository = repository()

        val first = assertIs<ItemImage.File>(repository.image(media, 0, CarouselRepository.Visit()))
        assertContentEquals(oldImage(0).toByteArray(), File(first.path).readBytes())
        assertEquals(images.path(pk, 0), first.path)

        assertEquals(first, repository.image(media, 0, CarouselRepository.Visit()), "the next visit reads the file")
        assertEquals(listOf(oldImage(0)), fetcher.fetched, "one download")
        assertEquals(emptyList(), fake.calls)
    }

    /**
     * R7: a download goes through the Pacer's CDN lane, like the sync's thumbnails: its jitter (at least 200 ms with the
     * Conservative policy) passes before the fetcher is called. Nothing else on this path waits on the clock: the items are
     * stored, so no `mediaInfo` and no gap, and the store and Room run on real threads.
     */
    @Test
    fun anImageDownloadGoesThroughTheCdnLane() = runTest {
        storeOldItems()
        var calledAt: Long? = null
        fetcher.answer = { url -> calledAt = testScheduler.currentTime; url.toByteArray() }
        val before = testScheduler.currentTime

        assertIs<ItemImage.File>(repository().image(media, 0, CarouselRepository.Visit()))

        val waited = checkNotNull(calledAt) - before
        assertTrue(waited >= PacingPolicy.Conservative.cdnJitterMs.first, "the fetch waited $waited ms: it did not go through the CDN lane")
        assertTrue(waited <= PacingPolicy.Conservative.cdnJitterMs.last, "the fetch waited $waited ms: more than the lane's jitter")
        assertEquals(emptyList(), fake.calls)
    }

    /** A post deleted while its image was on the way (Delete library) keeps no file behind: the bytes are dropped. */
    @Test
    fun anImageOfAPostDeletedWhileItDownloadedIsNotKept() = runTest {
        storeOldItems()
        fetcher.answer = { url -> db.mediaDao().deleteAll(); url.toByteArray() }

        assertEquals(ItemImage.Failed(CANT_LOAD), repository().image(media, 0, CarouselRepository.Visit()))

        assertNull(images.path(pk, 0))
        assertTrue(File(tmp.root, "carousel").list().isNullOrEmpty(), "no file was written")
        assertEquals(emptyList(), fake.calls)
    }

    /**
     * D3: a FULL sync's reconcile unsaves the post while its image is on the way. The row stays (marked removed) and the
     * reconcile's eviction has already run, so a file written now would stay until Delete library: it is not written.
     */
    @Test
    fun anImageOfAPostUnsavedWhileItDownloadedIsNotKept() = runTest {
        storeOldItems()
        fetcher.answer = { url ->
            db.mediaDao().markRemoved(listOf(pk), START)
            images.deleteFor(pk) // the reconcile's eviction, right after its commit
            url.toByteArray()
        }

        assertEquals(ItemImage.Failed(CANT_LOAD), repository().image(media, 1, CarouselRepository.Visit()))

        assertNull(images.path(pk, 1))
        assertTrue(File(tmp.root, "carousel").list().isNullOrEmpty(), "no file was written")
    }

    /** D3/C3: a post a sync found unsaved is not in any list the viewer shows, so a visit still open on it asks Instagram nothing. */
    @Test
    fun anUnsavedPostSendsNothing() = runTest {
        storePost()
        db.mediaDao().markRemoved(listOf(pk), START)

        assertEquals(ItemsResult.CoverOnly(CANT_LOAD), repository().items(media, CarouselRepository.Visit()), "no fill")
        storeOldItems(videoExpiresAt = START - 1)
        db.mediaDao().markRemoved(listOf(pk), START)
        assertEquals(VideoSource.Unavailable(CANT_LOAD), repository().video(media, 2, CarouselRepository.Visit()), "no renewal")

        assertEquals(emptyList(), fake.calls)
    }

    /**
     * Review Focus 3: the stored link is refused (the fetcher's null is a 403, 404 or 410), so the post's links are renewed
     * once: one `mediaInfo`, the items replaced, the image fetched again from its new link. When that fails too, the item
     * says it can't load, and nothing in this visit asks Instagram again.
     */
    @Test
    fun anExpiredImageLinkIsRenewedOncePerVisit() = runTest {
        storeOldItems()
        val repository = repository()
        val visit = CarouselRepository.Visit()

        assertEquals(ItemImage.Failed(CANT_LOAD), repository.image(media, 0, visit))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertEquals(listOf(oldImage(0), newImage(0)), fetcher.fetched, "the stored link, then the renewed one")
        assertEquals(List(post.carouselCount!!) { newImage(it) }, rows().map { it.imageUrl }, "the items were replaced")

        assertEquals(ItemImage.Failed(CANT_LOAD), repository.image(media, 1, visit))
        assertEquals(ItemImage.Failed(CANT_LOAD), repository.image(media, 0, visit))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls, "no second request in this visit")
        // R25: item 0's renewed link, refused already in this visit, is not asked for again.
        assertEquals(listOf(oldImage(0), newImage(0), newImage(1)), fetcher.fetched)
        assertNull(images.path(pk, 0))
    }

    @Test
    fun aRenewedLinkLoadsTheImageAndTheRestOfTheVisitUsesTheNewLinks() = runTest {
        storeOldItems()
        fetcher.answer = { url -> if (url.startsWith("fake://")) url.toByteArray() else null }
        val repository = repository()
        val visit = CarouselRepository.Visit()

        val first = assertIs<ItemImage.File>(repository.image(media, 0, visit))
        assertContentEquals(newImage(0).toByteArray(), File(first.path).readBytes())
        assertIs<ItemImage.File>(repository.image(media, 1, visit))

        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertEquals(listOf(oldImage(0), newImage(0), newImage(1)), fetcher.fetched, "item 1 went straight to its renewed link")
    }

    /** Two items refused at the same moment share the visit's one renewal; the second waits for it and uses its links. */
    @Test
    fun itemsRefusedTogetherShareOneRenewal() = runTest {
        storeOldItems()
        fetcher.answer = { url -> if (url.startsWith("fake://")) url.toByteArray() else null }
        val repository = repository()
        val visit = CarouselRepository.Visit()

        val first = async { repository.image(media, 0, visit) }
        val second = async { repository.image(media, 1, visit) }

        assertIs<ItemImage.File>(first.await())
        assertIs<ItemImage.File>(second.await())
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
    }

    /** Task 1: an item Instagram sent no image for has an empty link. Nothing is fetched for it; it is renewed like a refused one. */
    @Test
    fun anItemWithoutAnImageLinkIsRenewedOnceToo() = runTest {
        storeOldItems(firstImageUrl = "")
        fetcher.answer = { url -> url.toByteArray() }

        assertIs<ItemImage.File>(repository().image(media, 0, CarouselRepository.Visit()))

        assertEquals(listOf(newImage(0)), fetcher.fetched, "no download of an empty link")
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
    }

    @Test
    fun anImageThatCannotBeDownloadedOfflineSaysSoWithoutARenewal() = runTest {
        storeOldItems()
        fetcher.answer = { throw java.io.IOException("no route") }

        assertEquals(ItemImage.Failed(OFFLINE), repository().image(media, 0, CarouselRepository.Visit()))

        assertEquals(emptyList(), fake.calls, "a renewal can't help when the CDN can't be reached")
    }

    /** A CDN 429 is not offline and not a dead link: the item can't load, and Instagram is not asked. */
    @Test
    fun aCdnRateLimitIsAFailureWithoutARenewal() = runTest {
        storeOldItems()
        fetcher.answer = { throw CdnRateLimited() }

        assertEquals(ItemImage.Failed(CANT_LOAD), repository().image(media, 0, CarouselRepository.Visit()))
        assertEquals(emptyList(), fake.calls)
    }

    @Test
    fun anImageOfAPostWithoutStoredItemsFillsThemFirst() = runTest {
        storePost()
        fetcher.answer = { url -> url.toByteArray() }

        assertIs<ItemImage.File>(repository().image(media, 1, CarouselRepository.Visit()))

        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertEquals(listOf(newImage(1)), fetcher.fetched)
        assertEquals(post.carouselCount, rows().size)
    }

    // --- R23: an image fetched ahead; R25: a refused one may renew once; P1: what stops it; P2: no stale write ---

    /**
     * A prefetch downloads the item's stored link through the CDN lane (its jitter passes first), stores it as [image] would, and
     * asks Instagram nothing. The item's later on-demand load serves that file without a download.
     */
    @Test
    fun aPrefetchDownloadsTheImageThroughTheCdnLaneAndTheLaterLoadServesIt() = runTest {
        storeOldItems()
        var calledAt: Long? = null
        fetcher.answer = { url -> calledAt = testScheduler.currentTime; url.toByteArray() }
        val repository = repository()
        val visit = CarouselRepository.Visit()
        val before = testScheduler.currentTime

        val path = assertIs<ImageAhead.Shown>(repository.prefetchImage(media, 1, visit)).path

        assertEquals(images.path(pk, 1), path)
        assertContentEquals(oldImage(1).toByteArray(), File(path).readBytes())
        val waited = checkNotNull(calledAt) - before
        assertTrue(waited in PacingPolicy.Conservative.cdnJitterMs, "the fetch waited $waited ms: not the CDN lane's jitter")
        assertEquals(ItemImage.File(path), repository.image(media, 1, visit))
        assertEquals(listOf(oldImage(1)), fetcher.fetched, "one download")
        assertEquals(emptyList(), fake.calls)
    }

    /** Nothing ahead for item 0 (the cover, R10), a video item, a page past the last item, or one whose file is stored already. */
    @Test
    fun aPrefetchFetchesNothingForTheCoverAVideoItemOrPastTheLastItem() = runTest {
        storeOldItems()
        fetcher.answer = { url -> url.toByteArray() }
        val repository = repository()
        val visit = CarouselRepository.Visit()

        for (position in listOf(0, 2, 3, -1)) assertEquals(ImageAhead.Skipped, repository.prefetchImage(media, position, visit), "item $position")
        val stored = images.write(pk, 1, byteArrayOf(7))
        assertEquals(ImageAhead.Shown(stored), repository.prefetchImage(media, 1, visit), "a stored file is served, not downloaded again")

        assertEquals(emptyList(), fetcher.fetched)
        assertEquals(emptyList(), fake.calls)
    }

    /** No prefetch for a post that is not a carousel, unsaved, gone, or whose items are not stored: it never fills or asks. */
    @Test
    fun aPrefetchFetchesNothingForAPostOutOfTheLibraryNotACarouselOrWithoutItems() = runTest {
        fetcher.answer = { url -> url.toByteArray() }
        val repository = repository()
        storePost()
        assertEquals(ImageAhead.Skipped, repository.prefetchImage(media, 1, CarouselRepository.Visit()), "no stored items: never the fill")

        storeOldItems()
        for (type in listOf(MediaType.REEL, MediaType.VIDEO, MediaType.IMAGE)) {
            assertEquals(ImageAhead.Skipped, repository.prefetchImage(mediaEntity(pk, type), 1, CarouselRepository.Visit()), "$type")
        }
        db.mediaDao().markRemoved(listOf(pk), START)
        assertEquals(ImageAhead.Stop, repository.prefetchImage(media, 1, CarouselRepository.Visit()), "unsaved by a sync")
        db.mediaDao().deleteAll()
        assertEquals(ImageAhead.Skipped, repository.prefetchImage(media, 1, CarouselRepository.Visit()), "deleted")

        assertEquals(emptyList(), fetcher.fetched)
        assertEquals(emptyList(), fake.calls)
    }

    /** F4: a post deleted while its picture was being fetched ahead keeps no file behind, as for an on-demand image. */
    @Test
    fun aPrefetchOfAPostDeletedWhileItDownloadedKeepsNoFile() = runTest {
        storeOldItems()
        fetcher.answer = { url -> db.mediaDao().deleteAll(); url.toByteArray() }

        assertEquals(ImageAhead.Stop, repository().prefetchImage(media, 1, CarouselRepository.Visit()))

        assertEquals(listOf(oldImage(1)), fetcher.fetched, "precondition: the download ran")
        assertNull(images.path(pk, 1))
        assertTrue(File(tmp.root, "carousel").list().isNullOrEmpty(), "no file was written")
    }

    /**
     * F4, D3 ahead: a FULL sync's reconcile unsaves the post while its picture is being fetched ahead. The row and its items stay
     * (marked removed), and the reconcile's eviction has already run, so the bytes are not written and the queue stops.
     */
    @Test
    fun aPrefetchOfAPostUnsavedWhileItDownloadedKeepsNoFile() = runTest {
        storeOldItems()
        fetcher.answer = { url ->
            db.mediaDao().markRemoved(listOf(pk), START)
            images.deleteFor(pk) // the reconcile's eviction, right after its commit
            url.toByteArray()
        }

        assertEquals(ImageAhead.Stop, repository().prefetchImage(media, 1, CarouselRepository.Visit()))

        assertEquals(listOf(oldImage(1)), fetcher.fetched, "precondition: the download ran")
        assertTrue(File(tmp.root, "carousel").list().isNullOrEmpty(), "no file was written")
    }

    /**
     * P1: what keeps the CDN from being reached stops the queue (Stop) and sends no `mediaInfo`: the CDN's 429, offline, an
     * unforeseen failure (an unsafe key). None of them is a refusal, so the visit's one request is still there for the item's
     * own load, which renews when its link is refused.
     */
    @Test
    fun aPrefetchThatCannotReachTheCdnStopsAndLeavesTheVisitsRequest() = runTest {
        storeOldItems()
        val repository = repository()
        val visit = CarouselRepository.Visit()

        fetcher.answer = { throw CdnRateLimited() }
        assertEquals(ImageAhead.Stop, repository.prefetchImage(media, 1, visit), "cdn limit")
        fetcher.answer = { throw java.io.IOException("no route") }
        assertEquals(ImageAhead.Stop, repository.prefetchImage(media, 1, visit), "offline")
        val unsafe = mediaEntity("12-34", MediaType.CAROUSEL)
        db.mediaDao().upsert(listOf(unsafe))
        db.carouselItemDao().replace(unsafe.pk, listOf(CarouselItemEntity(unsafe.pk, 1, MediaType.IMAGE, 1, 1, oldImage(1), null, null)))
        assertEquals(ImageAhead.Stop, repository.prefetchImage(unsafe, 1, visit), "an unsafe key")
        assertEquals(emptyList(), fake.calls, "no request for any of them")
        assertNull(images.path(pk, 1))

        fetcher.answer = { url -> if (url.startsWith("fake://")) url.toByteArray() else null }
        assertIs<ItemImage.File>(repository.image(media, 1, visit), "the item's own load renews and loads")
        assertEquals(listOf("mediaInfo:$pk"), fake.calls, "the visit's one request was still there")
    }

    /**
     * R25: a link refused ahead spends the visit's one renewal (one `mediaInfo`, the interactive lane), and the item loads from
     * its renewed link. The renewal is the visit's: the item's own load afterwards uses the stored file, and asks nothing more.
     */
    @Test
    fun aRefusedPrefetchSpendsTheVisitsRenewalAndLoadsTheRenewedLink() = runTest {
        storeOldItems()
        fetcher.answer = { url -> if (url.startsWith("fake://")) url.toByteArray() else null }
        val repository = repository()
        val visit = CarouselRepository.Visit()

        val path = assertIs<ImageAhead.Shown>(repository.prefetchImage(media, 1, visit)).path

        assertContentEquals(newImage(1).toByteArray(), File(path).readBytes())
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertEquals(List(post.carouselCount!!) { newImage(it) }, rows().map { it.imageUrl }, "the renewed links are stored")
        assertEquals(listOf(oldImage(1), newImage(1)), fetcher.fetched)
        assertEquals(ItemImage.File(path), repository.image(media, 1, visit))
        assertEquals(ImageAhead.Shown(path), repository.prefetchImage(media, 1, visit), "a later prefetch finds the file")
        assertEquals(listOf("mediaInfo:$pk"), fake.calls, "still the one request")
    }

    /** R25: an item with no link ahead spends the renewal too, as a refused one does. */
    @Test
    fun aPrefetchOfAnItemWithoutALinkRenewsToo() = runTest {
        storeOldItems()
        db.carouselItemDao().replace(pk, rows().map { if (it.position == 1) it.copy(imageUrl = "") else it })
        fetcher.answer = { url -> url.toByteArray() }

        assertIs<ImageAhead.Shown>(repository().prefetchImage(media, 1, CarouselRepository.Visit()))

        assertEquals(listOf(newImage(1)), fetcher.fetched, "no download of an empty link")
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
    }

    /**
     * R25: a renewal the visit spent already stops the queue. Spent and failed (here offline), a refused link ahead gets
     * nothing more; spent on the links stored now, a refusal of one of them is final too. Neither sends a second request.
     */
    @Test
    fun aSpentRenewalStopsThePrefetch() = runTest {
        storeOldItems(videoExpiresAt = START - 1)
        fetcher.answer = { null }
        val repository = repository()

        val failed = CarouselRepository.Visit()
        fake.failures = FakeFailures { InstagramException.Transient() }
        assertEquals(VideoSource.Unavailable(OFFLINE), repository.video(media, 2, failed), "precondition: the visit's renewal failed")
        assertEquals(ImageAhead.Stop, repository.prefetchImage(media, 1, failed))
        assertEquals(1, mediaInfoCalls.size)

        fake.failures = FakeFailures { null }
        val renewed = CarouselRepository.Visit()
        assertEquals(ImageAhead.Stop, repository.prefetchImage(media, 1, renewed), "the old link, then the renewed one, refused")
        assertEquals(2, mediaInfoCalls.size, "that visit's own one request")
        assertEquals(ImageAhead.Stop, repository.prefetchImage(media, 1, renewed), "its renewed link, refused before: final")
        assertEquals(2, mediaInfoCalls.size, "no second request in that visit")
        assertEquals(listOf(oldImage(1), oldImage(1), newImage(1)), fetcher.fetched, "and no link asked twice in a visit")
    }

    /**
     * R25: a link the CDN refused is not requested again in the visit. Here the renewal cannot be made (no session): the
     * prefetch stops, and the item's own load goes straight to the visit's (failed) renewal, with no second try of the link.
     * A new visit may ask again.
     */
    @Test
    fun aRefusedLinkIsNeverRequestedTwiceInAVisit() = runTest {
        storeOldItems()
        fetcher.answer = { null }
        sessionReady = false
        val repository = repository()
        val visit = CarouselRepository.Visit()

        assertEquals(ImageAhead.Stop, repository.prefetchImage(media, 1, visit))
        assertEquals(ItemImage.Failed(NEEDS_ATTENTION), repository.image(media, 1, visit))
        assertEquals(ItemImage.Failed(NEEDS_ATTENTION), repository.image(media, 1, visit))

        assertEquals(listOf(oldImage(1)), fetcher.fetched)
        assertEquals(emptyList(), fake.calls, "nothing sent without a session")
        repository.image(media, 1, CarouselRepository.Visit())
        assertEquals(listOf(oldImage(1), oldImage(1)), fetcher.fetched, "a new visit asks again")
    }

    /**
     * The hard bound: a prefetch and the item on screen are refused at the same moment. They share the visit's one renewal:
     * one `mediaInfo` in all, and both load from the renewed links.
     */
    @Test
    fun aPrefetchAndAnImageRefusedTogetherSendOneRequest() = runTest {
        storeOldItems()
        fetcher.answer = { url -> if (url.startsWith("fake://")) url.toByteArray() else null }
        val reply = CompletableDeferred<Unit>()
        onReply = { reply.await(); it }
        val repository = repository()
        val visit = CarouselRepository.Visit()
        try {
            val ahead = async { repository.prefetchImage(media, 1, visit) }
            val shown = async { repository.image(media, 0, visit) }
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { while (mediaInfoCalls.isEmpty() || !fetcher.fetched.containsAll(listOf(oldImage(0), oldImage(1)))) delay(10) }
            }
            reply.complete(Unit)

            assertIs<ImageAhead.Shown>(ahead.await())
            assertIs<ItemImage.File>(shown.await())
            assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        } finally {
            reply.complete(Unit)
        }
    }

    /**
     * The item's prefetch is out: its on-demand load waits for it instead of downloading the item a second time, then serves the
     * file it stored. (Only the first download of the link is held, so a second one would finish on its own, at once.)
     */
    @Test
    fun anImageWaitsForItsPrefetchInsteadOfDownloadingTwice() = runTest {
        storeOldItems()
        val held = CompletableDeferred<Unit>()
        fetcher.answer = { url -> if (fetcher.fetched.size == 1) held.await(); url.toByteArray() }
        val repository = repository()
        val visit = CarouselRepository.Visit()

        try {
            val ahead = async { repository.prefetchImage(media, 1, visit) }
            withContext(Dispatchers.Default) { withTimeout(5_000) { while (fetcher.fetched.isEmpty()) delay(10) } }
            val shown = async { repository.image(media, 1, visit) }
            // Room and the store run on real threads, the lane's jitter in virtual time: a load that did not wait is done by now.
            withContext(Dispatchers.Default) { withTimeoutOrNull(500) { while (!shown.isCompleted) delay(10) } }
            assertTrue(!shown.isCompleted, "the on-demand load waits while the item's prefetch is out")

            held.complete(Unit)

            val path = assertIs<ImageAhead.Shown>(ahead.await()).path
            assertEquals(ItemImage.File(path), shown.await())
            assertEquals(listOf(oldImage(1)), fetcher.fetched, "one download")
        } finally {
            held.complete(Unit)
        }
    }

    /**
     * F2: the item's own load waited for its prefetch, which stored nothing (offline, so it stopped without a renewal): the load
     * then goes on as if there had been none, and when the CDN refuses the link it renews and loads.
     */
    @Test
    fun anImageThatWaitedForAFailedPrefetchStillRenewsAndLoads() = runTest {
        storeOldItems()
        val held = CompletableDeferred<Unit>()
        fetcher.answer = { url ->
            if (fetcher.fetched.size == 1) {
                held.await()
                throw java.io.IOException("no route")
            }
            if (url.startsWith("fake://")) url.toByteArray() else null
        }
        val repository = repository()
        val visit = CarouselRepository.Visit()
        try {
            val ahead = async { repository.prefetchImage(media, 1, visit) }
            withContext(Dispatchers.Default) { withTimeout(5_000) { while (fetcher.fetched.isEmpty()) delay(10) } }
            val shown = async { repository.image(media, 1, visit) }
            withContext(Dispatchers.Default) { withTimeoutOrNull(500) { while (!shown.isCompleted) delay(10) } }
            assertTrue(!shown.isCompleted, "precondition: the load waits for the prefetch")
            held.complete(Unit)

            assertEquals(ImageAhead.Stop, ahead.await(), "the prefetch could not reach the CDN")
            assertIs<ItemImage.File>(shown.await(), "the item's own load still renewed and loaded")
            assertEquals(listOf("mediaInfo:$pk"), fake.calls)
            assertEquals(listOf(oldImage(1), oldImage(1), newImage(1)), fetcher.fetched, "offline is no refusal: the link was asked again")
        } finally {
            held.complete(Unit)
        }
    }

    /** Another item's on-demand load does not wait for a prefetch that is out: the download lock is the item's, not the visit's. */
    @Test
    fun anotherItemDoesNotWaitForAPrefetch() = runTest {
        storeOldItems()
        val held = CompletableDeferred<Unit>()
        fetcher.answer = { url -> if (url == oldImage(1)) held.await(); url.toByteArray() }
        val repository = repository()
        val visit = CarouselRepository.Visit()
        try {
            val ahead = async { repository.prefetchImage(media, 1, visit) }
            withContext(Dispatchers.Default) { withTimeout(5_000) { while (fetcher.fetched.isEmpty()) delay(10) } }
            val shown = async { repository.image(media, 0, visit) }

            assertIs<ItemImage.File>(withContext(Dispatchers.Default) { withTimeout(5_000) { shown.await() } }, "item 0 loaded meanwhile")

            held.complete(Unit)
            assertIs<ImageAhead.Shown>(ahead.await())
        } finally {
            held.complete(Unit)
        }
    }

    /** Stored by an earlier sync: four images. The fake's reply (image, image, video, ...) is another shape (R20). */
    private suspend fun storeFourImages() {
        storePost()
        db.carouselItemDao().replace(pk, (0..3).map { CarouselItemEntity(pk, it, MediaType.IMAGE, 1080, 1350, oldImage(it), null, null) })
    }

    /**
     * P2: item 1's picture is being fetched ahead when item 2's refused link renews the post's links into another shape, and
     * R20 drops the post's pictures. The bytes of item 1's old link are not written after the drop: the prefetch reads the
     * item again and loads the new item 1, so position 1 never shows the old shape's slide, on this visit or a later one.
     */
    @Test
    fun aPictureFetchedAheadAcrossAReshapeIsNotWritten() = runTest {
        storeFourImages()
        images.write(pk, 3, byteArrayOf(3)) // an earlier visit's picture: the drop takes it
        val held = CompletableDeferred<Unit>()
        fetcher.answer = { url ->
            when (url) {
                oldImage(1) -> { held.await(); url.toByteArray() }
                oldImage(2) -> null // refused: item 2's own load renews the post's links, the visit's one request
                else -> url.toByteArray()
            }
        }
        val repository = repository()
        val visit = CarouselRepository.Visit()
        try {
            val ahead = async { repository.prefetchImage(media, 1, visit) }
            withContext(Dispatchers.Default) { withTimeout(5_000) { while (oldImage(1) !in fetcher.fetched) delay(10) } }
            repository.image(media, 2, visit)
            assertEquals(listOf(MediaType.IMAGE, MediaType.IMAGE, MediaType.VIDEO), rows().take(3).map { it.type }, "precondition: reshaped")
            assertNull(images.path(pk, 3), "precondition: R20 dropped the post's pictures")
            held.complete(Unit)

            val path = assertIs<ImageAhead.Shown>(ahead.await()).path
            assertContentEquals(newImage(1).toByteArray(), File(path).readBytes(), "the new item 1, never the old shape's")
            assertEquals(ItemImage.File(path), repository.image(media, 1, CarouselRepository.Visit()))
            assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        } finally {
            held.complete(Unit)
        }
    }

    /**
     * P2's re-read is bounded (RR2). Every download replaces the post's links (a sync storing its rows again and again while the
     * post is open): the prefetch and the item's own load each read the item once more and then end, two downloads each,
     * writing nothing of a replaced link and asking Instagram nothing. The waits run in real time, because Room works off the
     * test's dispatcher and a virtual-time timeout would fire at once.
     */
    @Test
    fun linksReplacedOnEveryDownloadAreReadAgainOnceThenLeft() = runTest {
        storeOldItems()
        val version = AtomicInteger()
        fetcher.answer = { url ->
            val v = version.incrementAndGet()
            db.carouselItemDao().replace(pk, rows().map { it.copy(imageUrl = "https://cdn.example.test/v$v/i${it.position}.jpg") })
            url.toByteArray()
        }
        val repository = repository()
        val visit = CarouselRepository.Visit()

        val ahead = withContext(Dispatchers.Default) { withTimeout(30_000) { repository.prefetchImage(media, 1, visit) } }
        val aheadDownloads = fetcher.fetched.size
        val shown = withContext(Dispatchers.Default) { withTimeout(30_000) { repository.image(media, 1, visit) } }
        val shownDownloads = fetcher.fetched.size - aheadDownloads

        assertEquals(ImageAhead.Skipped, ahead)
        assertEquals(2, aheadDownloads, "ahead: the stored link, then the one read again")
        assertEquals(ItemImage.Failed(CANT_LOAD), shown)
        assertEquals(2, shownDownloads, "on demand: the stored link, then the one read again")
        assertNull(images.path(pk, 1), "nothing written: every link was replaced while it downloaded")
        assertEquals(emptyList(), fake.calls)
    }

    /** P2 for the item's own load: its download out across a reshape is not written either; the load takes the new item. */
    @Test
    fun anImageDownloadedAcrossAReshapeLoadsTheNewItem() = runTest {
        storeFourImages()
        val held = CompletableDeferred<Unit>()
        fetcher.answer = { url ->
            when (url) {
                oldImage(1) -> { held.await(); url.toByteArray() }
                oldImage(2) -> null
                else -> url.toByteArray()
            }
        }
        val repository = repository()
        val visit = CarouselRepository.Visit()
        try {
            val shown = async { repository.image(media, 1, visit) }
            withContext(Dispatchers.Default) { withTimeout(5_000) { while (oldImage(1) !in fetcher.fetched) delay(10) } }
            assertEquals(ImageAhead.Skipped, repository.prefetchImage(media, 2, visit), "refused, renewed: item 2 is a video now")
            held.complete(Unit)

            val path = assertIs<ItemImage.File>(shown.await()).path
            assertContentEquals(newImage(1).toByteArray(), File(path).readBytes())
            assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        } finally {
            held.complete(Unit)
        }
    }

    // --- videos ---

    @Test
    fun aFreshVideoLinkPlaysWithoutARequest() = runTest {
        storeOldItems(videoExpiresAt = START + RealVideoSourceResolver.FRESH_MARGIN_MS + 1)

        val source = repository().video(media, 2, CarouselRepository.Visit())

        assertEquals(VideoSource.Play(Uri.parse(OLD_VIDEO), "${pk}_2"), source)
        assertEquals(emptyList(), fake.calls)
        assertEquals(emptyList(), cacheKeysAsked, "a fresh link needs no cache check")
    }

    /** The same rule as reel links: a link with at most [RealVideoSourceResolver.FRESH_MARGIN_MS] left is renewed, once a visit. */
    @Test
    fun aStaleVideoLinkIsRenewedOnce() = runTest {
        storeOldItems(videoExpiresAt = START + RealVideoSourceResolver.FRESH_MARGIN_MS)
        val repository = repository()
        val visit = CarouselRepository.Visit()

        assertEquals(VideoSource.Play(Uri.parse(newVideo(2)), "${pk}_2"), repository.video(media, 2, visit))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertEquals(newVideo(2), rows()[2].videoUrl)

        assertEquals(VideoSource.Play(Uri.parse(newVideo(2)), "${pk}_2"), repository.video(media, 2, visit), "the renewed link is stored")
        // The player was refused the renewed link: the visit's one renewal is spent.
        assertEquals(VideoSource.Unavailable(CANT_LOAD), repository.video(media, 2, visit, forceRefresh = true))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls, "no second request in this visit")

        // A new visit may renew again; a reply that brings back the very link the player was refused is not played again.
        assertEquals(VideoSource.Unavailable(CANT_LOAD), repository.video(media, 2, CarouselRepository.Visit(), forceRefresh = true))
        assertEquals(2, mediaInfoCalls.size)
    }

    /** Task 1: a video item without a link is renewed the same way. */
    @Test
    fun aVideoItemWithoutALinkIsRenewed() = runTest {
        storeOldItems(videoUrl = null, videoExpiresAt = null)

        assertEquals(VideoSource.Play(Uri.parse(newVideo(2)), "${pk}_2"), repository().video(media, 2, CarouselRepository.Visit()))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
    }

    /** R78: the bytes are on disk under the item's own key, so the link that names them is beside the point. */
    @Test
    fun aCachedVideoPlaysWhateverItsLinksAge() = runTest {
        storeOldItems(videoExpiresAt = START - 1)
        videoCache.cacheWholeFile("${pk}_2", File(tmp.root, "clip.mp4").apply { writeBytes(ByteArray(3_000) { it.toByte() }) })

        assertEquals(VideoSource.Play(Uri.parse(OLD_VIDEO), "${pk}_2"), repository().video(media, 2, CarouselRepository.Visit()))

        assertEquals(emptyList(), fake.calls)
        assertEquals(listOf("${pk}_2"), cacheKeysAsked, "the item's key, never the post's")
    }

    /**
     * R9, the reel resolver's table: the player was refused the stored link, the bytes are all cached, and the renewal failed
     * without Instagram being asked or reached (no session, a Pacer refusal, offline). The cached bytes play.
     */
    @Test
    fun aCachedVideoStillPlaysWhenItsRenewalCouldNotAskInstagram() = runTest {
        storeOldItems()
        videoCache.cacheWholeFile("${pk}_2", File(tmp.root, "clip.mp4").apply { writeBytes(ByteArray(3_000) { it.toByte() }) })
        val cached = VideoSource.Play(Uri.parse(OLD_VIDEO), "${pk}_2")

        sessionReady = false
        assertEquals(cached, repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true), "no session")
        sessionReady = true
        val cooling = InMemoryCooldownStore().apply { onRateLimited(START) }
        assertEquals(cached, repository(cooldowns = cooling).video(media, 2, CarouselRepository.Visit(), forceRefresh = true), "refused")
        fake.failures = FakeFailures { InstagramException.Transient() }
        assertEquals(cached, repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true), "offline")

        assertEquals(listOf("mediaInfo:$pk"), fake.calls, "only the offline one was sent")
    }

    /**
     * R9: when Instagram answered against the item (a login or a challenge, a rate limit, gone, a reply it could not read), the
     * cached bytes do not play, as for reels: the viewer says what happened.
     */
    @Test
    fun aCachedVideoDoesNotPlayWhenInstagramAnsweredAgainstIt() = runTest {
        storeOldItems()
        videoCache.cacheWholeFile("${pk}_2", File(tmp.root, "clip.mp4").apply { writeBytes(ByteArray(3_000) { it.toByte() }) })
        val answers = listOf(
            InstagramException.LoginRequired() to NEEDS_ATTENTION,
            InstagramException.ChallengeRequired("https://example.test/challenge/1/") to NEEDS_ATTENTION,
            InstagramException.RateLimited() to "Instagram is limiting requests",
            InstagramException.ShapeChanged("items[0]") to CANT_LOAD,
        )
        for ((failure, message) in answers) {
            fake.failures = FakeFailures { failure }
            // A fresh Pacer each time: the rate limit arms its cooldown, which would make the next one a refusal.
            assertEquals(VideoSource.Unavailable(message), repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true), "$failure")
        }
        fake.failures = FakeFailures { null }
        fake.library.unsave(pk)
        assertEquals(VideoSource.Unavailable(GONE), repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true), "gone")

        assertEquals(answers.size + 1, mediaInfoCalls.size)
    }

    @Test
    fun anImageItemIsNotAVideo() = runTest {
        storeOldItems()

        assertEquals(VideoSource.Unavailable(CANT_LOAD), repository().video(media, 0, CarouselRepository.Visit()))
        assertEquals(emptyList(), fake.calls)
    }

    /** R3: Mock mode plays the bundled clip for every video item, under the item's own key, whatever its stored link. */
    @Test
    fun mockModeVideoItemsPlayTheBundledClip() = runTest {
        storeOldItems(videoExpiresAt = START - 1)
        val clip = Uri.parse("android.resource://io.github.yuriimurha.reels/raw/sample_clip")
        val repository = repository(sampleClip = clip)

        assertEquals(VideoSource.Play(clip, "${pk}_2"), repository.video(media, 2, CarouselRepository.Visit()))
        assertEquals(VideoSource.Play(clip, "${pk}_2"), repository.video(media, 2, CarouselRepository.Visit(), forceRefresh = true))
        assertEquals(VideoSource.Unavailable(CANT_LOAD), repository.video(media, 0, CarouselRepository.Visit()))
        assertEquals(emptyList(), fake.calls)
    }

    // --- R20: items of another shape take the post's cached item media with them ---

    /** What earlier visits left: the images of items 0 and 1, and every byte of item 2's video. */
    private fun cacheEveryItem() {
        images.write(pk, 0, byteArrayOf(0))
        images.write(pk, 1, byteArrayOf(1))
        videoCache.cacheWholeFile("${pk}_2", File(tmp.root, "clip.mp4").apply { writeBytes(ByteArray(3_000) { it.toByte() }) })
    }

    /** Which of item 0's image, item 1's image and item 2's video are still cached. */
    private fun cachedItems() = listOf(images.path(pk, 0) != null, images.path(pk, 1) != null, videoCache.isFullyCached("${pk}_2"))

    /** The fake's first three items (image, image, video: the stored shape), changed by [change] on their way back. */
    private fun replyWithFirstThree(change: (List<RemoteCarouselItem>) -> List<RemoteCarouselItem> = { it }) {
        onReply = { reply -> reply?.copy(carouselItems = change(reply.carouselItems!!.take(3))) }
    }

    /** The poster deleted slide 1: the video moved to position 1, so position 1's image and position 2's video are stale. */
    @Test
    fun aRenewalThatLostASlideDropsTheCachedItemMedia() = runTest {
        storeOldItems()
        cacheEveryItem()
        replyWithFirstThree { items -> items.filterNot { it.position == 1 }.map { if (it.position == 2) it.copy(position = 1) else it } }

        repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true)

        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO), rows().map { it.type }, "precondition: the new set is stored")
        assertEquals(listOf(false, false, false), cachedItems(), "every image and video of the post's items went")
    }

    /** The usual renewal: the same items with new links. What the owner viewed stays, so it still shows offline. */
    @Test
    fun aRenewalWithTheSameItemsKeepsTheCachedItemMedia() = runTest {
        storeOldItems()
        cacheEveryItem()
        replyWithFirstThree()

        assertEquals(VideoSource.Play(Uri.parse(newVideo(2)), "${pk}_2"), repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true))

        assertEquals(newImage(0), rows()[0].imageUrl, "precondition: the renewed links are stored")
        assertEquals(listOf(true, true, true), cachedItems(), "nothing dropped")
    }

    /** The same count, but a video where an image was: position 1's image is another item's now, so the post's cached media go. */
    @Test
    fun aRenewalThatChangedAnItemsTypeDropsTheCachedItemMedia() = runTest {
        storeOldItems()
        cacheEveryItem()
        replyWithFirstThree { items ->
            items.map { if (it.position == 1) it.copy(type = MediaType.VIDEO, videoUrl = newVideo(1), videoUrlExpiresAt = null) else it }
        }

        repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true)

        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.VIDEO), rows().map { it.type }, "precondition: stored")
        assertEquals(listOf(false, false, false), cachedItems())
    }

    /** The lazy fill has nothing stored to compare with, and so nothing cached to drop. */
    @Test
    fun aFillDropsNothing() = runTest {
        storePost()
        images.write(pk, 0, byteArrayOf(0))

        assertIs<ItemsResult.Items>(repository().items(media, CarouselRepository.Visit()))
        assertTrue(images.path(pk, 0) != null)
    }

    // --- R28: a child's own id tells a reorder that keeps every position's type ---

    /**
     * The poster swapped slides 0 and 1, two images: every position keeps its type, so R20's shape alone cannot see it, but
     * positions 0 and 1 hold other children now (their child pks moved with them). Their cached pictures would show the old
     * slides: the post's cached item media go.
     */
    @Test
    fun aRenewalThatSwappedTwoSlidesOfOneTypeDropsTheCachedItemMedia() = runTest {
        storeOldItems(childPks = fakeChildPks)
        cacheEveryItem()
        replyWithFirstThree { items -> listOf(items[1].copy(position = 0), items[0].copy(position = 1), items[2]) }

        repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true)

        assertEquals(listOf(MediaType.IMAGE, MediaType.IMAGE, MediaType.VIDEO), rows().map { it.type }, "precondition: the same shape")
        assertEquals(listOf(fakeChildPks[1], fakeChildPks[0], fakeChildPks[2]), rows().map { it.childPk }, "precondition: swapped")
        assertEquals(listOf(false, false, false), cachedItems(), "every image and video of the post's items went")
    }

    /** The usual renewal, with the child pks known on both sides: the same children with new links keep everything (offline). */
    @Test
    fun aRenewalWithTheSameChildrenAndNewLinksKeepsTheCachedItemMedia() = runTest {
        storeOldItems(childPks = fakeChildPks)
        cacheEveryItem()
        replyWithFirstThree()

        assertEquals(VideoSource.Play(Uri.parse(newVideo(2)), "${pk}_2"), repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true))

        assertEquals(List(3) { newImage(it) }, rows().map { it.imageUrl }, "precondition: the renewed links are stored")
        assertEquals(fakeChildPks, rows().map { it.childPk }, "precondition: the same children, known")
        assertTrue(fakeChildPks.all { it != null }, "precondition: known")
        assertEquals(listOf(true, true, true), cachedItems(), "nothing dropped")
    }

    /**
     * Null means unknown on either side, and never drops: rows stored before version 3 get their child pks from the next
     * renewal with nothing dropped, and a reply without them later drops nothing either (and stores them unknown).
     */
    @Test
    fun anUnknownChildPkOnEitherSideDropsNothingAtARenewal() = runTest {
        storeOldItems()
        cacheEveryItem()
        replyWithFirstThree()
        repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true)
        assertEquals(fakeChildPks, rows().map { it.childPk }, "precondition: filled in")
        assertEquals(listOf(true, true, true), cachedItems(), "null to a value drops nothing")

        replyWithFirstThree { items -> items.map { it.copy(childPk = null) } }
        repository().video(media, 2, CarouselRepository.Visit(), forceRefresh = true)
        assertEquals(listOf(null, null, null), rows().map { it.childPk }, "precondition: unknown again")
        assertEquals(listOf(true, true, true), cachedItems(), "a value to null drops nothing either")
    }

    // --- Delete library ---

    /** Review Focus 5: Delete library removes the downloaded item images with the rows. */
    @Test
    fun deleteLibraryRemovesTheImages() = runTest {
        storeOldItems()
        fetcher.answer = { url -> url.toByteArray() }
        val repository = repository()
        val paths = listOf(0, 1).map { assertIs<ItemImage.File>(repository.image(media, it, CarouselRepository.Visit())).path }
        val library = LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs")), carouselImages = images)

        assertEquals(LibraryDeletion.COMPLETE, library.deleteLibrary())

        for (path in paths) assertTrue(!File(path).exists(), "$path was left behind")
        assertEquals(emptyList(), rows())
        assertEquals(ItemImage.Failed(CANT_LOAD), repository.image(media, 0, CarouselRepository.Visit()), "the post is gone, and so is its image")
        assertEquals(emptyList(), fake.calls)
    }

    @Test
    fun imagesThatCannotBeRemovedAreReportedByDeleteLibrary() = runTest {
        storeOldItems()
        val dir = File(images.write(pk, 0, byteArrayOf(1))).parentFile!!
        val library = LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs")), carouselImages = images)
        assertTrue(dir.setWritable(false), "precondition: a folder whose files cannot be removed")
        try {
            assertEquals(LibraryDeletion.CACHED_FILES_KEPT, library.deleteLibrary())
        } finally {
            dir.setWritable(true)
        }
        assertEquals(emptyList(), rows(), "the rows are gone all the same")
    }

    // --- R26: a video item's poster fetched ahead; its start written ahead into the video cache ---

    /**
     * A video item's poster (its preview frame) is fetched ahead as an image item's picture is: its stored link, through the
     * CDN lane, into the same store under `{pk}_{position}`, where nothing else of a video item is kept. The item's own load
     * then serves that file, and Instagram is asked nothing.
     */
    @Test
    fun aPosterIsFetchedAheadThroughTheCdnLaneIntoTheItemsOwnFile() = runTest {
        storeOldItems()
        var calledAt: Long? = null
        fetcher.answer = { url -> calledAt = testScheduler.currentTime; url.toByteArray() }
        val repository = repository()
        val visit = CarouselRepository.Visit()
        val before = testScheduler.currentTime

        val path = assertIs<ImageAhead.Shown>(repository.prefetchPoster(media, 2, visit)).path

        assertEquals(images.path(pk, 2), path)
        assertContentEquals(oldImage(2).toByteArray(), File(path).readBytes())
        assertTrue(checkNotNull(calledAt) - before in PacingPolicy.Conservative.cdnJitterMs, "through the CDN lane")
        assertEquals(listOf("${pk}_2.jpg"), File(tmp.root, "carousel").list()!!.toList(), "the item's own file, and no other")
        assertEquals(ItemImage.File(path), repository.image(media, 2, visit), "the item's own load serves it")
        assertEquals(listOf(oldImage(2)), fetcher.fetched)
        assertEquals(emptyList(), fake.calls)
        assertEquals(emptyList(), starts.writes, "a poster is no video start")
    }

    /** No poster ahead for item 0 (the cover shows it), an image item, past the last item, or a post without stored items. */
    @Test
    fun aPosterIsFetchedOnlyForAStoredVideoItemPastTheCover() = runTest {
        fetcher.answer = { url -> url.toByteArray() }
        val repository = repository()
        storePost()
        assertEquals(ImageAhead.Skipped, repository.prefetchPoster(media, 2, CarouselRepository.Visit()), "no stored items: never the fill")

        db.carouselItemDao().replace(pk, (0..2).map { CarouselItemEntity(pk, it, MediaType.VIDEO, 1, 1, oldImage(it), OLD_VIDEO, null) })
        for (position in listOf(0, 3, -1)) {
            assertEquals(ImageAhead.Skipped, repository.prefetchPoster(media, position, CarouselRepository.Visit()), "item $position")
        }
        storeOldItems()
        assertEquals(ImageAhead.Skipped, repository.prefetchPoster(media, 1, CarouselRepository.Visit()), "an image item has no poster")
        assertEquals(ImageAhead.Skipped, repository.prefetchImage(media, 2, CarouselRepository.Visit()), "and a video item no image")

        assertEquals(emptyList(), fetcher.fetched)
        assertEquals(emptyList(), fake.calls)
    }

    /** R25 for posters: a poster link refused ahead spends the visit's one renewal, and the poster loads from its renewed link. */
    @Test
    fun aRefusedPosterSpendsTheVisitsRenewalAndLoadsTheRenewedLink() = runTest {
        storeOldItems()
        fetcher.answer = { url -> if (url.startsWith("fake://")) url.toByteArray() else null }
        val repository = repository()
        val visit = CarouselRepository.Visit()

        val path = assertIs<ImageAhead.Shown>(repository.prefetchPoster(media, 2, visit)).path

        assertContentEquals(newImage(2).toByteArray(), File(path).readBytes())
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
        assertEquals(ImageAhead.Shown(path), repository.prefetchPoster(media, 2, visit))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls, "still the one request")
    }

    /** P1 and D3 for posters: the CDN's 429, offline and a post unsaved stop the queue, and nothing is asked of Instagram. */
    @Test
    fun aPosterThatCannotBeFetchedStopsTheQueue() = runTest {
        storeOldItems()
        val repository = repository()
        val visit = CarouselRepository.Visit()

        fetcher.answer = { throw CdnRateLimited() }
        assertEquals(ImageAhead.Stop, repository.prefetchPoster(media, 2, visit), "cdn limit")
        fetcher.answer = { throw java.io.IOException("no route") }
        assertEquals(ImageAhead.Stop, repository.prefetchPoster(media, 2, visit), "offline")
        fetcher.answer = { url -> url.toByteArray() }
        db.mediaDao().markRemoved(listOf(pk), START)
        assertEquals(ImageAhead.Stop, repository.prefetchPoster(media, 2, visit), "unsaved by a sync")

        assertEquals(listOf(oldImage(2), oldImage(2)), fetcher.fetched, "nothing for the unsaved post")
        assertNull(images.path(pk, 2))
        assertEquals(emptyList(), fake.calls)
    }

    /** P2 for posters: the item's links were replaced while its poster downloaded; the old bytes are not written, the new link is. */
    @Test
    fun aPosterDownloadedAcrossALinkChangeIsNotWritten() = runTest {
        storeOldItems()
        fetcher.answer = { url ->
            if (url == oldImage(2)) db.carouselItemDao().replace(pk, rows().map { it.copy(imageUrl = newImage(it.position)) })
            url.toByteArray()
        }

        val path = assertIs<ImageAhead.Shown>(repository().prefetchPoster(media, 2, CarouselRepository.Visit())).path

        assertContentEquals(newImage(2).toByteArray(), File(path).readBytes())
        assertEquals(listOf(oldImage(2), newImage(2)), fetcher.fetched)
        assertEquals(emptyList(), fake.calls)
    }

    /**
     * A video item's start goes into the video cache under `{pk}_{position}`: [VIDEO_START_BYTES] of its stored link, through
     * the CDN lane (its jitter passes first), and Instagram is asked nothing.
     */
    @Test
    fun aVideoStartIsWrittenUnderTheItemsKeyThroughTheCdnLane() = runTest {
        storeOldItems()
        var calledAt: Long? = null
        starts.answer = { _, _ -> calledAt = testScheduler.currentTime; StartWrite.WRITTEN }
        val before = testScheduler.currentTime

        assertEquals(StartAhead.Cached, repository().preloadVideo(media, 2, CarouselRepository.Visit()))

        assertEquals(listOf("${pk}_2 $OLD_VIDEO ${1 shl 20}"), starts.writes.toList())
        assertTrue(checkNotNull(calledAt) - before in PacingPolicy.Conservative.cdnJitterMs, "through the CDN lane")
        assertEquals(emptyList(), fake.calls)
        assertEquals(emptyList(), fetcher.fetched)
    }

    /** A start whose bytes are cached already costs nothing: no write, and not even the lane's jitter. */
    @Test
    fun aCachedStartFetchesNothing() = runTest {
        storeOldItems()
        starts.cached += "${pk}_2"
        val before = testScheduler.currentTime

        assertEquals(StartAhead.Cached, repository().preloadVideo(media, 2, CarouselRepository.Visit()))

        assertEquals(emptyList(), starts.writes)
        assertEquals(before, testScheduler.currentTime, "the lane was not waited for")
    }

    /** Only a link the item would play as it is: a stale one, or none, is skipped, never renewed for a start ahead. */
    @Test
    fun aStaleVideoLinkIsSkippedAndNothingIsAsked() = runTest {
        val repository = repository()
        storeOldItems(videoExpiresAt = START + RealVideoSourceResolver.FRESH_MARGIN_MS)
        assertEquals(StartAhead.Skipped, repository.preloadVideo(media, 2, CarouselRepository.Visit()), "10 minutes or less left")
        storeOldItems(videoUrl = null, videoExpiresAt = null)
        assertEquals(StartAhead.Skipped, repository.preloadVideo(media, 2, CarouselRepository.Visit()), "no link")
        storeOldItems(videoExpiresAt = null)
        assertEquals(StartAhead.Skipped, repository.preloadVideo(media, 2, CarouselRepository.Visit()), "no expiry")

        assertEquals(emptyList(), starts.writes)
        assertEquals(emptyList(), fake.calls, "no mediaInfo for a start ahead")
    }

    /**
     * A link the CDN refuses as running out (403, 410) is skipped quietly (no renewal) and never asked again in the visit: not
     * by another start ahead, and not by the item's own play, which goes straight to the visit's one renewal (as it would
     * after the player's own 403 or 410, R27) and plays the new link. A new visit may ask again.
     */
    @Test
    fun anExpiredStartIsSkippedAndNeverAskedAgainInTheVisit() = runTest {
        storeOldItems()
        starts.answer = { _, _ -> StartWrite.EXPIRED }
        val repository = repository()
        val visit = CarouselRepository.Visit()

        assertEquals(StartAhead.Skipped, repository.preloadVideo(media, 2, visit))
        assertEquals(StartAhead.Skipped, repository.preloadVideo(media, 2, visit))
        assertEquals(1, starts.writes.size, "the refused link once in the visit")
        assertEquals(emptyList(), fake.calls, "no renewal for a start ahead")

        assertEquals(VideoSource.Play(Uri.parse(newVideo(2)), "${pk}_2"), repository.video(media, 2, visit), "the item plays a renewed link")
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)

        storeOldItems()
        assertEquals(StartAhead.Skipped, repository.preloadVideo(media, 2, CarouselRepository.Visit()))
        assertEquals(2, starts.writes.size, "a new visit asks again")
    }

    /** The item's own play after an expired start, when the visit's renewal brings the same link back: nothing to play. */
    @Test
    fun anExpiredStartWhoseRenewalBringsTheSameLinkCannotLoad() = runTest {
        storeOldItems()
        starts.answer = { _, _ -> StartWrite.EXPIRED }
        replyWithFirstThree { items -> items.map { if (it.position == 2) it.copy(videoUrl = OLD_VIDEO) else it } }
        val repository = repository()
        val visit = CarouselRepository.Visit()
        repository.preloadVideo(media, 2, visit)

        assertEquals(VideoSource.Unavailable(CANT_LOAD), repository.video(media, 2, visit))
        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
    }

    /**
     * R27: a start refused with any other 4xx (a 404, a 408) is passed quietly and not asked again by a start in the visit,
     * but it changes nothing for the item's own play: the player plays the stored link, as before R26, and a 404 there renews
     * nothing (R19). A pre-load never changes what a play costs.
     */
    @Test
    fun aStartRefusedOtherwiseIsPassedAndLeavesThePlayToThePlayer() = runTest {
        storeOldItems()
        starts.answer = { _, _ -> StartWrite.REFUSED }
        val repository = repository()
        val visit = CarouselRepository.Visit()

        assertEquals(StartAhead.Skipped, repository.preloadVideo(media, 2, visit))
        assertEquals(StartAhead.Skipped, repository.preloadVideo(media, 2, visit))
        assertEquals(1, starts.writes.size, "not asked again by a start in the visit")

        assertEquals(VideoSource.Play(Uri.parse(OLD_VIDEO), "${pk}_2"), repository.video(media, 2, visit), "the stored link, as the player always had")
        assertEquals(emptyList(), fake.calls, "no mediaInfo")
    }

    /** I1: a start the cache failed (a full disk, a cache that could not start) stops the queue, with no request to Instagram. */
    @Test
    fun aFailingCacheStopsTheQueue() = runTest {
        storeOldItems()
        starts.answer = { _, _ -> StartWrite.FAILED }

        assertEquals(StartAhead.Stop, repository().preloadVideo(media, 2, CarouselRepository.Visit()))

        assertEquals(emptyList(), fake.calls)
    }

    /**
     * M2: a start write cancelled part way (the owner left the post, or settled on the item) keeps what it wrote, so it is
     * checked as a finished one is: kept when the item still has that link, dropped when the link was replaced while it was
     * written (R20's drop may already have run) or the post left the library. The cancellation still reaches the caller.
     */
    @Test
    fun aCancelledStartIsCheckedAsAFinishedOneIs() = runTest {
        storeOldItems()
        val repository = repository()
        suspend fun cancelledWhile(during: suspend () -> Unit) {
            val writing = CompletableDeferred<Unit>()
            starts.answer = { _, _ ->
                during()
                writing.complete(Unit)
                awaitCancellation()
            }
            val job = async { repository.preloadVideo(media, 2, CarouselRepository.Visit()) }
            withContext(Dispatchers.Default) { withTimeout(5_000) { writing.await() } }
            job.cancel()
            withContext(Dispatchers.Default) { withTimeout(5_000) { job.join() } }
            assertTrue(job.isCancelled)
        }

        cancelledWhile { }
        assertEquals(emptyList(), starts.removed.toList(), "the item's own start, kept")

        cancelledWhile { db.carouselItemDao().replace(pk, rows().map { item -> item.copy(videoUrl = item.videoUrl?.let { "https://cdn.example.test/new/v2.mp4?oe=1" }) }) }
        assertEquals(listOf("${pk}_2"), starts.removed.toList(), "a replaced link's bytes dropped")

        storeOldItems()
        cancelledWhile { db.mediaDao().markRemoved(listOf(pk), START) }
        assertEquals(listOf("${pk}_2", "${pk}_2"), starts.removed.toList(), "an unsaved post's bytes dropped")
        assertEquals(emptyList(), fake.calls)
    }

    /**
     * N-M1: a cancel that lands as a finished write is being checked (the owner settles on the item just then) does not skip
     * the check: bytes of a link replaced meanwhile are dropped all the same, before the item's play can read them.
     */
    @Test
    fun aStartCancelledAsItsWriteFinishesIsStillChecked() = runTest {
        storeOldItems()
        val repository = repository()
        lateinit var job: Job
        starts.answer = { _, _ ->
            db.carouselItemDao().replace(pk, rows().map { item -> item.copy(videoUrl = item.videoUrl?.let { NEW_LINK }) })
            job.cancel() // the settle's cancel, landing after the bytes are in and before they are checked
            StartWrite.WRITTEN
        }
        job = async { repository.preloadVideo(media, 2, CarouselRepository.Visit()) }

        withContext(Dispatchers.Default) { withTimeout(5_000) { job.join() } }

        assertTrue(job.isCancelled)
        assertEquals(listOf("${pk}_2"), starts.removed.toList(), "the replaced link's bytes dropped all the same")
    }

    /**
     * N-M1, the re-review's probe through the repository: the real writer (`CacheWriter`, a real cache) is writing item 2's
     * start from its old link when the post is reshaped (item 2 is another video now; R20's drop cannot see the write's
     * uncommitted bytes), and the owner settles on item 2, which cancels the write. Its read is stalled (one the abort cannot
     * cut, M5), so it outlasts the viewer's 300 ms. The item's source is not handed out until the write has stopped and its
     * old bytes were dropped: a player opened earlier would read past the write's lock and then, at its next look at the
     * cache, the old video's bytes it committed late. The player, as the app builds it, then reads none of them.
     */
    @Test
    fun aStartOfAReplacedLinkStillBeingWrittenHoldsTheItemsPlayUntilItIsChecked() = runTest {
        storeOldItems()
        val stall = Stall()
        val repository = repository(starts = stallingWriter(videoOf(OLD_BYTE), stall))
        val visit = CarouselRepository.Visit()
        val writing = async { repository.preloadVideo(media, 2, visit) }
        try {
            stall.awaitReached()
            db.carouselItemDao().replace(pk, rows().map { item -> item.copy(videoUrl = item.videoUrl?.let { NEW_LINK }) })
            videoCache.removeCarouselItems(pk) // R20's drop, as a reshape runs it
            writing.cancel() // the owner settles on item 2: the viewer stops its start

            val play = async { repository.video(media, 2, visit) }
            withContext(Dispatchers.Default) { delay(600) } // past the viewer's 300 ms
            assertFalse(play.isCompleted, "the item was handed out while the old link's write could still commit")

            stall.release()
            val source = withContext(Dispatchers.Default) { withTimeout(10_000) { play.await() } }
            assertEquals(VideoSource.Play(Uri.parse(NEW_LINK), "${pk}_2"), source)
            assertTrue(writing.isCancelled)
            assertEquals(0L, videoCache.cache.getCachedBytes("${pk}_2", 0, Long.MAX_VALUE), "the old video's bytes went first")
            assertEquals(0, playerReads(source as VideoSource.Play, videoOf(NEW_BYTE)).count { it == OLD_BYTE }, "no old bytes read")
        } finally {
            stall.release()
        }
    }

    /**
     * N-M1 across visits, the review's cross-visit probe: item 2's start is stalled on its old link and the post is reshaped;
     * the owner leaves the post (its visit ends without waiting for the write) and comes back while the write is still
     * stopping. The new visit's play is not handed out until that write has stopped and its old bytes were dropped. The
     * player, as the app builds it, reads from the moment it is handed the item, and reads none of the old video's bytes,
     * also in the worst case: the old write commits them while the repository cannot drop them yet (this thread holds the
     * test's dispatcher, as a busy main thread would).
     */
    @Test
    fun aStartOfAReplacedLinkStillStoppingFromAnEarlierVisitHoldsTheNextVisitsPlay() = runTest {
        storeOldItems()
        val stall = Stall()
        val repository = repository(starts = stallingWriter(videoOf(OLD_BYTE), stall))
        val writing = async { repository.preloadVideo(media, 2, CarouselRepository.Visit()) }
        try {
            stall.awaitReached()
            db.carouselItemDao().replace(pk, rows().map { item -> item.copy(videoUrl = item.videoUrl?.let { NEW_LINK }) })
            videoCache.removeCarouselItems(pk) // R20's drop, as a reshape runs it
            writing.cancel() // the owner leaves the post: its visit ends, and does not wait for the write

            val play = async { repository.video(media, 2, CarouselRepository.Visit()) } // and comes back: a new visit
            val player = PlayerOnceHandedOut(play, videoOf(NEW_BYTE))
            withContext(Dispatchers.Default) { delay(600) }
            val handedOutWhileStalled = play.isCompleted
            stall.release() // the stalled read returns: the old write stops, and commits what it wrote
            blockUntilCached("${pk}_2")
            player.goOn()
            player.awaitDone()

            assertEquals(0, player.oldBytes(), "the player read the old video's bytes")
            assertEquals(900 * 1024, player.read)
            assertFalse(handedOutWhileStalled, "the item was handed out while the earlier visit's write could still commit")
            assertTrue(writing.isCancelled)
        } finally {
            stall.release()
        }
    }

    /**
     * N-M1: while an earlier visit's write of a key is still stopping, a new visit writes no start of that key (it is passed,
     * with no request: two writes of one key would contend for its lock), and the item's play still waits for the old one.
     * Once that write has stopped and been checked its entry goes, so a later visit writes the start again.
     */
    @Test
    fun aStartStillStoppingFromAnEarlierVisitIsLeftAloneAndThenForgotten() = runTest {
        storeOldItems()
        val stall = Stall()
        val writes = AtomicInteger()
        val repository = repository(starts = stallingWriter(videoOf(OLD_BYTE), stall, writes))
        val writing = async { repository.preloadVideo(media, 2, CarouselRepository.Visit()) }
        try {
            stall.awaitReached()
            db.carouselItemDao().replace(pk, rows().map { item -> item.copy(videoUrl = item.videoUrl?.let { NEW_LINK }) })
            writing.cancel()

            val second = CarouselRepository.Visit()
            val again = async { repository.preloadVideo(media, 2, second) }
            assertEquals(StartAhead.Skipped, withContext(Dispatchers.Default) { withTimeout(5_000) { again.await() } })
            assertEquals(1, writes.get(), "no second write of the key while the first is out")
            val play = async { repository.video(media, 2, second) }
            withContext(Dispatchers.Default) { delay(300) }
            assertFalse(play.isCompleted, "the item's play still waits for the earlier visit's write")

            stall.release()
            val source = withContext(Dispatchers.Default) { withTimeout(10_000) { play.await() } }
            assertEquals(VideoSource.Play(Uri.parse(NEW_LINK), "${pk}_2"), source)
            withContext(Dispatchers.Default) { withTimeout(10_000) { writing.join() } }
            val later = async { repository.preloadVideo(media, 2, CarouselRepository.Visit()) }
            val outcome = withContext(Dispatchers.Default) { withTimeout(10_000) { later.await() } }
            assertEquals(StartAhead.Cached, outcome, "a later visit writes it")
            assertEquals(2, writes.get())
        } finally {
            stall.release()
        }
    }

    /**
     * N-M1 holds nothing it need not: leaving the post ends a held play at once (it does not wait for the write), and a post
     * that is not a carousel, or an item that is not a video, answers at once while a write of another link is stalled.
     */
    @Test
    fun aHeldPlayEndsWithTheVisitAndNothingElseIsHeld() = runTest {
        storeOldItems()
        val stall = Stall()
        val repository = repository(starts = stallingWriter(videoOf(OLD_BYTE), stall))
        val writing = async { repository.preloadVideo(media, 2, CarouselRepository.Visit()) }
        try {
            stall.awaitReached()
            db.carouselItemDao().replace(pk, rows().map { item -> item.copy(videoUrl = item.videoUrl?.let { NEW_LINK }) })
            writing.cancel()
            val visit = CarouselRepository.Visit()
            val play = async { repository.video(media, 2, visit) }
            withContext(Dispatchers.Default) { delay(300) }
            assertFalse(play.isCompleted, "precondition: the play is held")

            val reel = async { repository.video(mediaEntity(pk, MediaType.REEL), 2, visit) }
            val image = async { repository.video(media, 1, visit) }
            assertEquals(VideoSource.Unavailable(CANT_LOAD), withContext(Dispatchers.Default) { withTimeout(2_000) { reel.await() } })
            assertEquals(VideoSource.Unavailable(CANT_LOAD), withContext(Dispatchers.Default) { withTimeout(2_000) { image.await() } })
            val ended = measureTimeMillis {
                play.cancel() // the post is left: the visit's scope ends
                withContext(Dispatchers.Default) { withTimeout(2_000) { play.join() } }
            }
            assertTrue(play.isCancelled && ended < 1_000, "the held play ended with the visit, in $ended ms")
            assertFalse(writing.isCompleted, "and did not wait for the write, still stalled")
        } finally {
            stall.release()
        }
    }

    /**
     * N-M1's other side: a start of the very link the item plays is the same video, so its play is handed out at once while
     * the write is still stopping (the viewer's 300 ms cap holds), and what that write had written stays.
     */
    @Test
    fun aStartOfTheSameLinkStillBeingWrittenDoesNotHoldThePlay() = runTest {
        storeOldItems()
        val stall = Stall()
        val repository = repository(starts = stallingWriter(videoOf(OLD_BYTE), stall))
        val visit = CarouselRepository.Visit()
        val writing = async { repository.preloadVideo(media, 2, visit) }
        try {
            stall.awaitReached()
            writing.cancel()

            val play = async { repository.video(media, 2, visit) }
            val source = withContext(Dispatchers.Default) { withTimeout(5_000) { play.await() } }

            assertEquals(VideoSource.Play(Uri.parse(OLD_VIDEO), "${pk}_2"), source, "at once, the write still stalled")
            stall.release()
            withContext(Dispatchers.Default) { withTimeout(10_000) { writing.join() } }
            assertTrue(videoCache.cache.getCachedBytes("${pk}_2", 0, Long.MAX_VALUE) > 0, "what it wrote stays: the same video")
        } finally {
            stall.release()
        }
    }

    /**
     * N-M1's entry goes however the write ends. A write that throws (not a cancel: a writer or a cache failing in a way
     * nothing foresaw) still takes its key out of the starts out, so the key is neither left skipped (a later start of it is
     * written) nor held (the item's play from another link is handed out at once, not after a check that never comes).
     */
    @Test
    fun aStartWriteThatThrowsLeavesItsKeyNeitherSkippedNorHeld() = runTest {
        storeOldItems()
        val repository = repository()
        starts.answer = { _, _ -> throw IllegalStateException("the writer failed") }

        assertEquals(StartAhead.Stop, repository.preloadVideo(media, 2, CarouselRepository.Visit()), "unforeseen: the queue stops")

        // The item has another link now: a start of the old one still out would hold its play.
        db.carouselItemDao().replace(pk, rows().map { item -> item.copy(videoUrl = item.videoUrl?.let { NEW_LINK }) })
        val play = async { repository.video(media, 2, CarouselRepository.Visit()) }
        assertEquals(
            VideoSource.Play(Uri.parse(NEW_LINK), "${pk}_2"),
            withContext(Dispatchers.Default) { withTimeoutOrNull(2_000) { play.await() } },
            "not held",
        )
        starts.answer = { _, _ -> StartWrite.WRITTEN }
        assertEquals(StartAhead.Cached, repository.preloadVideo(media, 2, CarouselRepository.Visit()), "not skipped: written again")
        assertEquals(listOf("${pk}_2 $OLD_VIDEO ${1 shl 20}", "${pk}_2 $NEW_LINK ${1 shl 20}"), starts.writes.toList())
    }

    /**
     * N-M1 holds a play only on a start of its own key, `{pk}_{position}`. With a start of another link stalled under item 3
     * of the post (its link replaced meanwhile), item 3's own play is held, while the post's other video item and item 3 of
     * another post play at once.
     */
    @Test
    fun aPlayIsHeldOnlyByAStartOfItsOwnItem() = runTest {
        val oldVideo3 = "https://cdn.example.test/old/v3.mp4?oe=1"
        val newVideo3 = "https://cdn.example.test/new/v3.mp4?oe=1"
        storeOldItems()
        db.carouselItemDao().replace(pk, rows() + CarouselItemEntity(pk, 3, MediaType.VIDEO, 1080, 1920, oldImage(3), oldVideo3, START + 3_600_000))
        val other = mediaEntity(OTHER_PK, MediaType.CAROUSEL)
        db.mediaDao().upsert(listOf(other))
        // Its own links: a hold told by the link alone, or by the position alone, would show here.
        db.carouselItemDao().replace(OTHER_PK, rows().map { it.copy(parentPk = OTHER_PK, videoUrl = it.videoUrl?.replace("/old/", "/other/")) })
        val stall = Stall()
        val repository = repository(starts = stallingWriter(videoOf(OLD_BYTE), stall))
        val writing = async { repository.preloadVideo(media, 3, CarouselRepository.Visit()) }
        try {
            stall.awaitReached()
            db.carouselItemDao().replace(pk, rows().map { if (it.position == 3) it.copy(videoUrl = newVideo3) else it })
            val visit = CarouselRepository.Visit()

            val own = async { repository.video(media, 3, visit) }
            withContext(Dispatchers.Default) { delay(300) }
            assertFalse(own.isCompleted, "precondition: item 3's own play is held by its start of another link")

            val sameVisit = async { repository.video(media, 2, visit) }
            val otherPost = async { repository.video(other, 3, CarouselRepository.Visit()) }
            assertEquals(
                VideoSource.Play(Uri.parse(OLD_VIDEO), "${pk}_2"),
                withContext(Dispatchers.Default) { withTimeoutOrNull(2_000) { sameVisit.await() } },
                "the post's other video item: not held",
            )
            assertEquals(
                VideoSource.Play(Uri.parse(oldVideo3.replace("/old/", "/other/")), "${OTHER_PK}_3"),
                withContext(Dispatchers.Default) { withTimeoutOrNull(2_000) { otherPost.await() } },
                "another post's item 3: not held",
            )
            assertFalse(own.isCompleted, "item 3's play still waits")

            stall.release()
            assertEquals(
                VideoSource.Play(Uri.parse(newVideo3), "${pk}_3"),
                withContext(Dispatchers.Default) { withTimeout(10_000) { own.await() } },
            )
            withContext(Dispatchers.Default) { withTimeout(10_000) { writing.join() } }
        } finally {
            stall.release()
        }
    }

    /**
     * M4: a video item Instagram sent no preview frame for has no poster. Ahead it is passed (not a stop: the queue goes on to
     * the starts), and its own load fails quietly; neither spends the visit's `mediaInfo`, on any visit. Its start still comes.
     */
    @Test
    fun aVideoItemWithNoPosterLinkAsksNothingForAPoster() = runTest {
        storeOldItems()
        db.carouselItemDao().replace(pk, rows().map { if (it.position == 2) it.copy(imageUrl = "") else it })
        fetcher.answer = { url -> url.toByteArray() }
        val repository = repository()

        for (visit in listOf(CarouselRepository.Visit(), CarouselRepository.Visit())) {
            assertEquals(ImageAhead.Skipped, repository.prefetchPoster(media, 2, visit))
            assertEquals(ItemImage.Failed(CANT_LOAD), repository.image(media, 2, visit))
            assertEquals(StartAhead.Cached, repository.preloadVideo(media, 2, visit), "its start still comes")
        }

        assertEquals(emptyList(), fake.calls, "no mediaInfo for a poster that is not there")
        assertEquals(emptyList(), fetcher.fetched)
    }

    /** The whole start is what counts as cached: a start the player had begun (a few hundred kilobytes) is topped up. */
    @Test
    fun aPartlyCachedStartIsToppedUp() = runTest {
        storeOldItems()
        val asked = mutableListOf<Long>()
        val partly = object : VideoStarts by starts {
            override fun isCached(key: String, bytes: Long): Boolean = (bytes <= 300L * 1024).also { asked += bytes }
        }

        repository(starts = partly).preloadVideo(media, 2, CarouselRepository.Visit())

        assertEquals(listOf(VIDEO_START_BYTES), asked, "the whole start is what is checked")
        assertEquals(listOf("${pk}_2 $OLD_VIDEO ${1 shl 20}"), starts.writes.toList(), "and the rest of it is written")
    }

    /** R26's debug lines say what happened, in the words README section 8 gives, and never name a link, a host or a pk. */
    @Test
    fun thePosterAndStartLinesAreLabelledAndNameNoLinkOrPk() = runTest {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        storeOldItems()
        sessionReady = false // the renewal a refused poster asks for is not sent
        fetcher.answer = { null }
        repository(log = { lines += it }).prefetchPoster(media, 2, CarouselRepository.Visit())
        for (answer in listOf(StartWrite.EXPIRED, StartWrite.REFUSED, StartWrite.LIMITED, StartWrite.OFFLINE, StartWrite.FAILED)) {
            starts.answer = { _, _ -> answer }
            repository(log = { lines += it }).preloadVideo(media, 2, CarouselRepository.Visit())
        }
        starts.answer = { _, _ ->
            db.carouselItemDao().replace(pk, rows().map { item -> item.copy(videoUrl = item.videoUrl?.let { "https://cdn.example.test/new/v2.mp4?oe=1" }) })
            StartWrite.WRITTEN
        }
        repository(log = { lines += it }).preloadVideo(media, 2, CarouselRepository.Visit())

        for (kind in listOf(
            "carousel poster failed (refused)", "carousel start failed (expired)", "carousel start failed (refused)",
            "carousel start failed (cdn limit)", "carousel start failed (offline)", "carousel start failed (cache)",
            "carousel start dropped (link replaced)",
        )) assertTrue(lines.any { it.startsWith(kind) }, "a line '$kind...': $lines")
        val links = listOf("://", "cdn.", "example", "fake", ".jpg", ".mp4")
        assertTrue(lines.none { line -> pk in line || links.any { it in line } }, "no link, host or pk: $lines")
    }

    /**
     * N2, R16 with a start written ahead: a long video whose first megabyte only is cached (by the real writer) is not "all
     * cached", so offline it still says "Offline: this item isn't cached yet".
     */
    @Test
    fun offlineWithOnlyItsStartCachedSaysOffline() = runTest {
        storeOldItems()
        val source = File(tmp.root, "v2.mp4").apply { writeBytes(ByteArray(3 shl 20) { (it % 251).toByte() }) }
        val writer = CacheVideoStarts({ videoCache.cache }, { _ ->
            CacheDataSource.Factory().setCache(videoCache.cache).setUpstreamDataSourceFactory(FileDataSource.Factory()).createDataSource()
        })
        assertEquals(StartWrite.WRITTEN, writer.write("${pk}_2", Uri.fromFile(source), VIDEO_START_BYTES))
        assertTrue(writer.isCached("${pk}_2", VIDEO_START_BYTES), "precondition: the start is there")

        assertEquals(VideoSource.Unavailable(OFFLINE), repository(starts = writer).unreachable(media, 2))
    }

    /** P1: the CDN's 429 and offline stop the queue, with no request to Instagram. */
    @Test
    fun aLimitedOrOfflineStartStopsTheQueue() = runTest {
        storeOldItems()
        val repository = repository()
        starts.answer = { _, _ -> StartWrite.LIMITED }
        assertEquals(StartAhead.Stop, repository.preloadVideo(media, 2, CarouselRepository.Visit()))
        starts.answer = { _, _ -> StartWrite.OFFLINE }
        assertEquals(StartAhead.Stop, repository.preloadVideo(media, 2, CarouselRepository.Visit()))
        assertEquals(emptyList(), fake.calls)
        assertEquals(emptyList(), starts.removed, "what came before the connection dropped is the item's own start: kept")
    }

    /** D3: a post a sync unsaved gets no start; nor does an image item, a post that is not a carousel, or Mock mode (R3). */
    @Test
    fun noStartForAnUnsavedPostAnImageItemAReelOrMockMode() = runTest {
        storeOldItems()
        assertEquals(StartAhead.Skipped, repository().preloadVideo(media, 1, CarouselRepository.Visit()), "an image item")
        assertEquals(StartAhead.Skipped, repository().preloadVideo(mediaEntity(pk, MediaType.REEL), 2, CarouselRepository.Visit()), "a reel")
        val clip = Uri.parse("android.resource://io.github.yuriimurha.reels/raw/sample_clip")
        assertEquals(StartAhead.Skipped, repository(sampleClip = clip).preloadVideo(media, 2, CarouselRepository.Visit()), "Mock mode: the clip is local")
        db.mediaDao().markRemoved(listOf(pk), START)
        assertEquals(StartAhead.Stop, repository().preloadVideo(media, 2, CarouselRepository.Visit()), "unsaved by a sync")

        assertEquals(emptyList(), starts.writes)
        assertEquals(emptyList(), fake.calls)
    }

    /**
     * P2 and D3 for starts: the item's link is replaced (a renewal, a sync) or the post unsaved while its start is written. The
     * bytes may be another slide's, so the key is dropped again; the queue goes on, or stops for a post gone.
     */
    @Test
    fun aStartWrittenWhileItsLinkWasReplacedIsDropped() = runTest {
        storeOldItems()
        starts.answer = { _, _ ->
            db.carouselItemDao().replace(pk, rows().map { item -> item.copy(videoUrl = item.videoUrl?.let { "https://cdn.example.test/new/v2.mp4?oe=1" }) })
            StartWrite.WRITTEN
        }
        assertEquals(StartAhead.Skipped, repository().preloadVideo(media, 2, CarouselRepository.Visit()))
        assertEquals(listOf("${pk}_2"), starts.removed.toList())

        storeOldItems()
        starts.answer = { _, _ -> db.mediaDao().markRemoved(listOf(pk), START); StartWrite.WRITTEN }
        assertEquals(StartAhead.Stop, repository().preloadVideo(media, 2, CarouselRepository.Visit()))
        assertEquals(listOf("${pk}_2", "${pk}_2"), starts.removed.toList())

        // The connection dropped part way, after some bytes of the replaced link: those go too, and the queue stops (offline).
        storeOldItems()
        starts.answer = { _, _ ->
            db.carouselItemDao().replace(pk, rows().map { item -> item.copy(videoUrl = item.videoUrl?.let { "https://cdn.example.test/new/v2.mp4?oe=1" }) })
            StartWrite.OFFLINE
        }
        assertEquals(StartAhead.Stop, repository().preloadVideo(media, 2, CarouselRepository.Visit()))
        assertEquals(listOf("${pk}_2", "${pk}_2", "${pk}_2"), starts.removed.toList())
        assertEquals(emptyList(), fake.calls)
    }

    /**
     * R20 for R26's files: a renewal that reshapes the post drops its video items' posters and their starts, with the rest of
     * its item media. The start here is written by the real writer, from a local file, into this test's video cache.
     */
    @Test
    fun aReshapeDropsThePostersAndTheVideoStarts() = runTest {
        storeOldItems()
        fetcher.answer = { url -> url.toByteArray() }
        val source = File(tmp.root, "v2.mp4").apply { writeBytes(ByteArray(3_000) { it.toByte() }) }
        val writer = CacheVideoStarts({ videoCache.cache }, { _ ->
            CacheDataSource.Factory().setCache(videoCache.cache).setUpstreamDataSourceFactory(FileDataSource.Factory()).createDataSource()
        })
        assertEquals(StartWrite.WRITTEN, writer.write("${pk}_2", Uri.fromFile(source), 3_000L))
        val repository = repository()
        assertIs<ImageAhead.Shown>(repository.prefetchPoster(media, 2, CarouselRepository.Visit()))
        assertTrue(videoCache.cache.getCachedBytes("${pk}_2", 0, Long.MAX_VALUE) > 0 && images.path(pk, 2) != null, "precondition: a poster and a start")
        replyWithFirstThree { items -> items.map { if (it.position == 2) it.copy(type = MediaType.IMAGE, videoUrl = null, videoUrlExpiresAt = null) else it } }

        repository.video(media, 2, CarouselRepository.Visit(), forceRefresh = true)

        assertEquals(MediaType.IMAGE, rows()[2].type, "precondition: reshaped")
        assertNull(images.path(pk, 2), "the poster went")
        assertEquals(0L, videoCache.cache.getCachedBytes("${pk}_2", 0, Long.MAX_VALUE), "and the start")
    }

    /**
     * The hard bound, every path of a visit together: old links everywhere, a picture, a poster and a start refused ahead, the
     * items' own loads and plays. One `mediaInfo` in all.
     */
    @Test
    fun everyPathOfAVisitTogetherSendsOneMediaInfo() = runTest {
        storeOldItems()
        fetcher.answer = { null }
        starts.answer = { _, _ -> StartWrite.EXPIRED }
        val repository = repository()
        val visit = CarouselRepository.Visit()

        repository.preloadVideo(media, 2, visit)
        repository.prefetchImage(media, 1, visit)
        repository.prefetchPoster(media, 2, visit)
        repository.preloadVideo(media, 2, visit)
        repository.image(media, 1, visit)
        repository.image(media, 2, visit)
        repository.video(media, 2, visit)
        repository.video(media, 2, visit, forceRefresh = true)
        repository.items(media, visit)

        assertEquals(listOf("mediaInfo:$pk"), fake.calls)
    }

    // --- fakes ---

    /** R26: the video starts written ahead, recorded as "key uri bytes"; [cached] keys count as written already. */
    private class RecordingStarts : VideoStarts {
        val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val removed: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val cached: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        var answer: suspend (key: String, uri: Uri) -> StartWrite = { _, _ -> StartWrite.WRITTEN }

        override fun isCached(key: String, bytes: Long) = key in cached

        override suspend fun write(key: String, uri: Uri, bytes: Long): StartWrite {
            writes += "$key $uri $bytes"
            return answer(key, uri).also { if (it == StartWrite.WRITTEN) cached += key }
        }

        override fun remove(key: String) {
            removed += key
            cached -= key
        }
    }

    /** A video's three megabytes, every byte [value]: two videos told apart in what a player read. */
    private fun videoOf(value: Byte) = ByteArray(3 shl 20) { value }

    /**
     * N-M1: the real writer (`CacheWriter`) into this test's video cache, over a local upstream serving [bytes] whose read
     * stops at 600 KB on [stall]: a stall nothing cuts, as a body read stalled inside the platform's stream (M5). [writes]
     * counts the writes it starts.
     */
    private fun stallingWriter(bytes: ByteArray, stall: Stall, writes: AtomicInteger = AtomicInteger()) =
        CacheVideoStarts({ videoCache.cache }, { _ ->
            writes.incrementAndGet()
            CacheDataSource.Factory().setCache(videoCache.cache).setFlags(0)
                .setUpstreamDataSourceFactory { StallingSource(bytes, 600 * 1024, stall) }
                .createDataSource()
        })

    /**
     * 900 KB of [source] read as the viewer's player reads it (its flags: past a locked span, from the network), from a CDN
     * serving [network].
     */
    private fun playerReads(source: VideoSource.Play, network: ByteArray): ByteArray {
        val player = CacheDataSource.Factory().setCache(videoCache.cache).setUpstreamDataSourceFactory { ByteArrayDataSource(network) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).createDataSource()
        val got = ByteArray(900 * 1024)
        var read = 0
        player.open(DataSpec.Builder().setUri(source.uri).setKey(source.cacheKey).build())
        try {
            while (read < got.size) {
                val n = player.read(got, read, minOf(8 * 1024, got.size - read))
                if (n == C.RESULT_END_OF_INPUT) break
                read += n
            }
        } finally {
            player.close()
        }
        return got.copyOf(read)
    }

    /**
     * N-M1: the viewer's player, on a thread of its own, from the moment [play] hands it the item: it reads 250 KB (past a
     * write's lock, from the network, if the key is locked), waits for [goOn], then reads on to 900 KB, as the app builds it.
     */
    private inner class PlayerOnceHandedOut(play: Deferred<VideoSource>, network: ByteArray) {
        private val goOn = CountDownLatch(1)
        private val got = ByteArray(900 * 1024)

        @Volatile
        var read = 0
            private set

        @Volatile
        private var failure: Throwable? = null

        private val reading = thread(isDaemon = true, name = "n-m1-player") {
            try {
                val deadline = System.currentTimeMillis() + 20_000
                while (!play.isCompleted) {
                    check(System.currentTimeMillis() < deadline) { "the item was never handed out" }
                    Thread.sleep(2)
                }
                val source = play.getCompleted() as VideoSource.Play
                val player = CacheDataSource.Factory().setCache(videoCache.cache)
                    .setUpstreamDataSourceFactory { ByteArrayDataSource(network) }
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).createDataSource()
                player.open(DataSpec.Builder().setUri(source.uri).setKey(source.cacheKey).build())
                try {
                    readUpTo(player, 250 * 1024)
                    check(goOn.await(20, TimeUnit.SECONDS))
                    readUpTo(player, got.size)
                } finally {
                    player.close()
                }
            } catch (e: Throwable) {
                failure = e
            }
        }

        private fun readUpTo(player: DataSource, limit: Int) {
            while (read < limit) {
                val n = player.read(got, read, minOf(8 * 1024, limit - read))
                if (n == C.RESULT_END_OF_INPUT) break
                read += n
            }
        }

        fun goOn() = goOn.countDown()

        /**
         * Blocks this thread a moment first, so a player handed the item already reads on while nothing else runs, then
         * suspends until it is done.
         */
        suspend fun awaitDone() {
            reading.join(1_000)
            withContext(Dispatchers.IO) { reading.join(20_000) }
            failure?.let { throw it }
        }

        fun oldBytes() = got.copyOf(read).count { it == OLD_BYTE }
    }

    /** Waits on this thread, blocking (so the test's dispatcher runs nothing meanwhile), until [key] has bytes cached. */
    private fun blockUntilCached(key: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (videoCache.cache.getCachedBytes(key, 0, Long.MAX_VALUE) == 0L) {
            check(System.currentTimeMillis() < deadline) { "nothing was committed under $key" }
            Thread.sleep(2)
        }
    }

    /** Where a [StallingSource]'s read stops: [awaitReached] once it is there, until [release]. */
    private class Stall {
        private val reached = CountDownLatch(1)
        private val released = CountDownLatch(1)

        /** On the write's thread. */
        fun hold() {
            reached.countDown()
            released.await()
        }

        suspend fun awaitReached() = withContext(Dispatchers.IO) {
            check(reached.await(10, TimeUnit.SECONDS)) { "the write never reached its stall" }
        }

        fun release() = released.countDown()
    }

    /** [data] from the asked position, 16 KB a read, every read from [stallAt] on held by [stall] until it is released. */
    private class StallingSource(private val data: ByteArray, private val stallAt: Int, private val stall: Stall) : DataSource {
        private var position = 0
        private var end = 0
        private var uri: Uri? = null

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            position = dataSpec.position.toInt()
            end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) data.size else position + dataSpec.length.toInt()
            return (end - position).toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= end) return C.RESULT_END_OF_INPUT
            if (position >= stallAt) stall.hold()
            val n = minOf(length, end - position, 16 * 1024, if (position < stallAt) stallAt - position else Int.MAX_VALUE)
            System.arraycopy(data, position, buffer, offset, n)
            position += n
            return n
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            uri = null
        }
    }

    private class StubFetcher : MediaFetcher {
        val fetched: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var answer: suspend (String) -> ByteArray? = { null }

        override suspend fun fetch(url: String): ByteArray? {
            fetched += url
            return answer(url)
        }
    }

    private companion object {
        const val START = 1_800_000_000_000L
        const val OLD_VIDEO = "https://cdn.example.test/old/v2.mp4?oe=1"

        /** Item 2's link after a reshape or a renewal (N-M1): another video. */
        const val NEW_LINK = "https://cdn.example.test/new/v2.mp4?oe=1"

        /** Another carousel post, digits only as every pk is. */
        const val OTHER_PK = "2000001"
        const val OLD_BYTE: Byte = 0x0A
        const val NEW_BYTE: Byte = 0x0B
        const val CANT_LOAD = "Can't load this item"
        const val OFFLINE = "Offline: this item isn't cached yet"
        const val GONE = "This item is no longer available on Instagram"
        const val NEEDS_ATTENTION = "Instagram session needs attention (Sync screen)"

        /** What a cancelled call delivered when it delivered nothing. */
        val UNSET: Any = object {
            override fun toString() = "nothing (cancelled)"
        }

        fun oldImage(position: Int) = "https://cdn.example.test/old/i$position.jpg?oe=1"
    }
}
