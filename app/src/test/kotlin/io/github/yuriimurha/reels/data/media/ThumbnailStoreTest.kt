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

    @Test
    fun rejectsKeysThatCouldEscapeTheFolder() {
        assertFailsWith<IllegalArgumentException> { store.write("../evil", byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { store.delete("a/b") }
    }
}
