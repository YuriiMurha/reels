package io.github.yuriimurha.reels.data.media

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same rules as [ThumbnailStoreTest], for a carousel's item images (`{pk}_{position}.jpg`). */
class CarouselImageStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir by lazy { File(tmp.root, "carousel") }
    private val store by lazy { CarouselImageStore(dir) }

    @Test
    fun writesIntoPlaceAndReturnsTheAbsolutePath() {
        val path = store.write("123", 2, byteArrayOf(1, 2, 3))

        assertEquals(File(dir, "123_2.jpg").absolutePath, path)
        assertContentEquals(byteArrayOf(1, 2, 3), File(path).readBytes())
        assertFalse(File(dir, "123_2.jpg.tmp").exists(), "the partial file was moved into place")
    }

    @Test
    fun pathIsTheStoredFileOrNull() {
        assertNull(store.path("123", 0), "nothing stored yet, and no folder either")
        val path = store.write("123", 0, byteArrayOf(1))

        assertEquals(path, store.path("123", 0))
        assertNull(store.path("123", 1), "another position")
        assertNull(store.path("12", 0), "another post")
    }

    /** A half-written file is never what [CarouselImageStore.path] finds: the bytes go to a partial file that is moved into place. */
    @Test
    fun theWriteIsAtomic() {
        dir.mkdirs()
        File(dir, "123_0.jpg.tmp").writeBytes(byteArrayOf(7, 7, 7, 7)) // left by a write that died halfway
        assertNull(store.path("123", 0), "a partial file is not an image")

        store.write("123", 0, byteArrayOf(1))
        store.write("123", 0, byteArrayOf(9, 9))

        assertContentEquals(byteArrayOf(9, 9), File(store.path("123", 0)!!).readBytes(), "replaced whole")
        assertEquals(listOf("123_0.jpg"), dir.list()!!.sorted())
    }

    @Test
    fun deleteForRemovesOnlyThatPostsFiles() {
        for ((pk, position) in listOf("12" to 0, "12" to 1, "12" to 10, "123" to 0, "1" to 2, "112" to 0)) store.write(pk, position, byteArrayOf(1))
        File(dir, "12_3.jpg.tmp").writeBytes(byteArrayOf(1)) // a partial file of the post goes with it

        store.deleteFor("12")

        assertEquals(listOf("112_0.jpg", "123_0.jpg", "1_2.jpg"), dir.list()!!.sorted())
    }

    @Test
    fun deleteForAPostWithNothingStoredOrNoFolderIsNothingToDo() {
        store.deleteFor("12")
        store.write("13", 0, byteArrayOf(1))
        store.deleteFor("12")
        assertEquals(listOf("13_0.jpg"), dir.list()!!.toList())
    }

    @Test
    fun deleteAllRemovesEverything() {
        store.write("1", 0, byteArrayOf(1))
        store.write("2", 3, byteArrayOf(2))

        store.deleteAll()

        assertEquals(0, dir.listFiles()!!.size)
    }

    /** An image that cannot be removed is an error the caller can report (Delete library says so), never a silent leftover. */
    @Test
    fun deleteAllSaysSoWhenAFileCannotBeRemovedAndDoesNotNameIt() {
        store.write("1", 0, byteArrayOf(1))
        store.write("2", 0, byteArrayOf(2))
        assertTrue(dir.setWritable(false), "precondition: a folder whose files cannot be removed")
        try {
            val failure = assertFailsWith<IOException> { store.deleteAll() }
            assertFalse("1_0.jpg" in failure.message.orEmpty() || dir.path in failure.message.orEmpty(), "no file name or path: ${failure.message}")
        } finally {
            dir.setWritable(true)
        }
        store.deleteAll() // once the folder can be written again, it finishes the job
        assertEquals(0, dir.listFiles()!!.size)
    }

    /** Every file is tried even after one fails, whatever order the file system lists them in, and the count is all that stayed. */
    @Test
    fun deleteAllTriesEveryFileAndCountsWhatStayed() {
        val keys = listOf("1" to 0, "2" to 0, "3" to 1, "4" to 0, "5" to 2)
        for (stuck in keys) {
            val folder = File(tmp.root, "round-${stuck.first}")
            val round = CarouselImageStore(folder)
            for ((pk, position) in keys - stuck) round.write(pk, position, byteArrayOf(1))
            File(folder, "${stuck.first}_${stuck.second}.jpg").apply { mkdirs() }.let { File(it, "inner.txt").writeText("x") }

            val failure = assertFailsWith<IOException>("round $stuck") { round.deleteAll() }

            assertEquals(listOf("${stuck.first}_${stuck.second}.jpg"), folder.list()!!.toList(), "round $stuck: everything that could go has gone")
            val message = failure.message.orEmpty()
            assertEquals(listOf("1"), Regex("\\d+").findAll(message).map { it.value }.toList(), "round $stuck: only the count, $message")
            assertFalse("jpg" in message || folder.path in message, "round $stuck: no name or path in $message")
        }
        for (stuck in listOf("8_0.jpg", "9_1.jpg")) File(dir, stuck).apply { mkdirs() }.let { File(it, "inner.txt").writeText("x") }
        store.write("1", 0, byteArrayOf(1))
        val failure = assertFailsWith<IOException> { store.deleteAll() }
        assertEquals(listOf("2"), Regex("\\d+").findAll(failure.message.orEmpty()).map { it.value }.toList(), failure.message)
    }

    @Test
    fun deleteAllOfAFolderThatDoesNotExistIsNothingToDo() {
        CarouselImageStore(File(dir, "gone")).deleteAll()
    }

    @Test
    fun rejectsKeysThatCouldEscapeTheFolder() {
        assertFailsWith<IllegalArgumentException> { store.write("../evil", 0, byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { store.path("a/b", 0) }
        assertFailsWith<IllegalArgumentException> { store.deleteFor("a/b") }
        assertFailsWith<IllegalArgumentException> { store.deleteFor("") }
        assertFailsWith<IllegalArgumentException> { store.write("1", -1, byteArrayOf(1)) }
        assertFalse(File(tmp.root, "evil_0.jpg").exists())
    }
}
