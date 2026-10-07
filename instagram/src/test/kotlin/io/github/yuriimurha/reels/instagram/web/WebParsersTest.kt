package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException.ShapeChanged
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.RemoteMedia
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebParsersTest {
    private fun fixture(name: String): JsonObject =
        Json.parseToJsonElement(javaClass.getResource("/fixtures/web/$name")!!.readText()).jsonObject

    /** [change] applied to the media object inside item [index], or to the item itself when it has no `media`. */
    private fun JsonObject.withMedia(index: Int, change: (JsonObject) -> JsonObject): JsonObject {
        val items = getValue("items").jsonArray.toMutableList()
        val wrapper = items[index].jsonObject
        items[index] = JsonObject(wrapper + ("media" to change(wrapper.getValue("media").jsonObject)))
        return JsonObject(this + ("items" to JsonArray(items)))
    }

    private fun JsonObject.without(key: String) = JsonObject(filterKeys { it != key })

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private fun mediaOf(pageName: String, index: Int): RemoteMedia = WebParsers.savedPage(fixture(pageName)).items[index]

    @Test
    fun savedPageMapsEveryType() {
        val page = WebParsers.savedPage(fixture("saved_page_more.json"))
        assertEquals(listOf(MediaType.REEL, MediaType.IMAGE, MediaType.CAROUSEL), page.items.map { it.type })
        assertEquals("QVFE_cursor_2", page.nextCursor)

        val reel = page.items[0]
        assertEquals("3100000000000000001", reel.pk)
        assertEquals("Cx1aBcDeFg1", reel.code)
        assertEquals("user_1", reel.author)
        assertEquals("Synthetic caption one", reel.caption)
        assertEquals(Instant.ofEpochSecond(1700003000), reel.takenAt)
        assertEquals(1080, reel.width)
        assertEquals(1920, reel.height)
        assertNull(reel.carouselCount)
        assertEquals("https://cdn.example.invalid/v/t51/102.jpg?stp=x&oe=6720A3F0&_nc_ht=cdn", reel.thumbnailUrl)
        assertEquals("https://cdn.example.invalid/v/t50/104.mp4?stp=x&oe=6720A3F0&_nc_ht=cdn", reel.videoUrl)
        assertEquals(Instant.ofEpochSecond(0x6720A3F0), reel.videoUrlExpiresAt)
        assertEquals(listOf("17900000000000002"), reel.savedCollectionIds)

        val image = page.items[1]
        assertEquals("3100000000000000002", image.pk)
        assertNull(image.caption)
        assertNull(image.savedCollectionIds)
        assertNull(image.videoUrl)
        assertNull(image.videoUrlExpiresAt)
        assertEquals(Instant.ofEpochSecond(1700002000), image.takenAt)
        assertEquals("https://cdn.example.invalid/v/t51/202.jpg?stp=x&oe=6720A3F0&_nc_ht=cdn", image.thumbnailUrl)

        val carousel = page.items[2]
        assertEquals("3100000000000000003", carousel.pk)
        assertEquals(3, carousel.carouselCount)
        assertEquals("https://cdn.example.invalid/v/t51/302.jpg?stp=x&oe=6720A3F0&_nc_ht=cdn", carousel.thumbnailUrl)
        assertEquals(Instant.ofEpochSecond(1700001000), carousel.takenAt)
    }

    @Test
    fun lastPageHasNoCursor() {
        val page = WebParsers.savedPage(fixture("saved_page_last.json"))
        assertEquals(1, page.items.size)
        assertNull(page.nextCursor)
    }

    @Test
    fun collectionPageReadsLikeASavedPage() {
        val page = WebParsers.savedPage(fixture("collection_page.json"))
        assertEquals(listOf("3100000000000000005", "3100000000000000006"), page.items.map { it.pk })
        assertNull(page.nextCursor)
    }

    @Test
    fun unavailableItemsDoNotStopThePage() {
        val page = WebParsers.savedPage(fixture("saved_page_unavailable.json"))
        assertEquals(2, page.items.size)
        assertNull(page.nextCursor)
        assertEquals("3100000000000000007", page.items[0].pk)
        val bare = page.items[1]
        assertEquals("3100000000000000008", bare.pk)
        assertEquals("", bare.thumbnailUrl)
        assertEquals(0, bare.width)
        assertEquals(0, bare.height)
    }

    @Test
    fun missingMoreAvailableIsAShapeChange() {
        val json = fixture("saved_page_last.json").without("more_available")
        assertEquals("more_available", assertFailsWith<ShapeChanged> { WebParsers.savedPage(json) }.fieldPath)
    }

    @Test
    fun moreAvailableWithoutCursorIsAShapeChange() {
        val json = fixture("saved_page_more.json").without("next_max_id")
        assertEquals("next_max_id", assertFailsWith<ShapeChanged> { WebParsers.savedPage(json) }.fieldPath)
    }

    @Test
    fun missingRequiredFieldNamesItsPath() {
        val json = fixture("saved_page_more.json").withMedia(1) { it.without("user") }
        assertEquals("items[1].media.user", assertFailsWith<ShapeChanged> { WebParsers.savedPage(json) }.fieldPath)
    }

    @Test
    fun unknownMediaTypeIsAShapeChange() {
        val json = fixture("saved_page_more.json").withMedia(0) { it.with("media_type", JsonPrimitive(5)) }
        assertEquals("items[0].media.media_type", assertFailsWith<ShapeChanged> { WebParsers.savedPage(json) }.fieldPath)
    }

    @Test
    fun missingItemsIsAShapeChange() {
        assertEquals("items", assertFailsWith<ShapeChanged> { WebParsers.savedPage(JsonObject(emptyMap())) }.fieldPath)
    }

    @Test
    fun collectionsKeepOnlyMediaCollections() {
        val page = WebParsers.collectionsPage(fixture("collections_list.json"))
        assertEquals(listOf("Food", "Travel"), page.items.map { it.name })
        assertEquals(listOf("17900000000000002", "17900000000000003"), page.items.map { it.id })
        assertEquals("3100000000000000001", page.items[0].coverMediaPk)
        assertNull(page.items[1].coverMediaPk)
        assertNull(page.nextCursor)
    }

    @Test
    fun mediaInfoReadsTheFirstItem() {
        val info = assertNotNull(WebParsers.mediaInfo(fixture("media_info.json")))
        assertEquals(mediaOf("saved_page_more.json", 0), info)
        val empty = JsonObject(mapOf("items" to JsonArray(emptyList()), "status" to JsonPrimitive("ok")))
        assertNull(WebParsers.mediaInfo(empty))
    }

    @Test
    fun unknownKeysAreIgnored() {
        val extra = JsonObject(mapOf("x" to JsonPrimitive(1)))
        val base = fixture("saved_page_more.json")
        // The new key sits on the page, on every item wrapper and on every media object.
        val items = base.getValue("items").jsonArray.map { wrapper ->
            val media = wrapper.jsonObject.getValue("media").jsonObject.with("brand_new_key", extra)
            wrapper.jsonObject.with("brand_new_key", extra).with("media", media)
        }
        val json = base.with("brand_new_key", extra).with("items", JsonArray(items))
        assertEquals(WebParsers.savedPage(base), WebParsers.savedPage(json))
    }
}
