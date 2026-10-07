package io.github.yuriimurha.reels.sync.pacing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fake backend shares these across threads (a worker records while the Sync screen reads status). */
class InMemoryStoresTest {
    @Test
    fun requestLogSurvivesConcurrentWritersAndReaders() = runBlocking(Dispatchers.Default) {
        val log = InMemoryRequestLog()
        List(8) {
            launch {
                repeat(2_000) { i ->
                    log.record(i.toLong())
                    log.countSince(-1)
                    log.oldestSince(-1)
                }
            }
        }.joinAll()
        assertEquals(16_000, log.countSince(-1))
    }

    @Test
    fun cooldownStoreTreatsExactlyOneOfManyConcurrentRateLimitsAsTheFirst() = runBlocking(Dispatchers.Default) {
        repeat(300) { round ->
            val store = InMemoryCooldownStore()
            val results = List(8) { async { store.onRateLimited(1_000) } }.awaitAll()
            assertEquals(1, results.count { it == 1_000 + Cooldowns.SHORT_MS }, "round $round: $results")
            assertEquals(7, results.count { it == 1_000 + Cooldowns.LONG_MS }, "round $round: $results")
        }
    }
}
