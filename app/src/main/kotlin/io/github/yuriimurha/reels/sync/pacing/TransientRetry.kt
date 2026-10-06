package io.github.yuriimurha.reels.sync.pacing

import io.github.yuriimurha.reels.instagram.InstagramException
import kotlinx.coroutines.delay
import kotlin.random.Random

private val BACKOFF_MS = longArrayOf(30_000, 60_000, 120_000, 240_000)

/**
 * Retries [block] after [InstagramException.Transient] with 30 s, 60 s, 120 s, 240 s waits (each ±20 %),
 * then rethrows (spec 6.4). Any other failure propagates immediately. Wrap each attempt in the Pacer so
 * retries count as requests.
 */
suspend fun <T> retryTransient(random: Random = Random.Default, block: suspend () -> T): T {
    for (base in BACKOFF_MS) {
        try {
            return block()
        } catch (e: InstagramException.Transient) {
            delay((base * (0.8 + 0.4 * random.nextDouble())).toLong())
        }
    }
    return block()
}
