package io.github.yuriimurha.reels.data.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R26: the viewer's player starts a streamed video once 1 s is buffered, and goes on after a stall once 2 s is; everything
 * else is Media3's default. Read from the load control the player is built with (`viewerLoadControl`, which
 * `VideoWiringGuardTest` pins to the builder), by asking it what it decides.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class ViewerLoadControlTest {
    private val playerId = PlayerId("viewer")
    private val viewer = viewerLoadControl().apply { onPrepared(playerId) }
    private val defaults = DefaultLoadControl().apply { onPrepared(playerId) }

    private fun at(uri: String, bufferedMs: Long, rebuffering: Boolean = false): LoadControl.Parameters {
        val timeline = SinglePeriodTimeline(60_000_000L, true, false, false, null, MediaItem.fromUri(uri))
        return LoadControl.Parameters(
            playerId, timeline, MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0)),
            0L, bufferedMs * 1_000, 1f, true, rebuffering, C.TIME_UNSET, C.TIME_UNSET,
        )
    }

    private fun startsAt(bufferedMs: Long, rebuffering: Boolean = false) = viewer.shouldStartPlayback(at(STREAMED, bufferedMs, rebuffering))

    @Test
    fun aStreamedVideoStartsOnceOneSecondIsBuffered() {
        assertFalse(startsAt(START_BUFFER_MS - 1L))
        assertTrue(startsAt(START_BUFFER_MS.toLong()))
        assertEquals(1_000, START_BUFFER_MS)
    }

    @Test
    fun afterAStallItGoesOnOnceTwoSecondsAreBuffered() {
        assertFalse(startsAt(RESTART_BUFFER_MS - 1L, rebuffering = true))
        assertTrue(startsAt(RESTART_BUFFER_MS.toLong(), rebuffering = true))
        assertEquals(2_000, RESTART_BUFFER_MS)
    }

    /** Every other value is Media3's: how far ahead it loads, and local playback (Mock mode's bundled clip). */
    @Test
    fun everythingElseIsMedia3sDefault() {
        for (ms in listOf(0L, 10_000L, 49_999L, 50_000L, 50_001L, 120_000L)) {
            assertEquals(defaults.shouldContinueLoading(at(STREAMED, ms)), viewer.shouldContinueLoading(at(STREAMED, ms)), "loading ahead at $ms ms")
            for (rebuffering in listOf(false, true)) {
                assertEquals(
                    defaults.shouldStartPlayback(at(LOCAL, ms, rebuffering)),
                    viewer.shouldStartPlayback(at(LOCAL, ms, rebuffering)),
                    "local playback at $ms ms, rebuffering $rebuffering",
                )
            }
        }
        for (ms in listOf(0L, 500L, 999L, 1_000L, 1_999L, 2_000L)) {
            for (rebuffering in listOf(false, true)) {
                assertEquals(
                    defaults.shouldStartPlayback(at(LOCAL, ms, rebuffering)),
                    viewer.shouldStartPlayback(at(LOCAL, ms, rebuffering)),
                    "local playback at $ms ms, rebuffering $rebuffering",
                )
            }
        }
        assertEquals(defaults.getBackBufferDurationUs(playerId), viewer.getBackBufferDurationUs(playerId))
    }

    private companion object {
        const val STREAMED = "https://cdn.example.test/v.mp4?oe=1"
        const val LOCAL = "android.resource://io.github.yuriimurha.reels/raw/sample_clip"
    }
}
