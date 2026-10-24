package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.TestEvidence
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.KnownIccProfiles
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.LayerImports
import com.artflow.studio.data.export.ProcreateImport
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs

/** Native P3 tiles and Android-decoded sRGB thumbnails must not be interpreted as the same channels. */
@RunWith(AndroidJUnit4::class)
class ProcreateColorProfileDeviceTest {
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "procreate-colour-").toFile()
        val context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
    }

    @After
    fun tearDown() = StorageFileTree.delete(directory)

    @Test
    fun nativeP3DocumentKeepsItsChannelsBackgroundAndProfileThroughDiskReopen() =
        runBlocking(Dispatchers.Main) {
            val incoming = asset("display-p3-derived.procreate")
            assertEquals(FIXTURE_SHA, sha256(incoming))
            val expected = expectedNative()
            assertAndroidThumbnail(thumbnail(incoming))
            val repository = CanvasRepositoryImpl(storage)
            try {
                repository.loadOrCreate(601, WIDTH, HEIGHT, 144)
                ProcreateImport.open(incoming.inputStream(), directory).use { opened ->
                    val document = opened.document
                    assertEquals(ColorProfile.DISPLAY_P3, document.colorProfile)
                    assertFalse(document.hasUnsupportedColorProfile)
                    assertEquals(WIDTH, document.width)
                    assertEquals(HEIGHT, document.height)
                    assertEquals(NATIVE_BACKGROUND, document.background)
                    assertTrue("sRGB thumbnail calibration error=${document.calibrationError}", document.calibrationError <= 1.0)
                    assertArrayEquals(expected, document.pixels(document.layers().single()).pixels)
                    assertArrayEquals(expected, requireNotNull(document.composite()).pixels)
                    val result = LayerImports.importProcreate(repository, document, preserveDocumentProfile = true)
                    assertEquals(1, result.layers)
                    assertFalse(result.flattened)
                    assertFalse(result.hasUnsupportedColorProfile)
                }
                assertEquals(ColorProfile.DISPLAY_P3, repository.getColorProfile())
                assertEquals(NATIVE_BACKGROUND, repository.getBackgroundColor())
                assertArrayEquals(expected, requireNotNull(repository.layerPixels(repository.getActiveLayerId())).pixels)
                assertTrue(repository.saveCanvas(601) != null)
            } finally {
                repository.dispose()
            }

            val saved = requireNotNull(storage.loadDocument(601))
            assertNotNull(saved.checksum)
            assertEquals(ColorProfile.DISPLAY_P3.name, saved.colorProfile)
            assertEquals(NATIVE_BACKGROUND, saved.backgroundColor)
            val preview = storage.flattenedFile(601).readBytes()
            assertEquals(ColorProfile.DISPLAY_P3, KnownIccProfiles.recognize(requireNotNull(embeddedIcc(preview))))
            val displayed = requireNotNull(BitmapPixelBridge.fromEncodedBytes(preview))
            assertKnownColours(displayed)
            assertColour(0xFFFF0056.toInt(), displayed.pixels[0], tolerance = 1)

            val reopened = CanvasRepositoryImpl(storage)
            try {
                reopened.loadOrCreate(601, WIDTH, HEIGHT, 144)
                assertEquals(ColorProfile.DISPLAY_P3, reopened.getColorProfile())
                assertEquals(NATIVE_BACKGROUND, reopened.getBackgroundColor())
                assertArrayEquals(expected, requireNotNull(reopened.layerPixels(reopened.getActiveLayerId())).pixels)
                assertTrue(reopened.saveCanvas(601) != null)
                assertArrayEquals(preview, storage.flattenedFile(601).readBytes())
            } finally {
                reopened.dispose()
            }
            File(TestEvidence.directory(), "procreate-p3-imported.artflow").writeBytes(storage.documentFile(601).readBytes())
            File(TestEvidence.directory(), "procreate-p3-preview.png").writeBytes(preview)
        }

    @Test
    fun insertionAndFlattenedFallbackConvertIntoTheExistingCanvasProfileExactlyOnce() =
        runBlocking(Dispatchers.Main) {
            val incoming = asset("display-p3-derived.procreate")
            val native = expectedNative()
            // Android's ICC decoder is independent of ArtFlow's ColorProfiles implementation.
            val displayed = assertAndroidThumbnail(thumbnail(incoming))
            for ((profileIndex, profile) in ColorProfile.entries.withIndex()) {
                for (flattened in listOf(false, true)) {
                    val projectId = 610L + profileIndex * 2 + if (flattened) 1 else 0
                    val existing = PixelBuffer.filled(WIDTH, HEIGHT, 0xFF223344.toInt())
                    val repository = CanvasRepositoryImpl(storage)
                    var importedId = 0L
                    var existingId = 0L
                    var storedPixels = intArrayOf()
                    try {
                        repository.loadOrCreate(projectId, WIDTH, HEIGHT, 144)
                        assertTrue(repository.setColorProfile(profile, undoable = false))
                        existingId = repository.getActiveLayerId()
                        assertTrue(repository.setLayerPixels(existingId, existing, "Existing artwork"))
                        ProcreateImport.open(incoming.inputStream(), directory).use { opened ->
                            val result =
                                LayerImports.importProcreate(
                                    repository,
                                    opened.document,
                                    rasterBudgetBytes = if (flattened) 0 else Long.MAX_VALUE,
                                )
                            assertEquals(flattened, result.flattened)
                            assertEquals(1, result.layers)
                        }
                        assertEquals(profile, repository.getColorProfile())
                        assertArrayEquals(existing.pixels, requireNotNull(repository.layerPixels(existingId)).pixels)
                        importedId = repository.getActiveLayerId()
                        storedPixels = requireNotNull(repository.layerPixels(importedId)).pixels.copyOf()
                        if (profile == ColorProfile.DISPLAY_P3) {
                            assertArrayEquals(native, storedPixels)
                            assertEquals(NATIVE_BACKGROUND, repository.getBackgroundColor())
                        } else {
                            assertPixels(displayed.pixels, storedPixels)
                            assertKnownColours(PixelBuffer(WIDTH, HEIGHT, storedPixels))
                            assertColour(0xFFFF0056.toInt(), repository.getBackgroundColor(), tolerance = 1)
                        }
                        assertTrue(repository.saveCanvas(projectId) != null)
                    } finally {
                        repository.dispose()
                    }
                    val reopened = CanvasRepositoryImpl(storage)
                    try {
                        reopened.loadOrCreate(projectId, WIDTH, HEIGHT, 144)
                        assertEquals(profile, reopened.getColorProfile())
                        assertArrayEquals(existing.pixels, requireNotNull(reopened.layerPixels(existingId)).pixels)
                        assertArrayEquals(storedPixels, requireNotNull(reopened.layerPixels(importedId)).pixels)
                    } finally {
                        reopened.dispose()
                    }
                }
            }
        }

    private fun assertAndroidThumbnail(encoded: ByteArray): PixelBuffer {
        val preferred = ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        val options =
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = preferred
                inPremultiplied = false
                inScaled = false
            }
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options))
        try {
            assertEquals(preferred, bitmap.colorSpace)
            // Even a Display P3 bitmap's getPixels result is straight sRGB.
            val pixels = IntArray(WIDTH * HEIGHT)
            bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            assertKnownColours(PixelBuffer(WIDTH, HEIGHT, pixels))
        } finally {
            bitmap.recycle()
        }
        return requireNotNull(BitmapPixelBridge.fromEncodedBytes(encoded)).also(::assertKnownColours)
    }

    private fun assertKnownColours(image: PixelBuffer) {
        // LittleCMS 2.19 with the authored fixture ICC, relative-colorimetric intent; independent of ArtFlow.
        assertColour(0xFFFA3117.toInt(), image.pixels[12 * WIDTH + 12], tolerance = 1)
        assertColour(0xFF00B773.toInt(), image.pixels[12 * WIDTH + 40], tolerance = 1)
        assertColour(0xFF1D47EF.toInt(), image.pixels[36 * WIDTH + 12], tolerance = 1)
        assertColour(0xFFD000BF.toInt(), image.pixels[28 * WIDTH + 40], tolerance = 1)
    }

    private fun assertPixels(
        expected: IntArray,
        actual: IntArray,
    ) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { index -> assertColour(expected[index], actual[index], tolerance = 2, location = index) }
    }

    private fun assertColour(
        expected: Int,
        actual: Int,
        tolerance: Int,
        location: Int = -1,
    ) {
        assertEquals("alpha at $location", expected ushr 24, actual ushr 24)
        for (shift in listOf(16, 8, 0)) {
            val difference = abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255))
            assertTrue("pixel=$location channel=$shift expected=$expected actual=$actual", difference <= tolerance)
        }
    }

    private fun asset(name: String): ByteArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open("procreate/color/$name").use { it.readBytes() }
    }

    private fun expectedNative(): IntArray {
        val data = asset("display-p3-native.argb")
        assertEquals(NATIVE_SHA, sha256(data))
        val bytes = ByteBuffer.wrap(data)
        return IntArray(WIDTH * HEIGHT) { bytes.int }
    }

    private fun thumbnail(archive: ByteArray): ByteArray =
        ZipInputStream(archive.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.first { it.name == "QuickLook/Thumbnail.png" }
            zip.readBytes()
        }

    private fun embeddedIcc(png: ByteArray): ByteArray? {
        val data = ByteBuffer.wrap(png).apply { position(8) }
        while (data.remaining() >= 12) {
            val length = data.int
            val name = ByteArray(4).also { data.get(it) }.toString(Charsets.US_ASCII)
            val body = ByteArray(length).also { data.get(it) }
            data.int
            if (name == "iCCP") {
                val start = body.indexOf(0) + 2
                return InflaterInputStream(body.copyOfRange(start, body.size).inputStream()).use { it.readBytes() }
            }
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
        const val NATIVE_BACKGROUND = 0xFFF01E5A.toInt()
        const val FIXTURE_SHA = "a2f83b7e72ce522af2b96ea49b4429b82a2141f36a5a486fac892faa6d86a07a"
        const val NATIVE_SHA = "5f8f8945ff14e2f8b4b7eb5960f06c1b9d7579d09af286c639c547a3f0951ff9"
    }
}
