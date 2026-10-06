package io.github.yuriimurha.reels.data.library

import io.github.yuriimurha.reels.instagram.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The card id Home uses for the computed "Uncategorized" view. */
const val UNCATEGORIZED_ID = "uncategorized"

enum class TypeFilter(val types: List<MediaType>) {
    ALL(MediaType.entries),
    REELS(listOf(MediaType.REEL, MediaType.VIDEO)),
    POSTS(listOf(MediaType.IMAGE, MediaType.CAROUSEL)),
}

/** What a grid or the viewer is showing. Encoded into navigation routes. */
@Serializable
sealed interface MediaSource {
    @Serializable
    @SerialName("collection")
    data class Collection(val id: String) : MediaSource

    @Serializable
    @SerialName("uncategorized")
    data object Uncategorized : MediaSource

    /** [match] is already sanitised by [FtsQuery.from]. [scope] is a collection id or the All Saved id. */
    @Serializable
    @SerialName("search")
    data class Search(val match: String, val filter: TypeFilter, val scope: String) : MediaSource

    fun encode(): String = Json.encodeToString(MediaSource.serializer(), this)

    companion object {
        fun decode(encoded: String): MediaSource = Json.decodeFromString(MediaSource.serializer(), encoded)
    }
}
