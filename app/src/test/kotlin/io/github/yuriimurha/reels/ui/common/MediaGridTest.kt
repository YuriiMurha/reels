package io.github.yuriimurha.reels.ui.common

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
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
}
