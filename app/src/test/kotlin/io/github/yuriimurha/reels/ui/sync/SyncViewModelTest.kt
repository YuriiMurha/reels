package io.github.yuriimurha.reels.ui.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.sync.SyncController
import io.github.yuriimurha.reels.sync.SyncScheduler
import io.github.yuriimurha.reels.sync.pacing.InMemoryCooldownStore
import io.github.yuriimurha.reels.sync.pacing.InMemoryRequestLog
import io.github.yuriimurha.reels.sync.pacing.Pacer
import io.github.yuriimurha.reels.sync.pacing.PacingPolicy
import io.github.yuriimurha.reels.testutil.inMemoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SyncViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val db = inMemoryDb()
    private val cooldowns = InMemoryCooldownStore()

    @Before
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private object IdleScheduler : SyncScheduler {
        override fun enqueue(runId: Long) = Unit
        override fun cancel() = Unit
        override suspend fun isActive(): Boolean = false
    }

    /** The clock is the test scheduler's, so the Pacer, the ViewModel and the 1 s ticker agree on the time. */
    private fun kotlinx.coroutines.test.TestScope.viewModel(): SyncViewModel {
        val clock = { START + testScheduler.currentTime }
        val pacer = Pacer(PacingPolicy.Conservative, InMemoryRequestLog(), cooldowns, now = clock)
        return SyncViewModel(
            SyncController(db, IdleScheduler, now = clock),
            LibraryRepository(db, ThumbnailStore(File(tmp.root, "thumbs"))),
            pacer,
            now = clock,
        )
    }

    @Test
    fun theCooldownBannerCountsDownWhileTheScreenIsOpen() = runTest {
        // A rate limit an hour minus 90 s ago: the 1 h cooldown ends 90 s from now.
        cooldowns.onRateLimited(START - 3_600_000 + 90_000)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.ui.collect {} }
        runCurrent()
        assertEquals("Cooling down after a rate limit: 2 min left", viewModel.ui.value.banner)
        assertFalse(viewModel.ui.value.canStart)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("Cooling down after a rate limit: 1 min left", viewModel.ui.value.banner)
    }

    @Test
    fun theCooldownEndsOnItsOwnWhileTheScreenIsOpen() = runTest {
        cooldowns.onRateLimited(START - 3_600_000 + 90_000)
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.ui.collect {} }
        runCurrent()
        assertFalse(viewModel.ui.value.canStart)

        advanceTimeBy(91_000)
        runCurrent()
        assertNull(viewModel.ui.value.banner)
        assertTrue(viewModel.ui.value.canStart)
    }

    private companion object {
        const val START = 10_000_000L
    }
}
