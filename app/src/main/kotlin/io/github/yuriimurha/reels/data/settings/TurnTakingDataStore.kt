package io.github.yuriimurha.reels.data.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * [inner], except that a collector's first read and a write take turns.
 *
 * DataStore 1.2.1 can lose an update for a collector of [DataStore.data] that starts while a write is under way (b/431787506).
 * The collector's first read is made without the file lock: it can read the version the write has just taken but the file the
 * write has not yet renamed into place, and so start from the old value under the new version. The write's own update then
 * carries that same version, and the collector drops it as not newer. The collector shows the old value until the next write
 * (any key) or until it is collected anew. Upstream fixed it on androidx-main in commit 1ef22b8 (an unlocked read takes the
 * cached version); no release note names it, so TODO.md says how to tell when this class can go.
 *
 * A mutex held from a collector's start to its first value, and around each write, keeps the two apart. Later values are not
 * gated: by then the collector follows DataStore's in-memory cache, whose versions only grow. A write whose caller is cancelled
 * keeps the mutex until DataStore has finished whatever it still does for it (a no-op update queues behind it).
 *
 * The mutex is taken and given back only on [io], never on a caller's thread: a caller may block its thread waiting for the
 * mutex (`runBlocking` on a read), and a write or a first read that had to resume on that thread to give it back would wait for
 * ever. So a write's transform runs on [io] too (DataStore runs it in the caller's context).
 *
 * A transform must not use this store: a read or a write from inside one would wait for ever for its own write's mutex. Both
 * fail at once with an [IllegalStateException] instead (DataStore alone allows the read and refuses the nested write).
 */
internal class TurnTakingDataStore<T>(
    private val inner: DataStore<T>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DataStore<T> {
    private val turns = Mutex()

    /** In the context of this store's writes, so their transforms carry it (DataStore runs a transform in its caller's context). */
    private val writing = object : CoroutineContext.Element, CoroutineContext.Key<CoroutineContext.Element> {
        override val key: CoroutineContext.Key<*> get() = this
    }

    private fun checkNotInsideAWrite(context: CoroutineContext) =
        check(context[writing] == null) { "the settings store was used inside its own write's transform, which would wait for ever" }

    override val data: Flow<T> = flow {
        checkNotInsideAWrite(currentCoroutineContext())
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

    override suspend fun updateData(transform: suspend (t: T) -> T): T {
        checkNotInsideAWrite(currentCoroutineContext())
        return withContext(io + writing) {
            turns.withLock {
                try {
                    inner.updateData(transform)
                } catch (e: CancellationException) {
                    // DataStore may still be writing for this cancelled caller: let it finish before the mutex goes.
                    withContext(NonCancellable) { runCatching { inner.updateData { it } } }
                    throw e
                }
            }
        }
    }
}
