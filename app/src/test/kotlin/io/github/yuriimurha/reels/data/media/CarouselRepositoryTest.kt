package io.github.yuriimurha.reels.data.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
        )
    }

    private suspend fun storePost() = db.mediaDao().upsert(listOf(media))

    /** The post and three items with links from an earlier sync: two images, then a video whose link expires at [videoExpiresAt]. */
    private suspend fun storeOldItems(
        videoExpiresAt: Long? = START + 3_600_000,
        firstImageUrl: String = oldImage(0),
        videoUrl: String? = OLD_VIDEO,
    ) {
        storePost()
        db.carouselItemDao().replace(
            pk,
            listOf(
                CarouselItemEntity(pk, 0, MediaType.IMAGE, 1080, 1350, firstImageUrl, null, null),
                CarouselItemEntity(pk, 1, MediaType.IMAGE, 1080, 1350, oldImage(1), null, null),
                CarouselItemEntity(pk, 2, MediaType.VIDEO, 1080, 1920, oldImage(2), videoUrl, videoExpiresAt),
            ),
        )
    }

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

    // --- fakes ---

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
