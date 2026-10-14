package io.github.yuriimurha.reels.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.SingletonImageLoader
import coil3.decode.DataSource
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.yuriimurha.reels.ReelsApp
import io.github.yuriimurha.reels.data.media.CarouselImageStore
import io.github.yuriimurha.reels.data.media.ThumbnailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The app's Coil loader (R28's fix round): a picture rewritten at the same path, as R20 and R28's drop and the next download
 * leave it, loads the new picture, never the old one from the memory cache; and nothing comes from the network. Real PNGs,
 * decoded under Robolectric's native graphics, through the loader `ReelsApp` gives Coil.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLoadingTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** What every `Thumbnail` uses in the app: the Application is Coil's singleton factory. */
    private val loader = ReelsApp().newImageLoader(context)
    private val images by lazy { CarouselImageStore(File(tmp.root, "carousel")) }

    @After
    fun shutdown() = loader.shutdown()

    private fun png(color: Int, side: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun load(data: Any): ImageResult = runBlocking(Dispatchers.IO) {
        withTimeout(10_000) { loader.execute(ImageRequest.Builder(context).data(data).allowHardware(false).build()) }
    }

    private fun loadFile(path: String) = load(File(path))

    private val ImageResult.source: DataSource get() = assertIs<SuccessResult>(this, "a picture: $this").dataSource

    private val ImageResult.color: Int get() = assertIs<SuccessResult>(this, "a picture: $this").image.toBitmap().getPixel(0, 0)

    @Test
    fun theAppGivesCoilThisLoader() {
        assertIs<SingletonImageLoader.Factory>(ReelsApp(), "ReelsApp is the factory Coil asks for its one loader")
    }

    /** The precondition of every case below: an unchanged file is served from the memory cache the second time. */
    @Test
    fun anUnchangedPictureComesFromMemory() {
        val path = images.write(PK, 1, png(Color.RED, 4))

        assertNotEquals(DataSource.MEMORY_CACHE, loadFile(path).source)
        val again = loadFile(path)
        assertEquals(DataSource.MEMORY_CACHE, again.source)
        assertEquals(Color.RED, again.color)
    }

    /** R20 or R28 drops a post's pictures, and the new slide downloads under the same name: the new picture shows. */
    @Test
    fun aPictureRewrittenAtTheSamePathLoadsTheNewPicture() {
        val path = images.write(PK, 1, png(Color.RED, 4))
        assertEquals(Color.RED, loadFile(path).color)
        assertEquals(DataSource.MEMORY_CACHE, loadFile(path).source, "precondition: held in memory")

        images.deleteFor(PK)
        assertEquals(path, images.write(PK, 1, png(Color.BLUE, 64)), "precondition: the same name again")

        val again = loadFile(path)
        assertEquals(Color.BLUE, again.color, "the new picture")
        assertNotEquals(DataSource.MEMORY_CACHE, again.source)
    }

    /** The same size, written later: the time tells it. */
    @Test
    fun aRewriteOfTheSameSizeIsToldByItsTime() {
        val path = images.write(PK, 1, png(Color.RED, 4))
        val writtenAt = File(path).lastModified()
        val size = File(path).length()
        assertEquals(Color.RED, loadFile(path).color)

        images.write(PK, 1, png(Color.BLUE, 4))
        assertTrue(File(path).setLastModified(writtenAt + 5_000))
        assertEquals(size, File(path).length(), "precondition: the same size")

        assertEquals(Color.BLUE, loadFile(path).color)
    }

    /** Written within the same millisecond (or on a file system with coarse times): the size tells it. */
    @Test
    fun aRewriteAtTheSameTimeIsToldByItsSize() {
        val path = images.write(PK, 1, png(Color.RED, 4))
        val writtenAt = File(path).lastModified()
        assertEquals(Color.RED, loadFile(path).color)

        val size = File(path).length()
        images.write(PK, 1, png(Color.BLUE, 64))
        assertTrue(File(path).setLastModified(writtenAt))
        assertEquals(writtenAt, File(path).lastModified(), "precondition: the same time")
        assertNotEquals(size, File(path).length(), "precondition: another size")

        assertEquals(Color.BLUE, loadFile(path).color)
    }

    /** A dropped picture is not drawn from memory while its file is gone: the load fails and the viewer shows its placeholder. */
    @Test
    fun aDroppedPictureIsNotServedFromMemory() {
        val path = images.write(PK, 1, png(Color.RED, 4))
        assertEquals(Color.RED, loadFile(path).color)

        images.deleteFor(PK)

        assertIs<ErrorResult>(loadFile(path))
    }

    /** Grid thumbnails go through the same loader and keys. */
    @Test
    fun aThumbnailRewrittenAtTheSamePathLoadsTheNewPicture() {
        val thumbnails = ThumbnailStore(File(tmp.root, "thumbs"))
        val path = thumbnails.write(PK, png(Color.RED, 4))
        assertEquals(Color.RED, loadFile(path).color)

        thumbnails.write(PK, png(Color.GREEN, 64))

        assertEquals(Color.GREEN, loadFile(path).color)
    }

    /** The loader has no network fetcher: every picture is a local file (R1). A link is an error, with no request. */
    @Test
    fun theLoaderFetchesNothingFromTheNetwork() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(png(Color.RED, 4))).build())

            assertIs<ErrorResult>(load(server.url("/v/t51/1.jpg").toString()))

            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }

    private companion object {
        const val PK = "3100000000000000003"
    }
}
