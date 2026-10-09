package io.github.yuriimurha.reels.sync

import io.github.yuriimurha.reels.data.settings.SettingsStore
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.web.RepairedQuery
import io.github.yuriimurha.reels.instagram.web.WebGraphQl
import io.github.yuriimurha.reels.testutil.KotlinSource
import io.github.yuriimurha.reels.transport.PageHttpError
import io.github.yuriimurha.reels.transport.RepairLanding
import io.github.yuriimurha.reels.transport.RepairPage
import io.github.yuriimurha.reels.transport.WatchedQuery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec 2026-10-09 §3.3: the repair's 24 h limit, its one page, and what each way it can end means to the sync. */
class QueryRepairerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    @After
    fun tearDown() = storeScope.cancel()

    private val settings by lazy { SettingsStore.open(scope = storeScope, now = { NOW }) { File(tmp.root, "settings.preferences_pb") } }

    /** An account handle of the allowed shape, built from parts like every test value here. */
    private val handle = "test" + "." + "user_1"

    private val lines = mutableListOf<String>()
    private var pagesCreated = 0

    /** The fake page's watch and its record of what it was asked; [answer] is how the watch ends. */
    private inner class FakePage(private val answer: suspend () -> WatchedQuery?) : RepairPage {
        val watches = mutableListOf<Triple<String, String, Long>>()
        var destroyed = 0
        var repairAtDuringTheWatch: Long? = null

        override suspend fun watch(url: String, friendlyName: String, timeoutMs: Long): WatchedQuery? {
            watches += Triple(url, friendlyName, timeoutMs)
            repairAtDuringTheWatch = settings.collectionsRepairAt()
            return answer()
        }

        override fun destroy() {
            destroyed++
        }
    }

    private fun TestScope.repairer(
        page: () -> RepairPage,
        handle: String? = this@QueryRepairerTest.handle,
        now: Long = NOW,
    ) = QueryRepairer(
        settings,
        handle = { handle },
        createPage = {
            pagesCreated++
            page()
        },
        now = { now },
        main = StandardTestDispatcher(testScheduler),
        log = { lines += it },
    )

    private val reply = """{"data":{"viewer":{}}}"""

    private fun watched(code: Int = 200) = WatchedQuery("777", code, reply)

    // ---- The 24 h limit ----

    @Test
    fun refusesWithin24hOfTheLastAttempt() = runTest {
        settings.setCollectionsRepairAt(NOW - HOUR)
        val page = FakePage { watched() }

        val refused = assertFailsWith<InstagramException.RepairSkipped> { repairer({ page }).repair(WebGraphQl.SAVED_COLLECTIONS) }

        assertEquals("collections repair unavailable: limit", refused.message)
        assertEquals(0, pagesCreated, "no page")
        assertEquals(NOW - HOUR, settings.collectionsRepairAt(), "a refusal is not an attempt")
        assertEquals(listOf("repair: failed (limit)"), lines)
    }

    @Test
    fun theLimitIsExactlyADay() = runTest {
        settings.setCollectionsRepairAt(NOW - QueryRepairer.REPAIR_INTERVAL_MS + 1)
        assertFailsWith<InstagramException.RepairSkipped> { repairer({ FakePage { watched() } }).repair(WebGraphQl.SAVED_COLLECTIONS) }
        assertEquals(0, pagesCreated)

        settings.setCollectionsRepairAt(NOW - QueryRepairer.REPAIR_INTERVAL_MS)
        repairer({ FakePage { watched() } }).repair(WebGraphQl.SAVED_COLLECTIONS)
        assertEquals(1, pagesCreated, "24 h after the last attempt, one more is allowed")
        assertEquals(NOW, settings.collectionsRepairAt())
    }

    /**
     * A last attempt dated in the future (a clock set back) refuses too, and is moved back to now: the limit never lets two
     * repairs closer than a day, and never stalls them longer than a day either, however far ahead the date was.
     */
    @Test
    fun anAttemptDatedInTheFutureRefusesAndCountsFromNow() = runTest {
        settings.setCollectionsRepairAt(NOW + 30 * DAY)
        val refused = assertFailsWith<InstagramException.RepairSkipped> { repairer({ FakePage { watched() } }).repair(WebGraphQl.SAVED_COLLECTIONS) }
        assertEquals("collections repair unavailable: limit", refused.message)
        assertEquals(0, pagesCreated)
        assertEquals(NOW, settings.collectionsRepairAt(), "the future date is clamped to now")
        assertEquals(listOf("repair: failed (limit)"), lines)

        assertFailsWith<InstagramException.RepairSkipped> {
            repairer({ FakePage { watched() } }, now = NOW + DAY - 1).repair(WebGraphQl.SAVED_COLLECTIONS)
        }
        assertEquals(0, pagesCreated, "still within a day of the clamp")
        assertEquals(NOW, settings.collectionsRepairAt(), "a date in the past is never moved")
        repairer({ FakePage { watched() } }, now = NOW + DAY).repair(WebGraphQl.SAVED_COLLECTIONS)
        assertEquals(1, pagesCreated, "a day after the clamp, not a day after the future date")
    }

    @Test
    fun neverRepairedBeforeIsAllowed() = runTest {
        assertNull(settings.collectionsRepairAt())
        repairer({ FakePage { watched() } }).repair(WebGraphQl.SAVED_COLLECTIONS)
        assertEquals(1, pagesCreated)
    }

    /** R11: a settings file that could not be read is replaced by one that refuses a repair for a day, like the cooldown. */
    @Test
    fun aCorruptSettingsFileRefusesARepairForADay() = runTest {
        val file = File(tmp.root, "corrupt.preferences_pb").apply { writeBytes(ByteArray(11) { -1 }) }
        val corrupt = SettingsStore.open(scope = storeScope, now = { NOW }) { file }
        val repairer = QueryRepairer(
            corrupt, handle = { handle }, createPage = { pagesCreated++; FakePage { watched() } }, now = { NOW + HOUR },
            main = StandardTestDispatcher(testScheduler),
        )
        assertFailsWith<InstagramException.RepairSkipped> { repairer.repair(WebGraphQl.SAVED_COLLECTIONS) }
        assertEquals(0, pagesCreated)
    }

    // ---- The attempt and the page ----

    @Test
    fun recordsTheAttemptBeforeLoading() = runTest {
        val page = FakePage { watched() }
        repairer({ page }).repair(WebGraphQl.SAVED_COLLECTIONS)
        assertEquals(NOW, page.repairAtDuringTheWatch, "recorded before the page loads, so even a crash mid-repair counts")
    }

    @Test
    fun aPageThatCannotBeMadeIsAFailedRepairAndStillCounts() = runTest {
        val failed = assertFailsWith<InstagramException.RepairFailed> {
            repairer({ throw IllegalStateException("this WebView cannot run a script before the page's own") }).repair(WebGraphQl.SAVED_COLLECTIONS)
        }
        assertEquals("collections repair unavailable: page error", failed.message)
        assertEquals(NOW, settings.collectionsRepairAt())
        assertEquals(listOf("repair: start", "repair: failed (page error)"), lines)
    }

    @Test
    fun loadsTheOwnSavedPageInDesktopMode() = runTest {
        val page = FakePage { watched() }
        repairer({ page }).repair(WebGraphQl.SAVED_COLLECTIONS)
        assertEquals(
            listOf(Triple("https://www.instagram.com/$handle/saved/", WebGraphQl.SAVED_COLLECTIONS.friendlyName, 45_000L)),
            page.watches,
        )
        assertEquals(45_000L, QueryRepairer.REPAIR_TIMEOUT_MS)
        assertEquals(86_400_000L, QueryRepairer.REPAIR_INTERVAL_MS)
    }

    @Test
    fun noHandleMeansNoRepairAndNoPage() = runTest {
        val refused = assertFailsWith<InstagramException.RepairSkipped> {
            repairer({ FakePage { watched() } }, handle = null).repair(WebGraphQl.SAVED_COLLECTIONS)
        }
        assertEquals("collections repair unavailable: no handle", refused.message)
        assertEquals(0, pagesCreated)
        assertNull(settings.collectionsRepairAt(), "nothing was attempted")
        assertEquals(listOf("repair: failed (no handle)"), lines)
    }

    /** Only a handle of Instagram's shape goes into the URL: nothing that adds a path, a query, a fragment or another host. */
    @Test
    fun aHandleOfAnyOtherShapeIsRefused() = runTest {
        val bad = listOf(
            "", ".", "..", "a/b", "../x", "a b", "a?b", "a#b", "a%2Fb", "a\n", "\tab", "x".repeat(31), "ä", "a@b.com",
            "a:b", "a\\b", "١٢",
        )
        for (handle in bad) {
            assertFailsWith<InstagramException.RepairSkipped>(handle) { repairer({ FakePage { watched() } }, handle).repair(WebGraphQl.SAVED_COLLECTIONS) }
        }
        assertEquals(0, pagesCreated)
        for (handle in listOf("a", "x".repeat(30), "a.b_c9", "_.9")) {
            settings.setCollectionsRepairAt(null)
            val page = FakePage { watched() }
            repairer({ page }, handle).repair(WebGraphQl.SAVED_COLLECTIONS)
            assertEquals("https://www.instagram.com/$handle/saved/", page.watches.single().first)
        }
    }

    // ---- Outcomes ----

    @Test
    fun aWatchedQueryBecomesARepairedQuery() = runTest {
        for (code in listOf(200, 204, 299)) {
            settings.setCollectionsRepairAt(null)
            val repaired: RepairedQuery = repairer({ FakePage { WatchedQuery("777", code, reply) } }).repair(WebGraphQl.SAVED_COLLECTIONS)
            assertEquals("777", repaired.docId)
            assertEquals(code, repaired.reply.code)
            assertNull(repaired.reply.contentType)
            assertEquals(reply, repaired.reply.body)
            assertFalse(repaired.reply.redirected)
        }
        // Nothing is learned here: the client parses the reply, and only the engine says "learned new id", once it has.
        assertEquals(List(3) { "repair: start" }, lines)
    }

    /** A body the page could not read stays unreadable: the client classifies it, and learns nothing from it. */
    @Test
    fun anUnreadableBodyIsPassedOnAsUnreadable() = runTest {
        val repaired = repairer({ FakePage { WatchedQuery("777", 200, null) } }).repair(WebGraphQl.SAVED_COLLECTIONS)
        assertNull(repaired.reply.body)
    }

    /** Learn only from a 2xx: an error status says nothing about the id. A 429 is a rate limit, so the Pacer arms the cooldown. */
    @Test
    fun aWatchedErrorStatusIsNeverLearned() = runTest {
        assertFailsWith<InstagramException.RateLimited> { repairer({ FakePage { watched(429) } }).repair(WebGraphQl.SAVED_COLLECTIONS) }
        for (code in listOf(100, 302, 400, 404, 500, 503)) {
            settings.setCollectionsRepairAt(null)
            val failed = assertFailsWith<InstagramException.RepairFailed>("$code") { repairer({ FakePage { watched(code) } }).repair(WebGraphQl.SAVED_COLLECTIONS) }
            assertEquals("collections repair unavailable: http $code", failed.message)
        }
        assertEquals(
            listOf(429, 100, 302, 400, 404, 500, 503).flatMap { listOf("repair: start", "repair: failed (http $it)") },
            lines,
        )
    }

    @Test
    fun outcomes() = runTest {
        class Case(val answer: suspend () -> WatchedQuery?, val check: (Throwable) -> Boolean, val line: String)
        val cases = listOf(
            Case({ throw PageHttpError(429) }, { it is InstagramException.RateLimited }, "repair: failed (http 429)"),
            Case({ throw PageHttpError(500) }, { it is InstagramException.RepairFailed && it.message!!.endsWith(": http 500") }, "repair: failed (http 500)"),
            Case({ throw PageHttpError(404) }, { it is InstagramException.RepairFailed && it.message!!.endsWith(": http 404") }, "repair: failed (http 404)"),
            Case({ throw RepairLanding.Login }, { it is InstagramException.LoginRequired }, "repair: failed (login page)"),
            Case(
                { throw RepairLanding.Challenge },
                { it is InstagramException.ChallengeRequired && it.challengeUrl == null },
                "repair: failed (challenge page)",
            ),
            Case({ null }, { it is InstagramException.RepairFailed && it.message!!.endsWith(": no query") }, "repair: failed (no query)"),
            Case({ throw IOException("render process gone") }, { it is InstagramException.RepairFailed && it.message!!.endsWith(": page error") }, "repair: failed (page error)"),
            Case({ throw IllegalArgumentException("the repair page loads its own origin only") }, { it is InstagramException.RepairFailed && it.message!!.endsWith(": page error") }, "repair: failed (page error)"),
        )
        for (case in cases) {
            settings.setCollectionsRepairAt(null)
            lines.clear()
            val page = FakePage(case.answer)
            val thrown = runCatching { repairer({ page }).repair(WebGraphQl.SAVED_COLLECTIONS) }.exceptionOrNull()
            assertTrue(thrown != null && case.check(thrown), "${case.line}: got $thrown")
            assertEquals(listOf("repair: start", case.line), lines)
            assertEquals(1, page.destroyed, case.line)
        }
    }

    @Test
    fun thePageIsAlwaysDestroyed() = runTest {
        val answers: List<suspend () -> WatchedQuery?> = listOf(
            { watched() }, { watched(404) }, { watched(429) }, { null }, { throw PageHttpError(429) }, { throw RepairLanding.Login },
            { throw RepairLanding.Challenge }, { throw IOException("page destroyed") }, { error("anything else") },
        )
        for (answer in answers) {
            settings.setCollectionsRepairAt(null)
            val page = FakePage(answer)
            runCatching { repairer({ page }).repair(WebGraphQl.SAVED_COLLECTIONS) }
            assertEquals(1, page.destroyed)
        }
    }

    /** C-M4: a page that fails to destroy (a WebView whose renderer is gone, say) never hides how the watch ended. */
    @Test
    fun aDestroyThatThrowsKeepsTheOutcome() = runTest {
        class BrokenDestroy(answer: suspend () -> WatchedQuery?) : RepairPage {
            private val page = FakePage(answer)
            var destroys = 0

            override suspend fun watch(url: String, friendlyName: String, timeoutMs: Long) = page.watch(url, friendlyName, timeoutMs)

            override fun destroy() {
                destroys++
                throw IllegalStateException("the renderer is gone")
            }
        }
        val good = BrokenDestroy { watched() }
        assertEquals("777", repairer({ good }).repair(WebGraphQl.SAVED_COLLECTIONS).docId)
        assertEquals(1, good.destroys)

        val endings = listOf<Pair<suspend () -> WatchedQuery?, (Throwable?) -> Boolean>>(
            Pair({ null }, { it is InstagramException.RepairFailed && it.message!!.endsWith(": no query") }),
            Pair({ throw PageHttpError(429) }, { it is InstagramException.RateLimited }),
            Pair({ throw RepairLanding.Login }, { it is InstagramException.LoginRequired }),
        )
        for ((answer, check) in endings) {
            settings.setCollectionsRepairAt(null)
            val page = BrokenDestroy(answer)
            val thrown = runCatching { repairer({ page }).repair(WebGraphQl.SAVED_COLLECTIONS) }.exceptionOrNull()
            assertTrue(check(thrown), "got $thrown")
            assertEquals(1, page.destroys)
        }
    }

    @Test
    fun aCancelledRepairStillDestroysItsPageAndIsNotAFailure() = runTest {
        val watching = CompletableDeferred<Unit>()
        val page = FakePage {
            watching.complete(Unit)
            awaitCancellation()
        }
        var outcome: Throwable? = null
        val job = launch { outcome = runCatching { repairer({ page }).repair(WebGraphQl.SAVED_COLLECTIONS) }.exceptionOrNull() }
        watching.await()
        job.cancelAndJoin()
        assertEquals(1, page.destroyed)
        assertTrue(outcome is kotlinx.coroutines.CancellationException, "cancellation propagates: $outcome")
        assertEquals(listOf("repair: start"), lines, "a cancelled repair is not reported as a failed one")
    }

    /** The thread's own name, without the " @coroutine#n" the coroutines debug mode appends while one runs on it. */
    private fun threadName() = Thread.currentThread().name.substringBefore(" @")

    /** The WebView is main-thread only: the page is made, watched and destroyed on [QueryRepairer]'s main dispatcher. */
    @Test
    fun thePageLivesOnTheMainDispatcher() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "fake-main") }
        try {
            val threads = mutableListOf<String>()
            val page = object : RepairPage {
                override suspend fun watch(url: String, friendlyName: String, timeoutMs: Long): WatchedQuery {
                    threads += "watch:" + threadName()
                    return watched()
                }

                override fun destroy() {
                    threads += "destroy:" + threadName()
                }
            }
            val repairer = QueryRepairer(
                settings, handle = { handle },
                createPage = {
                    threads += "create:" + threadName()
                    page
                },
                now = { NOW }, main = executor.asCoroutineDispatcher(),
            )
            repairer.repair(WebGraphQl.SAVED_COLLECTIONS)
            assertEquals(listOf("create:fake-main", "watch:fake-main", "destroy:fake-main"), threads)
        } finally {
            executor.shutdown()
        }
    }

    /** The debug lines are a fixed set: never the doc id, the handle, the URL or anything of a reply. */
    @Test
    fun theLogNamesNoIdNoHandleAndNoBody() = runTest {
        val secretBody = """{"data":{"viewer":{"name":"private words"}}}"""
        repairer({ FakePage { WatchedQuery("123456789", 200, secretBody) } }).repair(WebGraphQl.SAVED_COLLECTIONS)
        settings.setCollectionsRepairAt(null)
        runCatching { repairer({ FakePage { WatchedQuery("123456789", 404, secretBody) } }).repair(WebGraphQl.SAVED_COLLECTIONS) }
        val allowed = Regex("""repair: (start|failed \((limit|no handle|http \d{3}|login page|challenge page|no query|page error)\))""")
        assertTrue(lines.isNotEmpty() && lines.all(allowed::matches), "$lines")
        for (secret in listOf("123456789", handle, "private", "instagram.com")) assertFalse(lines.any { secret in it }, secret)
    }

    /**
     * D-C1: the Saved page's URL is `:instagram`'s ([io.github.yuriimurha.reels.instagram.web.WebEndpoints.savedPage]), so the
     * repairer spells no Instagram URL, path or handle rule of its own (repo CLAUDE.md: Instagram's endpoints live in one module).
     */
    @Test
    fun theRepairerBuildsNoInstagramUrlOfItsOwn() {
        val file = File("src/main/kotlin/io/github/yuriimurha/reels/sync/QueryRepairer.kt")
        assertTrue(file.isFile, "unit tests must run from the app module directory")
        val code = KotlinSource.code(file.readText())
        assertEquals(1, Regex("""WebEndpoints\.savedPage\(""").findAll(code).count(), "the URL comes from WebEndpoints.savedPage")
        for (banned in listOf("HOME_URL", "saved/", "instagram.com", "Regex(", "A-Za-z")) {
            assertFalse(banned in code, "QueryRepairer.kt spells `$banned`")
        }
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }
}
