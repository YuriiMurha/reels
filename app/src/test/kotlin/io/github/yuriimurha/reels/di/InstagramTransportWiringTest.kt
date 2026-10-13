package io.github.yuriimurha.reels.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.db.SyncMode
import io.github.yuriimurha.reels.data.db.SyncRunEntity
import io.github.yuriimurha.reels.data.db.SyncStatus
import io.github.yuriimurha.reels.data.media.CarouselRepository
import io.github.yuriimurha.reels.data.media.ItemImage
import io.github.yuriimurha.reels.data.media.ItemsResult
import io.github.yuriimurha.reels.data.media.VideoSource
import io.github.yuriimurha.reels.data.media.sampleClipUri
import io.github.yuriimurha.reels.instagram.InstagramException
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.instagram.fake.FakeInstagramClient
import java.io.File
import kotlin.test.assertIs
import io.github.yuriimurha.reels.session.SessionState
import io.github.yuriimurha.reels.testutil.MainThreadTimeout
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Instagram transport is a hidden WebView on instagram.com. It must exist only because a real request needed it: Mock
 * mode (the fake backend) never builds it, and neither does anything that merely changes or asks about the session (a cold
 * logout, Delete library, the hooks themselves). Building it is the first step towards loading Instagram, so each of those is
 * run here against a container with no session, and the transport is asked whether it was made.
 */
@RunWith(AndroidJUnit4::class)
class InstagramTransportWiringTest {
    /** T6: a hook that waits for the main thread must fail a test here, never hang the suite. */
    @get:Rule
    val timeout = MainThreadTimeout(120_000)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val containers = mutableListOf<AppContainer>()

    @After
    fun closeDatabases() {
        containers.forEach {
            it.requestLogDb.close()
            it.db.close()
        }
    }

    private fun container(useFake: Boolean): AppContainer {
        context.getSharedPreferences(BackendChoice.PREFS, Context.MODE_PRIVATE).edit().putBoolean(BackendChoice.KEY_USE_FAKE, useFake).commit()
        return AppContainer(context).also { containers += it }
    }

    /**
     * Everything a session change, a check or Delete library does to the transport's hooks, on a container whose cookie jar holds no
     * session (so `validate()` makes no request). Delete library builds the video cache, which must be released again.
     */
    private suspend fun AppContainer.runEveryHook() {
        try {
            assertEquals(SessionState.LoggedOut, session.validate())
            // R106: a stored expiry or challenge, and a login screen opened to fix one, close the page of a transport that exists.
            session.loginRequired(session.epoch())
            session.challengeRequired(null, session.epoch())
            session.closeHiddenPage()
            closeInstagramPage()
            session.logout()
            resetInstagramTransport()
            allowNewInstagramAttempts()
            library.deleteLibrary()
        } finally {
            videoCache.cache.release() // SimpleCache allows one per directory per process
        }
    }

    @Test
    fun mockModeNeverBuildsTheTransport() = runBlocking {
        val container = container(useFake = true)
        assertTrue(container.usesFake)
        assertTrue(container.backend is Backend.Fake)
        // Everything the screens build in Mock mode.
        container.adapterLab
        container.videoResolver
        container.carouselRepository
        container.syncEngine()
        assertFalse(container.instagramTransportCreated, "building the Mock mode graph built the transport")

        container.runEveryHook()

        assertFalse(container.instagramTransportCreated, "a hook built the transport in Mock mode")
    }

    /**
     * Spec 2026-10-09 §3.3: Mock mode's client has no repair behind it (the fake library's answer is the interface's default),
     * so no repair page can be made and no attempt is counted. Where the page is constructed is pinned in `BackendWiringGuardTest`.
     */
    @Test
    fun mockModeHasNoRepair() = runBlocking {
        val container = container(useFake = true)
        assertFailsWith<InstagramException.Transient> { container.backend.client.repairCollections() }
        assertNull(container.settings.collectionsRepairAt(), "no attempt was recorded")
        assertFalse(container.instagramTransportCreated)
    }

    /**
     * The real client's repair is the container's [io.github.yuriimurha.reels.sync.QueryRepairer] over the stored session: with no
     * handle stored it refuses before it builds anything (no repair page, no transport) or counts an attempt against its limit.
     */
    @Test
    fun theRealRepairWithoutAStoredHandleBuildsNothing() = runBlocking {
        val container = container(useFake = false)
        assertNull(container.settings.session.first().handle, "precondition: no session is stored")

        val refused = assertFailsWith<InstagramException.RepairSkipped> { container.backend.client.repairCollections() }

        assertEquals("collections repair unavailable: no handle", refused.message)
        assertNull(container.settings.collectionsRepairAt(), "nothing was attempted")
        assertFalse(container.instagramTransportCreated)
    }

    /**
     * The fake library's engine, started for real and stopped after its first requests: its run start must not reach the
     * transport. The run is on a background thread and the test thread (Robolectric's main thread) idles the main looper while
     * it waits, never blocks it: a hook that hopped to the main thread (as the transport's do) then runs, builds the transport
     * and fails the assertion, instead of waiting for a looper nobody turns (T6).
     */
    @Test
    fun aMockModeSyncRunNeverBuildsTheTransport() {
        val container = container(useFake = true)
        val runId = runBlocking { container.db.syncDao().insertRun(SyncRunEntity(mode = SyncMode.QUICK, status = SyncStatus.RUNNING, startedAt = 0)) }
        val engine = container.syncEngine()
        val requestsUsed = { runBlocking { container.db.syncDao().run(runId)?.requestsUsed ?: 0 } }

        val run = CoroutineScope(Dispatchers.Default).launch { engine.run(runId) }
        try {
            idleTheMainLooperUntil(20_000) { requestsUsed() >= 2 }
        } finally {
            run.cancel()
            assertTrue(idleTheMainLooperUntil(20_000) { run.isCompleted }, "the run did not stop")
        }

        assertTrue(requestsUsed() >= 2, "precondition: the run really sent requests to the fake library")
        assertFalse(container.instagramTransportCreated, "a Mock mode run built the transport")
    }

    /**
     * R3: a Mock mode carousel visit (the lazy fill, an image, a video item) runs on the fake library alone: its request goes to
     * the fake client on the fake backend's own Pacer, so the real 24 h budget does not move, the transport is never built, the
     * image is the fake fetcher's placeholder, and the video item plays the bundled clip under its own key.
     */
    @Test
    fun aMockModeCarouselVisitNeverReachesTheTransportOrTheRealBudget() = runBlocking {
        val container = container(useFake = true)
        val fake = container.backend.client as FakeInstagramClient
        val post = fake.library.allSaved().first { it.type == MediaType.CAROUSEL && (it.carouselCount ?: 0) >= 3 }
        val media = MediaEntity(
            pk = post.pk, code = post.code, type = post.type, author = post.author, caption = post.caption, takenAt = 0,
            width = post.width, height = post.height, carouselCount = post.carouselCount, thumbPath = null, thumbUrl = post.thumbnailUrl,
            videoUrl = null, videoUrlExpiresAt = null, collectionNames = "", firstSeenAt = 0, lastSeenAt = 0, removedAt = null,
        )
        container.db.mediaDao().upsert(listOf(media))
        val realRequestsBefore = container.instagramPacer.status().requestsLast24h
        val visit = CarouselRepository.Visit()

        val items = assertIs<ItemsResult.Items>(container.carouselRepository.items(media, visit)).items
        val image = assertIs<ItemImage.File>(container.carouselRepository.image(media, 0, visit))
        val video = container.carouselRepository.video(media, 2, visit)

        assertEquals(post.carouselCount, items.size)
        assertEquals(listOf("mediaInfo:${post.pk}"), fake.calls, "the fill went to the fake client, once")
        assertTrue(File(image.path).length() > 0, "the fake fetcher's placeholder was stored")
        assertEquals(VideoSource.Play(sampleClipUri(context.packageName), "${post.pk}_2"), video)
        assertEquals(realRequestsBefore, container.instagramPacer.status().requestsLast24h, "the real budget did not move")
        assertFalse(container.instagramTransportCreated, "a Mock mode carousel visit built the transport")
    }

    /**
     * R23 in Mock mode: a picture fetched ahead comes from the fake fetcher on the fake backend's own CDN lane, as an on-demand
     * one does: the placeholder is stored, nothing more goes to the fake client, the real budget does not move and the transport
     * is never built.
     */
    @Test
    fun aMockModePrefetchStoresTheFakeFetchersPlaceholderAndAsksNothing() = runBlocking {
        val container = container(useFake = true)
        val fake = container.backend.client as FakeInstagramClient
        val post = fake.library.allSaved().first { it.type == MediaType.CAROUSEL && (it.carouselCount ?: 0) >= 3 }
        val media = MediaEntity(
            pk = post.pk, code = post.code, type = post.type, author = post.author, caption = post.caption, takenAt = 0,
            width = post.width, height = post.height, carouselCount = post.carouselCount, thumbPath = null, thumbUrl = post.thumbnailUrl,
            videoUrl = null, videoUrlExpiresAt = null, collectionNames = "", firstSeenAt = 0, lastSeenAt = 0, removedAt = null,
        )
        container.db.mediaDao().upsert(listOf(media))
        val realRequestsBefore = container.instagramPacer.status().requestsLast24h
        val visit = CarouselRepository.Visit()
        assertIs<ItemsResult.Items>(container.carouselRepository.items(media, visit))

        val path = container.carouselRepository.prefetchImage(media, 1, visit)

        assertTrue(path != null && File(path).length() > 0, "the fake fetcher's placeholder was stored: $path")
        assertEquals(ItemImage.File(path), container.carouselRepository.image(media, 1, visit), "and the item's own load serves it")
        assertEquals(listOf("mediaInfo:${post.pk}"), fake.calls, "only the fill went to the fake client")
        assertEquals(realRequestsBefore, container.instagramPacer.status().requestsLast24h, "the real budget did not move")
        assertFalse(container.instagramTransportCreated, "a Mock mode prefetch built the transport")
    }

    /** Turns the main looper (so work posted to the main thread runs) until [condition] holds or [timeoutMs] passes. */
    private fun idleTheMainLooperUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    /** Building the real graph, and everything that only changes or asks about the session, makes no transport; a request would. */
    @Test
    fun theRealModeBuildsNoTransportUntilARequest() = runBlocking {
        val container = container(useFake = false)
        assertTrue(container.backend is Backend.Real)
        container.adapterLab
        container.carouselRepository
        container.syncEngine()
        assertFalse(container.instagramTransportCreated, "building the real graph built the transport")

        container.runEveryHook()

        assertFalse(container.instagramTransportCreated, "a hook, or a check with no session, built the transport")
    }

    /**
     * Once something did build the transport (not its page: that is made by the first call), the hooks run against it and finish
     * (no hang, no failure): the `withTimeout` is the assertion. That they reach it is not observable here: its state is
     * private, and a call would load the site, which no test may do. `BackendWiringGuardTest` pins what each hook calls.
     */
    @Test
    fun theHooksFinishOnATransportThatExists() = runBlocking {
        val container = container(useFake = false)
        container.instagramTransport
        assertTrue(container.instagramTransportCreated)

        withTimeout(20_000) { container.runEveryHook() }
    }
}
