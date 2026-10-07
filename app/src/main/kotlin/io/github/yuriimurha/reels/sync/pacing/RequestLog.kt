package io.github.yuriimurha.reels.sync.pacing

/** Timestamps of Instagram API requests, for the rolling 24 h budget. */
interface RequestLog {
    suspend fun record(at: Long)
    suspend fun countSince(since: Long): Int
    suspend fun oldestSince(since: Long): Long?

    /** The most recent recorded request time, or null when nothing is logged. The Pacer seeds its gap from it. */
    suspend fun latest(): Long?
}

/** Thread-safe: a sync worker records while the Sync screen reads the status. */
class InMemoryRequestLog(initial: List<Long> = emptyList()) : RequestLog {
    private val lock = Any()
    private val times = initial.toMutableList()

    override suspend fun record(at: Long) {
        synchronized(lock) { times += at }
    }

    override suspend fun countSince(since: Long): Int = synchronized(lock) { times.count { it > since } }

    override suspend fun oldestSince(since: Long): Long? =
        synchronized(lock) { times.filter { it > since }.minOrNull() }

    override suspend fun latest(): Long? = synchronized(lock) { times.maxOrNull() }
}
