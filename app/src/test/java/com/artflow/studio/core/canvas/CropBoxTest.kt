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

    @Test fun overlappingHandlesGrabOnlyTheNearestOpposingEdges() {
        val small = CropBox.Box(10f, 10f, 20f, 20f)
        assertEquals(CropBox.Grip(right = true, top = true), CropBox.grip(small, 19f, 11f, 40f))
        assertEquals(CropBox.Grip(left = true, bottom = true), CropBox.grip(small, 11f, 19f, 40f))
        assertEquals(CropBox.Grip(left = true, top = true), CropBox.grip(small, 15f, 15f, 40f))
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

    @Test fun aSupportedOnePixelCanvasCanBeDraggedWithoutCrossingItsEdges() {
        val tiny = CropBox.Box.of(1, 1)
        val grip = CropBox.grip(tiny, 1f, 1f, 40f)!!
        assertEquals(tiny, CropBox.drag(tiny, grip, -100f, -100f, 1, 1))
        assertEquals(tiny, CropBox.drag(tiny, CropBox.Grip(left = true, top = true), 100f, 100f, 1, 1))
    }

    @Test fun aNarrowCropKeepsItsWidthWhenItsOtherEdgeIsDragged() {
        val narrow = CropBox.Box(2f, 10f, 6f, 40f)
        val dragged = CropBox.drag(narrow, CropBox.Grip(left = true, bottom = true), 100f, -100f, 100, 100)
        assertEquals(CropBox.Box(2f, 10f, 6f, 18f), dragged)
    }

    @Test fun positiveSubpixelSpansStillProduceOnePixelBounds() {
        assertEquals(IntBounds(0, 1, 0, 1), CropBox.Box(0f, 0.8f, 1f, 1.3625f).toBounds())
        assertEquals(IntBounds(2, 0, 2, 0), CropBox.Box(2.6f, 0f, 3f, 1f).toBounds())
    }
}
