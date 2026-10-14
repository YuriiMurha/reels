package io.github.yuriimurha.reels.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CarouselItemDao {
    /** [pk]'s items in position order; empty when none are stored. */
    @Query("SELECT * FROM carousel_item WHERE parentPk = :pk ORDER BY position")
    suspend fun items(pk: String): List<CarouselItemEntity>

    @Query("SELECT * FROM carousel_item WHERE parentPk = :pk ORDER BY position")
    fun itemsFlow(pk: String): Flow<List<CarouselItemEntity>>

    @Query("DELETE FROM carousel_item WHERE parentPk = :pk")
    suspend fun deleteFor(pk: String)

    @Insert
    suspend fun insert(items: List<CarouselItemEntity>)

    /** Exactly [items] for [pk] afterwards, nothing left of its old set. Its post must be stored (the foreign key). */
    @Transaction
    suspend fun replace(pk: String, items: List<CarouselItemEntity>) {
        deleteFor(pk)
        insert(items)
    }

    @Query("DELETE FROM carousel_item")
    suspend fun deleteAll()
}

/**
 * R20, with R28: whether [new], in place of [old], puts something else at a position: the count changed, or the type at some
 * position did (a slide deleted, an image and a video swapped), or the child at some position is another one, by its own
 * Instagram id (`childPk`, R28: the poster reordered slides of the same type). A carousel's downloaded images and cached item
 * videos are keyed by position (`{pk}_{position}`), so they must go then, or the shifted positions would show the old ones.
 *
 * A null `childPk` on either side means unknown and never counts as a change: rows stored before version 3 have none (the
 * next sync fills them in without a drop), and a reply may not carry one. Links are never compared, so the same items with
 * renewed links keep everything (a CDN path may not stay the same when Instagram signs it again). Nothing stored before ([old]
 * empty) has nothing cached to go stale.
 */
fun itemShapeChanged(old: List<CarouselItemEntity>, new: List<CarouselItemEntity>): Boolean {
    if (old.isEmpty()) return false
    if (old.shape() != new.shape()) return true
    val before = old.associateBy { it.position }
    return new.any { item ->
        val was = before[item.position]?.childPk
        was != null && item.childPk != null && was != item.childPk
    }
}

private fun List<CarouselItemEntity>.shape() = map { it.position to it.type }.sortedBy { it.first }
