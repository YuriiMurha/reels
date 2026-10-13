package io.github.yuriimurha.reels.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.FileStorage
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.sync.StoredLibraryAccount
import io.github.yuriimurha.reels.sync.pacing.Cooldowns
import io.github.yuriimurha.reels.sync.pacing.DataStoreCooldownStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** R54: an unreadable settings file must not silently end an active cooldown. */
class SettingsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    @After
    fun tearDown() = scope.cancel()

    private fun corruptFile(): File = File(tmp.root, "settings.preferences_pb").apply {
        // Eleven 0xFF bytes: a protobuf varint that never ends, which DataStore reports as corruption.
        writeBytes(ByteArray(11) { -1 })
    }

    private fun open(file: File, now: Long) = SettingsStore.open(scope = scope, now = { now }) { file }

    // The persisted key names are part of the on-disk schema, so they are spelled out here rather than shared.
    private val cooldownUntil = longPreferencesKey("cooldown_until")
    private val lastRateLimitAt = longPreferencesKey("last_rate_limit_at")
    private val collectionsRepairAt = longPreferencesKey("collections_repair_at")

    @Test
    fun theFallbackHoldsAOneHourCooldownAndTheRateLimitThatStartedIt() {
        val fallback = SettingsStore.corruptionFallback(now = 5_000_000L)
        assertEquals(5_000_000L + 3_600_000L, fallback[cooldownUntil], "1 h from now (spec 7.3)")
        assertEquals(5_000_000L, fallback[lastRateLimitAt], "so a rate limit within 24 h escalates")
        assertEquals(
            setOf(cooldownUntil, lastRateLimitAt, collectionsRepairAt),
            fallback.asMap().keys,
            "nothing else: the session keys stay empty and read as LoggedOut",
        )
    }

    /** R11: like the cooldown, the repair limit assumes the worst: a repair just ran, so none runs within the next 24 h. */
    @Test
    fun theFallbackAlsoHoldsTheRepairLimitForADay() {
        assertEquals(5_000_000L, SettingsStore.corruptionFallback(now = 5_000_000L)[collectionsRepairAt])
    }

    @Test
    fun aCorruptFileIsReplacedByARepairLimitThatStartsNow() = runTest {
        assertEquals(5_000_000L, open(corruptFile(), now = 5_000_000L).collectionsRepairAt())
    }

    @Test
    fun theFallbackUsesThePacersOwnOneHourCooldown() {
        assertEquals(Cooldowns.SHORT_MS, SettingsStore.corruptionFallback(now = 0L)[cooldownUntil])
    }

    @Test
    fun aCorruptFileIsReplacedByAConservativeCooldownNotByNothing() = runTest {
        val file = corruptFile()
        val settings = open(file, now = 5_000_000L)
        assertEquals(CooldownState(until = 5_000_000L + 3_600_000L, lastRateLimitAt = 5_000_000L), settings.cooldown())
        assertNull(settings.session.first().kind, "an empty session reads as LoggedOut")
        assertEquals(false, settings.muted.first())
    }

    @Test
    fun theReplacementIsWrittenToTheFileSoItIsNotRestartedOnTheNextOpen() = runTest {
        val file = corruptFile()
        assertEquals(5_000_000L + 3_600_000L, open(file, now = 5_000_000L).cooldown().until)
        scope.cancel()
        val reopened = SettingsStore.open(scope = CoroutineScope(Dispatchers.IO + Job()), now = { 9_000_000L }) { file }
        assertEquals(5_000_000L + 3_600_000L, reopened.cooldown().until, "a healthy file is read as written, not re-derived from the clock")
    }

    @Test
    fun aRateLimitRightAfterACorruptionGoesStraightToTheTwentyFourHourTier() = runTest {
        val file = corruptFile()
        val cooldowns = DataStoreCooldownStore(open(file, now = 5_000_000L))
        assertEquals(5_000_000L + 3_600_000L, cooldowns.activeUntil())
        assertEquals(5_100_000L + Cooldowns.LONG_MS, cooldowns.onRateLimited(now = 5_100_000L))
    }

    /** R84: each library remembers its own account; the fake library's never stands in for the real one's. */
    @Test
    fun eachLibraryKeepsItsOwnAccount() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")
        val settings = open(file, now = 1L)
        val real = StoredLibraryAccount(settings, "real")
        val fake = StoredLibraryAccount(settings, "fake")
        assertNull(real.pk())

        real.remember("7")
        assertEquals("7", real.pk())
        assertNull(fake.pk(), "the fake library has no account yet")
        fake.remember("1")
        assertEquals("7", real.pk())

        real.forget()
        assertNull(real.pk())
        assertEquals("1", fake.pk(), "forgetting one library's account leaves the other's")
        assertEquals("1", settings.libraryAccountPk("fake"))
        assertEquals(setOf(stringPreferencesKey("library_account_pk_fake")), readKeys(file), "on disk as library_account_pk_<kind>")
    }

    /** The keys a settings file holds, read back through a fresh DataStore on a copy (one DataStore per file per process). */
    private suspend fun readKeys(file: File): Set<Preferences.Key<*>> {
        val copy = File(tmp.root, "copy.preferences_pb").also { file.copyTo(it, overwrite = true) }
        val readScope = CoroutineScope(Dispatchers.IO + Job())
        return try {
            PreferenceDataStoreFactory.create(scope = readScope) { copy }.data.first().asMap().keys
        } finally {
            readScope.cancel()
        }
    }

    /** Spec 2026-10-09 §3.3: a learned doc id per query, by its friendly name; null goes back to none (the built-in one). */
    @Test
    fun aGraphQlDocIdIsKeptPerQueryAndNullRemovesIt() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")
        val settings = open(file, now = 1L)
        val name = WebGraphQl.SAVED_COLLECTIONS.friendlyName
        assertNull(settings.graphqlDocId(name))

        settings.setGraphqlDocId(name, "777")
        assertEquals("777", settings.graphqlDocId(name))
        assertNull(settings.graphqlDocId("OtherQuery"), "each query has its own id")
        assertEquals(setOf(stringPreferencesKey("graphql_doc_$name")), readKeys(file), "on disk as graphql_doc_<friendly name>")

        settings.setGraphqlDocId(name, null)
        assertNull(settings.graphqlDocId(name))
        assertEquals(emptySet(), readKeys(file))
    }

    @Test
    fun aQueryNameThatIsNotAnIdentifierIsRefused() = runTest {
        val settings = open(File(tmp.root, "settings.preferences_pb"), now = 1L)
        for (bad in listOf("", "a b", "a/b", "x".repeat(101))) {
            assertFailsWith<IllegalArgumentException>(bad) { settings.graphqlDocId(bad) }
            assertFailsWith<IllegalArgumentException>(bad) { settings.setGraphqlDocId(bad, "1") }
        }
    }

    /** The 24 h repair limit's clock (spec 2026-10-09 §3.3); R3: null clears it (Forget collections query id). */
    @Test
    fun theCollectionsRepairTimeIsKeptAndNullClearsIt() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")
        val settings = open(file, now = 1L)
        assertNull(settings.collectionsRepairAt())

        settings.setCollectionsRepairAt(5_000L)
        assertEquals(5_000L, settings.collectionsRepairAt())
        assertEquals(setOf(longPreferencesKey("collections_repair_at")), readKeys(file), "on disk as collections_repair_at")

        settings.setCollectionsRepairAt(null)
        assertNull(settings.collectionsRepairAt())
        assertEquals(emptySet(), readKeys(file))
    }

    /**
     * R18/R21: "Forget collections query id" arms one forced repair (`collections_force_repair`) and clears the repair limit, in
     * ONE edit, so that repair is not refused by the last one's 24 h. It never stores a made-up id: the doc id in use is
     * untouched until a repair learns a new one, and nothing depends on how Instagram answers a wrong one.
     */
    @Test
    fun forgetArmsOneForcedRepairAndClearsTheRepairLimitInOneEdit() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")
        val edits = CountingStore(PreferenceDataStoreFactory.create(scope = scope) { file })
        val settings = SettingsStore(edits)
        val name = WebGraphQl.SAVED_COLLECTIONS.friendlyName
        settings.setGraphqlDocId(name, "777")
        settings.setCollectionsRepairAt(5_000L)
        assertEquals(false, settings.collectionsForceRepair(), "not armed until Forget")
        edits.updates = 0

        settings.forgetCollectionsQueryId()

        assertEquals(1, edits.updates, "one atomic edit")
        assertEquals(true, settings.collectionsForceRepair())
        assertNull(settings.collectionsRepairAt())
        assertEquals("777", settings.graphqlDocId(name), "the id in use is untouched")
        assertEquals("777", SettingsDocIdStore(settings).docId(WebGraphQl.SAVED_COLLECTIONS))
        assertEquals(
            setOf(stringPreferencesKey("graphql_doc_$name"), booleanPreferencesKey("collections_force_repair")),
            readKeys(file),
            "on disk as collections_force_repair, and nothing else is written",
        )

        settings.clearCollectionsForceRepair()
        assertEquals(false, settings.collectionsForceRepair(), "a one-shot")
        assertEquals(setOf(stringPreferencesKey("graphql_doc_$name")), readKeys(file), "cleared means removed")
    }

    /** Counts the edits made through it. */
    private class CountingStore(private val inner: DataStore<Preferences>) : DataStore<Preferences> {
        var updates = 0

        override val data get() = inner.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            updates++
            return inner.updateData(transform)
        }
    }

    @Test
    fun theCollectionNamesStaleFlagDefaultsToFalse() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")
        val settings = open(file, now = 1L)
        assertEquals(false, settings.collectionNamesStale.first())

        settings.setCollectionNamesStale(true)
        assertEquals(true, settings.collectionNamesStale.first())
        assertEquals(setOf(booleanPreferencesKey("collection_names_stale")), readKeys(file), "on disk as collection_names_stale")

        settings.setCollectionNamesStale(false)
        assertEquals(false, settings.collectionNamesStale.first())
    }

    /**
     * A flow that starts while a write lands still sees that write (`TurnTakingDataStore`). DataStore 1.2.1 alone loses it for
     * good: the flow's first read takes the version the write has just taken but the file from before the write's rename, and
     * the write's own update, under that same version, is then dropped as not newer. The write is held here between the two
     * until the flow has had its chance to read (half a second, or its first value, whichever comes first).
     */
    @Test
    fun aFlowThatStartsWhileAWriteLandsSeesThatWrite() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = object : Serializer<Preferences> by PreferencesFileSerializer {
            override suspend fun writeTo(t: Preferences, output: OutputStream) {
                writing.complete(Unit)
                release.await()
                PreferencesFileSerializer.writeTo(t, output)
            }
        }
        val file = File(tmp.root, "settings.preferences_pb")
        val settings = SettingsStore(PreferenceDataStoreFactory.create(FileStorage(held) { file }, scope = scope))
        assertEquals(false, settings.collectionNamesStale.first())

        val write = launch(Dispatchers.IO) { settings.setCollectionNamesStale(true) }
        writing.await()
        val seen = MutableStateFlow<Boolean?>(null)
        val flow = launch(Dispatchers.IO) { settings.collectionNamesStale.collect { seen.value = it } }
        withTimeoutOrNull(500) { seen.first { it != null } }
        release.complete(Unit)
        write.join()

        assertEquals(true, withTimeoutOrNull(5_000) { seen.first { it == true } }, "the flow kept the value from before the write")
        flow.cancel()
    }

    /**
     * The fence is never taken or given back on a caller's thread: a write's caller may block that thread waiting for the fence
     * (a screen test reads with `runBlocking` on the main thread while its ViewModel's write, launched from that thread, holds
     * it), and a write that had to resume there to give the fence back would wait for ever.
     */
    @Test
    fun aReadThatBlocksTheThreadOfAWriteInFlightIsNotStuck() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = object : Serializer<Preferences> by PreferencesFileSerializer {
            override suspend fun writeTo(t: Preferences, output: OutputStream) {
                writing.complete(Unit)
                release.await()
                PreferencesFileSerializer.writeTo(t, output)
            }
        }
        val file = File(tmp.root, "settings.preferences_pb")
        val settings = SettingsStore(PreferenceDataStoreFactory.create(FileStorage(held) { file }, scope = scope))
        assertEquals(false, settings.collectionNamesStale.first())
        val caller = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }.asCoroutineDispatcher()
        val onCaller = CoroutineScope(caller + Job()) // not this test's children: on a regression they never finish

        onCaller.launch { settings.setCollectionNamesStale(true) }
        writing.await()
        val read = onCaller.async { runBlocking { settings.collectionNamesStale.first() } }
        delay(200) // the caller's thread is now blocked in that read
        release.complete(Unit)

        assertEquals(true, withTimeoutOrNull(5_000) { read.await() }, "the read never returned: the write could not give the fence back")
        onCaller.cancel()
        caller.close()
    }

    /** The same for a flow's first read: it gives the fence back on its own thread, not on its collector's. */
    @Test
    fun aWriteThatBlocksTheThreadOfAFlowsFirstReadIsNotStuck() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = object : Serializer<Preferences> by PreferencesFileSerializer {
            override suspend fun readFrom(input: InputStream): Preferences {
                reading.complete(Unit)
                release.await()
                return PreferencesFileSerializer.readFrom(input)
            }
        }
        val file = File(tmp.root, "settings.preferences_pb")
        file.outputStream().use { PreferencesFileSerializer.writeTo(preferencesOf(booleanPreferencesKey("muted") to true), it) }
        val settings = SettingsStore(PreferenceDataStoreFactory.create(FileStorage(held) { file }, scope = scope))
        val caller = Executors.newSingleThreadExecutor { Thread(it).apply { isDaemon = true } }.asCoroutineDispatcher()
        val onCaller = CoroutineScope(caller + Job()) // not this test's children: on a regression they never finish

        val seen = MutableStateFlow<Boolean?>(null)
        onCaller.launch { settings.collectionNamesStale.collect { seen.value = it } }
        reading.await()
        val write = onCaller.async { runBlocking { settings.setCollectionNamesStale(true) } }
        delay(200) // the collector's thread is now blocked in that write
        release.complete(Unit)

        assertNotNull(withTimeoutOrNull(5_000) { write.await() }, "the write never returned: the first read could not give the fence back")
        assertEquals(true, withTimeoutOrNull(5_000) { seen.first { it == true } })
        onCaller.cancel()
        caller.close()
    }

    @Test
    fun aHealthyFileIsNeverReplaced() = runTest {
        val file = File(tmp.root, "settings.preferences_pb")
        val settings = open(file, now = 1L)
        settings.setCooldown(until = 7_000L, rateLimitAt = 3_000L)
        settings.setMuted(true)
        assertEquals(CooldownState(until = 7_000L, lastRateLimitAt = 3_000L), settings.cooldown())
        assertEquals(true, settings.muted.first())
    }
}
