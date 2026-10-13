package io.github.yuriimurha.reels.data.media

import java.io.File
import java.io.IOException

/**
 * A carousel's item images, downloaded on demand by [CarouselRepository], in app-private `{dir}/{pk}_{position}.jpg`, which
 * the OS never evicts, so an item seen once shows offline (R1). The same rules as [ThumbnailStore]: a key that could leave the
 * folder is refused, a write is atomic, and [deleteAll] reports what it could not remove. Disk work: call it off the main thread.
 */
class CarouselImageStore(private val dir: File) {
    /** The stored image of [pk]'s item at [position], or null when there is none. */
    fun path(pk: String, position: Int): String? = fileFor(pk, position).takeIf { it.isFile }?.absolutePath

    /** Stores [bytes] as [pk]'s item at [position], replacing any earlier one whole, and returns its absolute path. */
    fun write(pk: String, position: Int, bytes: ByteArray): String {
        val target = fileFor(pk, position)
        dir.mkdirs()
        val partial = File(dir, "${target.name}.tmp")
        partial.writeBytes(bytes)
        check(partial.renameTo(target)) { "Could not move a carousel image into place" }
        return target.absolutePath
    }

    /** Removes every image of [pk]'s items, and a partial one a write left behind; nothing of any other post. */
    fun deleteFor(pk: String) {
        require(SAFE_KEY.matches(pk)) { "Unexpected media key" }
        val ofThePost = Regex("${Regex.escape(pk)}_\\d+\\.jpg(\\.tmp)?")
        dir.listFiles { file -> ofThePost.matches(file.name) }?.forEach { it.delete() }
    }

    /**
     * Removes every image it can. A file that cannot be removed is an [IOException] once all the others have been tried, so
     * the caller can say so; the message has a count, never a name or a path. A folder that does not exist has nothing to remove.
     */
    fun deleteAll() {
        val left = dir.listFiles()?.count { !it.delete() } ?: 0
        if (left > 0) throw IOException("$left carousel images could not be removed")
    }

    private fun fileFor(pk: String, position: Int): File {
        require(SAFE_KEY.matches(pk) && position >= 0) { "Unexpected media key" }
        return File(dir, "${pk}_$position.jpg")
    }

    private companion object {
        val SAFE_KEY = Regex("[A-Za-z0-9_]+")
    }
}
