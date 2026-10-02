package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewTransformTest {
    @Test fun viewCentreMapsToCanvasCentre() {
        val view = ViewTransform(scale = 2f, offsetX = 0f, offsetY = 0f, rotationDegrees = 37f)
        val at = view.toCanvas(Offset(500f, 300f), 1000f, 600f, 400, 200)
        assertEquals(200f, at.x, 1e-3f)
        assertEquals(100f, at.y, 1e-3f)
    }

    @Test fun scaleAndPanAreUndone() {
        val view = ViewTransform(scale = 2f, offsetX = 50f, offsetY = -20f, rotationDegrees = 0f)
        // 40 view px right of the shifted centre is 20 canvas px right of the canvas centre.
        val at = view.toCanvas(Offset(500f + 50f + 40f, 300f - 20f), 1000f, 600f, 400, 200)
        assertEquals(220f, at.x, 1e-3f)
        assertEquals(100f, at.y, 1e-3f)
    }

    @Test fun rotationIsUndone() {
        val view = ViewTransform(scale = 1f, offsetX = 0f, offsetY = 0f, rotationDegrees = 90f)
        // With the canvas turned 90° clockwise, a point below the centre came from the canvas's right.
        val at = view.toCanvas(Offset(500f, 310f), 1000f, 600f, 400, 200)
        assertEquals(210f, at.x, 1e-3f)
        assertEquals(100f, at.y, 1e-3f)
    }
}
