package com.artflow.studio.core.export

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
    fun anythingElseIsRefused() {
        assertTrue(runCatching { ProcreateBrush.read("bplist00 not really".toByteArray()) }.exceptionOrNull() is IllegalArgumentException)
    }
}
