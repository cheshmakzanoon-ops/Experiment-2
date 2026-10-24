package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.GrainBlend
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Grain blends have the same local coverage meaning on either component of a compound brush. */
class SecondaryGrainBlendTest {
    private val plain = BrushParams(size = 20f, pressureToSize = 0f, pressureToOpacity = 0f, spacing = 0.25f)

    @Test
    fun secondarySubtractMatchesTheSoloCoverageInsteadOfMultiplyingTheGrain() {
        val second = textured(GrainBlend.SUBTRACT)
        assertEquals(13, alpha(render(second)))
        assertEquals(13, alpha(render(plain.copy(dual = DualBrush(second)))))
        assertEquals(38, alpha(render(plain.copy(dual = DualBrush(second.copy(grainBlend = GrainBlend.MULTIPLY))))))
    }

    @Test
    fun secondaryHardMixMatchesTheSoloCoverageInsteadOfMultiplyingTheGrain() {
        val second = textured(GrainBlend.HARD_MIX)
        assertEquals(128, alpha(render(second)))
        assertEquals(128, alpha(render(plain.copy(dual = DualBrush(second)))))
        assertEquals(38, alpha(render(plain.copy(dual = DualBrush(second.copy(grainBlend = GrainBlend.MULTIPLY))))))
    }

    @Test
    fun hardMixUsesTheStrictCoverageThresholdBeforeSecondaryOpacity() {
        // The center has 153/255 shape coverage. 102/255 reaches exactly one; 103/255 exceeds it.
        val atThreshold = textured(GrainBlend.HARD_MIX, grain = 102)
        val aboveThreshold = textured(GrainBlend.HARD_MIX, grain = 103)
        assertEquals(0, alpha(render(plain.copy(dual = DualBrush(atThreshold)))))
        assertEquals(128, alpha(render(plain.copy(dual = DualBrush(aboveThreshold)))))
        assertEquals(64, alpha(render(plain.copy(dual = DualBrush(aboveThreshold.copy(opacity = 0.25f))))))
    }

    @Test
    fun everyDualCombineModeUsesTheBlendedSecondaryCoverageBeforePrimaryOpacity() {
        val expected =
            mapOf(
                GrainBlend.SUBTRACT to listOf(13, 242, 255),
                GrainBlend.HARD_MIX to listOf(128, 128, 255),
            )
        val faded =
            mapOf(
                GrainBlend.SUBTRACT to listOf(5, 97, 102),
                GrainBlend.HARD_MIX to listOf(51, 51, 102),
            )
        expected.forEach { (grainBlend, values) ->
            DualBrush.Mode.entries.forEachIndexed { index, mode ->
                val brush = plain.copy(dual = DualBrush(textured(grainBlend), mode))
                assertEquals("$grainBlend / $mode", values[index], alpha(render(brush)))
                assertEquals(
                    "$grainBlend / $mode / primary opacity",
                    faded.getValue(grainBlend)[index],
                    alpha(render(brush.copy(opacity = 0.4f))),
                )
            }
        }
    }

    @Test
    fun secondaryGrainAlsoControlsEraserCoverageWithoutChangingRemainingRgb() {
        for ((grainBlend, remaining) in listOf(GrainBlend.SUBTRACT to 242, GrainBlend.HARD_MIX to 127)) {
            val output = render(plain.copy(dual = DualBrush(textured(grainBlend))), erase = true)
            assertEquals(remaining, alpha(output))
            assertEquals(0x008899AA, output.pixels[30 * output.width + 30] and 0x00FFFFFF)
        }
    }

    @Test
    fun zeroDepthDisabledAndMissingTexturesRemainNeutralForEveryGrainBlend() {
        val untextured = textured(GrainBlend.MULTIPLY).copy(blendTexture = false)
        val expected = render(plain.copy(dual = DualBrush(untextured)))
        assertEquals(77, alpha(expected))
        for (blend in GrainBlend.entries) {
            val second = textured(blend)
            val neutral =
                listOf(
                    second.copy(grainDepth = 0f),
                    second.copy(blendTexture = false),
                    second.copy(textureId = "unavailable-secondary-grain"),
                )
            neutral.forEach { assertArrayEquals(expected.pixels, render(plain.copy(dual = DualBrush(it))).pixels) }
        }
    }

    @Test
    fun secondaryNonMultiplyGrainKeepsItsCanvasOriginWhenRenderingACrop() {
        val tile = CustomGrains.Tile(4, byteArrayOf(0, 64, 127, -1, -64, 32, -128, 96, 1, -2, 80, -96, 110, 20, -16, 50))
        val id = CustomGrains.idFor(tile)
        CustomGrains.register(id, tile)
        for (blend in listOf(GrainBlend.SUBTRACT, GrainBlend.HARD_MIX)) {
            for (moving in listOf(false, true)) {
                val second = textured(blend).copy(textureId = id, grainMoving = moving, textureRotation = 27f, textureScale = 1.3f)
                val stroke = stroke(plain.copy(dual = DualBrush(second)))
                val full = StrokeRasterizer().rasterize(listOf(stroke), WIDTH, HEIGHT)
                val shifted = stroke.copy(points = stroke.points.map { it.copy(x = it.x - 16, y = it.y - 8) })
                val crop = StrokeRasterizer(16, 8).rasterize(listOf(shifted), 32, 40)
                val expected = IntArray(32 * 40) { index -> full.pixels[(index / 32 + 8) * WIDTH + index % 32 + 16] }
                assertArrayEquals("$blend / moving=$moving", expected, crop.pixels)
            }
        }
    }

    @Test
    fun historicalMultiplyPixelsRemainIdenticalForPaintAndEraseInEveryDualMode() {
        // Captured using the published 2d57dbd renderer, independently of this corrected renderer.
        val source = requireNotNull(javaClass.getResourceAsStream("/documents/secondary-grain-multiply-v3.sha256"))
        val expected =
            source.bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }.associate { line ->
                val parts = line.split(' ')
                parts[0] to parts[1]
            }
        assertEquals(6, expected.size)
        for (mode in DualBrush.Mode.entries) {
            for (erase in listOf(false, true)) {
                val key = "${mode.name}-${if (erase) "erase" else "paint"}"
                val target = SecondaryGrainLegacyFixture.backdrop()
                StrokeRasterizer().draw(target, SecondaryGrainLegacyFixture.stroke(mode, erase))
                assertEquals(key, expected.getValue(key), pixelSha(target.pixels))
                assertTrue(target.pixels.any { it != 0 })
            }
        }
    }

    private fun textured(
        blend: GrainBlend,
        grain: Int = 128,
    ): BrushParams {
        val tile = CustomGrains.Tile(4, ByteArray(16) { grain.toByte() })
        val id = CustomGrains.idFor(tile)
        CustomGrains.register(id, tile)
        return plain.copy(flow = 0.6f, opacity = 0.5f, textureId = id, blendTexture = true, grainBlend = blend)
    }

    private fun stroke(params: BrushParams): Stroke =
        Stroke(
            id = 961L,
            points = listOf(StrokePoint(30.5f, 30.5f, timestamp = 0L)),
            brushParams = params,
            layerId = 1L,
            color = 0xFF114477.toInt(),
            timestamp = 0L,
        )

    private fun render(
        params: BrushParams,
        erase: Boolean = false,
    ): PixelBuffer =
        PixelBuffer.filled(WIDTH, HEIGHT, if (erase) 0xFF8899AA.toInt() else 0).also {
            StrokeRasterizer().draw(it, stroke(params).copy(isEraser = erase))
        }

    private fun alpha(buffer: PixelBuffer): Int = buffer.pixels[30 * buffer.width + 30] ushr 24

    private fun pixelSha(pixels: IntArray): String {
        val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
        pixels.forEach(bytes::putInt)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    companion object {
        private const val WIDTH = 96
        private const val HEIGHT = 64
    }
}

/** Explicit fixture shared with the old-code capture recipe; all timestamps and random seeds are fixed. */
internal object SecondaryGrainLegacyFixture {
    fun backdrop(): PixelBuffer =
        PixelBuffer(
            96,
            64,
            IntArray(96 * 64) { index ->
                if ((index / 96 + index % 96) % 7 <
                    3
                ) {
                    0xFF3A6EA5.toInt()
                } else {
                    0x80E0B040.toInt()
                }
            },
        )

    fun stroke(
        mode: DualBrush.Mode,
        erase: Boolean,
    ): Stroke {
        val second =
            BrushParams(
                size = 14f,
                flow = 0.61f,
                opacity = 0.47f,
                spacing = 0.19f,
                roundness = 0.6f,
                rotation = 27f,
                textureId = "canvas",
                blendTexture = true,
                textureScale = 1.3f,
                textureRotation = 19f,
                grainDepth = 0.73f,
                grainMoving = true,
            )
        val primary =
            BrushParams(
                size = 22f,
                opacity = 0.73f,
                flow = 0.86f,
                spacing = 0.17f,
                textureId = "paper",
                blendTexture = true,
                grainDepth = 0.45f,
                dual = DualBrush(second, mode),
            )
        return Stroke(
            id = 961L,
            points =
                listOf(
                    StrokePoint(18.5f, 18.5f, pressure = 0.25f, timestamp = 0L),
                    StrokePoint(45.5f, 37.5f, pressure = 0.8f, timestamp = 9L),
                    StrokePoint(78.5f, 22.5f, pressure = 0.5f, timestamp = 25L),
                ),
            brushParams = primary,
            layerId = 1L,
            color = 0xFF114477.toInt(),
            timestamp = 0L,
            isEraser = erase,
        )
    }
}
