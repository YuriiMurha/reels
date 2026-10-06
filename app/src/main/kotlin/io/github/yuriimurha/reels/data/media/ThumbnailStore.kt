package io.github.yuriimurha.reels.data.media

import java.io.File

/** Thumbnails in app-private `filesDir/thumbs/{pk}.jpg`, which the OS never evicts (spec 5.4). */
class ThumbnailStore(private val dir: File) {
    fun write(pk: String, bytes: ByteArray): String {
        val target = fileFor(pk)
        dir.mkdirs()
        val partial = File(dir, "${target.name}.tmp")
        partial.writeBytes(bytes)
        check(partial.renameTo(target)) { "Could not move thumbnail into place" }
        return target.absolutePath
    }

    fun delete(pk: String) {
        fileFor(pk).delete()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(pk: String): File {
        require(SAFE_KEY.matches(pk)) { "Unexpected media key" }
        return File(dir, "$pk.jpg")
    }

    private companion object {
        val SAFE_KEY = Regex("[A-Za-z0-9_]+")
    }
}
