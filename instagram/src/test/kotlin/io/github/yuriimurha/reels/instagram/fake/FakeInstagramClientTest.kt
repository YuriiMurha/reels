package io.github.yuriimurha.reels.instagram.fake

import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.RemoteMedia
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeInstagramClientTest {
    @Test
    fun pagesThroughAllSaved() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 45))
        val seen = mutableListOf<RemoteMedia>()
        var cursor: String? = null
        do {
            val page = client.savedMedia(collectionId = null, cursor = cursor)
            assertTrue(page.items.size <= 20)
            seen += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(client.library.allSaved(), seen)
        assertEquals(listOf("saved:all:null", "saved:all:o:20", "saved:all:o:40"), client.calls)
    }

    @Test
    fun collectionPagesFollowMembership() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 60))
        val page = client.savedMedia(collectionId = "c1", cursor = null)
        assertEquals(client.library.itemsIn("c1").take(20), page.items)
    }

    @Test
    fun hidesSavedCollectionIdsWhenNotReported() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 10), reportsSavedCollectionIds = false)
        assertTrue(client.savedMedia(null, null).items.all { it.savedCollectionIds == null })
    }

    @Test
    fun scriptedFailureHitsExactlyThatCall() = runTest {
        val client = FakeInstagramClient(
            FakeLibrary(itemCount = 10),
            failures = FakeFailures { call -> if (call == 2) InstagramException.ChallengeRequired(null) else null },
        )
        client.currentUser()
        assertFailsWith<InstagramException.ChallengeRequired> { client.collections(null) }
        assertEquals(2, client.calls.size)
    }

    @Test
    fun cursorPastTheEndGivesAnEmptyLastPage() = runTest {
        val client = FakeInstagramClient(FakeLibrary(itemCount = 5))
        val page = client.savedMedia(null, "o:40")
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun mediaInfoHidesSavedCollectionIdsWhenNotReported() = runTest {
        val library = FakeLibrary(itemCount = 10)
        // Find an item that has non-empty savedCollectionIds to avoid vacuous test
        val itemWithCollections = library.allSaved().first { it.savedCollectionIds.orEmpty().isNotEmpty() }
        assertTrue(itemWithCollections.savedCollectionIds.orEmpty().isNotEmpty(), "precondition: item has collections")

        // With reportsSavedCollectionIds = false, mediaInfo should hide the ids
        val clientHiding = FakeInstagramClient(library, reportsSavedCollectionIds = false)
        val mediaHiding = clientHiding.mediaInfo(itemWithCollections.pk)
        assertNull(mediaHiding.savedCollectionIds)

        // With reportsSavedCollectionIds = true, mediaInfo should expose the ids
        val clientReporting = FakeInstagramClient(library, reportsSavedCollectionIds = true)
        val mediaReporting = clientReporting.mediaInfo(itemWithCollections.pk)
        assertEquals(itemWithCollections.savedCollectionIds, mediaReporting.savedCollectionIds)
    }
}
