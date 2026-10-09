package io.github.yuriimurha.reels.instagram

/** Answers "who is logged in?". Separate so login (M2) can ship before the rest of the adapter (M3). */
interface SessionProbe {
    suspend fun currentUser(): Account
}

interface InstagramClient : SessionProbe {
    /** True when [RemoteMedia.savedCollectionIds] is populated, which lets sync walk only All Saved (strategy A). */
    val reportsSavedCollectionIds: Boolean

    /**
     * One page of the account's own collections, with their names. Throws [InstagramException.StaleQuery] when Instagram no
     * longer runs the query the client sends; it never repairs that by itself ([repairCollections] is the caller's choice).
     */
    suspend fun collections(cursor: String?): Page<RemoteCollection>

    /**
     * Learns the current collections query from the site itself (spec 2026-10-09 §3.3) and returns its first page: the site's
     * own page sends the query, and the client reads the names from that reply and keeps its doc id for the next [collections].
     * Throws [InstagramException.RepairUnavailable] when the repair did not run or saw no query, and
     * [InstagramException.StaleQuery] (or any other classification) when the site's own reply is not a page of collections;
     * nothing is learned then. [onReplyFailure] hears that classification first, and only it: a failure of the site's reply,
     * never one of the repair itself (which the repairer reports on its own), so the caller can say which it was.
     * The default is the fake backend's: it has no query to repair.
     */
    suspend fun repairCollections(onReplyFailure: (InstagramException) -> Unit = {}): Page<RemoteCollection> =
        throw InstagramException.Transient()

    /** Saved items newest first. [collectionId] null means All Saved. */
    suspend fun savedMedia(collectionId: String?, cursor: String?): Page<RemoteMedia>

    /** One item with fresh media links, or null when Instagram no longer has it (P8). */
    suspend fun mediaInfo(mediaPk: String): RemoteMedia?
}
