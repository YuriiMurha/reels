package io.github.yuriimurha.reels.ui.viewer

import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.data.media.VideoSource
import io.github.yuriimurha.reels.data.media.VideoSourceResolver
import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun viewModel(): ViewerViewModel {
        val settings = SettingsStore(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "s.preferences_pb") })
        return ViewerViewModel(
            MediaSource.Collection(ALL_SAVED_ID),
            startIndex = 0,
            library = LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs"))),
            resolver = resolver,
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

        viewModel.onSettled(page, next)
        runCurrent()
        viewModel.onSettled(page, next)
        runCurrent()

        assertEquals(listOf("m2"), resolver.calls, "two settles on the same page make one prefetch")
        assertEquals(listOf(false), resolver.forced, "a prefetch is an ordinary resolve, never a forced one")
    }

    @Test
    fun aNextLinkThatIsStillFreshIsLeftAlone() = runTest {
        val viewModel = viewModel()
        viewModel.onSettled(reel("m1", fresh), reel("m2", expiresAt = now + 3_600_000))
        runCurrent()
        assertEquals(emptyList(), resolver.calls)
    }

    @Test
    fun aNextLinkThatExpiresWithinTheMarginIsPrefetched() = runTest {
        val viewModel = viewModel()
        viewModel.onSettled(reel("m1", fresh), reel("m2", expiresAt = now + 9 * 60_000))
        runCurrent()
        assertEquals(listOf("m2"), resolver.calls)
    }

    @Test
    fun nothingIsPrefetchedForAnImageOrForTheLastItem() = runTest {
        val viewModel = viewModel()
        viewModel.onSettled(reel("m1", fresh), mediaEntity("i1", MediaType.IMAGE))
        viewModel.onSettled(reel("m2", fresh), null)
        runCurrent()
        assertEquals(emptyList(), resolver.calls)
    }

    @Test
    fun aNextLinkWithNoUrlOrNoExpiryIsPrefetched() = runTest {
        val viewModel = viewModel()
        viewModel.onSettled(reel("m1", fresh), reel("m2", expiresAt = null))
        runCurrent()
        viewModel.onSettled(reel("m2", fresh), reel("m3", expiresAt = null, url = null))
        runCurrent()
        assertEquals(listOf("m2", "m3"), resolver.calls)
    }

    @Test
    fun aNewSettleCancelsThePrefetchStillInFlight() = runTest {
        val viewModel = viewModel()
        resolver.hold = true
        viewModel.onSettled(reel("m1", fresh), reel("m2", expiresAt = expired))
        runCurrent()
        assertEquals(listOf("m2"), resolver.calls)

        viewModel.onSettled(reel("m5", fresh), reel("m6", expiresAt = expired))
        runCurrent()

        assertEquals(listOf("m2", "m6"), resolver.calls)
        assertEquals(listOf("m2"), resolver.cancelled, "only one prefetch is ever in flight")
    }

    @Test
    fun aFailingPrefetchNeverCrashesTheViewer() = runTest {
        val viewModel = viewModel()
        resolver.failWith = IllegalStateException("boom")

        viewModel.onSettled(reel("m1", fresh), reel("m2", expiresAt = expired))
        runCurrent()

        assertEquals(listOf("m2"), resolver.calls)
        assertTrue(viewModel.viewModelScope.isActive, "an exception in the prefetch did not take the ViewModel's scope down")
    }

    // --- one refresh after a 403 or 410 ---

    @Test
    fun aRefusedLinkIsRefreshedOnceAndThenGivenUp() = runTest {
        val viewModel = viewModel()
        val media = reel("m1", fresh)
        viewModel.onSettled(media, null)
        resolver.forcedAnswer = VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m1")

        val first = viewModel.recover(media, playbackFailure(403))
        val second = viewModel.recover(media, playbackFailure(403))

        assertEquals(VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m1"), first)
        assertEquals(listOf("m1"), resolver.calls, "one refresh")
        assertEquals(listOf(true), resolver.forced, "and it was forced, because the link only looked fresh")
        assertEquals(VideoSource.Unavailable(CANT_PLAY), second, "the second error on the same item gives up")
    }

    @Test
    fun a410IsRefreshedToo() = runTest {
        val viewModel = viewModel()
        val media = reel("m1", fresh)
        viewModel.onSettled(media, null)
        resolver.forcedAnswer = VideoSource.Play(Uri.parse("https://video.example.test/new.mp4"), "m1")

        assertTrue(viewModel.recover(media, playbackFailure(410)) is VideoSource.Play)
        assertEquals(listOf("m1"), resolver.calls)
    }

    @Test
    fun anyOtherErrorGivesUpWithoutARequest() = runTest {
        val viewModel = viewModel()
        val media = reel("m1", fresh)
        viewModel.onSettled(media, null)

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
        viewModel.onSettled(a, null)
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
        viewModel.onSettled(media, null)
        viewModel.recover(media, playbackFailure(403))

        viewModel.onSettled(reel("m2", fresh), null)
        viewModel.onSettled(media, null)

        assertTrue(viewModel.recover(media, playbackFailure(403)) is VideoSource.Play, "coming back to the item is a new visit")
    }

    @Test
    fun aRefreshThatFindsNothingKeepsItsOwnMessage() = runTest {
        val viewModel = viewModel()
        val media = reel("m1", fresh)
        viewModel.onSettled(media, null)
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
        var hold = false
        var failWith: Exception? = null
        var forcedAnswer: VideoSource? = null

        override suspend fun resolve(media: MediaEntity, forceRefresh: Boolean): VideoSource? {
            calls += media.pk
            forced += forceRefresh
            failWith?.let { throw it }
            if (hold) {
                try {
                    CompletableDeferred<Unit>().await()
                } catch (e: CancellationException) {
                    cancelled += media.pk
                    throw e
                }
            }
            return if (forceRefresh) forcedAnswer else VideoSource.Play(Uri.parse("https://video.example.test/${media.pk}.mp4"), media.pk)
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
