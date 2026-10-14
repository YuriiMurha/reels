package io.github.yuriimurha.reels.instagram.fake

import io.github.yuriimurha.reels.instagram.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    fun aFakeCarouselHasItsItems() {
        val items = FakeLibrary().allSaved()
        val carousels = items.filter { it.type == MediaType.CAROUSEL }
        assertTrue(carousels.any { (it.carouselCount ?: 0) >= 3 }, "some carousels long enough to hold a video")
        for (carousel in carousels) {
            val children = assertNotNull(carousel.carouselItems, carousel.pk)
            assertEquals(carousel.carouselCount, children.size, carousel.pk)
            assertEquals(children.indices.toList(), children.map { it.position }, carousel.pk)
            for (child in children) {
                // Every third child (positions 2, 5, ...) is a video; the rest are images.
                val video = child.position % 3 == 2
                assertEquals(if (video) MediaType.VIDEO else MediaType.IMAGE, child.type, carousel.pk)
                assertTrue(child.imageUrl.startsWith("fake://thumb/"), "a poster or image for every child")
                assertEquals(video, child.videoUrl != null, carousel.pk)
            }
            if (children.size >= 3) assertTrue(children.any { it.type == MediaType.VIDEO }, carousel.pk)
        }
        val urls = carousels.flatMap { it.carouselItems.orEmpty() }.map { it.imageUrl }
        assertEquals(urls.size, urls.toSet().size, "every child has its own image url")
        assertTrue(items.filter { it.type != MediaType.CAROUSEL }.all { it.carouselItems == null })
    }

    /** R28: every fake child has its own synthetic child pk, digits only, that is no other child's and no post's. */
    @Test
    fun everyFakeChildHasItsOwnChildPk() {
        val items = FakeLibrary().allSaved()
        val childPks = items.flatMap { it.carouselItems.orEmpty() }.map { assertNotNull(it.childPk) }
        assertTrue(childPks.size > 100, "precondition: many children (${childPks.size})")
        assertTrue(childPks.all { it.matches(Regex("[0-9]{1,30}")) }, "digits only")
        assertEquals(childPks.size, childPks.toSet().size, "no two children share one")
        val postPks = items.map { it.pk }.toSet()
        assertTrue(childPks.none { it in postPks }, "and none is a post's pk")
    }

    @Test
    fun theSameSeedGivesTheSameCarouselItems() {
        assertEquals(
            FakeLibrary(seed = 7, itemCount = 80).allSaved().map { it.carouselItems },
            FakeLibrary(seed = 7, itemCount = 80).allSaved().map { it.carouselItems },
        )
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
