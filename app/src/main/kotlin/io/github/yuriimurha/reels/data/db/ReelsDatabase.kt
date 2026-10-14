package io.github.yuriimurha.reels.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediaEntity::class, MediaFts::class, CollectionEntity::class, CollectionMediaEntity::class,
        SyncRunEntity::class, SyncCursorEntity::class, ApiRequestEntity::class, CarouselItemEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class ReelsDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun collectionDao(): CollectionDao
    abstract fun syncDao(): SyncDao
    abstract fun apiRequestDao(): ApiRequestDao
    abstract fun carouselItemDao(): CarouselItemDao

    /** Wipes the library and sync history but keeps `api_request`, so the rolling budget survives (spec 7.3). */
    suspend fun deleteLibrary() = withTransaction {
        collectionDao().deleteAllMemberships()
        collectionDao().deleteAll()
        carouselItemDao().deleteAll()
        mediaDao().deleteAll()
        syncDao().deleteAllCursors()
        syncDao().deleteAllRuns()
    }

    companion object {
        /**
         * [name] is the database file: `reels.db` for the fake library, `library.db` for the real one and for the request
         * log behind the 24 h budget (P2). [callback] is for database-level hooks (the one-time request-log copy). No
         * destructive fallback: a lost library costs a full, paced re-sync (spec 5.4). Both files upgrade through the same
         * migrations.
         */
        fun build(context: Context, name: String, callback: RoomDatabase.Callback? = null): ReelsDatabase =
            Room.databaseBuilder(context, ReelsDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .apply { if (callback != null) addCallback(callback) }
                .build()
    }
}

/**
 * Version 2 adds `carousel_item` (spec 2026-10-12 §3.1) and changes nothing else, so every existing row stays. The statement
 * is `2.json`'s, verbatim: the composite primary key's leading `parentPk` already indexes the foreign key, so Room declares
 * no separate index.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `carousel_item` (`parentPk` TEXT NOT NULL, `position` INTEGER NOT NULL, `type` TEXT NOT NULL, " +
                "`width` INTEGER NOT NULL, `height` INTEGER NOT NULL, `imageUrl` TEXT NOT NULL, `videoUrl` TEXT, `videoUrlExpiresAt` INTEGER, " +
                "PRIMARY KEY(`parentPk`, `position`), " +
                "FOREIGN KEY(`parentPk`) REFERENCES `media`(`pk`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}

/**
 * Version 3 (R28) adds `carousel_item.childPk`, a nullable text column, and changes nothing else: every row stays, its new
 * column null ("unknown", which never drops anything, [itemShapeChanged]) until the next sync or renewal stores the item again.
 * An `ADD COLUMN` puts it last, where `3.json` has it too.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `carousel_item` ADD COLUMN `childPk` TEXT")
    }
}
