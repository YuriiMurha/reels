package io.github.yuriimurha.reels.testutil

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestDispatcher
import org.robolectric.Shadows.shadowOf

/**
 * Ends [viewModels]' scopes and waits until every coroutine in them has finished, running [main] (the test's Main dispatcher)
 * meanwhile. Call it before `Dispatchers.resetMain()` and before closing the database.
 *
 * Cancelling is not enough: a cancelled coroutine still resumes on its own dispatcher to finish. One that waits in
 * `withContext(Dispatchers.IO)` (or on a Room or DataStore thread) is resumed from that thread, which reads `Dispatchers.Main`;
 * a `resetMain` (or the next test's `setMain`) racing that read throws "Dispatchers.Main is used concurrently with setting it".
 * Nothing is left to read it once every job has completed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun endViewModels(viewModels: Collection<ViewModel>, main: TestDispatcher, timeoutMs: Long = 10_000) {
    val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
    jobs.forEach { it.cancel() }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        main.scheduler.advanceUntilIdle()
        if (jobs.all { it.isCompleted }) return
        check(System.currentTimeMillis() < deadline) { "a ViewModel's coroutine did not finish within $timeoutMs ms of its cancel" }
        Thread.sleep(1)
    }
}

/**
 * [endViewModels] for a screen test, whose Main is Robolectric's main looper rather than a test dispatcher: ends [viewModels]'
 * scopes and waits until every coroutine in them has finished, idling the main looper meanwhile. Call it from the test's own
 * (main) thread before closing the database.
 *
 * Disposing the screen is not enough: a ViewModel's `stateIn(WhileSubscribed(5_000))` keeps its Room flows running for 5 s
 * after the screen stops collecting, and a Room query queued or under way when the test ends still runs. Closing the database
 * under either throws "connection pool has been closed" into the ViewModel's scope, which fails whichever test idles the looper
 * next. A cancelled coroutine that waits on a query finishes only once that query has returned, so after this nothing of
 * theirs can touch the database; one whose query has not started yet never starts it.
 */
fun endViewModelsOnMainLooper(viewModels: Collection<ViewModel>, timeoutMs: Long = 10_000) {
    val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
    jobs.forEach { it.cancel() }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (jobs.all { it.isCompleted }) return
        check(System.currentTimeMillis() < deadline) { "a ViewModel's coroutine did not finish within $timeoutMs ms of its cancel" }
        Thread.sleep(1)
    }
}
