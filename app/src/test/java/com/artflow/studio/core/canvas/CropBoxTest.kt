package com.artflow.studio.core.canvas

import com.artflow.studio.core.pixels.IntBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropBoxTest {
    private val box = CropBox.Box(10f, 10f, 90f, 60f)

    @Test fun cornersEdgesAndBodyAreGrabbed() {
        assertEquals(CropBox.Grip(left = true, top = true), CropBox.grip(box, 12f, 9f, 4f))
        assertEquals(CropBox.Grip(right = true), CropBox.grip(box, 91f, 30f, 4f))
        assertTrue(CropBox.grip(box, 50f, 30f, 4f)!!.moves)
        assertNull(CropBox.grip(box, 150f, 150f, 4f))
    }

    @Test fun draggingStaysInsideTheCanvas() {
        val moved = CropBox.drag(box, CropBox.Grip(), 50f, -40f, 100, 80)
        assertEquals(CropBox.Box(20f, 0f, 100f, 50f), moved)
        val grown = CropBox.drag(box, CropBox.Grip(left = true, bottom = true), -30f, 40f, 100, 80)
        assertEquals(CropBox.Box(0f, 10f, 90f, 80f), grown)
        val pinched = CropBox.drag(box, CropBox.Grip(right = true), -200f, 0f, 100, 80)
        assertEquals(18f, pinched.right, 1e-3f)
    }

    @Test fun boundsCoverWholePixels() {
        assertEquals(IntBounds(10, 10, 89, 59), box.toBounds())
        assertEquals(IntBounds(0, 0, 99, 79), CropBox.Box.of(100, 80).toBounds())
    }
}
