package io.github.yuriimurha.reels.data.media

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.yuriimurha.reels.R
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.mediaEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class FakeVideoSourceResolverTest {
    private val resolver = FakeVideoSourceResolver("io.github.yuriimurha.reels")

    @Test
    fun videosResolveToTheBundledClip() = runTest {
        val clip = Uri.parse("android.resource://io.github.yuriimurha.reels/${R.raw.sample_clip}")
        assertEquals(VideoSource.Play(clip, "m1"), resolver.resolve(mediaEntity("m1", MediaType.REEL)))
        assertEquals(VideoSource.Play(clip, "m2"), resolver.resolve(mediaEntity("m2", MediaType.VIDEO)))
    }

    /** Mock mode has no links to refresh: forcing one changes nothing. */
    @Test
    fun aForcedRefreshIsTheSameClip() = runTest {
        val clip = Uri.parse("android.resource://io.github.yuriimurha.reels/${R.raw.sample_clip}")
        assertEquals(VideoSource.Play(clip, "m1"), resolver.resolve(mediaEntity("m1", MediaType.REEL), forceRefresh = true))
    }

    @Test
    fun imagesHaveNoVideo() = runTest {
        assertNull(resolver.resolve(mediaEntity("m3", MediaType.IMAGE)))
        assertNull(resolver.resolve(mediaEntity("m4", MediaType.CAROUSEL)))
    }
}
