package io.github.yuriimurha.reels.data.db

import org.junit.Test
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * R29's review: the engine's progress write (`SyncDao.updateProgress`) carries the columns of a run row that are progress, and
 * never the ones other writers own. A counter added to `SyncRunEntity` and forgotten in [SyncRunProgress] would be written by
 * the run's last, whole-row write only (`finish`), so every mid-run reader would see it frozen at its start and no run-level
 * test would notice: these two tests make the forgetting fail by itself.
 */
class SyncRunProgressTest {
    /**
     * What a progress write must never carry: the run's kind, its status and error (the Cancel button's and the run's own end),
     * and its start and end times. Anything else in `sync_run` is progress and is in [SyncRunProgress].
     */
    private val notProgress = setOf("mode", "status", "startedAt", "finishedAt", "lastError")

    private fun fieldsOf(type: Class<*>): List<Field> =
        type.declaredFields.filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }

    @Test
    fun theProgressWriteCarriesEveryColumnOfTheRunButTheOnesOtherWritersOwn() {
        val entity = fieldsOf(SyncRunEntity::class.java).associate { it.name to it.type }
        val progress = fieldsOf(SyncRunProgress::class.java).associate { it.name to it.type }

        assertTrue(entity.keys.containsAll(notProgress), "precondition: every excluded column still exists: ${notProgress - entity.keys}")
        assertEquals(
            entity - notProgress,
            progress,
            "SyncRunProgress and SyncRunEntity differ: a new counter belongs in both (SyncRunEntity.progress() too); a new column " +
                "that is not progress belongs in notProgress here",
        )
    }

    @Test
    fun progressCopiesEveryColumnItCarriesUnchanged() {
        val run = SyncRunEntity(
            id = 7, mode = SyncMode.FULL, status = SyncStatus.PAUSED, startedAt = 11, finishedAt = 12, phase = "Syncing All Saved",
            collectionsDone = 1, collectionsTotal = 2, requestsUsed = 3, newItems = 4, seenItems = 5, thumbsCached = 6, failures = 8,
            lastError = "Cancelled",
        )
        val progress = run.progress()

        val values = fieldsOf(SyncRunProgress::class.java).map { field ->
            field.isAccessible = true
            val column = SyncRunEntity::class.java.getDeclaredField(field.name).apply { isAccessible = true }
            val value = field.get(progress)
            assertEquals(column.get(run), value, "${field.name} was not copied from the row")
            assertTrue(value != 0 && value != 0L && value != "", "${field.name} needs a value in this test's run that is not 0 or empty, or a copy that drops it would pass")
            value
        }
        assertEquals(values.size, values.toSet().size, "every column needs a value of its own in this test's run, or two copied into each other would pass: $values")
    }
}
