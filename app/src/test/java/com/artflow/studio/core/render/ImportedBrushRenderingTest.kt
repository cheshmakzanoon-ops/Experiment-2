package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.ImportedPressure
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedBrushRenderingTest {
    private val plain = BrushParams(size = 18f, pressureToSize = 0f, pressureToOpacity = 0f, spacing = 0.25f)

    private fun stroke(params: BrushParams) =
        Stroke(
            id = 961L,
            points = listOf(StrokePoint(12f, 30f, timestamp = 0), StrokePoint(80f, 30f, timestamp = 90)),
            brushParams = params,
            layerId = 1L,
            color = 0xFF114477.toInt(),
        )

    private fun render(
        stroke: Stroke,
        fill: Int = 0,
    ): PixelBuffer =
        PixelBuffer(96, 64).also { canvas ->
            canvas.fill(fill)
            StrokeRasterizer().draw(canvas, stroke)
        }

    @Test fun secondaryOpacityControlsPaintAndEraseCoverage() {
        for (opacity in listOf(0f, 0.25f, 0.5f, 1f)) {
            val params = plain.copy(dual = DualBrush(plain.copy(opacity = opacity)))
            val paint = render(stroke(params))
            val erase = render(stroke(params).copy(isEraser = true), -1)
            val alpha = paint.pixels[30 * 96 + 45] ushr 24
            assertEquals("secondary opacity $opacity", (opacity * 255f).toDouble(), alpha.toDouble(), 1.0)
            assertEquals("eraser coverage $opacity", (255 - alpha).toDouble(), (erase.pixels[30 * 96 + 45] ushr 24).toDouble(), 1.0)
        }
    }

    @Test fun addAndSubtractRespectATransparentSecondary() {
        val base = render(stroke(plain))
        for (mode in listOf(DualBrush.Mode.ADD, DualBrush.Mode.SUBTRACT)) {
            val params = plain.copy(dual = DualBrush(plain.copy(opacity = 0f), mode))
            assertArrayEquals(base.pixels, render(stroke(params)).pixels)
        }
    }

    @Test fun secondaryMovingGrainStaysStrokeAnchoredAcrossCroppedRendering() {
        val params = plain.copy(dual = DualBrush(plain.copy(textureId = "paper", blendTexture = true, grainMoving = true)))
        val whole = stroke(params)
        val expected = render(whole)
        val region = IntBounds(23, 13, 77, 47)
        // draw() receives crop-local samples. The rasterizer's origin restores canvas coordinates.
        val shifted = whole.copy(points = whole.points.map { it.copy(x = it.x - region.left, y = it.y - region.top) })
        val cropped = PixelBuffer(region.width, region.height)
        StrokeRasterizer(region.left, region.top).draw(cropped, shifted)
        assertArrayEquals(expected.crop(region).pixels, cropped.pixels)
        val fixed = render(whole.copy(brushParams = params.copy(dual = DualBrush(params.dual!!.params.copy(grainMoving = false)))))
        assertFalse(expected.pixels.contentEquals(fixed.pixels))
    }

    @Test fun randomShapeMirroringReplaysExactlyAndIsNotAFixedFlip() {
        val tile =
            CustomGrains.Tile(
                CustomGrains.TILE,
                ByteArray(CustomGrains.TILE * CustomGrains.TILE) { i ->
                    if (i % CustomGrains.TILE < CustomGrains.TILE / 3 && i / CustomGrains.TILE < CustomGrains.TILE / 2) 255.toByte() else 0
                },
            )
        val id = CustomGrains.idFor(tile)
        CustomGrains.register(id, tile)
        val params = plain.copy(shapeId = id, spacing = 0.8f, tipFlipXJitter = true, tipFlipYJitter = true)
        val original = render(stroke(params))
        assertTrue(original.pixels.any { it ushr 24 > 0 })
        assertArrayEquals(original.pixels, render(stroke(params)).pixels)
        val fixed = params.copy(tipFlipXJitter = false, tipFlipYJitter = false, tipFlipX = true, tipFlipY = true)
        assertFalse(original.pixels.contentEquals(render(stroke(fixed)).pixels))
        assertFalse(original.pixels.contentEquals(render(stroke(params).copy(id = 962L)).pixels))
    }

    @Test fun livePreviewWithSignedDynamicsCurvesAndRandomFlipsMatchesFullReplay() {
        val tile = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it % 251).toByte() })
        val id = CustomGrains.idFor(tile)
        CustomGrains.register(id, tile)
        val params =
            plain.copy(
                shapeId = id,
                tipFlipXJitter = true,
                tipFlipYJitter = true,
                pressureToSize = -0.7f,
                pressureToOpacity = -0.5f,
                velocityToSize = -0.7f,
                velocityToOpacity = -0.4f,
                textureId = "canvas",
                blendTexture = true,
                grainMoving = true,
                importedPressure =
                    ImportedPressure(
                        size =
                            PressureMapping(
                                listOf(
                                    PressureMapping.Point(0f, 0.1f),
                                    PressureMapping.Point(0.3f, 0.8f),
                                    PressureMapping.Point(1f, 1f),
                                ),
                            ),
                    ),
            )
        val whole =
            stroke(params).copy(
                points =
                    (0..12).map { i ->
                        StrokePoint(8f + i * 6f, 28f + (i % 3) * 4f, pressure = 0.1f + (i % 7) * 0.12f, timestamp = i * 9L)
                    },
            )
        assertTrue(StrokeRasterizer.canDrawLive(whole))
        val rasterizer = StrokeRasterizer()
        val live = rasterizer.startLive(whole, 96, 64)
        val region = IntBounds(19, 11, 83, 53)
        val croppedRasterizer = StrokeRasterizer(region.left, region.top)
        val croppedLive = croppedRasterizer.startLive(whole, 96, 64)
        for (size in 1..whole.points.size) {
            val partial = whole.copy(points = whole.points.take(size))
            val actual = PixelBuffer(96, 64)
            rasterizer.drawLive(actual, live, partial, PixelBuffer(96, 64))
            assertArrayEquals("after $size samples", render(partial).pixels, actual.pixels)
            val crop = PixelBuffer(region.width, region.height)
            croppedRasterizer.drawLive(crop, croppedLive, partial, PixelBuffer(96, 64))
            assertArrayEquals("crop after $size samples", actual.crop(region).pixels, crop.pixels)
        }
    }

    @Test fun fineImportedGrainScaleIsNotSilentlyClampedToTheOldMinimum() {
        val fine = requireNotNull(BrushTexture.from(plain.copy(blendTexture = true, textureId = "canvas", textureScale = 0.1f)))
        val coarse = requireNotNull(BrushTexture.from(plain.copy(blendTexture = true, textureId = "canvas", textureScale = 0.25f)))
        assertTrue((0..30).any { x -> fine.coverage(x, 7) != coarse.coverage(x, 7) })
    }
}
