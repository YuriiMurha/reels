package io.github.yuriimurha.reels.sync

import io.github.yuriimurha.reels.data.settings.SettingsStore

/**
 * R84: which Instagram account (its pk) a library belongs to. The first run whose session check succeeds remembers it, a run
 * under any other account stops before it writes a page, and Delete library forgets it.
 */
interface LibraryAccount {
    /** The pk of the account this library belongs to, or null while none is known (a new or deleted library). */
    suspend fun pk(): String?

    suspend fun remember(pk: String)

    suspend fun forget()
}

/** [LibraryAccount] kept in [SettingsStore], one per library: [library] is `"fake"` or `"real"` ([SyncWorker.kindOf]). */
class StoredLibraryAccount(private val settings: SettingsStore, private val library: String) : LibraryAccount {
    override suspend fun pk(): String? = settings.libraryAccountPk(library)

    override suspend fun remember(pk: String) = settings.setLibraryAccountPk(library, pk)

    override suspend fun forget() = settings.setLibraryAccountPk(library, null)
}
