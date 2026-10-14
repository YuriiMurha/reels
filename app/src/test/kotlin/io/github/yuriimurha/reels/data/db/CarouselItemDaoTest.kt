package io.github.yuriimurha.reels.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.RemoteCarouselItem
import io.github.yuriimurha.reels.testutil.inMemoryDb
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class CarouselItemDaoTest {
    private val db = inMemoryDb()
    private val media = db.mediaDao()
    private val items = db.carouselItemDao()

    @After
    fun close() = db.close()

    private fun item(parentPk: String, position: Int, type: MediaType = MediaType.IMAGE) = CarouselItemEntity(
        parentPk = parentPk,
        position = position,
        type = type,
        width = 1080,
        height = if (type == MediaType.VIDEO) 1920 else 1350,
        imageUrl = "fake://thumb/$parentPk-$position",
        videoUrl = if (type == MediaType.VIDEO) "fake://video/$parentPk-$position" else null,
        videoUrlExpiresAt = if (type == MediaType.VIDEO) 1_700_000_000_000 else null,
    )

    private suspend fun givenCarousels(vararg pks: String) =
        media.upsert(pks.map { mediaEntity(it, type = MediaType.CAROUSEL) })

    private fun itemCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM carousel_item").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun replaceSwapsAPostsItems() = runTest {
        givenCarousels("m1", "m2")
        items.replace("m1", listOf(item("m1", 0), item("m1", 1), item("m1", 2, MediaType.VIDEO)))
        items.replace("m2", listOf(item("m2", 0), item("m2", 1)))

        val fewer = listOf(item("m1", 0, MediaType.VIDEO).copy(imageUrl = "fake://thumb/m1-new"))
        items.replace("m1", fewer)

        assertEquals(fewer, items.items("m1"), "exactly the new set: nothing of the old one is left")
        assertEquals(listOf(item("m2", 0), item("m2", 1)), items.items("m2"), "another post's items are left alone")
    }

    @Test
    fun itemsComeInPositionOrder() = runTest {
        givenCarousels("m1")
        // 10 after 2: positions are numbers, not text.
        items.insert(listOf(item("m1", 2), item("m1", 10), item("m1", 0), item("m1", 1)))

        assertEquals(listOf(0, 1, 2, 10), items.items("m1").map { it.position })
        assertEquals(listOf(0, 1, 2, 10), items.itemsFlow("m1").first().map { it.position })
    }

    @Test
    fun deletingThePostDeletesItsItems() = runTest {
        givenCarousels("m1", "m2")
        items.replace("m1", listOf(item("m1", 0), item("m1", 1)))
        items.replace("m2", listOf(item("m2", 0)))

        db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE pk = 'm1'")

        assertEquals(emptyList(), items.items("m1"))
        assertEquals(listOf(item("m2", 0)), items.items("m2"))
    }

    /** The sync's upsert updates the post in place: the cascade must not fire and take the items with it. */
    @Test
    fun upsertingThePostAgainKeepsItsItems() = runTest {
        givenCarousels("m1")
        items.replace("m1", listOf(item("m1", 0), item("m1", 1)))

        media.upsert(listOf(mediaEntity("m1", type = MediaType.CAROUSEL, caption = "edited")))

        assertEquals("edited", media.byPks(listOf("m1")).single().caption)
        assertEquals(listOf(item("m1", 0), item("m1", 1)), items.items("m1"))
    }

    @Test
    fun deleteLibraryRemovesEveryItem() = runTest {
        givenCarousels("m1", "m2")
        items.replace("m1", listOf(item("m1", 0), item("m1", 1)))
        items.replace("m2", listOf(item("m2", 0)))

        db.deleteLibrary()

        assertEquals(0, itemCount())
    }

    @Test
    fun deleteAllRemovesEveryItemAndKeepsThePosts() = runTest {
        givenCarousels("m1", "m2")
        items.replace("m1", listOf(item("m1", 0)))
        items.replace("m2", listOf(item("m2", 0)))

        items.deleteAll()

        assertEquals(0, itemCount())
        assertEquals(listOf("m1", "m2"), media.byPks(listOf("m1", "m2")).map { it.pk }.sorted())
    }

    @Test
    fun aRemoteItemBecomesItsRowWithTheExpiryInMillisLikeTheMediaRow() {
        val remote = RemoteCarouselItem(
            position = 2, type = MediaType.VIDEO, width = 720, height = 1280,
            imageUrl = "fake://thumb/m1-2", videoUrl = "fake://video/m1-2", videoUrlExpiresAt = Instant.ofEpochSecond(1_700_000_000),
            childPk = "3100000000000000032",
        )

        assertEquals(
            CarouselItemEntity(
                parentPk = "m1", position = 2, type = MediaType.VIDEO, width = 720, height = 1280,
                imageUrl = "fake://thumb/m1-2", videoUrl = "fake://video/m1-2", videoUrlExpiresAt = 1_700_000_000_000,
                childPk = "3100000000000000032",
            ),
            remote.toEntity("m1"),
        )
        assertNull(remote.copy(videoUrl = null, videoUrlExpiresAt = null).toEntity("m1").videoUrlExpiresAt)
        assertNull(remote.copy(childPk = null).toEntity("m1").childPk, "unknown stays unknown")
    }

    /** R28's version-3 column is stored and read back, null included. */
    @Test
    fun theChildPkIsStoredWithTheItem() = runTest {
        givenCarousels("m1")
        items.replace("m1", listOf(item("m1", 0).copy(childPk = "3100000000000000031"), item("m1", 1)))

        assertEquals(listOf("3100000000000000031", null), items.items("m1").map { it.childPk })
    }

    /**
     * R20 with R28, the rule itself: a change of count, of a position's type, or of the child at a position when both sides
     * know it drops; renewed links never do, and a child pk unknown on either side never does.
     */
    @Test
    fun itemShapeChangedTellsAnotherChildOnlyWhenBothSidesKnowIt() {
        fun of(vararg children: Pair<MediaType, String?>) =
            children.mapIndexed { position, (type, childPk) -> item("m1", position, type).copy(childPk = childPk) }
        val stored = of(MediaType.IMAGE to "11", MediaType.IMAGE to "12", MediaType.VIDEO to "13")

        assertEquals(false, itemShapeChanged(emptyList(), stored), "nothing stored, nothing to drop")
        assertEquals(false, itemShapeChanged(stored, stored.map { it.copy(imageUrl = "${it.imageUrl}-renewed", videoUrl = it.videoUrl?.let { url -> "$url-renewed" }) }), "renewed links")
        assertEquals(true, itemShapeChanged(stored, stored.dropLast(1)), "a slide fewer")
        assertEquals(true, itemShapeChanged(stored, of(MediaType.IMAGE to "11", MediaType.VIDEO to "12", MediaType.VIDEO to "13")), "a type")
        assertEquals(true, itemShapeChanged(stored, of(MediaType.IMAGE to "12", MediaType.IMAGE to "11", MediaType.VIDEO to "13")), "a same-type swap")
        assertEquals(true, itemShapeChanged(stored, of(MediaType.IMAGE to "11", MediaType.IMAGE to "14", MediaType.VIDEO to "13")), "a slide replaced")
        assertEquals(false, itemShapeChanged(of(MediaType.IMAGE to null, MediaType.IMAGE to null, MediaType.VIDEO to null), stored), "null to a value")
        assertEquals(false, itemShapeChanged(stored, of(MediaType.IMAGE to null, MediaType.IMAGE to null, MediaType.VIDEO to null)), "a value to null")
        assertEquals(true, itemShapeChanged(stored, of(MediaType.IMAGE to null, MediaType.IMAGE to "11", MediaType.VIDEO to "13")), "one known position that changed is enough")
    }
}
