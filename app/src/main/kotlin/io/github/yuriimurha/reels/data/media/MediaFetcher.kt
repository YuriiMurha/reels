package io.github.yuriimurha.reels.data.media

/** Downloads media bytes for a URL the adapter returned. */
fun interface MediaFetcher {
    /** The bytes, or null when the item is unavailable (deleted, private, 404). Throws IOException on network trouble. */
    suspend fun fetch(url: String): ByteArray?
}
