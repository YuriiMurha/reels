package io.github.yuriimurha.reels.sync.pacing

/** Timestamps of Instagram API requests, for the rolling 24 h budget. */
interface RequestLog {
    suspend fun record(at: Long)
    suspend fun countSince(since: Long): Int
    suspend fun oldestSince(since: Long): Long?
}

class InMemoryRequestLog(initial: List<Long> = emptyList()) : RequestLog {
    private val times = initial.toMutableList()

    override suspend fun record(at: Long) {
        times += at
    }

    override suspend fun countSince(since: Long): Int = times.count { it > since }

    override suspend fun oldestSince(since: Long): Long? = times.filter { it > since }.minOrNull()
}
