package io.github.yuriimurha.reels.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.yuriimurha.reels.sync.StoredLibraryAccount
import io.github.yuriimurha.reels.sync.pacing.Cooldowns
import io.github.yuriimurha.reels.sync.pacing.DataStoreCooldownStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
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

    @Test
    fun theFallbackHoldsAOneHourCooldownAndTheRateLimitThatStartedIt() {
        val fallback = SettingsStore.corruptionFallback(now = 5_000_000L)
        assertEquals(5_000_000L + 3_600_000L, fallback[cooldownUntil], "1 h from now (spec 7.3)")
        assertEquals(5_000_000L, fallback[lastRateLimitAt], "so a rate limit within 24 h escalates")
        assertEquals(2, fallback.asMap().size, "nothing else: the session keys stay empty and read as LoggedOut")
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
