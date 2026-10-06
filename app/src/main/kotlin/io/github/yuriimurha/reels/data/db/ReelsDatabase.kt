package io.github.yuriimurha.reels.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

@Database(
    entities = [
        MediaEntity::class, MediaFts::class, CollectionEntity::class, CollectionMediaEntity::class,
        SyncRunEntity::class, SyncCursorEntity::class, ApiRequestEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ReelsDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun collectionDao(): CollectionDao
    abstract fun syncDao(): SyncDao
    abstract fun apiRequestDao(): ApiRequestDao

    /** Wipes the library and sync history but keeps `api_request`, so the rolling budget survives (spec 7.3). */
    suspend fun deleteLibrary() = withTransaction {
        collectionDao().deleteAllMemberships()
        collectionDao().deleteAll()
        mediaDao().deleteAll()
        syncDao().deleteAllCursors()
        syncDao().deleteAllRuns()
    }

    companion object {
        /** No destructive fallback: a lost library costs a full, paced re-sync (spec 5.4). */
        fun build(context: Context): ReelsDatabase =
            Room.databaseBuilder(context, ReelsDatabase::class.java, "reels.db").build()
    }
}
