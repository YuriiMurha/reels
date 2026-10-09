package io.github.yuriimurha.reels.data.settings

import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Spec 2026-10-09 §3.3: the doc id sent for a query is the one learned last, or the built-in one; R8: only digits ever. */
class SettingsDocIdStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    @After
    fun tearDown() = scope.cancel()

    private val query = WebGraphQl.SAVED_COLLECTIONS
    private val builtIn = query.builtInDocId

    private fun settings(file: File = File(tmp.root, "settings.preferences_pb"), now: Long = 1L) =
        SettingsStore.open(scope = scope, now = { now }) { file }

    @Test
    fun theDefaultIsTheBuiltInId() = runTest {
        assertEquals(builtIn, SettingsDocIdStore(settings()).docId(query))
    }

    @Test
    fun aLearnedIdOverridesItAndIsKept() = runTest {
        val settings = settings()
        val store = SettingsDocIdStore(settings)
        store.learned(query, "777")
        assertEquals("777", store.docId(query))
        assertEquals("777", settings.graphqlDocId(query.friendlyName), "kept in the settings, under the query's name")
        // A second store over the same settings (a new process's) sends it too.
        assertEquals("777", SettingsDocIdStore(settings).docId(query))
    }

    @Test
    fun clearingTheStoredIdGoesBackToTheBuiltInId() = runTest {
        val settings = settings()
        val store = SettingsDocIdStore(settings)
        store.learned(query, "777")
        settings.setGraphqlDocId(query.friendlyName, null)
        assertEquals(builtIn, store.docId(query))
    }

    /** Task 5's Forget action stores a digit id known to be wrong: it is sent as it is, so the site rejects it. */
    @Test
    fun aStoredDigitIdIsSentEvenWhenItIsWrong() = runTest {
        val settings = settings()
        settings.setGraphqlDocId(query.friendlyName, "0")
        assertEquals("0", SettingsDocIdStore(settings).docId(query))
    }

    /** R8: both transports refuse a doc id that is not digits, so one must never be answered (or stored). */
    @Test
    fun aStoredIdThatIsNotDigitsIsNeverAnswered() = runTest {
        val settings = settings()
        val store = SettingsDocIdStore(settings)
        for (bad in listOf("", "12a", " 777", "777\n", "1".repeat(WebGraphQl.DOC_ID_MAX_DIGITS + 1), "١٢")) {
            settings.setGraphqlDocId(query.friendlyName, bad)
            assertEquals(builtIn, store.docId(query), "stored '$bad'")
        }
    }

    @Test
    fun learningAnIdThatIsNotDigitsStoresNothing() = runTest {
        val settings = settings()
        val store = SettingsDocIdStore(settings)
        store.learned(query, "12a")
        assertNull(settings.graphqlDocId(query.friendlyName))
        assertEquals(builtIn, store.docId(query))

        store.learned(query, "777")
        store.learned(query, "x")
        assertEquals("777", store.docId(query), "a bad id never replaces a good one")
    }

    /** R54: an unreadable settings file still opens (with its cooldown); the query then goes out with the built-in id. */
    @Test
    fun theCorruptionFallbackKeepsWorking() = runTest {
        val file = File(tmp.root, "settings.preferences_pb").apply { writeBytes(ByteArray(11) { -1 }) }
        val settings = settings(file, now = 5_000_000L)
        val store = SettingsDocIdStore(settings)
        assertEquals(builtIn, store.docId(query))
        assertEquals(5_000_000L + 3_600_000L, settings.cooldown().until, "the conservative cooldown is still there")

        store.learned(query, "777")
        assertEquals("777", store.docId(query))
    }
}
