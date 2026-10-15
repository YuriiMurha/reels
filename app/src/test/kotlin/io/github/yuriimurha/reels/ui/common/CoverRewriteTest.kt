package io.github.yuriimurha.reels.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import io.github.yuriimurha.reels.data.db.MediaEntity
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import io.github.yuriimurha.reels.instagram.MediaType
import io.github.yuriimurha.reels.testutil.mediaEntity
import io.github.yuriimurha.reels.ui.theme.ReelsTheme
import io.github.yuriimurha.reels.ui.viewer.CAROUSEL_COVER_TAG
import io.github.yuriimurha.reels.ui.viewer.CarouselItemUi
import io.github.yuriimurha.reels.ui.viewer.CarouselPager
import io.github.yuriimurha.reels.ui.viewer.CarouselUiState
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals

/**
 * R29 (the review's M-1): a sync drops a changed post's cover and writes the new one under the same `{pk}.jpg`, with the post's
 * new cover link. When both rows reach the screen in one recomposition (the app in the background while the sync ran), the
 * path never changes on screen; the new cover link makes the picture load again, so the grid tile and the carousel's item 0
 * show the new cover, never the old slide they held (only a carousel's cover is ever rewritten). Real PNGs under native
 * graphics, through the loader the app gives Coil ([reelsImageLoader]).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DelicateCoilApi::class)
class CoverRewriteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val thumbs by lazy { ThumbnailStore(File(tmp.root, "thumbs")) }

    @Before
    fun theAppsLoader() = SingletonImageLoader.setUnsafe(reelsImageLoader(context))

    @After
    fun noLoader() = SingletonImageLoader.reset()

    private fun png(color: Int, side: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    /**
     * The colour inside [tag]'s picture: the middle of its node, or for the grid tile (whose node holds the text under the
     * picture too) the middle of its top square.
     */
    private fun colourOf(tag: String): Int {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        val y = if (tag == TILE) pixels.width / 2 else pixels.height / 2
        return pixels[pixels.width / 2, y].toArgb()
    }

    private fun showsWithin(tag: String, colour: Int, millis: Long): Boolean =
        runCatching { compose.waitUntil(millis) { colourOf(tag) == colour } }.isSuccess

    /**
     * Shows [content] over a row of [pk] whose cover is red, then rewrites the cover green under the same name and hands the
     * screen the sync's new row (the same path, a new cover link) at once: the screen must show green.
     */
    private fun rewriteShowsTheNewCover(pk: String, type: MediaType, tag: String, content: @Composable (MediaEntity) -> Unit) {
        val path = thumbs.write(pk, png(Color.RED, 8))
        var row by mutableStateOf(mediaEntity(pk, type, thumbPath = path).copy(thumbUrl = "fake://thumb/$pk/old"))
        compose.setContent { ReelsTheme { Box(Modifier.size(120.dp, 600.dp)) { content(row) } } }
        compose.waitUntil(10_000) { colourOf(tag) == Color.RED }

        thumbs.delete(pk)
        assertEquals(path, thumbs.write(pk, png(Color.GREEN, 16)), "precondition: the new cover under the same name")
        row = row.copy(thumbUrl = "fake://thumb/$pk/new")
        compose.waitForIdle()

        assertEquals(true, showsWithin(tag, Color.GREEN, 5_000), "the new cover, never the old slide: #${Integer.toHexString(colourOf(tag))}")
    }

    @Test
    fun theGridTileShowsTheNewCover() = rewriteShowsTheNewCover("1001", MediaType.CAROUSEL, TILE) { row ->
        MediaTile(row, onClick = {})
    }

    @Test
    fun aCarouselsItemZeroShowsTheNewCover() = rewriteShowsTheNewCover("1002", MediaType.CAROUSEL, CAROUSEL_COVER_TAG) { row ->
        CarouselPager(row, CarouselUiState(items = listOf(CarouselItemUi(0, MediaType.IMAGE))), player = null, onItemSettled = {})
    }

    /** A cover link Instagram only signed again, over the same file: the tile goes on showing the cover it has. */
    @Test
    fun aNewLinkForTheSameCoverKeepsShowingIt() {
        val path = thumbs.write("1004", png(Color.RED, 8))
        var row by mutableStateOf(mediaEntity("1004", MediaType.CAROUSEL, thumbPath = path).copy(thumbUrl = "fake://thumb/1004/a"))
        compose.setContent { ReelsTheme { Box(Modifier.size(120.dp, 600.dp)) { MediaTile(row, onClick = {}) } } }
        compose.waitUntil(10_000) { colourOf(TILE) == Color.RED }

        row = row.copy(thumbUrl = "fake://thumb/1004/b")
        compose.waitForIdle()

        assertEquals(true, showsWithin(TILE, Color.RED, 5_000))
    }

    private companion object {
        /** The grid tile's test tag (`MediaTile`). */
        const val TILE = "tile"
    }
}
