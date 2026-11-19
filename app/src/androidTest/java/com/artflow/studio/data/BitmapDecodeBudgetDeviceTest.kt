package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.CanvasImportController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Uses real platform decoders with small sources that cross the same sampling boundaries. */
@RunWith(AndroidJUnit4::class)
class BitmapDecodeBudgetDeviceTest {
    private lateinit var context: Context
    private lateinit var directory: File

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "bitmap-decode-budget-").toFile()
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
    }

    @After
    fun tearDown() {
        StorageFileTree.delete(directory)
    }

    @Test
    fun oddSizeUriAndFileStayWithinThePreallocatedEdge() {
        val source = imageFile(127, 65)
        val original = source.readBytes()
        val picked = requireNotNull(BitmapPixelBridge.decodeUri(context.contentResolver, Uri.fromFile(source), 64, 8 * MIB))
        val file = requireNotNull(BitmapPixelBridge.decodeFile(source.path, 64, 8 * MIB))
        listOf(picked, file).forEach { decoded ->
            assertTrue(decoded.width in 63..64)
            assertTrue(decoded.height in 32..33)
            assertTrue(decoded.pixels.all { it == RED })
        }
        assertArrayEquals(original, source.readBytes())
    }

    @Test
    fun aTightBudgetSamplesTheActualUriBeforeThePixelArrayCopy() {
        val source = imageFile(512, 256)
        val decoded =
            requireNotNull(
                BitmapPixelBridge.decodeUri(context.contentResolver, Uri.fromFile(source), 4096, MIB + 100_000),
            )
        assertEquals(64, decoded.width)
        assertEquals(32, decoded.height)
        assertTrue(decoded.pixels.all { it == RED })
    }

    @Test
    fun metadataAndPixelsUseOneClosedSnapshotOfAChangingProvider() {
        val first = PngCodec.encode(PixelBuffer.filled(127, 65, RED))
        val replacement = PngCodec.encode(PixelBuffer.filled(2, 2, BLUE))
        var opens = 0
        var closed = false
        val decoded =
            requireNotNull(
                BitmapPixelBridge.decodeImportedSource(
                    openStream = {
                        opens++
                        object : ByteArrayInputStream(if (opens == 1) first else replacement) {
                            override fun close() {
                                closed = true
                                super.close()
                            }
                        }
                    },
                    maxDimension = 64,
                    memoryBudgetBytes = 8 * MIB,
                ),
            )
        assertEquals(1, opens)
        assertTrue(closed)
        assertTrue(decoded.width in 63..64)
        assertTrue(decoded.pixels.all { it == RED })
    }

    @Test
    fun aRejectedSourceClosesWithoutReadingItsPayload() {
        val source = byteArrayOf(1, 2, 3)
        var stream: ByteArrayInputStream? = null
        var closed = false
        assertThrows(IllegalArgumentException::class.java) {
            BitmapPixelBridge.decodeImportedSource(
                openStream = {
                    object : ByteArrayInputStream(source) {
                        override fun close() {
                            closed = true
                            super.close()
                        }
                    }.also { stream = it }
                },
                maxDimension = 4096,
                memoryBudgetBytes = 0,
            )
        }
        assertTrue(closed)
        assertEquals(source.size, requireNotNull(stream).available())
    }

    @Test
    fun unexpectedAllocationPressureClosesTheSourceAndReportsAnImportFailure() {
        val pressure = OutOfMemoryError("Injected provider allocation pressure")
        var closed = false
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                BitmapPixelBridge.decodeImportedSource(
                    openStream = {
                        object : InputStream() {
                            override fun read(): Int = throw pressure

                            override fun close() {
                                closed = true
                            }
                        }
                    },
                    maxDimension = 4096,
                    memoryBudgetBytes = 8 * MIB,
                )
            }
        assertSame(pressure, failure.cause)
        assertTrue(failure.message.orEmpty().contains("memory"))
        assertTrue(closed)
    }

    @Test
    fun rejectedUriAllocationLeavesArtworkHistoryAndSavedBytesUnchanged() =
        runBlocking(Dispatchers.Main) {
            val storage = ProjectStorage(context)
            val repository = CanvasRepositoryImpl(storage)
            try {
                assertNotNull(repository.loadOrCreate(1, 4, 4, 72))
                assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(4, 4, BLUE), "Existing paint"))
                assertNotNull(repository.saveCanvas(1))
                val source = imageFile(127, 65)
                val layers = repository.getAllLayers()
                val revision = repository.contentRevision
                val history = repository.historyMark
                val pixels = repository.layerPixels(repository.getActiveLayerId())!!.pixels.copyOf()
                val saved = storage.documentFile(1).readBytes()
                val messages = mutableListOf<String>()
                val controller = CanvasImportController(repository, this, messages::add, {})
                val request = controller.beginImage()
                request.complete { BitmapPixelBridge.decodeUri(context.contentResolver, Uri.fromFile(source), memoryBudgetBytes = 0) }
                request.job!!.join()
                assertEquals(layers, repository.getAllLayers())
                assertEquals(revision, repository.contentRevision)
                assertEquals(history, repository.historyMark)
                assertArrayEquals(pixels, repository.layerPixels(repository.getActiveLayerId())!!.pixels)
                assertArrayEquals(saved, storage.documentFile(1).readBytes())
                assertFalse(repository.hasUnsavedChanges())
                assertTrue(messages.single().contains("memory"))
            } finally {
                repository.dispose()
            }
        }

    @Test
    fun nativeEncodedRastersRetainFullDimensionsAndExactPixels() {
        val original = PixelBuffer.filled(131, 67, RED)
        original.setSafe(65, 33, 0)
        original.setSafe(130, 66, BLUE)
        val decoded = requireNotNull(BitmapPixelBridge.fromEncodedBytes(PngCodec.encode(original)))
        assertEquals(131, decoded.width)
        assertEquals(67, decoded.height)
        assertArrayEquals(original.pixels, decoded.pixels)
    }

    @Test
    fun metadataOrientationRemainsCorrectAfterBoundedDecode() {
        val file = File(directory, "rotated.jpg")
        file.writeBytes(orientedJpeg(6))
        val decoded = requireNotNull(BitmapPixelBridge.decodeUri(context.contentResolver, Uri.fromFile(file), 8, 8 * MIB))
        assertEquals(4, decoded.width)
        assertEquals(8, decoded.height)
    }

    @Test
    fun allExifRotationsAndMirrorsMatchForFileAndUriOnEverySupportedApi() {
        val expected =
            listOf(
                intArrayOf(RED, GREEN, BLUE, YELLOW),
                intArrayOf(GREEN, RED, YELLOW, BLUE),
                intArrayOf(YELLOW, BLUE, GREEN, RED),
                intArrayOf(BLUE, YELLOW, RED, GREEN),
                intArrayOf(RED, BLUE, GREEN, YELLOW),
                intArrayOf(BLUE, RED, YELLOW, GREEN),
                intArrayOf(YELLOW, GREEN, BLUE, RED),
                intArrayOf(GREEN, YELLOW, RED, BLUE),
            )
        expected.forEachIndexed { index, corners ->
            val orientation = index + 1
            val source = File(directory, "orientation-$orientation.jpg")
            val bytes = orientedJpeg(orientation, quadrants = true)
            source.writeBytes(bytes)
            val picked = requireNotNull(BitmapPixelBridge.decodeUri(context.contentResolver, Uri.fromFile(source), 16, 8 * MIB))
            val file = requireNotNull(BitmapPixelBridge.decodeFile(source.path, 16, 8 * MIB))
            listOf(picked, file).forEach { decoded ->
                assertEquals(if (orientation >= 5) 8 else 16, decoded.width)
                assertEquals(if (orientation >= 5) 16 else 8, decoded.height)
                assertCorners(decoded, corners, "EXIF $orientation")
            }
            assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test
    fun permittedJpegFillBytesDoNotHideTheOrientationTag() {
        val tagged = orientedJpeg(6, quadrants = true)
        for (fillCount in listOf(1, 3)) {
            // JPEG permits additional FF fill bytes before a marker; ExifInterface 1.4.2 fixes this parse boundary.
            val bytes = tagged.copyOfRange(0, 2) + ByteArray(fillCount) { 0xFF.toByte() } + tagged.copyOfRange(2, tagged.size)
            val source = File(directory, "fill-$fillCount.jpg").apply { writeBytes(bytes) }
            val picked = requireNotNull(BitmapPixelBridge.decodeUri(context.contentResolver, Uri.fromFile(source), 16, 8 * MIB))
            val file = requireNotNull(BitmapPixelBridge.decodeFile(source.path, 16, 8 * MIB))
            listOf(picked, file).forEach { decoded ->
                assertEquals(8, decoded.width)
                assertEquals(16, decoded.height)
                assertCorners(decoded, intArrayOf(BLUE, RED, YELLOW, GREEN), "JPEG fill count $fillCount")
            }
            assertArrayEquals(bytes, source.readBytes())
        }
    }

    @Test
    fun aRejectedBitmapStillBelongsToItsCaller() {
        val bitmap = Bitmap.createBitmap(8193, 1, Bitmap.Config.ARGB_8888)
        try {
            assertThrows(IllegalArgumentException::class.java) { BitmapPixelBridge.fromBitmap(bitmap) }
            assertFalse(bitmap.isRecycled)
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageFile(
        width: Int,
        height: Int,
    ): File = File(directory, "source.png").apply { writeBytes(PngCodec.encode(PixelBuffer.filled(width, height, RED))) }

    private fun assertCorners(
        decoded: PixelBuffer,
        expected: IntArray,
        label: String,
    ) {
        expected.forEachIndexed { corner, color ->
            val x = if (corner % 2 == 0) decoded.width / 4 else decoded.width * 3 / 4
            val y = if (corner < 2) decoded.height / 4 else decoded.height * 3 / 4
            val actual = decoded.getSafe(x, y)
            for (shift in intArrayOf(0, 8, 16)) {
                assertTrue(
                    "$label corner $corner",
                    kotlin.math.abs((actual ushr shift and 255) - (color ushr shift and 255)) <= 8,
                )
            }
        }
    }

    private fun orientedJpeg(
        orientation: Int,
        quadrants: Boolean = false,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
        val output = ByteArrayOutputStream()
        val encoded =
            try {
                bitmap.eraseColor(RED)
                if (quadrants) {
                    val colors = intArrayOf(RED, GREEN, BLUE, YELLOW)
                    val pixels = IntArray(32 * 16) { colors[(if (it / 32 < 8) 0 else 2) + if (it % 32 < 16) 0 else 1] }
                    bitmap.setPixels(pixels, 0, 32, 0, 0, 32, 16)
                }
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
                output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        // Independently constructed JPEG APP1 EXIF: little-endian TIFF, one SHORT Orientation entry.
        val exif =
            byteArrayOf(
                0x45,
                0x78,
                0x69,
                0x66,
                0,
                0,
                0x49,
                0x49,
                0x2A,
                0,
                8,
                0,
                0,
                0,
                1,
                0,
                0x12,
                1,
                3,
                0,
                1,
                0,
                0,
                0,
                orientation.toByte(),
                0,
                0,
                0,
                0,
                0,
                0,
                0,
            )
        return encoded.copyOfRange(0, 2) + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0, (exif.size + 2).toByte()) +
            exif + encoded.copyOfRange(2, encoded.size)
    }

    private companion object {
        const val MIB = 1024 * 1024L
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
        val GREEN = 0xFF00FF00.toInt()
        val YELLOW = 0xFFFFFF00.toInt()
    }
}
