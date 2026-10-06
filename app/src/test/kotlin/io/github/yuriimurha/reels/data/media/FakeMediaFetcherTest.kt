package io.github.yuriimurha.reels.data.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FakeMediaFetcherTest {
    @Test
    fun thumbnailsAreJpegs() = runTest {
        val bytes = FakeMediaFetcher().fetch("fake://thumb/1000001")!!
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])
    }

    @Test
    fun missingAndUnknownUrlsAreUnavailable() = runTest {
        assertNull(FakeMediaFetcher().fetch("fake://missing/1000096"))
        assertNull(FakeMediaFetcher().fetch("https://example.invalid/x.jpg"))
    }
}
