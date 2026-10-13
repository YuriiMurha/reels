package io.github.yuriimurha.reels.ui.viewer

import android.net.Uri
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.media.VideoSource
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.KotlinSource
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The settle flow over the playable unit (Review Focus 1): a post, or one item of a carousel post. The player only ever
 * gets the unit whose post AND item are both settled. A carousel's items here follow the fake library: 0, 1, 3, 4 are
 * images, 2 and 5 videos.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ViewerPlaybackTest {
    private val carousel = mediaEntity("1000001", MediaType.CAROUSEL).copy(carouselCount = 6)
    private val reel = mediaEntity("1000002", MediaType.REEL)

    private fun isVideoItem(position: Int) = position % 3 == 2

    /** Resolves a unit as the ViewModel does: a reel by its pk, a carousel's video item under `pk_position`, an image to nothing. */
    private class GatedUnits(private val video: (Int) -> Boolean, private val nonCancellable: Boolean = false) {
        val started = mutableListOf<String>()
        private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val held = mutableSetOf<String>()

        fun gate(key: String) = gates.getOrPut(key) { CompletableDeferred() }

        suspend fun resolve(media: MediaEntity, position: Int?): VideoSource? {
            val key = if (position == null) media.pk else "${media.pk}_$position"
            if (position != null && !video(position)) return null
            started += key
            if (key in held) {
                if (nonCancellable) withContext(NonCancellable) { gate(key).await() } else gate(key).await()
            }
            return VideoSource.Play(Uri.parse("https://video.example.test/$key.mp4"), key)
        }

        fun release(key: String) = gate(key).complete(Unit)

        fun releaseAll() = gates.values.forEach { it.complete(Unit) }
    }

    /** The player as the screen drives it: every settle stops it, a Play sets its item (`set:<cacheKey>`), an Unavailable stops it. */
    private class FakePlayer(val settled: MutableStateFlow<SettledPage>) {
        val events = mutableListOf<String>()
        val setMediaItems get() = events.filter { it.startsWith("set:") }.map { it.removePrefix("set:") }

        fun onSettled(page: SettledPage) {
            events += "stop"
        }

        fun onSource(page: SettledPage, source: VideoSource) {
            events += when (source) {
                is VideoSource.Play -> "set:${source.cacheKey}"
                is VideoSource.Unavailable -> "stop"
            }
        }

        fun isStillSettled(unit: Playable) = settled.value.playable == unit
    }

    private fun TestScope.following(units: GatedUnits, player: FakePlayer): Job = launch {
        playSettledPages(
            settled = player.settled,
            resolve = units::resolve,
            isStillSettled = player::isStillSettled,
            onSettled = player::onSettled,
            afterResolved = {},
            onSource = player::onSource,
        )
    }

    private fun item(position: Int, index: Int = 0) = SettledPage(index, carousel, position)

    /** Review Focus 1: a fast swipe through image, video, image, video plays only the video it ends on. */
    @Test
    fun onlyTheSettledItemOfTheSettledPostPlays() = runTest {
        val units = GatedUnits(::isVideoItem).apply { held += "1000001_2" } // the video swiped past is slow to resolve
        val player = FakePlayer(MutableStateFlow(SettledPage(0, null)))
        val job = following(units, player)
        try {
            runCurrent()
            for (position in listOf(1, 2, 3, 5)) {
                player.settled.value = item(position)
                runCurrent()
            }
            assertEquals(listOf("1000001_2", "1000001_5"), units.started, "only the video items were resolved")
            assertEquals(listOf("1000001_5"), player.setMediaItems, "the item the swipe ended on plays")

            units.release("1000001_2")
            runCurrent()
            assertEquals(listOf("1000001_5"), player.setMediaItems, "the video swiped past never reaches the player, even late")
        } finally {
            units.releaseAll()
            job.cancel()
        }
    }

    /** One setMediaItem per settled video item, under `pk_position`; settling on an image stops the player and plays nothing. */
    @Test
    fun eachSettledVideoItemIsSetOnceAndAnImageStopsThePlayer() = runTest {
        val units = GatedUnits(::isVideoItem)
        val player = FakePlayer(MutableStateFlow(SettledPage(0, null)))
        val job = following(units, player)
        try {
            runCurrent()
            for (position in listOf(1, 2, 3, 5)) {
                player.settled.value = item(position)
                runCurrent()
            }
            assertEquals(
                listOf("stop", "stop", "stop", "set:1000001_2", "stop", "stop", "set:1000001_5"),
                player.events,
                "settle (nothing), image 1, video 2 (played), image 3 (stopped, nothing set), video 5 (played)",
            )
        } finally {
            job.cancel()
        }
    }

    /** A resolve that ignores cancellation and returns after the item was swiped away is dropped by the re-check. */
    @Test
    fun aVideoItemsAnswerThatArrivesAfterTheSwipeIsNotPlayed() = runTest {
        val units = GatedUnits(::isVideoItem, nonCancellable = true).apply { held += "1000001_2" }
        val player = FakePlayer(MutableStateFlow(SettledPage(0, null)))
        val job = following(units, player)
        try {
            runCurrent()
            player.settled.value = item(2)
            runCurrent()
            player.settled.value = item(3)
            runCurrent()
            units.release("1000001_2")
            runCurrent()
            assertEquals(emptyList(), player.setMediaItems)
        } finally {
            units.releaseAll()
            job.cancel()
        }
    }

    /** The unit is the post AND the item: the same item position on another post is another unit. */
    @Test
    fun theSameItemOfAnotherPostIsAnotherUnit() {
        assertEquals(Playable("1000001", 2), item(2).playable)
        assertEquals(Playable("1000002", null), SettledPage(1, reel).playable)
        assertEquals(null, SettledPage(1, null).playable)
        assertEquals(false, Playable("1000001", 2) == Playable("1000003", 2))
        assertEquals(false, Playable("1000001", 2) == Playable("1000001", 5))
    }

    @Test
    fun leavingTheCarouselForAReelStopsTheItemAndPlaysTheReel() = runTest {
        val units = GatedUnits(::isVideoItem)
        val player = FakePlayer(MutableStateFlow(SettledPage(0, null)))
        val job = following(units, player)
        try {
            runCurrent()
            player.settled.value = item(2)
            runCurrent()
            player.settled.value = SettledPage(1, reel)
            runCurrent()
            assertEquals(listOf("stop", "stop", "set:1000001_2", "stop", "set:1000002"), player.events)
        } finally {
            job.cancel()
        }
    }

    /** A non-carousel post is the unit `(pk, null)`, resolved by its pk exactly as before. */
    @Test
    fun aReelIsStillPlayedUnderItsPk() = runTest {
        val units = GatedUnits(::isVideoItem)
        val player = FakePlayer(MutableStateFlow(SettledPage(0, null)))
        val job = following(units, player)
        try {
            runCurrent()
            player.settled.value = SettledPage(3, reel)
            runCurrent()
            assertEquals(listOf("1000002"), player.setMediaItems)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun aCarouselItemFailureIsAttachedToThatItemOnly() {
        val state = CarouselUiState(items = List(3) { CarouselItemUi(it, if (it == 2) MediaType.VIDEO else MediaType.IMAGE) }, settled = 2)

        val failed = state.withItemFailure(2, "Can't load this item")

        assertEquals(listOf(null, null, "Can't load this item"), failed.items.map { it.failure })
        assertEquals(state, state.withItemFailure(7, "Can't load this item"), "no such item: nothing changes")
    }

    /**
     * C1: a settle is reported only when the list's row and the pager's laid-out key agree (`ViewerScreenCarouselTest` runs the
     * list moves themselves). A placeholder row is never held back, though its key is not a pk: its settle stops the previous
     * post's video while the row loads.
     */
    @Test
    fun aSettleIsReportedOnlyWhereTheListAndThePagerAgree() {
        assertEquals(SettledPage(3, carousel, 2), settledPageOrNull(3, carousel, carousel.pk, 2), "the same post")
        assertEquals(null, settledPageOrNull(3, carousel, reel.pk, 2), "the list moved: the pager still has another post there")
        assertEquals(SettledPage(3, reel), settledPageOrNull(3, reel, null, null), "before the pager's first layout")

        val placeholderKey = PlaceholderKey(3) // stands in for Paging's own key of a row not loaded yet
        assertEquals(SettledPage(3, null), settledPageOrNull(3, null, placeholderKey, null), "a placeholder still settles")
    }

    private data class PlaceholderKey(val index: Int)

    /** Every call the viewer makes on the player, marked when it did not come on the main thread (Media3 allows no other). */
    private class ThreadCheckingPlayer(player: Player) : ForwardingPlayer(player) {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        private fun record(call: String) {
            calls += if (Looper.myLooper() == Looper.getMainLooper()) call else "$call off the main thread (${Thread.currentThread().name})"
        }

        override fun stop() {
            record("stop")
            super.stop()
        }

        override fun clearMediaItems() {
            record("clear")
            super.clearMediaItems()
        }
    }

    /**
     * Task 6 minor 1: the viewer's two player-calling effects run through [onMainThread], so the player is only ever called on
     * the main thread, even when what wakes the effect resumes it on another thread (here, a channel fed from a background
     * thread, with the effect started on the IO dispatcher).
     */
    @Test
    fun thePlayerEffectsCallThePlayerOnTheMainThreadWhateverWakesThem() {
        val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        val player = ThreadCheckingPlayer(exo)
        val wakes = Channel<Int>()
        val scope = CoroutineScope(Dispatchers.IO + Job())
        try {
            val effect = scope.launch { onMainThread { repeat(3) { wakes.receive(); player.stop(); player.clearMediaItems() } } }
            thread(name = "waker") { runBlocking { repeat(3) { wakes.send(it) } } }
            // Robolectric's main looper is this test's thread: run it until the effect is done.
            val deadline = System.currentTimeMillis() + 10_000
            while (!effect.isCompleted) {
                check(System.currentTimeMillis() < deadline) { "the effect never finished: ${player.calls}" }
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertEquals(List(3) { listOf("stop", "clear") }.flatten(), player.calls.toList())
        } finally {
            scope.cancel()
            exo.release()
        }
    }

    /** ...and both of the screen's effects that call the player go through it (the source is pinned: no semantics can see a thread). */
    @Test
    fun bothOfTheScreensPlayerEffectsRunOnTheMainThread() {
        val screen = KotlinSource.code(File("src/main/kotlin/io/github/yuriimurha/reels/ui/viewer/ViewerScreen.kt").readText())
        for (call in listOf("viewModel.followSettledPages(", "playIfStillSettled(")) {
            val at = screen.indexOf(call)
            assertTrue(at >= 0, "ViewerScreen no longer calls $call: update this pin")
            val effect = screen.substring(screen.lastIndexOf("LaunchedEffect(", at), at)
            assertTrue("onMainThread {" in effect, "the effect around $call must run in onMainThread { }: $effect")
        }
    }
}
