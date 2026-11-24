package com.artflow.studio.core

import com.artflow.studio.core.color.SpectralMixer
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.BrushSourceLibrary
import com.artflow.studio.core.render.BrushTexture
import com.artflow.studio.core.render.Compositor
import com.artflow.studio.core.render.PhysicalPaintEngine
import com.artflow.studio.core.render.PhysicalPaintPlane
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.CanvasDocumentCodec
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PhysicalPaintMode
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.brush.StudioBrushesPhysical
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalPaintEngineTest {
    private val blue = 0xFF1A4CE0.toInt()
    private val yellow = 0xFFF2E020.toInt()
    private val red = 0xFFD92B2B.toInt()

    @Test
    fun `spectral mixer blends blue and yellow into natural green instead of muddy gray`() {
        val mixed = SpectralMixer.mixArgb(blue, yellow, 0.5f)
        val r = (mixed shr 16) and 0xFF
        val g = (mixed shr 8) and 0xFF
        val b = mixed and 0xFF
        assertTrue("Expected green channel ($g) to dominate red ($r) and blue ($b)", g > r + 20 && g > b + 20)
        assertEquals(blue, SpectralMixer.mixArgb(blue, yellow, 0f))
        assertEquals(yellow, SpectralMixer.mixArgb(blue, yellow, 1f))
        assertEquals(red, SpectralMixer.mixArgb(red, red, 0.5f))

        val glazed = SpectralMixer.glazeArgb(yellow, blue, 0.6f)
        val gr = (glazed shr 16) and 0xFF
        val gg = (glazed shr 8) and 0xFF
        val gb = glazed and 0xFF
        assertTrue("Glazing blue over yellow should shift toward green (r=$gr, g=$gg, b=$gb)", gg > gr && gg > gb)
    }

    @Test
    fun `sparse physical paint plane allocates lazily, dries cleanly, and round-trips binary encoding`() {
        val plane = PhysicalPaintPlane(256, 256)
        assertTrue(plane.isEmpty())
        assertEquals(0L, plane.estimatedBytes())

        PhysicalPaintEngine.depositWetness(plane, 32f, 32f, 12f, waterAmount = 0.9f, pigmentLoad = 0.7f)
        assertEquals(1, plane.allocatedTileCount())
        assertTrue(plane.wetnessAt(32, 32) > 0.7f)
        assertEquals(0f, plane.wetnessAt(200, 200), 0.0001f)

        PhysicalPaintEngine.depositImpasto(plane, 32f, 32f, 12f, thickness = 0.85f, dirX = 1f, dirY = 0f)
        assertTrue(plane.impastoAt(32, 32) > 0.1f)

        val encoded = plane.encodeBinary()
        val decoded = PhysicalPaintPlane.decodeBinary(encoded)
        assertEquals(plane.width, decoded.width)
        assertEquals(plane.height, decoded.height)
        assertEquals(plane.allocatedTileCount(), decoded.allocatedTileCount())
        assertEquals(plane.wetnessAt(32, 32), decoded.wetnessAt(32, 32), 0.01f)
        assertEquals(plane.impastoAt(32, 32), decoded.impastoAt(32, 32), 0.01f)

        // Dry keeps impasto ridges while clearing wetness & mobile pigment.
        decoded.dryAll()
        assertEquals(0f, decoded.wetnessAt(32, 32), 0.0001f)
        assertTrue(decoded.impastoAt(32, 32) > 0.1f)

        // Rewet activates wetness again.
        decoded.rewetRegion(32f, 32f, 10f, 0.8f)
        assertTrue(decoded.wetnessAt(32, 32) > 0.5f)
    }

    @Test
    fun `watercolor diffusion mixes adjacent wet colors spectrally and respects gravity`() {
        val buffer = PixelBuffer(48, 48)
        val plane = PhysicalPaintPlane(48, 48)
        for (y in 12 until 36) {
            for (x in 12 until 24) {
                buffer.set(x, y, blue)
                plane.setCell(x, y, wetness = 0.95f, impasto = 0f, pigment = 0.8f)
            }
            for (x in 24 until 36) {
                buffer.set(x, y, yellow)
                plane.setCell(x, y, wetness = 0.95f, impasto = 0f, pigment = 0.8f)
            }
        }
        val paper =
            BrushTexture.from(
                BrushParams(blendTexture = true, textureId = BrushSourceLibrary.GrainSource.COLD_PRESS.id),
            )
        PhysicalPaintEngine.stepWatercolorDiffusion(
            colorBuffer = buffer,
            plane = plane,
            paperGrain = paper,
            granulation = 0.5f,
            gravityX = 0.4f,
            gravityY = 0.2f,
            iterations = 3,
        )
        val boundary = buffer.get(24, 24)
        assertNotEquals("Expected wet boundary pixel to diffuse", yellow, boundary)
        assertTrue("Wetness should evaporate gradually during diffusion", plane.wetnessAt(24, 24) < 0.95f)
    }

    @Test
    fun `impasto stroke deposits 3d ridges and compositor applies dynamic raking light`() {
        val rasterizer = StrokeRasterizer()
        val plane = PhysicalPaintPlane(64, 64)
        val stroke =
            Stroke(
                id = 77L,
                points =
                    listOf(
                        StrokePoint(12f, 32f, pressure = 0.9f, timestamp = 0L),
                        StrokePoint(52f, 32f, pressure = 0.9f, timestamp = 100L),
                    ),
                brushParams =
                    BrushParams(
                        size = 22f,
                        spacing = 0.15f,
                        spectralMixing = true,
                        physicalMode = PhysicalPaintMode.IMPASTO_OIL,
                        impastoThickness = 0.9f,
                    ),
                layerId = 1L,
                color = red,
            )
        val layerBuffer = PixelBuffer(64, 64)
        rasterizer.draw(layerBuffer, stroke, physicalPlane = plane)
        assertFalse("Impasto stroke should populate physical plane", plane.isEmpty())

        val compositor = Compositor()
        val layer = Layer(id = 1L, name = "Oil", index = 0)
        val flat = compositor.composite(listOf(Compositor.LayerInput(layer, raster = layerBuffer.copy())), 64, 64)
        val lit =
            compositor.composite(
                listOf(Compositor.LayerInput(layer, raster = layerBuffer.copy(), physicalPlane = plane)),
                64,
                64,
                options = Compositor.Options(physicalLightAngle = 45f, physicalLightElevation = 30f, physicalImpastoDepth = 0.8f),
            )
        assertFalse("Raking light should shade impasto ridges differently from flat paint", flat.pixels.contentEquals(lit.pixels))
    }

    @Test
    fun `artflow v8 document codec persists physical paint metadata and validates version contract`() {
        val doc =
            CanvasDocument(
                version = CanvasDocument.CURRENT_VERSION,
                width = 128,
                height = 128,
                dpi = 300,
                backgroundColor = 0xFFFFFFFF.toInt(),
                activeLayerId = 1L,
                nextLayerId = 2L,
                layers = listOf(Layer(id = 1L, name = "Watercolor", index = 0, physicalPaintFile = "physical/layer_1.afpp")),
                physicalLightAngle = 120f,
                physicalLightElevation = 25f,
                physicalImpastoDepth = 0.75f,
            )
        val encoded = CanvasDocumentCodec.encode(doc)
        val decoded = CanvasDocumentCodec.decode(String(encoded))
        assertEquals(8, decoded.version)
        assertEquals("physical/layer_1.afpp", decoded.layers.first().physicalPaintFile)
        assertEquals(120f, decoded.physicalLightAngle, 0.001f)
        assertEquals(25f, decoded.physicalLightElevation, 0.001f)
        assertEquals(0.75f, decoded.physicalImpastoDepth, 0.001f)

        // All 12 Physical Paint presets encode/decode cleanly via BrushLibraryCodec.
        assertEquals(12, StudioBrushesPhysical.presets.size)
        val saved =
            StudioBrushesPhysical.presets.map { preset ->
                SavedBrush(id = preset.id, name = preset.name, parameters = preset.parameters)
            }
        val decodedBrushes = BrushLibraryCodec.decode(BrushLibraryCodec.encode(saved))
        assertEquals(12, decodedBrushes.size)
        assertTrue(decodedBrushes.all { it.parameters.spectralMixing })
    }
}
