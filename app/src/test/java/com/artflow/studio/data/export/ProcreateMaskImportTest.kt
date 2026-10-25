package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.export.ProcreateWriter
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.util.zip.ZipInputStream

/** Native mask visibility changes application, not the retained coverage or concealed paint. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcreateMaskImportTest {
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun anEnabledNativeMaskImportsAsEditableCoverageAndCanBeDisabled() =
        runTest {
            assertImportedMask(enabled = true)
        }

    @Test
    fun aDisabledNativeMaskStaysDisabledOnImportAndCanBeEnabled() =
        runTest {
            assertImportedMask(enabled = false)
        }

    private suspend fun assertImportedMask(enabled: Boolean) {
        repository.createCanvas(3, 1, 72)
        val imported = LayerImports.importProcreate(repository, document(enabled), preserveDocumentProfile = true)
        assertEquals(1, imported.layers)
        assertFalse(imported.flattened)
        val layer = repository.getAllLayers().single()
        assertEquals(enabled, layer.maskEnabled)
        assertTrue(layer.isVisible)
        val maskPixels =
            repository
                .exportSnapshot(false, true, true, preserveLayerMasks = true)
                .layerMasks
                .getValue(layer.id)
                .pixels
        assertArrayEquals(intArrayOf(0, 128, 255), maskPixels.map { it ushr 24 }.toIntArray())
        assertArrayEquals(IntArray(3) { PAINT }, requireNotNull(repository.layerPixels(layer.id)).pixels)
        assertArrayEquals(if (enabled) MASKED else IntArray(3) { PAINT }, requireNotNull(repository.compositeFrame(0, true)).pixels)
        assertTrue(repository.setLayerMaskEnabled(layer.id, !enabled))
        assertEquals(!enabled, repository.getAllLayers().single().maskEnabled)
        assertArrayEquals(if (enabled) IntArray(3) { PAINT } else MASKED, requireNotNull(repository.compositeFrame(0, true)).pixels)
        val after = repository.exportSnapshot(false, true, true, preserveLayerMasks = true).layerMasks.getValue(layer.id)
        assertArrayEquals(maskPixels, after.pixels)
        assertArrayEquals(IntArray(3) { PAINT }, requireNotNull(repository.layerPixels(layer.id)).pixels)
    }

    private fun document(enabled: Boolean): ProcreateReader.Document {
        val pixels = PixelBuffer.filled(3, 1, PAINT)
        val bytes =
            ProcreateWriter.write(
                3,
                1,
                listOf(
                    ProcreateWriter.Layer(
                        "Native mask",
                        pixels,
                        mask = SelectionMask(3, 1, byteArrayOf(0, 128.toByte(), 255.toByte())),
                        maskEnabled = enabled,
                    ),
                ),
                pixels,
            )
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        return ProcreateReader.open(
            object : ProcreateReader.Files {
                override val names = files.keys

                override fun read(name: String): ByteArray? = files[name]
            },
        ) { null }
    }

    companion object {
        private const val PAINT = 0xFF114477.toInt()
        private val MASKED = intArrayOf(0, 0x80114477.toInt(), PAINT)
    }
}
