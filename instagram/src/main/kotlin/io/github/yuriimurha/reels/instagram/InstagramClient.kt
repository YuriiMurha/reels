package io.github.yuriimurha.reels.instagram

/** Answers "who is logged in?". Separate so login (M2) can ship before the rest of the adapter (M3). */
interface SessionProbe {
    suspend fun currentUser(): Account
}

interface InstagramClient : SessionProbe {
    /** True when [RemoteMedia.savedCollectionIds] is populated, which lets sync walk only All Saved (strategy A). */
    val reportsSavedCollectionIds: Boolean

    suspend fun collections(cursor: String?): Page<RemoteCollection>

    /** Saved items newest first. [collectionId] null means All Saved. */
    suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia>

    /** One item with fresh media links. */
    suspend fun mediaInfo(mediaPk: String): RemoteMedia
}
