package com.artflow.studio.data.local

import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.core.export.TestPng
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/**
 * Procreate brushes written by [ProcreateBrushWriter] and read back through [BrushArchives.read], the import
 * path the app uses: settings within float rounding, names and order, and the shape and grain pixels exactly.
 */
class BrushExportRoundTripTest {
    private val decode: (ByteArray) -> PixelBuffer? = { TestPng.decode(it) }

    private val settings =
        BrushParams(
            size = 40f,
            opacity = 0.8f,
            spacing = 0.15f,
            scatter = 0.2f,
            count = 3,
            rotation = 30f,
            taperStart = 0.25f,
            taperEnd = 0.1f,
            taperOpacity = 0.4f,
            pressureToSize = 0.6f,
            pressureToOpacity = 0.35f,
            hueJitter = 0.1f,
            saturationJitter = 0.2f,
            brightnessJitter = 0.3f,
            sizeJitter = 0.15f,
            opacityJitter = 0.05f,
            smoothing = 0.45f,
            wetMix = 0.4f,
            flow = 0.7f,
            textureScale = 1.5f,
            tiltInfluence = 0.5f,
            tiltToRotation = true,
            velocityToSize = -0.3f,
            velocityToOpacity = 0.25f,
            roundness = 0.6f,
            blendMode = BlendMode.MULTIPLY,
            wetEdges = 0.2f,
            burntEdges = 0.15f,
            grainDepth = 0.8f,
            falloff = 0.35f,
            tipFlipX = true,
            tipFlipY = false,
            tipRandomized = true,
            buildUp = true,
            grainMoving = true,
            secondaryPressure = 0.3f,
            secondaryJitter = 0.12f,
        )

    /**
     * A tile whose values run from 0 to 255 (x * 31 covers every byte along a row), so the import's stretch to
     * the full range leaves them unchanged.
     */
    private fun shapeTile(seed: Int) =
        CustomGrains.Tile(
            CustomGrains.TILE,
            ByteArray(CustomGrains.TILE * CustomGrains.TILE) { index ->
                val x = index % CustomGrains.TILE
                val y = index / CustomGrains.TILE
                ((x * 31 + y * 17 + seed) and 0xFF).toByte()
            },
        )

    private fun assertSettings(
        expected: BrushParams,
        actual: BrushParams,
    ) {
        fun close(
            field: String,
            want: Float,
            got: Float,
        ) = assertEquals(field, want.toDouble(), got.toDouble(), TOLERANCE)
        close("size", expected.size, actual.size)
        close("opacity", expected.opacity, actual.opacity)
        close("spacing", expected.spacing, actual.spacing)
        close("scatter", expected.scatter, actual.scatter)
        assertEquals("count", expected.count, actual.count)
        close("rotation", expected.rotation, actual.rotation)
        close("taperStart", expected.taperStart, actual.taperStart)
        close("taperEnd", expected.taperEnd, actual.taperEnd)
        close("taperOpacity", expected.taperOpacity, actual.taperOpacity)
        close("pressureToSize", expected.pressureToSize, actual.pressureToSize)
        close("pressureToOpacity", expected.pressureToOpacity, actual.pressureToOpacity)
        close("hueJitter", expected.hueJitter, actual.hueJitter)
        close("saturationJitter", expected.saturationJitter, actual.saturationJitter)
        close("brightnessJitter", expected.brightnessJitter, actual.brightnessJitter)
        close("sizeJitter", expected.sizeJitter, actual.sizeJitter)
        close("opacityJitter", expected.opacityJitter, actual.opacityJitter)
        close("smoothing", expected.smoothing, actual.smoothing)
        close("wetMix", expected.wetMix, actual.wetMix)
        close("flow", expected.flow, actual.flow)
        close("textureScale", expected.textureScale, actual.textureScale)
        close("tiltInfluence", expected.tiltInfluence, actual.tiltInfluence)
        close("velocityToSize", expected.velocityToSize, actual.velocityToSize)
        close("velocityToOpacity", expected.velocityToOpacity, actual.velocityToOpacity)
        close("roundness", expected.roundness, actual.roundness)
        close("wetEdges", expected.wetEdges, actual.wetEdges)
        close("burntEdges", expected.burntEdges, actual.burntEdges)
        close("grainDepth", expected.grainDepth, actual.grainDepth)
        close("falloff", expected.falloff, actual.falloff)
        close("secondaryPressure", expected.secondaryPressure, actual.secondaryPressure)
        close("secondaryJitter", expected.secondaryJitter, actual.secondaryJitter)
        assertEquals("blendMode", expected.blendMode, actual.blendMode)
        assertEquals("tiltToRotation", expected.tiltToRotation, actual.tiltToRotation)
        assertEquals("tipFlipX", expected.tipFlipX, actual.tipFlipX)
        assertEquals("tipFlipY", expected.tipFlipY, actual.tipFlipY)
        assertEquals("tipRandomized", expected.tipRandomized, actual.tipRandomized)
        assertEquals("buildUp", expected.buildUp, actual.buildUp)
        assertEquals("grainMoving", expected.grainMoving, actual.grainMoving)
    }

    @Test
    fun aBrushFileReadsBackItsSettingsNameAndImages() {
        val shape = shapeTile(seed = 0)
        val grain = shapeTile(seed = 91)
        CustomGrains.register("custom-7e1a", shape)
        CustomGrains.register("custom-7e1b", grain)
        val brush = SavedBrush("one", "Round Ink", settings.copy(shapeId = "custom-7e1a", textureId = "custom-7e1b"))

        val imported = BrushArchives.read(BrushArchives.procreateBrush(brush), decode).single()

        assertEquals("Round Ink", imported.name)
        assertSettings(settings, requireNotNull(imported.settings).parameters)
        assertEquals("Round Ink", imported.settings?.name)
        assertArrayEquals(shape.values, requireNotNull(imported.shape).values)
        assertArrayEquals(grain.values, requireNotNull(imported.grain).values)
        assertEquals(false, imported.settings?.shapeInverted)
    }

    @Test
    fun aBrushFileHasItsArchiveAndImagesAtTheTopLevel() {
        CustomGrains.register("custom-7e1c", shapeTile(seed = 3))
        val bytes = BrushArchives.procreateBrush(SavedBrush("two", "Top level", settings.copy(shapeId = "custom-7e1c")))
        val names = ZipInputStream(bytes.inputStream()).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }
        assertEquals(listOf("Brush.archive", "Shape.png"), names)
    }

    @Test
    fun imagesThatAreNotLoadedAreLeftOutAndTheBrushStillImports() {
        val bytes = BrushArchives.procreateBrush(SavedBrush("three", "Bare", BrushParams(shapeId = "custom-7e1d")))
        val imported = BrushArchives.read(bytes, decode).single()
        assertNull(imported.shape)
        assertNull(imported.grain)
        assertNotNull(imported.settings)
        assertEquals("Bare", imported.name)
    }

    @Test
    fun aBrushSetKeepsItsOrderNamesAndSettings() {
        val second = BrushParams(size = 12f, spacing = 0.04f, count = 1, blendMode = BlendMode.SCREEN, roundness = 0.3f)
        val third = BrushParams(size = 150f, opacity = 0.5f, rotation = 170f, tipRandomized = true)
        val bytes =
            ProcreateBrushWriter.brushset(
                "Fixture set",
                listOf(
                    ProcreateBrushWriter.Brush("First", settings),
                    ProcreateBrushWriter.Brush("Second", second, shape = shapeTile(seed = 5)),
                    ProcreateBrushWriter.Brush("Third", third),
                ),
            )
        val brushes = BrushArchives.read(bytes, decode)
        assertEquals(listOf("First", "Second", "Third"), brushes.map { it.name })
        assertSettings(settings, requireNotNull(brushes[0].settings).parameters)
        assertSettings(second, requireNotNull(brushes[1].settings).parameters)
        assertSettings(third, requireNotNull(brushes[2].settings).parameters)
        assertArrayEquals(shapeTile(seed = 5).values, requireNotNull(brushes[1].shape).values)
        assertNull(brushes[0].shape)
        assertTrue(brushes.all { it.grain == null })
    }

    private companion object {
        const val TOLERANCE = 1e-4
    }
}
