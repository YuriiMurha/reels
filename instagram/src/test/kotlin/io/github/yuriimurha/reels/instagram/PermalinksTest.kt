package io.github.yuriimurha.reels.instagram

import kotlin.test.Test
import kotlin.test.assertEquals

class PermalinksTest {
    @Test
    fun reelsUseReelPathEverythingElsePostPath() {
        assertEquals("https://www.instagram.com/reel/ABC/", Permalinks.of(MediaType.REEL, "ABC"))
        assertEquals("https://www.instagram.com/p/ABC/", Permalinks.of(MediaType.VIDEO, "ABC"))
        assertEquals("https://www.instagram.com/p/ABC/", Permalinks.of(MediaType.IMAGE, "ABC"))
        assertEquals("https://www.instagram.com/p/ABC/", Permalinks.of(MediaType.CAROUSEL, "ABC"))
    }
}
