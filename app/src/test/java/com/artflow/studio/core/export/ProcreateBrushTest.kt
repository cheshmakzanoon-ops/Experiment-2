package com.artflow.studio.core.export

import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.layer.BlendMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/** The brush archives in fixture.brushset were written with Procreate's own setting names. */
class ProcreateBrushTest {
    private fun archive(folder: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/procreate/fixture.brushset")).use { stream ->
            ZipInputStream(stream).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "$folder/Brush.archive" }
                zip.readBytes()
            }
        }

    @Test
    fun settingsCarryOverToTheBrushModel() {
        val settings = ProcreateBrush.read(archive("A-INKED"))
        val brush = settings.parameters
        assertEquals("Inked Grain", settings.name)
        assertEquals(0.2f, brush.spacing, 1e-6f)
        assertEquals(0.3f, brush.scatter, 1e-6f)
        assertEquals(4, brush.count)
        assertEquals(0.5f, brush.roundness, 1e-6f)
        assertEquals(0.8f, brush.pressureToSize, 1e-6f)
        assertEquals(0.1f, brush.pressureToOpacity, 1e-6f)
        assertEquals(24f, brush.size, 1e-4f)
        assertEquals(0.9f, brush.opacity, 1e-6f)
        // The extended blend mode wins over the older field.
        assertEquals(BlendMode.LINEAR_LIGHT, brush.blendMode)
        assertEquals(0.25f, brush.taperStart, 1e-6f)
        assertEquals(0.15f, brush.hueJitter, 1e-6f)
        assertEquals(0.4f, brush.wetMix, 1e-6f)
        assertEquals(0.7f, brush.flow, 1e-6f)
        assertEquals(1.5f, brush.textureScale, 1e-6f)
        assertTrue(brush.tipRandomized && brush.buildUp && brush.grainMoving)
        assertTrue(settings.shapeInverted)
        assertFalse(settings.grainInverted || settings.bundledShape || settings.bundledGrain)
    }

    @Test
    fun aBrushOnProcreatesOwnShapeSaysSo() {
        val settings = ProcreateBrush.read(archive("B-PLAIN"))
        assertEquals("Plain", settings.name)
        assertTrue(settings.bundledShape)
        assertEquals(0.05f, settings.parameters.spacing, 1e-6f)
        assertEquals(BlendMode.NORMAL, settings.parameters.blendMode)
    }

    @Test
    fun importedValuesStayInsideTheLibraryRanges() {
        val settings =
            ProcreateBrush.parameters(
                mapOf(
                    "shapeAngle" to -0.5,
                    "textureScale" to 0.1,
                    "shapeCount" to 1.0,
                    "dynamicsSpeedSize" to -0.6,
                    "dynamicsSpeedOpacity" to -1.0,
                ),
            )
        // A negative angle turns into [0, 360); a grain scale below the stored minimum is raised to it.
        assertEquals(331.35f, settings.rotation, 0.01f)
        assertEquals(0.25f, settings.textureScale, 1e-6f)
        assertEquals(16, settings.count)
        assertEquals(-0.6f, settings.velocityToSize, 1e-6f)
        assertEquals(-1f, settings.velocityToOpacity, 1e-6f)
        val saved = BrushLibraryCodec.encode(listOf(SavedBrush("imported", "Imported", settings)))
        assertEquals(settings, BrushLibraryCodec.decode(saved).single().parameters)
    }

    @Test
    fun tiltSizeAndTiltOpacityImportAsSeparateAmounts() {
        val settings =
            ProcreateBrush.parameters(mapOf("dynamicsTiltSize" to 0.0, "dynamicsTiltOpacity" to 1.0))
        assertEquals(0f, settings.tiltInfluence, 0f)
        assertEquals(1f, settings.tiltOpacityInfluence ?: -1f, 0f)
    }

    @Test
    fun signedReferenceColourPressureAndTiltAreImportedIndependently() {
        val params =
            ProcreateBrush.parameters(
                mapOf(
                    "dynamicsPressureHue" to -0.125,
                    "dynamicsPressureSaturation" to -0.2,
                    "dynamicsPressureBrightness" to 0.45,
                    "dynamicsTiltHue" to -0.01,
                    "dynamicsTiltSaturation" to 0.01,
                    "dynamicsTiltBrightness" to -0.02,
                ),
            )
        val color = params.colorDynamics
        assertTrue(color.isActive)
        assertEquals(-0.125f, color.pressureHue, 1e-6f)
        assertEquals(-0.2f, color.pressureSaturation, 1e-6f)
        assertEquals(0.45f, color.pressureBrightness, 1e-6f)
        assertEquals(-0.01f, color.tiltHue, 1e-6f)
        assertEquals(0.01f, color.tiltSaturation, 1e-6f)
        assertEquals(-0.02f, color.tiltBrightness, 1e-6f)
        val saved = BrushLibraryCodec.encode(listOf(SavedBrush("colour", "Colour", params)))
        assertEquals(params, BrushLibraryCodec.decode(saved).single().parameters)
    }

    @Test
    fun negativeColourDynamicsActuallyChangeStrokesWithoutChangingAlpha() {
        val brush =
            ProcreateBrush.parameters(
                mapOf("dynamicsPressureHue" to -0.25, "dynamicsPressureBrightness" to -0.3),
            )
        val ink = 0x806EC860.toInt()
        val atLightPressure = brush.applyColorJitter(ink, pressure = 0f)
        val atFullPressure = brush.applyColorJitter(ink, pressure = 1f)
        assertEquals(ink ushr 24, atLightPressure ushr 24)
        assertEquals(ink, atFullPressure)
        assertTrue(atLightPressure != ink)
    }

    @Test
    fun anythingElseIsRefused() {
        assertTrue(runCatching { ProcreateBrush.read("bplist00 not really".toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
    }
}
