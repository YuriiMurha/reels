package io.github.yuriimurha.reels.instagram.web

import io.github.yuriimurha.reels.instagram.InstagramException.ShapeChanged
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.RemoteMedia
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    /** [change] applied to item [index] (the `{"media": ...}` wrapper). */
    private fun JsonObject.withWrapper(index: Int, change: (JsonObject) -> JsonObject): JsonObject {
        val items = getValue("items").jsonArray.toMutableList()
        items[index] = change(items[index].jsonObject)
        return JsonObject(this + ("items" to JsonArray(items)))
    }

    /** [change] applied to the media object inside item [index]. */
    private fun JsonObject.withMedia(index: Int, change: (JsonObject) -> JsonObject): JsonObject =
        withWrapper(index) { it.with("media", change(it.getValue("media").jsonObject)) }

    private fun JsonObject.without(key: String) = JsonObject(filterKeys { it != key })

    private fun JsonObject.with(key: String, value: JsonElement) = JsonObject(this + (key to value))

    private fun mediaOf(pageName: String, index: Int): RemoteMedia = WebParsers.savedPage(fixture(pageName)).items[index]

    private fun assertSavedShapeChange(path: String, json: JsonObject) =
        assertEquals(path, assertFailsWith<ShapeChanged> { WebParsers.savedPage(json) }.fieldPath)

    private fun assertCollectionsShapeChange(path: String, json: JsonObject) =
        assertEquals(path, assertFailsWith<ShapeChanged> { WebParsers.collectionsPage(json) }.fieldPath)

    /** The first media of `saved_page_more.json` (the reel) with [key] set to [value], parsed. */
    private fun reelWith(key: String, value: JsonElement): RemoteMedia =
        WebParsers.savedPage(fixture("saved_page_more.json").withMedia(0) { it.with(key, value) }).items[0]

    private fun num(literal: String): JsonElement = Json.parseToJsonElement(literal)

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
        val more = fixture("saved_page_more.json")
        assertSavedShapeChange("items[1].media.user", more.withMedia(1) { it.without("user") })
        assertSavedShapeChange("items[0].media.pk", more.withMedia(0) { it.without("pk") })
        assertSavedShapeChange("items[0].media.code", more.withMedia(0) { it.without("code") })
        assertSavedShapeChange("items[0].media.taken_at", more.withMedia(0) { it.without("taken_at") })
        assertSavedShapeChange("items[0].media.user.username", more.withMedia(0) { it.with("user", JsonObject(emptyMap())) })
        assertSavedShapeChange("items[0]", more.with("items", JsonArray(listOf(JsonPrimitive("x")))))
    }

    @Test
    fun aMissingOrMistypedMediaIsAShapeChangeNotASkippedItem() {
        val more = fixture("saved_page_more.json")
        assertSavedShapeChange("items[1].media", more.withWrapper(1) { it.without("media") })
        assertSavedShapeChange("items[1].media", more.withWrapper(1) { JsonObject(mapOf("medium" to it.getValue("media"))) })
        assertSavedShapeChange("items[1].media", more.withWrapper(1) { it.with("media", JsonPrimitive("x")) })
        assertSavedShapeChange("items[1].media", more.withWrapper(1) { it.with("media", JsonArray(emptyList())) })
    }

    @Test
    fun anExplicitNullMediaIsSkipped() {
        val more = fixture("saved_page_more.json").withWrapper(0) { it.with("media", JsonNull) }
        assertEquals(listOf("3100000000000000002", "3100000000000000003"), WebParsers.savedPage(more).items.map { it.pk })
    }

    @Test
    fun moreAvailableThatIsNotABooleanIsAShapeChange() {
        val last = fixture("saved_page_last.json")
        assertSavedShapeChange("more_available", last.with("more_available", JsonPrimitive("false")))
        assertSavedShapeChange("more_available", last.with("more_available", JsonPrimitive("true")))
        assertSavedShapeChange("more_available", last.with("more_available", JsonNull))
        assertSavedShapeChange("more_available", last.with("more_available", JsonPrimitive(0)))
    }

    @Test
    fun anEmptyOrNullCursorIsAShapeChange() {
        val more = fixture("saved_page_more.json")
        assertSavedShapeChange("next_max_id", more.with("next_max_id", JsonPrimitive("")))
        assertSavedShapeChange("next_max_id", more.with("next_max_id", JsonNull))
    }

    @Test
    fun aPkThatIsNotDigitsIsAShapeChange() {
        val more = fixture("saved_page_more.json")
        assertSavedShapeChange("items[0].media.pk", more.withMedia(0) { it.with("pk", JsonPrimitive("x")) })
        assertSavedShapeChange("items[0].media.pk", more.withMedia(0) { it.with("pk", num("3.1E18")) })
        assertSavedShapeChange("items[0].media.pk", more.withMedia(0) { it.with("pk", JsonPrimitive("1".repeat(31))) })
        assertSavedShapeChange("items[0].media.pk", more.withMedia(0) { it.with("pk", JsonPrimitive("")) })
    }

    @Test
    fun aPkSentAsAStringOrNumberKeepsItsExactDigits() {
        assertEquals("3100000000000000001", reelWith("pk", JsonPrimitive("3100000000000000001")).pk)
        assertEquals("3100000000000000001", reelWith("pk", num("3100000000000000001")).pk)
    }

    @Test
    fun aTakenAtOutsideTheInstantRangeIsAShapeChange() {
        val more = fixture("saved_page_more.json")
        assertSavedShapeChange("items[0].media.taken_at", more.withMedia(0) { it.with("taken_at", JsonPrimitive(Long.MAX_VALUE)) })
    }

    @Test
    fun savedCollectionIdsThatAreNotAllStringsMeanTheResponseDoesNotSay() {
        val mixed = JsonArray(listOf(JsonPrimitive(1), JsonObject(mapOf("a" to JsonPrimitive(1)))))
        assertNull(reelWith("saved_collection_ids", mixed).savedCollectionIds)
        // One bad entry must not shorten the list: a shorter list would read as "not saved there" and delete memberships.
        val partlyBad = JsonArray(listOf(JsonPrimitive("17900000000000002"), JsonPrimitive(1)))
        assertNull(reelWith("saved_collection_ids", partlyBad).savedCollectionIds)
        assertNull(reelWith("saved_collection_ids", JsonArray(listOf(JsonPrimitive("17900000000000002"), JsonNull))).savedCollectionIds)
        assertEquals(emptyList(), reelWith("saved_collection_ids", JsonArray(emptyList())).savedCollectionIds)
    }

    @Test
    fun theCarouselCountComesFromTheFieldAndFallsBackToTheChildren() {
        val more = fixture("saved_page_more.json")
        fun carousel(count: JsonElement?): RemoteMedia = WebParsers.savedPage(
            more.withMedia(2) { media ->
                val twoChildren = JsonArray(media.getValue("carousel_media").jsonArray.take(2))
                val withoutCount = media.without("carousel_media_count").with("carousel_media", twoChildren)
                if (count == null) withoutCount else withoutCount.with("carousel_media_count", count)
            },
        ).items[2]
        assertEquals(2, carousel(null).carouselCount)
        assertEquals(5, carousel(JsonPrimitive(5)).carouselCount)
    }

    @Test
    fun aCarouselWithNeitherCountNorChildrenHasNoCount() {
        val more = fixture("saved_page_more.json").withMedia(2) { it.without("carousel_media_count").without("carousel_media") }
        val carousel = WebParsers.savedPage(more).items[2]
        assertNull(carousel.carouselCount)
        assertEquals("", carousel.thumbnailUrl)
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
    fun collectionsReturnTheCursorWhenMoreIsAvailable() {
        val json = fixture("collections_list.json")
            .with("more_available", JsonPrimitive(true))
            .with("next_max_id", JsonPrimitive("QVFE_cursor_c"))
        assertEquals("QVFE_cursor_c", WebParsers.collectionsPage(json).nextCursor)
    }

    @Test
    fun collectionsWithoutMoreAvailableAreAShapeChange() {
        assertCollectionsShapeChange("more_available", fixture("collections_list.json").without("more_available"))
    }

    @Test
    fun collectionsMoreAvailableWithoutACursorIsAShapeChange() {
        val json = fixture("collections_list.json").with("more_available", JsonPrimitive(true))
        assertCollectionsShapeChange("next_max_id", json)
    }

    @Test
    fun aCoverPkThatIsNotDigitsIsJustNoCover() {
        val list = fixture("collections_list.json")
        fun foodWithCover(pk: JsonElement) =
            WebParsers.collectionsPage(list.withWrapper(1) { it.with("cover_media", JsonObject(mapOf("pk" to pk))) }).items[0]
        assertNull(foodWithCover(JsonPrimitive("x")).coverMediaPk)
        assertNull(foodWithCover(num("3.1E18")).coverMediaPk)
        assertEquals("3100000000000000009", foodWithCover(JsonPrimitive("3100000000000000009")).coverMediaPk)
    }

    @Test
    fun mediaInfoReadsTheFirstItem() {
        val info = fixture("media_info.json")
        val reel = mediaOf("saved_page_more.json", 0)
        assertEquals(reel, assertNotNull(WebParsers.mediaInfo(info)))
        // With a second item present, the first still wins.
        val image = fixture("saved_page_more.json").getValue("items").jsonArray[1].jsonObject.getValue("media")
        val two = info.with("items", JsonArray(listOf(info.getValue("items").jsonArray[0], image)))
        assertEquals(reel, WebParsers.mediaInfo(two))
        val empty = JsonObject(mapOf("items" to JsonArray(emptyList()), "status" to JsonPrimitive("ok")))
        assertNull(WebParsers.mediaInfo(empty))
    }

    @Test
    fun mediaInfoNamesThePathOfAMissingField() {
        val info = fixture("media_info.json")
        val first = info.getValue("items").jsonArray[0].jsonObject
        val noCode = info.with("items", JsonArray(listOf(first.without("code"))))
        assertEquals("items[0].code", assertFailsWith<ShapeChanged> { WebParsers.mediaInfo(noCode) }.fieldPath)
        val notAnObject = info.with("items", JsonArray(listOf(JsonPrimitive("x"))))
        assertEquals("items[0]", assertFailsWith<ShapeChanged> { WebParsers.mediaInfo(notAnObject) }.fieldPath)
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
