package io.github.yuriimurha.reels.data.media

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** The reconcile's eviction tries every step of every pk on its own (the container's: video, item videos, item images). */
class MediaEvictionTest {
    @Test
    fun aFailingStepSkipsNoOtherStepAndNoLaterPk() {
        val done = mutableListOf<String>()
        val eviction = MediaEviction.eachStep(
            { pk -> if (pk == "2") throw IOException("disk error") else done += "video:$pk" },
            { pk -> if (pk == "3") throw IllegalArgumentException("Unexpected media key") else done += "items:$pk" },
            { pk -> done += "images:$pk" },
        )

        eviction.evict(listOf("1", "2", "3"))

        assertEquals(
            listOf("video:1", "items:1", "images:1", "items:2", "images:2", "video:3", "images:3"),
            done,
            "every step that could run did, in order",
        )
    }

    @Test
    fun nothingToEvictRunsNothing() {
        var runs = 0
        MediaEviction.eachStep({ runs++ }).evict(emptyList())
        assertEquals(0, runs)
    }
}
