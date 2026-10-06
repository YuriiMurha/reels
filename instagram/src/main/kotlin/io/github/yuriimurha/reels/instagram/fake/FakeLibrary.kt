package io.github.yuriimurha.reels.instagram.fake

import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.RemoteCollection
import io.github.yuriimurha.reels.instagram.RemoteMedia
import java.time.Instant
import kotlin.random.Random

/**
 * A deterministic, mutable stand-in for one account's saved items. Index 0 is the newest save.
 * Thumbnails use `fake://thumb/{pk}`; `fake://missing/{pk}` simulates an item Instagram no longer serves.
 */
class FakeLibrary(seed: Long = 42, itemCount: Int = 2_000, collectionCount: Int = 8) {
    private class Entry(val media: RemoteMedia, val collectionIds: MutableSet<String>)

    private val random = Random(seed)
    private val entries = mutableListOf<Entry>()
    private val collectionList = mutableListOf<RemoteCollection>()
    private var nextIndex = 0

    val collections: List<RemoteCollection> get() = collectionList.toList()

    init {
        require(collectionCount <= NAMES.size) { "At most ${NAMES.size} fake collections" }
        repeat(collectionCount) { i -> collectionList += RemoteCollection("c${i + 1}", NAMES[i], coverMediaPk = null) }
        // Generated oldest first, so the highest index (newest) ends up at position 0.
        val generated = List(itemCount) { newEntry(randomCollections()) }
        entries += generated.asReversed()
    }

    fun allSaved(): List<RemoteMedia> = entries.map { it.snapshot() }

    fun itemsIn(collectionId: String): List<RemoteMedia> =
        entries.filter { collectionId in it.collectionIds }.map { it.snapshot() }

    fun media(pk: String): RemoteMedia? = entries.firstOrNull { it.media.pk == pk }?.snapshot()

    /** Saves [count] new items on top. Returns them newest first. */
    fun addNewSaves(count: Int, collectionIds: Set<String> = emptySet()): List<RemoteMedia> {
        val added = List(count) { newEntry(collectionIds.toMutableSet()) }.asReversed()
        entries.addAll(0, added)
        return added.map { it.snapshot() }
    }

    fun unsave(pk: String) {
        entries.removeAll { it.media.pk == pk }
    }

    fun setCollections(pk: String, collectionIds: Set<String>) {
        val entry = entries.first { it.media.pk == pk }
        entry.collectionIds.clear()
        entry.collectionIds.addAll(collectionIds)
    }

    private fun Entry.snapshot() = media.copy(savedCollectionIds = collectionIds.sorted())

    private fun randomCollections(): MutableSet<String> {
        val roll = random.nextInt(100)
        val count = when {
            roll < 25 -> 0
            roll < 85 -> 1
            else -> 2
        }
        return collectionList.shuffled(random).take(count).map { it.id }.toMutableSet()
    }

    private fun newEntry(collectionIds: MutableSet<String>): Entry {
        val i = nextIndex++
        val type = when (random.nextInt(100)) {
            in 0..59 -> MediaType.REEL
            in 60..69 -> MediaType.VIDEO
            in 70..89 -> MediaType.IMAGE
            else -> MediaType.CAROUSEL
        }
        val pk = (1_000_000 + i).toString()
        val (width, height) = when (type) {
            MediaType.REEL, MediaType.VIDEO -> 1080 to 1920
            MediaType.IMAGE -> if (random.nextBoolean()) 1080 to 1080 else 1080 to 1350
            MediaType.CAROUSEL -> 1080 to 1350
        }
        val caption = if (random.nextInt(10) == 0) {
            null
        } else {
            List(3 + random.nextInt(8)) { WORDS[random.nextInt(WORDS.size)] }.joinToString(" ")
        }
        val missing = i % 97 == 96
        val media = RemoteMedia(
            pk = pk,
            code = "F" + i.toString(36).uppercase().padStart(6, '0'),
            type = type,
            author = "creator_${random.nextInt(200)}",
            caption = caption,
            takenAt = BASE_TIME.plusSeconds(i * 3_600L),
            width = width,
            height = height,
            carouselCount = if (type == MediaType.CAROUSEL) 2 + random.nextInt(9) else null,
            thumbnailUrl = if (missing) "fake://missing/$pk" else "fake://thumb/$pk",
            videoUrl = if (type == MediaType.REEL || type == MediaType.VIDEO) "fake://video/$pk" else null,
            videoUrlExpiresAt = null,
            savedCollectionIds = null,
        )
        return Entry(media, collectionIds)
    }

    private companion object {
        val NAMES = listOf("Workouts", "Recipes", "Travel", "Music", "Funny", "Tech", "Design", "Later")
        val WORDS = listOf(
            "morning", "routine", "pasta", "quick", "lisbon", "sunset", "guitar", "cover", "cat", "dog",
            "kotlin", "compose", "tips", "leg", "day", "mobility", "coffee", "street", "food", "minimal",
            "poster", "type", "beach", "train", "hack", "laugh", "drums", "bread", "hike", "city",
        )
        val BASE_TIME: Instant = Instant.parse("2024-01-01T00:00:00Z")
    }
}
