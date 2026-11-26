package com.artflow.studio.core.canvas

import com.artflow.studio.core.pixels.IntBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Procreate's Crop & Resize aspect lock: a locked box keeps its proportion and stays on the canvas. */
class CropBoxAspectTest {
    private val start = CropBox.Box(10f, 10f, 50f, 50f)
    private val corner = CropBox.Grip(right = true, bottom = true)

    private fun CropBox.Box.aspectIs(aspect: CropBox.Aspect): Boolean = kotlin.math.abs(width / height - aspect.ratio) < 0.001f

    @Test
    fun freeformDraggingIsUnchanged() {
        val box = CropBox.drag(start, corner, 20f, 10f, 100, 100)
        assertEquals(10f, box.left, 0f)
        assertEquals(70f, box.right, 0f)
        assertEquals(60f, box.bottom, 0f)
    }

    @Test
    fun aLockedCornerDragAnchorsTheOppositeCorner() {
        val box = CropBox.drag(start, corner, 20f, 10f, 100, 100, CropBox.Aspect.Square)
        assertEquals(10f, box.left, 0.01f)
        assertEquals(10f, box.top, 0.01f)
        assertEquals(70f, box.right, 0.01f)
        assertEquals(70f, box.bottom, 0.01f)
        assertTrue(box.aspectIs(CropBox.Aspect.Square))
    }

    @Test
    fun draggingOneEdgeGrowsTheOtherSideAroundTheCentre() {
        val box = CropBox.drag(CropBox.Box(50f, 50f, 150f, 150f), CropBox.Grip(right = true), 20f, 0f, 200, 200, CropBox.Aspect.Square)
        assertEquals(50f, box.left, 0.01f)
        assertEquals(170f, box.right, 0.01f)
        assertEquals(40f, box.top, 0.01f)
        assertEquals(160f, box.bottom, 0.01f)
    }

    @Test
    fun aLockedBoxNeverLeavesTheCanvas() {
        val box =
            CropBox.drag(
                CropBox.Box(60f, 60f, 100f, 100f),
                CropBox.Grip(left = true, top = true),
                -200f,
                -200f,
                100,
                100,
                CropBox.Aspect.FourThree,
            )
        assertTrue(box.left >= 0f && box.top >= 0f)
        assertTrue(box.right <= 100f && box.bottom <= 100f)
        assertTrue(box.aspectIs(CropBox.Aspect.FourThree))
    }

    @Test
    fun movingTheBoxIgnoresTheLock() {
        val box = CropBox.drag(start, CropBox.Grip(), 5f, 5f, 100, 100, CropBox.Aspect.ThreeTwo)
        assertEquals(15f, box.left, 0f)
        assertEquals(55f, box.right, 0f)
        assertEquals(55f, box.bottom, 0f)
    }

    @Test
    fun everyPresetKeepsItsProportion() {
        CropBox.Aspect.presets.forEach { preset ->
            val box = CropBox.drag(start, corner, 25f, 5f, 120, 120, preset)
            assertTrue("${preset.label} kept its ratio", box.aspectIs(preset))
        }
        assertEquals(listOf("1:1", "4:3", "3:2", "16:9"), CropBox.Aspect.presets.map { it.label })
    }

    @Test
    fun aLockedBoxFitsCanvasesSmallerThanThePreferredMinimum() {
        for ((width, height) in listOf(1 to 1, 10 to 4, 4 to 10)) {
            for (aspect in CropBox.Aspect.presets) {
                val box = CropBox.drag(CropBox.Box.of(width, height), corner, -100f, -100f, width, height, aspect)
                assertTrue(box.left >= 0f && box.top >= 0f)
                assertTrue(box.right <= width && box.bottom <= height)
                assertTrue(box.width > 0f && box.height > 0f)
                assertTrue(box.aspectIs(aspect))
            }
        }
    }

    @Test
    fun fittingALockedBoxKeepsItsOppositeCornerFixed() {
        val box =
            CropBox.drag(
                CropBox.Box(10f, 10f, 18f, 18f),
                corner,
                20f,
                20f,
                18,
                18,
                CropBox.Aspect.SixteenNine,
            )
        assertEquals(10f, box.left, 0f)
        assertEquals(10f, box.top, 0f)
        assertEquals(18f, box.right, 0f)
        assertTrue(box.aspectIs(CropBox.Aspect.SixteenNine))
    }

    @Test
    fun movingALockedCropOnAOnePixelWideCanvasKeepsAnApplicableCrop() {
        val locked = CropBox.drag(CropBox.Box.of(1, 4), corner, 0f, 0f, 1, 4, CropBox.Aspect.SixteenNine)
        val moved = CropBox.drag(locked, CropBox.Grip(), 0f, 0.8f, 1, 4, CropBox.Aspect.SixteenNine)
        assertEquals(IntBounds(0, 1, 0, 1), moved.toBounds())
    }
}
