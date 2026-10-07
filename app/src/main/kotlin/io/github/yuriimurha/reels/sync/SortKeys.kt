package io.github.yuriimurha.reels.sync

/** Newest-saved-first ordering keys (spec 5.2). Views sort by `sortKey DESC`. */
object SortKeys {
    /** Larger than any collection, so a new walk's keys always sit above the previous ones. */
    const val WALK_SPAN = 1_000_000L

    fun walkBase(currentMax: Long?): Long = (currentMax ?: 0L) + WALK_SPAN

    fun key(walkBase: Long, walkIndex: Long): Long {
        require(walkIndex in 0 until WALK_SPAN) { "Walk index out of range" }
        return walkBase - walkIndex
    }
}
