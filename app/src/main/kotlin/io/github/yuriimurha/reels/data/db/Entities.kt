package io.github.yuriimurha.reels.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.RemoteCarouselItem

/** The pseudo-collection holding every saved item ("All Saved"). */
const val ALL_SAVED_ID = "__all__"

@Entity(tableName = "media", indices = [Index(value = ["code"], unique = true)])
data class MediaEntity(
    @PrimaryKey val pk: String,
    val code: String,
    val type: MediaType,
    val author: String,
    val caption: String?,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val carouselCount: Int?,
    val thumbPath: String?,
    val thumbUrl: String,
    val videoUrl: String?,
    val videoUrlExpiresAt: Long?,
    /** Space-joined names of the item's live real collections; only for search. */
    val collectionNames: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val removedAt: Long?,
)

/**
 * One item of a carousel post, at its 0-based [position] (spec 2026-10-12 §3.1). Deleting the post deletes its items; the
 * sync's upsert of the post updates it in place, so that never fires on a re-sync.
 */
@Entity(
    tableName = "carousel_item",
    primaryKeys = ["parentPk", "position"],
    foreignKeys = [ForeignKey(entity = MediaEntity::class, parentColumns = ["pk"], childColumns = ["parentPk"], onDelete = ForeignKey.CASCADE)],
)
data class CarouselItemEntity(
    val parentPk: String,
    val position: Int,
    /** [MediaType.IMAGE] or [MediaType.VIDEO]. */
    val type: MediaType,
    val width: Int,
    val height: Int,
    /** Empty when Instagram sent no image for the item. */
    val imageUrl: String,
    val videoUrl: String?,
    /** Epoch millis, like [MediaEntity.videoUrlExpiresAt]. */
    val videoUrlExpiresAt: Long?,
    /**
     * R28 (version 3): the child's own Instagram id, digits only, or null when unknown (a reply without a readable one, or a
     * row stored before version 3, until the next sync or renewal stores the item again). It tells R20 which slide is at
     * [position]: [itemShapeChanged].
     */
    val childPk: String? = null,
)

/** This item's row, as an item of the post [parentPk]. */
fun RemoteCarouselItem.toEntity(parentPk: String) = CarouselItemEntity(
    parentPk = parentPk,
    position = position,
    type = type,
    width = width,
    height = height,
    imageUrl = imageUrl,
    videoUrl = videoUrl,
    videoUrlExpiresAt = videoUrlExpiresAt?.toEpochMilli(),
    childPk = childPk,
)

@Fts4(contentEntity = MediaEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "media_fts")
data class MediaFts(val caption: String?, val author: String, val collectionNames: String)

@Entity(tableName = "collection")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val coverPk: String?,
    val position: Int,
    val removedAt: Long? = null,
)

@Entity(
    tableName = "collection_media",
    primaryKeys = ["collectionId", "mediaPk"],
    indices = [Index(value = ["collectionId", "sortKey"]), Index(value = ["mediaPk"])],
)
data class CollectionMediaEntity(
    val collectionId: String,
    val mediaPk: String,
    val sortKey: Long,
    val lastSeenRunId: Long,
)

enum class SyncMode { QUICK, FULL }

enum class SyncStatus {
    RUNNING, PAUSED, DONE, CANCELLED, STOPPED_CHALLENGE, STOPPED_LOGIN, STOPPED_RATE_LIMIT, STOPPED_SHAPE;

    /** A run that ended unfinished; the next Sync tap resumes it from its cursors (spec 7.1). */
    val isResumable: Boolean get() = this == PAUSED || name.startsWith("STOPPED_")
}

@Entity(tableName = "sync_run")
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: SyncMode,
    val status: SyncStatus,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val phase: String = "",
    val collectionsDone: Int = 0,
    val collectionsTotal: Int = 0,
    val requestsUsed: Int = 0,
    val newItems: Int = 0,
    val seenItems: Int = 0,
    val thumbsCached: Int = 0,
    val failures: Int = 0,
    /** Already-redacted text shown to the owner. */
    val lastError: String? = null,
)

@Entity(tableName = "sync_cursor", primaryKeys = ["runId", "scope"])
data class SyncCursorEntity(
    val runId: Long,
    val scope: String,
    val nextCursor: String?,
    val walkBase: Long,
    val walkIndex: Long,
    val done: Boolean,
)

/** One row per Instagram API request; backs the rolling 24 h budget. */
@Entity(tableName = "api_request")
data class ApiRequestEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long)

data class CollectionCard(val id: String, val name: String, val count: Int, val coverThumbPath: String?)
