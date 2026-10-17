package com.artflow.studio.core.export

import com.artflow.studio.domain.model.brush.RenderingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/** Procreate's recursive-mixing rendering key imports as the blending rendering mode. */
class ProcreateRenderingModeTest {
    private fun archive(folder: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/procreate/fixture.brushset")).use { stream ->
            ZipInputStream(stream).use { zip ->
                generateSequence { zip.nextEntry }.first { it.name == "$folder/Brush.archive" }
                zip.readBytes()
            }
        }

    @Test
    fun recursiveMixingImportsAsBlendingUniform() {
        // The fixture's inked brush sets renderingRecursiveMixing.
        val brush = ProcreateBrush.read(archive("A-INKED")).parameters
        assertTrue(brush.buildUp)
        assertEquals(RenderingMode.BLEND_UNIFORM, brush.renderingMode)
        assertEquals(RenderingMode.BLEND_UNIFORM, brush.effectiveRenderingMode)
    }

    @Test
    fun aBrushWithoutRecursiveMixingImportsAsGlazeUniform() {
        val brush = ProcreateBrush.read(archive("B-PLAIN")).parameters
        assertFalse(brush.buildUp)
        assertEquals(RenderingMode.GLAZE_UNIFORM, brush.renderingMode)
    }
}
