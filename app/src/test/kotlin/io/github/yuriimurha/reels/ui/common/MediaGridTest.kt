package io.github.yuriimurha.reels.ui.common

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A screen tall enough for the [revealTile] grid (Robolectric's default is 470 dp, which would squeeze it). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w320dp-h900dp")
class MediaGridTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tappingATileOpensItsIndex() {
        var opened = -1
        compose.setContent {
            ReelsTheme {
                val items = flowOf(PagingData.from(List(4) { mediaEntity("m$it", thumbPath = null) })).collectAsLazyPagingItems()
                MediaGrid(items, onOpen = { opened = it })
            }
        }
        compose.onAllNodesWithTag("tile")[2].performClick()
        assertEquals(2, opened)
    }

    @Test
    fun itemsWithoutDimensionsStillRender() {
        var opened = -1
        compose.setContent {
            ReelsTheme {
                val items = flowOf(
                    PagingData.from(
                        listOf(
                            mediaEntity("zero", thumbPath = null).copy(width = 0, height = 0),
                            mediaEntity("fine", thumbPath = null),
                        ),
                    ),
                ).collectAsLazyPagingItems()
                MediaGrid(items, onOpen = { opened = it })
            }
        }
        compose.onAllNodesWithTag("tile").assertCountEquals(2)
        compose.onAllNodesWithTag("tile")[0].performClick()
        assertEquals(0, opened)
    }

    // --- revealTile: the tile the post shrinks back into after the viewer ------------------------------------------------

    private lateinit var state: LazyStaggeredGridState
    private lateinit var scope: CoroutineScope

    /**
     * 40 tiles (item `i` is `m$i`) in a 300 x 800 dp grid with 50 dp of top padding (a top bar) and 30 dp of bottom padding:
     * tiles show whole between 50 and [END] dp. Two columns of 9:16 tiles about 340 dp tall each (with their two lines of
     * text), so the first two rows are whole (tiles 0 to 3) and the third (tiles 4 and 5) only begins.
     */
    private fun showGrid() {
        compose.setContent {
            ReelsTheme {
                state = rememberLazyStaggeredGridState()
                scope = rememberCoroutineScope()
                val items = flowOf(PagingData.from(List(40) { mediaEntity("m$it", thumbPath = null) })).collectAsLazyPagingItems()
                Box(Modifier.size(300.dp, 800.dp)) {
                    MediaGrid(items, onOpen = {}, state = state, contentPadding = PaddingValues(top = 50.dp, bottom = 30.dp))
                }
            }
        }
        compose.waitForIdle()
    }

    private fun run(block: suspend () -> Unit) {
        compose.runOnIdle { scope.launch { block() } }
        compose.waitForIdle()
    }

    /** Where tile [index] is laid out, whole (bounds in root would be cut off at the grid's edges); null when not composed. */
    private fun tile(index: Int): Rect? = compose.onAllNodes(hasTestTag("tile") and hasText("@author_m$index"))
        .fetchSemanticsNodes().singleOrNull()?.let { Rect(it.positionInRoot, it.size.toSize()) }

    /** The pixel edges of the visible run, from the dp ones. */
    private fun px(dp: Int): Float = with(compose.density) { dp.dp.toPx() }

    private fun assertNear(expected: Float, actual: Float, what: String) =
        assertTrue(abs(expected - actual) <= 1f, "$what: expected $expected, was $actual")

    @Test
    fun revealTileLeavesATileThatIsWhollyInViewWhereItIs() {
        showGrid()
        val before = tile(2)!!
        assertTrue(before.top > px(50) && before.bottom <= px(END), "the setup: tile 2 is whole in view, below the top, $before")
        run { state.revealTile(2) }
        assertEquals(before, tile(2))
    }

    @Test
    fun revealTileScrollsATileThatIsOutOfViewToTheTop() {
        showGrid()
        assertEquals(null, tile(20), "the setup: tile 20 is out of view")
        run { state.revealTile(20) }
        assertEquals(20, state.firstVisibleItemIndex)
        val shown = tile(20)!!
        assertNear(px(50), shown.top, "top")
    }

    @Test
    fun revealTileScrollsATileCutOffAtTheBottomJustIntoView() {
        showGrid()
        val before = tile(4)!!
        assertTrue(before.top < px(END) && before.bottom > px(END), "the setup: tile 4 is cut off at the bottom, $before")
        run { state.revealTile(4) }
        val shown = tile(4)!!
        assertNear(px(END), shown.bottom, "bottom")
        assertTrue(shown.top > px(50), "tile 4 went to the top instead of just into view: $shown")
    }

    @Test
    fun revealTileScrollsATileUnderTheTopBarJustIntoView() {
        showGrid()
        run { state.scrollBy(px(100)) }
        val before = tile(0)!!
        assertTrue(before.top < px(50) && before.bottom > px(50), "the setup: tile 0 is partly under the top bar, $before")
        run { state.revealTile(0) }
        assertNear(px(50), tile(0)!!.top, "top")
    }

    private companion object {
        /** Where the visible run ends: the grid's 800 dp less its 30 dp of bottom padding. */
        const val END = 770
    }
}
