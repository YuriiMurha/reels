package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransientRetryTest {
    @Test
    fun retriesWithGrowingWaitsThenSucceeds() = runTest {
        var attempts = 0
        val result = retryTransient(Random(1)) {
            attempts++
            if (attempts <= 4) throw InstagramException.Transient() else "ok"
        }
        assertEquals("ok", result)
        assertEquals(5, attempts)
        assertTrue(testScheduler.currentTime in 360_000L..540_000L, "waited ${testScheduler.currentTime} ms")
    }

    /** The constant the WebView transport's idle time is checked against is the wait this really makes, at most. */
    @Test
    fun theLongestWaitIsTheLastBackoffWithItsFullJitter() = runTest {
        assertEquals(288_000L, LONGEST_TRANSIENT_WAIT_MS)
        val highest = object : Random() {
            override fun nextBits(bitCount: Int): Int = Random.Default.nextBits(bitCount)

            override fun nextDouble(): Double = 1.0 - 1e-12 // as high as nextDouble() goes
        }
        val waits = mutableListOf<Long>()
        var last = 0L
        assertFailsWith<InstagramException.Transient> {
            retryTransient(highest) {
                waits += testScheduler.currentTime - last
                last = testScheduler.currentTime
                throw InstagramException.Transient()
            }
        }
        assertTrue(waits.max() <= LONGEST_TRANSIENT_WAIT_MS, "waits: $waits")
        assertTrue(waits.max() >= LONGEST_TRANSIENT_WAIT_MS - 1, "waits: $waits")
    }

    @Test
    fun givesUpAfterTheFifthAttempt() = runTest {
        var attempts = 0
        assertFailsWith<InstagramException.Transient> {
            retryTransient(Random(1)) { attempts++; throw InstagramException.Transient() }
        }
        assertEquals(5, attempts)
    }

    @Test
    fun otherFailuresAreNeverRetried() = runTest {
        var attempts = 0
        assertFailsWith<InstagramException.ChallengeRequired> {
            retryTransient { attempts++; throw InstagramException.ChallengeRequired(null) }
        }
        assertEquals(1, attempts)
        assertEquals(0L, testScheduler.currentTime)
    }
}
