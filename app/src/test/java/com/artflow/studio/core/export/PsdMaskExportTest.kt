package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PsdMaskExportTest {
    private val ink = PixelBuffer(3, 2, intArrayOf(-1, 0x80446688.toInt(), 0, 0xFF112233.toInt(), -1, -1))

    private fun mask(enabled: Boolean = true) = PsdCodec.PsdMask(-1, 1, 2, 3, byteArrayOf(0, 30, 64, -128, -56, -1), 255, enabled)

    @Test
    fun rawAndRleKeepMaskRectangleCoverageAndOriginalAlphaIndependent() {
        for (rle in listOf(false, true)) {
            val sourceMask = mask()
            val source = PsdCodec.PsdLayer("Paint", ink, left = 1, top = -1, mask = sourceMask)
            val decoded = requireNotNull(PsdCodec.read(PsdCodec.write(3, 2, listOf(source), ink, useRle = rle)))
            val restored = decoded.layers.single()
            assertArrayEquals(ink.pixels, restored.pixels.pixels)
            assertEquals(1, restored.left)
            assertEquals(-1, restored.top)
            val restoredMask = requireNotNull(restored.mask)
            assertEquals(-1, restoredMask.left)
            assertEquals(1, restoredMask.top)
            assertEquals(2, restoredMask.width)
            assertEquals(3, restoredMask.height)
            assertArrayEquals(sourceMask.coverage, restoredMask.coverage)
            assertEquals(255, restoredMask.outside)
            assertTrue(restoredMask.isEnabled)
        }
    }

    @Test
    fun disabledMasksAndFolderMasksSurviveWithoutShiftingFollowingChannels() {
        val folder = PsdCodec.PsdLayer.groupHeader("Folder").copy(mask = mask(false))
        val layers = listOf(PsdCodec.PsdLayer.groupEnd(), PsdCodec.PsdLayer("Ink", ink), folder, PsdCodec.PsdLayer("Top", ink))
        for (rle in listOf(false, true)) {
            val restored = requireNotNull(PsdCodec.read(PsdCodec.write(3, 2, layers, ink, useRle = rle))).layers
            assertEquals(4, restored.size)
            val restoredMask = requireNotNull(restored[2].mask)
            assertFalse(restoredMask.isEnabled)
            assertArrayEquals(mask().coverage, restoredMask.coverage)
            assertArrayEquals(ink.pixels, restored[3].pixels.pixels)
            assertNull(restored[1].mask)
        }
    }

    @Test
    fun recordUsesDedicatedMinusTwoChannelAndTwentyByteMaskHeader() {
        val bytes = PsdCodec.write(3, 2, listOf(PsdCodec.PsdLayer("Ink", ink, mask = mask(false))), ink, useRle = false)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        data.position(26)
        data.position(data.position() + 4 + data.getInt(data.position())) // colour mode section
        data.position(data.position() + 4 + data.getInt(data.position())) // image resources
        data.int // layer/mask section length
        data.int // layer info length
        assertEquals(-1, data.short.toInt()) // merged fourth plane is alpha
        data.position(data.position() + 16) // layer rectangle
        assertEquals(5, data.short.toInt())
        val channels = List(5) { data.short.toInt() to data.int }
        assertEquals(listOf(0, 1, 2, -1, -2), channels.map { it.first })
        assertEquals(8, channels.last().second) // six mask samples plus compression flag
        data.position(data.position() + 12) // blend signature/key, opacity, clipping, flags, filler
        data.int // extra length
        assertEquals(20, data.int)
        assertEquals(1, data.int) // top
        assertEquals(-1, data.int) // left
        assertEquals(4, data.int) // bottom
        assertEquals(1, data.int) // right
        assertEquals(255, data.get().toInt() and 255)
        assertEquals(2, data.get().toInt()) // disabled, absolute coordinates
        assertEquals(0, data.short.toInt())
    }

    @Test
    fun capturedDensityInversionAndFeatherMatchCompositorWithoutTouchingInk() {
        val renderer = Compositor(StrokeRasterizer())
        try {
            val source = PixelBuffer(3, 2, intArrayOf(-1, 0, 0x80FFFFFF.toInt(), -1, 0xFF000000.toInt(), -1))
            val layer = Layer(id = 8, name = "Mask", index = 0, maskDensity = 0.7f, maskInverted = true, maskFeather = 1f)
            val expected = PixelBuffer.filled(3, 2, -1)
            renderer.applyMask(expected, source, layer)
            val captured = LayerMaskExport.capture(renderer, layer, source, 3, 2)
            assertArrayEquals(expected.pixels, captured.pixels)
            val psd = LayerMaskExport.psd(captured, false)
            assertFalse(psd.isEnabled)
            assertArrayEquals(ByteArray(expected.size) { (expected.pixels[it] ushr 24).toByte() }, psd.coverage)
            assertArrayEquals(intArrayOf(-1, 0, 0x80FFFFFF.toInt(), -1, 0xFF000000.toInt(), -1), source.pixels)
        } finally {
            renderer.release()
        }
    }

    @Test
    fun fitResizingCopiesCoverageInsteadOfCompositingItAway() {
        val coverage = PixelBuffer(2, 1, intArrayOf(0x00FFFFFF, 0x80FFFFFF.toInt()))
        val result = LayerMaskExport.resize(coverage, 2, 3, FitMode.FIT)
        assertEquals(2, result.width)
        assertEquals(3, result.height)
        assertEquals(255, result.pixels[0] ushr 24)
        assertEquals(0, result.pixels[2] ushr 24)
        assertEquals(128, result.pixels[3] ushr 24)
        assertEquals(255, result.pixels[5] ushr 24)
    }

    @Test
    fun hugeMaskRectangleIsRejectedBeforeAllocatingCoverage() {
        val bytes = PsdCodec.write(3, 2, listOf(PsdCodec.PsdLayer("Ink", ink, mask = mask())), ink, useRle = false)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        data.position(26)
        data.position(data.position() + 4 + data.getInt(data.position()))
        data.position(data.position() + 4 + data.getInt(data.position()))
        data.position(data.position() + 4 + 4 + 2 + 16)
        val channels = data.short.toInt()
        data.position(data.position() + channels * 6 + 12 + 4 + 4)
        val maskRectangle = data.position()
        data.putInt(maskRectangle, Int.MIN_VALUE)
        data.putInt(maskRectangle + 8, Int.MAX_VALUE)
        assertNull(PsdCodec.read(bytes))
    }

    @Test
    fun zeroAreaMaskKeepsOutsideCoverageAndSubsequentLayer() {
        val empty = PsdCodec.PsdMask(0, 0, 0, 0, byteArrayOf(), 255)
        val layers = listOf(PsdCodec.PsdLayer("Empty mask", ink, mask = empty), PsdCodec.PsdLayer("Top", ink))
        for (rle in listOf(false, true)) {
            val restored = requireNotNull(PsdCodec.read(PsdCodec.write(3, 2, layers, ink, useRle = rle)))
            assertNotNull(restored.layers[0].mask)
            assertArrayEquals(ByteArray(6) { -1 }, restored.layers[0].mask!!.coverageFor(3, 2, 0, 0))
            assertArrayEquals(ink.pixels, restored.layers[1].pixels.pixels)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedCoverageIsRejected() {
        PsdCodec.PsdMask(0, 0, 3, 2, byteArrayOf(1), 255)
    }

    @Test
    fun extremePlacementDoesNotWrapOntoCanvas() {
        val source = PsdCodec.PsdMask(Int.MAX_VALUE - 1, 0, 1, 1, byteArrayOf(0), 255)
        assertArrayEquals(ByteArray(6) { -1 }, source.coverageFor(3, 2, Int.MAX_VALUE, 0))
    }

    @Test
    fun activeFolderMaskKeepsTheCompositorsIsolatedGroupSemantics() {
        val group = Layer(9, "Group", 0, isGroup = true, hasInMemoryMask = true)
        assertEquals(BlendMode.NORMAL, PsdFolders.headerBlend(group))
        assertEquals(BlendMode.PASS_THROUGH, PsdFolders.headerBlend(group.copy(maskEnabled = false)))
    }
}
