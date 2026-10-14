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

/**
 * The upgrades: version 1 to 2 (spec 2026-10-12 §3.1) only adds `carousel_item`, version 2 to 3 (R28) only adds its nullable
 * `childPk`, and every existing row stays through both.
 */
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

    /** Version 2's two items of the carousel '31', written with version 2's own columns (no `childPk`). */
    private fun SupportSQLiteDatabase.writeVersion2Items() {
        execSQL(
            """
            INSERT INTO carousel_item (parentPk, position, type, width, height, imageUrl, videoUrl, videoUrlExpiresAt)
            VALUES ('31', 0, 'IMAGE', 1080, 1350, 'fake://thumb/31-0', NULL, NULL),
                   ('31', 1, 'VIDEO', 1080, 1920, 'fake://thumb/31-1', 'fake://video/31-1', 9)
            """,
        )
    }

    /** The items of '31' as their rows read, in position order: every column, the new `childPk` last. */
    private fun SupportSQLiteDatabase.itemRows(): List<List<String?>> =
        query(
            "SELECT parentPk, position, type, width, height, imageUrl, videoUrl, videoUrlExpiresAt, childPk FROM carousel_item " +
                "WHERE parentPk = '31' ORDER BY position",
        ).use { row ->
            buildList { while (row.moveToNext()) add((0 until row.columnCount).map { if (row.isNull(it)) null else row.getString(it) }) }
        }

    /** What [writeVersion2Items] wrote, with no child pk: the migration knows none, and null means unknown (R28). */
    private val version2ItemsAtVersion3 = listOf(
        listOf("31", "0", "IMAGE", "1080", "1350", "fake://thumb/31-0", null, null, null),
        listOf("31", "1", "VIDEO", "1080", "1920", "fake://thumb/31-1", "fake://video/31-1", "9", null),
    )

    /** R28: version 3 only adds `carousel_item.childPk`, nullable. Every row stays, each item's child pk unknown. */
    @Test
    fun migrating2To3KeepsEveryRowAndAddsANullChildPk() {
        helper.createDatabase(NAME, 2).use { db ->
            db.writeVersion1Rows()
            db.writeVersion2Items()
        }

        // Runs MIGRATION_2_3, then Room's schema check of every table against 3.json.
        val db = helper.runMigrationsAndValidate(NAME, 3, true, MIGRATION_2_3)

        assertEquals(version1Tables.map { 1 }, version1Tables.map { db.count(it) }, "rows per table: $version1Tables")
        assertEquals(version2ItemsAtVersion3, db.itemRows())
        db.execSQL("UPDATE carousel_item SET childPk = '3100000000000000031' WHERE position = 0")
        assertEquals("3100000000000000031", db.itemRows()[0][8], "the column takes a child pk")
    }

    /** A library still at version 1 goes through both migrations: every row stays, and the table has its version-3 columns. */
    @Test
    fun migrating1To3GoesThroughBothMigrations() {
        helper.createDatabase(NAME, 1).use { it.writeVersion1Rows() }

        val db = helper.runMigrationsAndValidate(NAME, 3, true, MIGRATION_1_2, MIGRATION_2_3)

        assertEquals(version1Tables.map { 1 }, version1Tables.map { db.count(it) }, "rows per table: $version1Tables")
        db.query("SELECT pk, caption, carouselCount FROM media").use { row ->
            row.moveToFirst()
            assertEquals(listOf("31", "caption 31", "3"), (0 until row.columnCount).map { row.getString(it) })
        }
        assertEquals(0, db.count("carousel_item"))
        db.writeVersion2Items()
        assertEquals(version2ItemsAtVersion3, db.itemRows(), "the version-3 table, childPk included")
    }

    /** The fake library (`reels.db`) and the real one (`library.db`) are the same database class: [ReelsDatabase.build] upgrades both. */
    @Test
    fun buildOpensAVersion1FileOfEitherLibraryAtTheCurrentVersion() = runTest {
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
                assertEquals(3, db.openHelper.readableDatabase.version, name)
            } finally {
                db.close()
            }
        }
    }

    /**
     * R28: a version-2 file of either library (the build on the owner's phone today) opens through [ReelsDatabase.build] with
     * its items kept, each with an unknown child pk, and stores one when the item is written again.
     */
    @Test
    fun buildOpensAVersion2FileOfEitherLibraryKeepingItsItems() = runTest {
        for (name in listOf("library.db", "reels.db")) {
            helper.createDatabase(name, 2).use { db ->
                db.writeVersion1Rows()
                db.writeVersion2Items()
            }

            val db = ReelsDatabase.build(ApplicationProvider.getApplicationContext(), name)
            try {
                val items = db.carouselItemDao().items("31")
                assertEquals(listOf("fake://thumb/31-0", "fake://thumb/31-1"), items.map { it.imageUrl }, name)
                assertEquals(listOf(null, null), items.map { it.childPk }, name)
                db.carouselItemDao().replace("31", items.map { it.copy(childPk = "31000${it.position}") })
                assertEquals(listOf("310000", "310001"), db.carouselItemDao().items("31").map { it.childPk }, name)
                assertEquals(3, db.openHelper.readableDatabase.version, name)
            } finally {
                db.close()
            }
        }
    }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
