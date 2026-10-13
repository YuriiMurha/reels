package io.github.yuriimurha.reels.data.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [inner], except that a collector's first read and a write take turns.
 *
 * DataStore 1.2.1 can lose an update for a collector of [DataStore.data] that starts while a write is under way. The
 * collector's first read is made without the file lock: it can read the version the write has just taken but the file the
 * write has not yet renamed into place, and so start from the old value under the new version. The write's own update then
 * carries that same version, and the collector drops it as not newer. The collector shows the old value until the next write
 * (any key) or until it is collected anew. Upstream (androidx-main) reads without the lock under the cached version instead;
 * drop this class once the app is on a stable release with that fix (TODO.md).
 *
 * A mutex held from a collector's start to its first value, and around each write, keeps the two apart. Later values are not
 * gated: by then the collector follows DataStore's in-memory cache, whose versions only grow.
 *
 * The mutex is taken and given back only on [io], never on a caller's thread: a caller may block its thread waiting for the
 * mutex (`runBlocking` on a read), and a write or a first read that had to resume on that thread to give it back would wait for
 * ever. So a write's transform runs on [io] too (DataStore runs it in the caller's context). A caller cancelled during a write
 * gives the mutex back before DataStore has finished that write, so a first read can still meet that one write (accepted: a
 * cancelled write and a collector starting within the same milliseconds).
 */
internal class TurnTakingDataStore<T>(
    private val inner: DataStore<T>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DataStore<T> {
    private val turns = Mutex()

    override val data: Flow<T> = flow {
        turns.lock()
        var holding = true
        try {
            inner.data.collect { value ->
                if (holding) {
                    holding = false
                    turns.unlock()
                }
                emit(value)
            }
        } finally {
            if (holding) turns.unlock()
        }
    }.flowOn(io)

    override suspend fun updateData(transform: suspend (t: T) -> T): T =
        withContext(io) { turns.withLock { inner.updateData(transform) } }
}
