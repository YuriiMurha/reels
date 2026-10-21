package io.github.yuriimurha.reels.ui.home

import io.github.yuriimurha.reels.data.db.CollectionCard
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.testutil.endViewModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals

/** Home's badge from the latest run and, for the real library only, the session ([SyncBadge.from] has the mapping itself). */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val run = MutableStateFlow<SyncRunEntity?>(null)
    private val viewModels = mutableListOf<HomeViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        endViewModels(viewModels, main)
        Dispatchers.resetMain()
    }

    private fun runOf(status: SyncStatus) = SyncRunEntity(mode = SyncMode.QUICK, status = status, startedAt = 0)

    /** The badge as the screen sees it, after each of [steps]. */
    private fun badges(session: Flow<SessionState>?, vararg steps: () -> Unit): List<SyncBadge> {
        val seen = mutableListOf<SyncBadge>()
        runTest(main) {
            val viewModel = HomeViewModel(flowOf(emptyList<CollectionCard>()), run, session).also { viewModels += it }
            backgroundScope.launch { viewModel.badge.collect {} }
            for (step in steps) {
                step()
                seen += viewModel.badge.value
            }
        }
        return seen
    }

    @Test
    fun theBadgeFollowsTheLatestRun() {
        assertEquals(
            listOf(SyncBadge.IDLE, SyncBadge.RUNNING, SyncBadge.ATTENTION, SyncBadge.RUNNING, SyncBadge.IDLE),
            badges(
                null,
                {},
                { run.value = runOf(SyncStatus.RUNNING) },
                { run.value = runOf(SyncStatus.STOPPED_LOGIN) },
                { run.value = runOf(SyncStatus.RUNNING) },
                { run.value = runOf(SyncStatus.DONE) },
            ),
        )
    }

    @Test
    fun theRealLibrarysSessionCanAskForAttention() {
        val session = MutableStateFlow<SessionState>(SessionState.LoggedOut)
        assertEquals(
            listOf(SyncBadge.ATTENTION, SyncBadge.IDLE, SyncBadge.ATTENTION, SyncBadge.RUNNING),
            badges(
                session,
                {},
                { session.value = SessionState.Valid("handle") },
                { session.value = SessionState.Expired("handle") },
                { run.value = runOf(SyncStatus.RUNNING) },
            ),
        )
    }

    /** Mock mode passes no session: the fake library never asks for a login. */
    @Test
    fun mockModeHasNoSessionDot() {
        assertEquals(listOf(SyncBadge.IDLE, SyncBadge.IDLE), badges(null, {}, { run.value = runOf(SyncStatus.DONE) }))
    }

    /** A session that has not been read yet holds nothing up: the run shows at once. */
    @Test
    fun aRunShowsBeforeTheSessionHasBeenRead() {
        assertEquals(
            listOf(SyncBadge.IDLE, SyncBadge.RUNNING, SyncBadge.ATTENTION),
            badges(
                emptyFlow(),
                {},
                { run.value = runOf(SyncStatus.RUNNING) },
                { run.value = runOf(SyncStatus.PAUSED) },
            ),
        )
    }
}
