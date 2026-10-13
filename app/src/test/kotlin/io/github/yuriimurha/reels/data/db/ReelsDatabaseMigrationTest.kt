package io.github.yuriimurha.reels.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuriimurha.reels.instagram.MediaType
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** The upgrade from version 1 (spec 2026-10-12 §3.1): it only adds `carousel_item`, and every existing row stays. */
@RunWith(AndroidJUnit4::class)
class ReelsDatabaseMigrationTest {
    /** Builds the old versions from the exported schemas (`app/schemas`, debug assets: R5) and validates against them. */
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ReelsDatabase::class.java)

    /** One row in each of version 1's data tables, written with its own columns. */
    private fun SupportSQLiteDatabase.writeVersion1Rows() {
        execSQL(
            """
            INSERT INTO media (pk, code, type, author, caption, takenAt, width, height, carouselCount, thumbPath, thumbUrl,
                videoUrl, videoUrlExpiresAt, collectionNames, firstSeenAt, lastSeenAt, removedAt)
            VALUES ('31', 'C31', 'CAROUSEL', 'author_31', 'caption 31', 5, 1080, 1350, 3, '/thumbs/31.jpg', 'fake://thumb/31',
                NULL, NULL, 'Workouts', 6, 7, NULL)
            """,
        )
        execSQL("INSERT INTO collection (id, name, coverPk, position, removedAt) VALUES ('c1', 'Workouts', '31', 0, NULL)")
        execSQL("INSERT INTO collection_media (collectionId, mediaPk, sortKey, lastSeenRunId) VALUES ('c1', '31', 0, 1)")
        execSQL(
            """
            INSERT INTO sync_run (id, mode, status, startedAt, finishedAt, phase, collectionsDone, collectionsTotal, requestsUsed,
                newItems, seenItems, thumbsCached, failures, lastError)
            VALUES (1, 'FULL', 'DONE', 1, 2, 'DONE', 0, 0, 0, 0, 0, 0, 0, NULL)
            """,
        )
        execSQL("INSERT INTO sync_cursor (runId, scope, nextCursor, walkBase, walkIndex, done) VALUES (1, 'all', NULL, 0, 0, 1)")
        // The rolling 24 h request log behind the budget: an upgrade that lost it would let the Pacer start from an empty budget.
        execSQL("INSERT INTO api_request (id, at) VALUES (1, 5)")
    }

    /** Version 1's data tables (`media_fts` is the index of `media`, checked through it). */
    private val version1Tables = listOf("media", "collection", "collection_media", "sync_run", "sync_cursor", "api_request")

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }

    @Test
    fun migrating1To2KeepsEveryRowAndAddsTheTable() {
        helper.createDatabase(NAME, 1).use { it.writeVersion1Rows() }

        // Runs MIGRATION_1_2, then Room's schema check of every table against 2.json.
        val db = helper.runMigrationsAndValidate(NAME, 2, true, MIGRATION_1_2)

        assertEquals(version1Tables.map { 1 }, version1Tables.map { db.count(it) }, "rows per table: $version1Tables")
        db.query("SELECT pk, code, type, caption, carouselCount, thumbPath, collectionNames, firstSeenAt FROM media").use { row ->
            assertEquals(1, row.count)
            row.moveToFirst()
            assertEquals(
                listOf("31", "C31", "CAROUSEL", "caption 31", "3", "/thumbs/31.jpg", "Workouts", "6"),
                (0 until row.columnCount).map { row.getString(it) },
            )
        }
        db.query("SELECT id, name, coverPk, position FROM collection").use { row ->
            assertEquals(1, row.count)
            row.moveToFirst()
            assertEquals(listOf("c1", "Workouts", "31", "0"), (0 until row.columnCount).map { row.getString(it) })
        }
        assertEquals(0, db.count("carousel_item"))
    }

    /** The fake library (`reels.db`) and the real one (`library.db`) are the same database class: [ReelsDatabase.build] upgrades both. */
    @Test
    fun buildOpensAVersion1FileOfEitherLibraryAtVersion2() = runTest {
        for (name in listOf("library.db", "reels.db")) {
            helper.createDatabase(name, 1).use { it.writeVersion1Rows() }

            val db = ReelsDatabase.build(ApplicationProvider.getApplicationContext(), name)
            try {
                assertEquals(listOf("caption 31"), db.mediaDao().byPks(listOf("31")).map { it.caption }, name)
                assertEquals("Workouts", db.collectionDao().collection("c1")?.name, name)
                assertEquals(emptyList(), db.carouselItemDao().items("31"), name)

                // Room turns foreign keys on for this database, so an item goes with its post on the upgraded file too.
                db.carouselItemDao().replace("31", listOf(CarouselItemEntity("31", 0, MediaType.IMAGE, 1080, 1350, "fake://thumb/31-0", null, null)))
                assertEquals(1, db.carouselItemDao().items("31").size, name)
                db.openHelper.writableDatabase.execSQL("DELETE FROM media WHERE pk = '31'")
                assertEquals(emptyList(), db.carouselItemDao().items("31"), name)
            } finally {
                db.close()
            }
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
