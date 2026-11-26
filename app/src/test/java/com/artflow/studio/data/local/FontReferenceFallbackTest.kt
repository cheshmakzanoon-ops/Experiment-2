package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class FontReferenceFallbackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun malformedSavedLegacyPathsUseTheMissingFontFallbackAfterReopen() =
        runBlocking {
            val filesDir = temporary.newFolder("files")
            val context = mock(Context::class.java).apply { `when`(getFilesDir()).thenReturn(filesDir) }
            val storage = ProjectStorage(context)
            val font = File(filesDir, "fonts/Poster.ttf").apply { parentFile.mkdirs() }
            val fontBytes = byteArrayOf(1, 2, 3, 4)
            font.writeBytes(fontBytes)
            val family = "file:/invalid\u0000/Poster.ttf"
            val pixels = byteArrayOf(5, 6, 7, 8)
            val raster = storage.writeRaster(1L, 1L, 1, pixels)
            val layer =
                Layer(
                    id = 1L,
                    name = "Saved text",
                    index = 0,
                    rasterFile = raster,
                    textContent = TextLayerContent("Poster", TextLayout.TextStyle(fontFamily = family), -1, 2f, 4f),
                )
            storage.saveDocument(
                1L,
                CanvasDocument(
                    width = 32,
                    height = 32,
                    dpi = 72,
                    backgroundColor = 0,
                    activeLayerId = 1L,
                    nextLayerId = 2L,
                    layers = listOf(layer),
                ),
            )

            val reopened = requireNotNull(storage.loadDocument(1L)).layers.single()
            val restored = requireNotNull(reopened.textContent).style.fontFamily

            assertEquals(family, restored)
            assertTrue(FontLibrary.isImported(restored))
            assertNull(FontLibrary.resolveFile(restored, filesDir))
            assertFalse(FontLibrary.exists(restored, filesDir))
            assertNull(FontLibrary.typeface(restored, filesDir))
            assertEquals("sans-serif", TextLayout.fallbackFamily(restored))
            assertArrayEquals(pixels, storage.readRaster(1L, reopened.rasterFile))
            assertArrayEquals(fontBytes, font.readBytes())
        }

    @Test
    fun malformedPortableNamesCannotReachPlatformPathOrTypefaceLoading() {
        val filesDir = temporary.newFolder("files")

        for (family in listOf("font:Invalid\u0000.ttf", "font:Invalid\uD800.ttf", "file:/invalid\u0000.ttf")) {
            assertNull(FontLibrary.resolveFile(family, filesDir))
            assertFalse(FontLibrary.exists(family, filesDir))
            assertNull(FontLibrary.typeface(family, filesDir))
        }
    }

    @Test
    fun aMissingValidLegacyPathStillResolvesToItsRestoredLocalFont() {
        val filesDir = temporary.newFolder("files")
        val font = File(filesDir, "fonts/Poster.ttf").apply { parentFile.mkdirs() }
        font.writeBytes(byteArrayOf(1, 2, 3, 4))
        val legacy = "file:${temporary.root}/old-device/fonts/Poster.ttf"

        assertEquals(font, FontLibrary.resolveFile(legacy, filesDir))
        assertTrue(FontLibrary.exists(legacy, filesDir))
        assertEquals(FontLibrary.fingerprint("font:Poster.ttf", filesDir), FontLibrary.fingerprint(legacy, filesDir))
    }
}
