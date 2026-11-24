package com.artflow.studio.core

import com.artflow.studio.core.export.ProcreateBrush
import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.BrushPatch
import com.artflow.studio.core.render.BrushSourceLibrary
import com.artflow.studio.core.render.BrushTexture
import com.artflow.studio.core.render.StrokeRasterizer
import com.artflow.studio.core.render.WetBrushPickup
import com.artflow.studio.core.tool.PixelBrushes
import com.artflow.studio.domain.model.Color
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BrushEngineParityTest {
    private val flatZero =
        PressureMapping(
            listOf(
                PressureMapping.Point(0f, 0f),
                PressureMapping.Point(1f, 0f),
            ),
        )

    private val flatHalf =
        PressureMapping(
            listOf(
                PressureMapping.Point(0f, 0.5f),
                PressureMapping.Point(1f, 0.5f),
            ),
        )

    private val flatOne =
        PressureMapping(
            listOf(
                PressureMapping.Point(0f, 1f),
                PressureMapping.Point(1f, 1f),
            ),
        )

    private val midGray = 0xFF808080.toInt()
    private val midRed = 0xFFCC4444.toInt()
    private val blue = 0xFF2244DD.toInt()

    @Test
    fun `independent lightness and darkness jitter shift brightness in one direction only`() {
        val baseV = Color.rgbToHsv(midGray)[2]
        val darkOnly = BrushParams(brightnessJitter = 0f, darknessJitter = 0.8f)
        val lightOnly = BrushParams(brightnessJitter = 0.8f, darknessJitter = 0f)
        val rngDark = Random(42L)
        val rngLight = Random(42L)
        var sawDarker = false
        var sawLighter = false

        repeat(40) {
            val dV = Color.rgbToHsv(darkOnly.applyColorJitter(midGray, pressure = 1f, random = rngDark))[2]
            assertTrue("Dark-only jitter must not brighten ($dV > $baseV)", dV <= baseV + 0.01f)
            if (dV < baseV - 0.05f) sawDarker = true

            val lV = Color.rgbToHsv(lightOnly.applyColorJitter(midGray, pressure = 1f, random = rngLight))[2]
            assertTrue("Light-only jitter must not darken ($lV < $baseV)", lV >= baseV - 0.01f)
            if (lV > baseV + 0.05f) sawLighter = true
        }
        assertTrue("Expected at least one darkened dab", sawDarker)
        assertTrue("Expected at least one lightened dab", sawLighter)
    }

    @Test
    fun `procreate brush import and export preserve separate lightness and darkness jitter`() {
        val original =
            BrushParams(
                size = 24f,
                brightnessJitter = 0.15f,
                darknessJitter = 0.72f,
            )
        val zipBytes = ProcreateBrushWriter.brush(ProcreateBrushWriter.Brush("Asymmetric Jitter", original))
        var archiveBytes: ByteArray? = null
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(zipBytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name.endsWith("Brush.archive")) {
                    archiveBytes = zin.readBytes()
                    break
                }
                entry = zin.nextEntry
            }
        }
        val imported = ProcreateBrush.read(requireNotNull(archiveBytes)).parameters
        assertEquals(0.15f, imported.brightnessJitter, 0.01f)
        assertEquals(0.72f, imported.effectiveDarknessJitter, 0.01f)
    }

    @Test
    fun `color jitter auxiliary curves suppress jitter when mapped to zero`() {
        val base =
            BrushParams(
                hueJitter = 0.8f,
                saturationJitter = 0.8f,
                brightnessJitter = 0.8f,
                darknessJitter = 0.8f,
                secondaryJitter = 0.9f,
                importedAuxiliaryCurves =
                    mapOf(
                        "dynamicsPressureHueJitterCurve" to flatZero,
                        "dynamicsPressureSaturationJitterCurve" to flatZero,
                        "dynamicsPressureLightnessJitterCurve" to flatZero,
                        "dynamicsPressureDarknessJitterCurve" to flatZero,
                        "dynamicsPressureSecondaryColorJitterCurve" to flatZero,
                    ),
            )
        val result = base.applyColorJitter(midRed, pressure = 0.7f, random = Random(99L), secondary = blue)
        assertEquals(midRed, result)
    }

    @Test
    fun `wet grade and blur auxiliary curves alter wet brush pickup output`() {
        val canvas = PixelBuffer(32, 32)
        for (y in 0 until 32) {
            for (x in 0 until 32) {
                canvas.setSafe(x, y, if (x < 16) blue else 0xFF22CC44.toInt())
            }
        }
        val sample = WetBrushPickup.Sample(16f, 16f, 1f, 0f, 10f, 0.8f)

        val fullGrade =
            BrushParams(
                size = 16f,
                wetMix = 0.9f,
                importedAuxiliaryCurves = mapOf("dynamicsPressureGradeCurve" to flatOne),
            )
        val zeroGrade =
            fullGrade.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureGradeCurve" to flatZero))
        val cFull = WetBrushPickup.color(fullGrade, canvas, midRed, sample, Random(7L))
        val cZero = WetBrushPickup.color(zeroGrade, canvas, midRed, sample, Random(7L))
        assertEquals("Zero grade curve should pick up nothing", midRed, cZero)
        assertNotEquals("Full grade curve should pick up canvas color", midRed, cFull)

        val fullBlur =
            BrushParams(
                size = 20f,
                wetMix = 0.9f,
                wetBlur = 0.8f,
                importedAuxiliaryCurves = mapOf("dynamicsPressureBlurCurve" to flatOne),
            )
        val zeroBlur =
            fullBlur.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureBlurCurve" to flatZero))
        val blurred = WetBrushPickup.color(fullBlur, canvas, midRed, sample, Random(7L))
        val sharp = WetBrushPickup.color(zeroBlur, canvas, midRed, sample, Random(7L))
        assertNotEquals("Blur curve should modulate wet blur reach", blurred, sharp)
    }

    @Test
    fun `smudge pull auxiliary curve modulates smudge strength`() {
        fun runSmudge(curve: PressureMapping): PixelBuffer {
            val buf = PixelBuffer(40, 20)
            for (y in 0 until 20) {
                for (x in 0 until 15) buf.setSafe(x, y, midRed)
            }
            val brush =
                BrushParams(
                    size = 14f,
                    opacity = 1f,
                    importedAuxiliaryCurves = mapOf("dynamicsPressureSmudgePullCurve" to curve),
                )
            val session =
                PixelBrushes.SmudgeSession(
                    startX = 12f,
                    startY = 10f,
                    settings = PixelBrushes.SmudgeSettings(size = 14f, strength = 0.9f, brushParams = brush),
                    pressure = 0.8f,
                )
            session.dragTo(30f, 10f, buf, pressure = 0.8f)
            session.finish(buf)
            return buf
        }

        val withPull = runSmudge(flatOne)
        val noPull = runSmudge(flatZero)
        assertNotEquals(0, withPull.getSafe(22, 10) ushr 24)
        assertEquals(0, noPull.getSafe(22, 10) ushr 24)
    }

    @Test
    fun `plot spacing, scatter, longitudinal jitter, and roundness curves alter rasterized strokes`() {
        val rasterizer = StrokeRasterizer()
        val points =
            listOf(
                StrokePoint(10f, 32f, pressure = 0.7f, timestamp = 0L),
                StrokePoint(54f, 32f, pressure = 0.7f, timestamp = 100L),
            )

        fun render(params: BrushParams): PixelBuffer =
            rasterizer.rasterize(
                listOf(Stroke(id = 123L, points = points, brushParams = params, layerId = 1L, color = midRed)),
                64,
                64,
            )

        // Spacing curve
        val baseSpacing = BrushParams(size = 12f, spacing = 0.8f)
        val denseSpacing =
            baseSpacing.copy(importedAuxiliaryCurves = mapOf("dynamicsPressurePlotSpacingJitter" to flatZero))
        assertFalse(render(baseSpacing).pixels.contentEquals(render(denseSpacing).pixels))

        // Plot jitter & longitudinal jitter
        val baseScatter = BrushParams(size = 10f, spacing = 0.4f, scatter = 1.2f)
        val zeroPlotJitter =
            baseScatter.copy(importedAuxiliaryCurves = mapOf("dynamicsPressurePlotJitterCurve" to flatZero))
        val withLongJitter =
            baseScatter.copy(importedAuxiliaryCurves = mapOf("dynamicsPressurePlotJitterLongitudinalCurve" to flatOne))
        assertFalse(render(baseScatter).pixels.contentEquals(render(zeroPlotJitter).pixels))
        assertFalse(render(baseScatter).pixels.contentEquals(render(withLongJitter).pixels))

        // Roundness & roundness jitter curves
        val baseRound = BrushParams(size = 18f, spacing = 0.5f, roundness = 1f)
        val flatRound =
            baseRound.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureShapeRoundnessCurve" to flatHalf))
        val jitterH =
            baseRound.copy(
                importedAuxiliaryCurves = mapOf("dynamicsPressureShapeRoundessHorizontalJitterCurve" to flatOne),
            )
        val jitterV =
            baseRound.copy(
                importedAuxiliaryCurves = mapOf("dynamicsPressureShapeRoundessVerticalJitterCurve" to flatOne),
            )
        assertFalse(render(baseRound).pixels.contentEquals(render(flatRound).pixels))
        assertFalse(render(baseRound).pixels.contentEquals(render(jitterH).pixels))
        assertFalse(render(baseRound).pixels.contentEquals(render(jitterV).pixels))
    }

    @Test
    fun `grain auxiliary curves modulate grain depth, scale, zoom, and movement`() {
        val base =
            BrushParams(
                size = 20f,
                spacing = 0.25f,
                blendTexture = true,
                textureId = BrushTexture.Kind.CANVAS.id,
                textureScale = 2f,
                grainDepth = 1f,
            )
        val zeroDepth =
            BrushTexture.from(
                base.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureGrainDepthCurve" to flatZero)),
                pressure = 0.8f,
            )!!
        assertEquals(1f, zeroDepth.coverage(0, 0), 0.001f)

        val scaled =
            BrushTexture.from(
                base.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureGrainScaleCurve" to flatHalf)),
                pressure = 0.8f,
            )!!
        val zoomed =
            BrushTexture.from(
                base.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureGrainZoomCurve" to flatHalf)),
                pressure = 0.8f,
            )!!
        val jittered =
            BrushTexture.from(
                base.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureGrainDepthJitterCurve" to flatOne)),
                pressure = 0.8f,
                random = Random(5L),
            )!!
        assertNotNull(scaled)
        assertNotNull(zoomed)
        assertNotNull(jittered)
        assertTrue(
            BrushTexture.hasPressureCurves(
                base.copy(importedAuxiliaryCurves = mapOf("dynamicsPressureGrainMovementCurve" to flatHalf)),
            ),
        )
    }

    @Test
    fun `built-in shape and grain source library resolves, renders, and round-trips through BrushLibraryCodec`() {
        val rasterizer = StrokeRasterizer()
        val tap = listOf(StrokePoint(32f, 32f, pressure = 1f, timestamp = 0L))

        val renderedShapes =
            BrushSourceLibrary.ShapeSource.entries.map { shape ->
                val tile = BrushSourceLibrary.resolveShape(shape.id)
                assertNotNull("Expected tile for ${shape.id}", tile)
                val preview = BrushSourceLibrary.previewPixels(tile!!, 32)
                assertEquals(32 * 32, preview.size)
                val params = BrushParams(size = 36f, spacing = 0.2f, shapeId = shape.id)
                assertNotNull(BrushPatch.texture(params).shape)
                rasterizer.rasterize(
                    listOf(Stroke(id = 1L, points = tap, brushParams = params, layerId = 1L, color = midRed)),
                    64,
                    64,
                )
            }
        // Each shape produces visible pixels and at least soft-round vs flat-chisel differ.
        assertTrue(renderedShapes.all { buf -> buf.pixels.any { (it ushr 24) > 0 } })
        assertFalse(renderedShapes.first().pixels.contentEquals(renderedShapes[2].pixels))

        BrushSourceLibrary.GrainSource.entries.forEach { grain ->
            val tile = BrushSourceLibrary.resolveGrain(grain.id)
            assertNotNull("Expected grain tile for ${grain.id}", tile)
            val texture = BrushTexture.from(BrushParams(blendTexture = true, textureId = grain.id))
            assertNotNull("BrushTexture should resolve ${grain.id}", texture)
        }

        val saved =
            SavedBrush(
                id = "source-lib-brush-1",
                name = "Cold Press Charcoal",
                parameters =
                    BrushParams(
                        size = 28f,
                        shapeId = BrushSourceLibrary.ShapeSource.CHARCOAL.id,
                        textureId = BrushSourceLibrary.GrainSource.COLD_PRESS.id,
                        blendTexture = true,
                        brightnessJitter = 0.1f,
                        darknessJitter = 0.45f,
                    ),
            )
        val encoded = BrushLibraryCodec.encode(listOf(saved))
        val decoded = BrushLibraryCodec.decode(encoded)
        assertEquals(1, decoded.size)
        assertEquals(saved.parameters, decoded.first().parameters)
    }
}
