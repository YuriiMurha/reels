package io.github.yuriimurha.reels.ui.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.ALL_SAVED_ID
import io.github.yuriimurha.reels.data.library.LibraryRepository
import io.github.yuriimurha.reels.data.library.MediaSource
import io.github.yuriimurha.reels.data.library.TypeFilter
import io.github.yuriimurha.reels.testutil.inMemoryDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SearchViewModelTest {
    private val db = inMemoryDb()
    private val viewModel by lazy { SearchViewModel(LibraryRepository(db)) }

    @Before
    fun setMain() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun typingProducesASearchAfterTheDebounce() = runTest {
        viewModel.query.value = "Leg day"
        advanceTimeBy(199)
        runCurrent()
        assertNull(viewModel.source.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(MediaSource.Search("leg* day*", TypeFilter.ALL, ALL_SAVED_ID), viewModel.source.value)
    }

    @Test
    fun nothingSearchableGivesNoSearch() = runTest {
        viewModel.query.value = "\"-*"
        advanceUntilIdle()
        assertNull(viewModel.source.value)
    }

    @Test
    fun filterAndScopeAreApplied() = runTest {
        viewModel.query.value = "pasta"
        viewModel.filter.value = TypeFilter.REELS
        viewModel.scope.value = "c2"
        advanceUntilIdle()
        assertEquals(MediaSource.Search("pasta*", TypeFilter.REELS, "c2"), viewModel.source.value)
    }
}
