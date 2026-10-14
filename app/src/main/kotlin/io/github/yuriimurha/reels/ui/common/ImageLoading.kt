package io.github.yuriimurha.reels.ui.common

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.filePath
import coil3.key.Keyer
import coil3.request.Options
import okio.Path.Companion.toPath

/**
 * The app's one Coil [ImageLoader] (`ReelsApp` is Coil's `SingletonImageLoader.Factory`, so every [Thumbnail] uses it):
 * Coil's defaults, which here have no network fetcher (the app has only `coil-compose`; every picture is a local file, R1),
 * plus [LocalFileKeyer].
 */
fun reelsImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components { add(LocalFileKeyer, Uri::class) }
        .build()

/**
 * Keys a local file's picture in Coil's memory cache by its path, last-modified time and size, not by its path alone.
 *
 * Coil 3.6.3 keys a `file:` model by its path only (its own last-modified key is off by default), so a picture replaced at
 * the same path was served from the memory cache with the old bitmap until the process died. Carousel pictures are kept
 * under fixed names (`{pk}_{position}.jpg`), and R20 and R28 drop a changed post's pictures and download the new slides
 * under the same names. Every store writes a new file and renames it into place, so a rewrite has a new last-modified time
 * (and, all but always, a new size): another key, and the new picture loads. Thumbnails (`{pk}.jpg`) follow the same rule.
 *
 * A file that is not there makes [Okio's metadata][okio.FileSystem.metadata] throw, as Coil's own last-modified key does:
 * the load fails, so a dropped picture is never served from memory either. The stat runs once per load, as Coil's own does.
 */
internal object LocalFileKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String? {
        if (data.scheme != "file") return null
        val path = data.filePath ?: return null
        val metadata = options.fileSystem.metadata(path.toPath())
        return "$data#${metadata.lastModifiedAtMillis}:${metadata.size}"
    }
}
