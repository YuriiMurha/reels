package io.github.yuriimurha.reels.ui.viewer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.media.VideoSource
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

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
}
