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
