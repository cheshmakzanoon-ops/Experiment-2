package com.artflow.studio.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class NonDestructiveTextTest {

    @Test
    fun transformUpdatesAffineParametersPreservingStyle() {
        val style = TextLayout.TextStyle(
            fontFamily = "Futura",
            fontSize = 48f,
            bold = true,
            letterSpacing = 2.5f,
            lineHeight = 1.4f,
        )

        val initial = TextLayerContent(
            text = "Procreate Parity",
            style = style,
            color = 0xFF112233.toInt(),
            x = 100f,
            y = 150f,
            rotationDegrees = 15f,
            scaleX = 1.0f,
            scaleY = 1.0f,
            baselineShift = 5f,
            kerning = 1.2f,
            tracking = 0.8f,
        )

        // Non-destructive transformation: translate by (25, -30), rotate by 45, scale by 1.5x
        val transformed = initial.transform(
            deltaX = 25f,
            deltaY = -30f,
            deltaRotation = 45f,
            scaleMultiplierX = 1.5f,
            scaleMultiplierY = 1.5f,
        )

        // Vector text properties preserved exactly
        assertEquals("Procreate Parity", transformed.text)
        assertEquals(0xFF112233.toInt(), transformed.color)
        assertEquals(style, transformed.style)

        // Affine geometry updated
        assertEquals(125f, transformed.x, 0.001f)
        assertEquals(120f, transformed.y, 0.001f)
        assertEquals(60f, transformed.rotationDegrees, 0.001f)
        assertEquals(1.5f, transformed.scaleX, 0.001f)
        assertEquals(1.5f, transformed.scaleY, 0.001f)
        assertEquals(5f, transformed.baselineShift, 0.001f)
        assertEquals(1.2f, transformed.kerning, 0.001f)
        assertEquals(0.8f, transformed.tracking, 0.001f)
    }

    @Test
    fun rotationWrapsModulo360() {
        val content = TextLayerContent(
            text = "Test",
            style = TextLayout.TextStyle(),
            color = 0,
            x = 0f,
            y = 0f,
            rotationDegrees = 350f,
        )

        val rotated = content.transform(deltaRotation = 30f)
        assertEquals(20f, rotated.rotationDegrees, 0.001f)
    }
}
