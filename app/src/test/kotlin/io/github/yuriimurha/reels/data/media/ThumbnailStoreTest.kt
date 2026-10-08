package io.github.yuriimurha.reels.data.media

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThumbnailStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir by lazy { File(tmp.root, "thumbs") }
    private val store by lazy { ThumbnailStore(dir) }

    @Test
    fun writesIntoPlaceAndReturnsTheAbsolutePath() {
        val path = store.write("123", byteArrayOf(1, 2, 3))
        assertEquals(File(dir, "123.jpg").absolutePath, path)
        assertContentEquals(byteArrayOf(1, 2, 3), File(path).readBytes())
        assertFalse(File(dir, "123.jpg.tmp").exists())
    }

    @Test
    fun overwriteReplacesContent() {
        store.write("123", byteArrayOf(1))
        store.write("123", byteArrayOf(9, 9))
        assertContentEquals(byteArrayOf(9, 9), File(dir, "123.jpg").readBytes())
    }

    @Test
    fun deleteAndDeleteAll() {
        store.write("1", byteArrayOf(1))
        store.write("2", byteArrayOf(2))
        store.delete("1")
        assertFalse(File(dir, "1.jpg").exists())
        assertTrue(File(dir, "2.jpg").exists())
        store.deleteAll()
        assertEquals(0, dir.listFiles()!!.size)
    }

    /** H6/M6: a thumbnail that cannot be removed is an error the caller can report, not a silent leftover. */
    @Test
    fun deleteAllSaysSoWhenAFileCannotBeRemovedAndDoesNotNameIt() {
        store.write("1", byteArrayOf(1))
        store.write("2", byteArrayOf(2))
        assertTrue(dir.setWritable(false), "precondition: a folder whose files cannot be removed")
        try {
            val failure = assertFailsWith<java.io.IOException> { store.deleteAll() }
            assertFalse("1.jpg" in failure.message.orEmpty() || dir.path in failure.message.orEmpty(), "no file name or path in the message: ${failure.message}")
        } finally {
            dir.setWritable(true)
        }
        store.deleteAll() // and once the folder can be written again, it finishes the job
        assertEquals(0, dir.listFiles()!!.size)
    }

    /**
     * `deleteAll` tries every file even after one fails. A non-empty directory named like a thumbnail cannot be deleted (without
     * root or a read-only folder, so the others can be). `listFiles` has no promised order, so each name takes its turn as the
     * undeletable one: whatever order this file system lists in, a loop that stopped at the first failure would leave files
     * behind in every round but the one where the undeletable entry happens to be listed last.
     */
    @Test
    fun deleteAllTriesEveryFileWhenExactlyOneCannotBeRemoved() {
        val pks = listOf("1", "2", "3", "4", "5")
        for (stuck in pks) {
            val folder = File(tmp.root, "round-$stuck")
            val round = ThumbnailStore(folder)
            for (pk in pks - stuck) round.write(pk, byteArrayOf(1))
            File(folder, "$stuck.jpg").apply { mkdirs() }.let { File(it, "inner.txt").writeText("x") }

            val failure = assertFailsWith<java.io.IOException>("round $stuck") { round.deleteAll() }

            for (pk in pks - stuck) assertFalse(File(folder, "$pk.jpg").exists(), "round $stuck: $pk.jpg was left behind")
            assertTrue(File(folder, "$stuck.jpg").isDirectory, "round $stuck: the one that cannot go is still there")
            // The only number in the message is the count: a leaked name ("3.jpg") or path would add digits.
            val message = failure.message.orEmpty()
            assertEquals(listOf("1"), Regex("\\d+").findAll(message).map { it.value }.toList(), "round $stuck: $message")
            assertFalse("jpg" in message || folder.path in message || tmp.root.path in message, "round $stuck: no name or path in $message")
        }
    }

    /** The count is every file that stayed, not just the first one. */
    @Test
    fun deleteAllCountsEveryFileThatCouldNotBeRemoved() {
        for (pk in listOf("1", "2", "3")) store.write(pk, byteArrayOf(1))
        for (stuck in listOf("8", "9")) File(dir, "$stuck.jpg").apply { mkdirs() }.let { File(it, "inner.txt").writeText("x") }

        val failure = assertFailsWith<java.io.IOException> { store.deleteAll() }

        assertEquals(listOf("8.jpg", "9.jpg"), dir.list()!!.sorted(), "everything that could go has gone")
        assertEquals(listOf("2"), Regex("\\d+").findAll(failure.message.orEmpty()).map { it.value }.toList(), failure.message)
    }

    @Test
    fun deleteAllOfAFolderThatDoesNotExistIsNothingToDo() {
        File(dir, "gone").let { ThumbnailStore(it).deleteAll() }
    }

    @Test
    fun rejectsKeysThatCouldEscapeTheFolder() {
        assertFailsWith<IllegalArgumentException> { store.write("../evil", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { store.delete("a/b") }
    }
}
