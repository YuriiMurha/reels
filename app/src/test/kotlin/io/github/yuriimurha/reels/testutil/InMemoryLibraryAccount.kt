package io.github.yuriimurha.reels.testutil

import io.github.yuriimurha.reels.sync.LibraryAccount

/** A [LibraryAccount] in memory; [remembered] counts the writes. */
class InMemoryLibraryAccount(private var stored: String? = null) : LibraryAccount {
    var remembered = 0
        private set

    override suspend fun pk(): String? = stored

    override suspend fun remember(pk: String) {
        remembered++
        stored = pk
    }

    override suspend fun forget() {
        stored = null
    }
}
