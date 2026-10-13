package io.github.yuriimurha.reels.testutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestDispatcher

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
