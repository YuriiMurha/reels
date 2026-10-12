package io.github.yuriimurha.reels.instagram

import java.time.Instant

enum class MediaType { REEL, VIDEO, IMAGE, CAROUSEL }

data class Account(val pk: String, val username: String)

/** One page of a paginated Instagram listing. [nextCursor] is null on the last page. */
data class Page<T>(val items: List<T>, val nextCursor: String?)

data class RemoteCollection(val id: String, val name: String, val coverMediaPk: String?)

/** One child of a carousel post, in the order Instagram sends them. */
data class RemoteCarouselItem(
    /** 0-based. */
    val position: Int,
    /** [MediaType.IMAGE] or [MediaType.VIDEO] only. */
    val type: MediaType,
    val width: Int,
    val height: Int,
    /** The best candidate by `MediaLinks.chooseThumbnail`; empty when the child has none. */
    val imageUrl: String,
    /** The first `video_versions` url of a [MediaType.VIDEO] child, else null. */
    val videoUrl: String?,
    val videoUrlExpiresAt: Instant?,
)

data class RemoteMedia(
    val pk: String,
    val code: String,
    val type: MediaType,
    val author: String,
    val caption: String?,
    val takenAt: Instant,
    val width: Int,
    val height: Int,
    val carouselCount: Int?,
    val thumbnailUrl: String,
    val videoUrl: String?,
    val videoUrlExpiresAt: Instant?,
    /** Real collections this item is saved in, or null when the response doesn't say (spec 7.2). */
    val savedCollectionIds: List<String>?,
    /** A carousel's children, or null when this is not a carousel or the response carries no `carousel_media`. */
    val carouselItems: List<RemoteCarouselItem>? = null,
)
