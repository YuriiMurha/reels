package io.github.yuriimurha.reels.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SortKeysTest {
    @Test
    fun keysFallWithWalkIndex() {
        val base = SortKeys.walkBase(currentMax = null)
        val keys = (0L until 100L).map { SortKeys.key(base, it) }
        assertEquals(keys.sortedDescending(), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun aNewWalkSitsAboveEverythingBefore() {
        val oldMax = SortKeys.key(SortKeys.walkBase(null), 0)
        val base = SortKeys.walkBase(oldMax)
        assertTrue(SortKeys.key(base, SortKeys.WALK_SPAN - 1) > oldMax)
    }

    @Test
    fun indexOutsideTheSpanIsRejected() {
        assertFailsWith<IllegalArgumentException> { SortKeys.key(SortKeys.walkBase(null), SortKeys.WALK_SPAN) }
    }
}
