package io.github.yuriimurha.reels.instagram.fake

import io.github.yuriimurha.reels.instagram.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeLibraryTest {
    @Test
    fun sameSeedGivesSameLibrary() {
        assertEquals(FakeLibrary(seed = 7, itemCount = 50).allSaved(), FakeLibrary(seed = 7, itemCount = 50).allSaved())
    }

    @Test
    fun allSavedIsNewestFirst() {
        val taken = FakeLibrary(itemCount = 100).allSaved().map { it.takenAt }
        assertEquals(taken.sortedDescending(), taken)
    }

    @Test
    fun defaultLibraryCoversEveryShape() {
        val items = FakeLibrary().allSaved()
        assertEquals(2_000, items.size)
        assertEquals(MediaType.entries.toSet(), items.map { it.type }.toSet())
        assertTrue(items.any { it.savedCollectionIds.orEmpty().isEmpty() }, "some items in no collection")
        assertTrue(items.any { it.savedCollectionIds.orEmpty().size == 2 }, "some items in two collections")
        assertTrue(items.any { it.thumbnailUrl.startsWith("fake://missing/") }, "some unavailable items")
        assertEquals(items.size, items.map { it.code }.toSet().size, "codes are unique")
    }

    @Test
    fun itemsInFollowsMembership() {
        val library = FakeLibrary(itemCount = 200)
        val inFirst = library.itemsIn("c1")
        assertTrue(inFirst.isNotEmpty())
        assertTrue(inFirst.all { "c1" in it.savedCollectionIds.orEmpty() })
    }

    @Test
    fun newSavesGoOnTopNewestFirst() {
        val library = FakeLibrary(itemCount = 30)
        val added = library.addNewSaves(3, setOf("c2"))
        assertEquals(added, library.allSaved().take(3))
        assertEquals(added, library.itemsIn("c2").take(3))
    }

    @Test
    fun unsaveAndMoveChangeTheViews() {
        val library = FakeLibrary(itemCount = 30)
        val first = library.allSaved().first()
        library.unsave(first.pk)
        assertTrue(library.allSaved().none { it.pk == first.pk })

        val second = library.allSaved().first()
        library.setCollections(second.pk, setOf("c3"))
        assertEquals(listOf("c3"), library.media(second.pk)!!.savedCollectionIds)
    }
}
