package com.artflow.studio.core

import com.artflow.studio.core.canvas.LayerTransform
import com.artflow.studio.core.export.GifEncoder
import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.PhysicalPaintEngine
import com.artflow.studio.core.render.PhysicalPaintPlane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream

class PhysicalPlanePrimitivesTest {
    private fun plane(): PhysicalPaintPlane =
        PhysicalPaintPlane(96, 96).also {
            it.setCell(10, 12, wetness = 0.8f, impasto = -0.5f, pigment = 0.4f)
            it.setCell(70, 80, wetness = 0.3f, impasto = 0.75f, pigment = 0.9f)
        }

    @Test
    fun packedPixelsRoundTripEveryFieldIncludingNegativeRelief() {
        val original = plane()
        val restored = PhysicalPaintPlane.fromPackedPixels(original.toPackedPixels())
        assertEquals(96, restored.width)
        assertEquals(96, restored.height)
        for ((x, y) in listOf(10 to 12, 70 to 80)) {
            assertEquals(original.wetnessAt(x, y), restored.wetnessAt(x, y), 1f / 255f)
            assertEquals(original.impastoAt(x, y), restored.impastoAt(x, y), 2f / 255f)
            assertEquals(original.pigmentAt(x, y), restored.pigmentAt(x, y), 1f / 255f)
        }
        // An untouched, zero-valued cell must not turn into relief.
        assertEquals(0f, restored.impastoAt(40, 40), 0f)
        assertEquals(2, restored.allocatedTileCount())
    }

    @Test
    fun theColourTransformMovesPhysicalStateToTheSameCell() {
        val moved =
            PhysicalPaintPlane.fromPackedPixels(
                LayerTransform.apply(plane().toPackedPixels(), LayerTransform.Parameters(translationX = 20f, translationY = 5f)),
            )
        assertEquals(0f, moved.wetnessAt(10, 12), 0.0001f)
        assertEquals(0.8f, moved.wetnessAt(30, 17), 1f / 255f)
        assertEquals(-0.5f, moved.impastoAt(30, 17), 2f / 255f)
    }

    @Test
    fun cellsPushedOffTheCanvasAreDroppedNotWrapped() {
        val moved =
            PhysicalPaintPlane.fromPackedPixels(
                LayerTransform.apply(plane().toPackedPixels(), LayerTransform.Parameters(translationX = 200f)),
            )
        assertTrue(moved.isEmpty())
    }

    @Test
    fun pruningRemovesStateBeneathTransparentColourOnly() {
        val plane = plane()
        val raster = PixelBuffer(96, 96)
        raster.setUnchecked(10, 12, 0xFF112233.toInt())
        assertTrue(plane.hasStateBeneathTransparent(raster))
        plane.pruneTransparent(raster)
        assertFalse(plane.hasStateBeneathTransparent(raster))
        assertEquals(0.8f, plane.wetnessAt(10, 12), 0.0001f)
        assertEquals(0f, plane.wetnessAt(70, 80), 0f)
        assertEquals("The emptied tile must be released", 1, plane.allocatedTileCount())
    }

    @Test
    fun contentEqualityIgnoresZeroOnlyTilesAndSeesRealDifferences() {
        val a = plane()
        val b = plane()
        assertTrue(a.contentEquals(b))
        b.obtainTileForTest(2, 2)
        assertTrue("A tile holding only zeros is not content", a.contentEquals(b))
        b.setCell(10, 12, 0.81f, -0.5f, 0.4f)
        assertFalse(a.contentEquals(b))
        assertTrue(PhysicalPaintPlane(96, 96).contentEquals(null))
    }

    @Test
    fun wetPaintAlwaysReachesQuiescenceEvenAtTheCanvasEdge() {
        val buffer = PixelBuffer(64, 64)
        val plane = PhysicalPaintPlane(64, 64)
        for (y in 20..30) {
            for (x in 0..8) {
                buffer.setUnchecked(x, y, 0xFF2060CC.toInt())
                plane.setCell(x, y, wetness = 0.9f, impasto = 0f, pigment = 0.7f)
            }
        }
        // A cell resting between the old activity threshold (0.01) and the old processing floor (0.02).
        plane.setCell(40, 40, wetness = 0.015f, impasto = 0f, pigment = 0.2f)
        buffer.setUnchecked(40, 40, 0xFF2060CC.toInt())

        var steps = 0
        while (plane.hasActiveWetness() && steps < 400) {
            PhysicalPaintEngine.stepWatercolorDiffusion(buffer, plane)
            steps++
        }
        assertFalse("Wetness must settle; still active after $steps steps", plane.hasActiveWetness())
        assertTrue("Settling should take a bounded, modest number of steps (took $steps)", steps < 200)
    }

    @Test
    fun aDamagedPlaneWithATileOutsideItsBoundsIsRejected() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { data ->
            data.writeInt(0x41465050)
            data.writeInt(1)
            data.writeInt(64)
            data.writeInt(64)
            data.writeInt(1)
            data.writeInt(900) // tile x far outside a 64 px plane
            data.writeInt(0)
            repeat(PhysicalPaintPlane.TILE_PIXELS * 3) { data.writeByte(0) }
        }
        try {
            PhysicalPaintPlane.decodeBinary(bytes.toByteArray())
            fail("A tile outside the plane must not decode")
        } catch (expected: IllegalArgumentException) {
            assertNotNull(expected.message)
        }
    }

    @Test
    fun levelsAcceptEveryEndpointWithoutThrowing() {
        for (inBlack in listOf(0f, 254f, 255f)) {
            for (inWhite in listOf(0f, 128f, 255f)) {
                for (outBlack in listOf(0f, 255f)) {
                    for (outWhite in listOf(0f, 255f)) {
                        val lut = AdjustmentProcessor.buildLevelsLut(inBlack, inWhite, 1f, outBlack, outWhite)
                        assertEquals(256, lut.size)
                        assertTrue(lut.all { it in 0..255 })
                    }
                }
            }
        }
        // Collapsing the output range to white is a legitimate setting and must give all white.
        val white = AdjustmentProcessor.buildLevelsLut(0f, 255f, 1f, 255f, 255f)
        assertTrue(white.all { it == 255 })
    }

    @Test
    fun gifFramesDisposeToBackgroundSoTransparentFramesDoNotKeepEarlierMarks() {
        val width = 2
        val height = 2
        val frames = listOf(IntArray(4) { 0xFFFF0000.toInt() }, IntArray(4) { 0 })
        val gif = GifEncoder.encode(frames, width, height, listOf(100, 100), keepTransparency = true)
        val extensions =
            gif.indices.filter { i ->
                i + 3 < gif.size && gif[i] == 0x21.toByte() && gif[i + 1] == 0xF9.toByte() && gif[i + 2] == 0x04.toByte()
            }
        assertEquals(2, extensions.size)
        extensions.forEach { i ->
            val packed = gif[i + 3].toInt() and 0xFF
            assertEquals("Disposal method must be 2 (restore to background)", 2, (packed shr 2) and 0x07)
            assertEquals("Transparency flag must be set", 1, packed and 0x01)
        }
    }

    private fun PhysicalPaintPlane.obtainTileForTest(
        tx: Int,
        ty: Int,
    ) {
        // setCell never allocates for an all-zero value; the internal accessor adds an empty tile.
        obtainTile(tx, ty)
    }
}
