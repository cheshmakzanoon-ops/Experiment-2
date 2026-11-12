package com.artflow.studio.core.tool

import com.artflow.studio.core.canvas.PalmRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquifyFreezeMaskTest {
    @Test
    fun frozenPixelsResistDisplacement() {
        val width = 64
        val height = 64
        val freezeMask = BooleanArray(width * height)

        // Freeze circle at center (32, 32) with radius 12
        LiquifyTool.paintFreezeMask(
            freezeMask = freezeMask,
            width = width,
            height = height,
            centerX = 32f,
            centerY = 32f,
            radius = 12f,
            frozen = true,
        )

        assertTrue("Freeze percent should be greater than zero", LiquifyTool.frozenPercent(freezeMask) > 0)
        assertTrue("Center pixel should be frozen", freezeMask[32 * width + 32])

        val settings =
            LiquifyTool.Settings(
                mode = LiquifyTool.Mode.PUSH,
                size = 40f,
                strength = 0.8f,
                freezeMask = freezeMask,
            )

        val session =
            LiquifyTool.beginSession(
                x = 20f,
                y = 32f,
                settings = settings,
                width = width,
                height = height,
            )

        // Drag right across the frozen region
        session.dragTo(45f, 32f)

        // Center pixel is frozen, so displacement must be zero
        val centerIndex = 32 * width + 32
        assertEquals(0f, session.map.offsetX[centerIndex], 0.0001f)
        assertEquals(0f, session.map.offsetY[centerIndex], 0.0001f)
        assertFalse(session.map.hasDisplacement(centerIndex))

        // Outside frozen circle (e.g. at 24, 20), displacement should occur
        assertTrue(session.map.dirtyBounds != null)
    }

    @Test
    fun dynamicFreezeAndThawModesInSession() {
        val width = 64
        val height = 64
        val freezeMask = BooleanArray(width * height)

        val freezeSettings =
            LiquifyTool.Settings(
                mode = LiquifyTool.Mode.FREEZE,
                size = 20f,
                freezeMask = freezeMask,
            )

        val session =
            LiquifyTool.beginSession(
                x = 10f,
                y = 10f,
                settings = freezeSettings,
                width = width,
                height = height,
            )

        // Dynamic freeze stroke
        session.dragTo(15f, 15f)
        val frozenIndex = 12 * width + 12
        assertTrue("Pixel should be dynamically frozen by FREEZE mode", session.settings.freezeMask!![frozenIndex])

        // Thaw it with UNFREEZE
        val thawSettings =
            LiquifyTool.Settings(
                mode = LiquifyTool.Mode.UNFREEZE,
                size = 20f,
                freezeMask = session.settings.freezeMask,
            )
        val thawSession =
            LiquifyTool.beginSession(
                x = 10f,
                y = 10f,
                settings = thawSettings,
                width = width,
                height = height,
            )
        thawSession.dragTo(15f, 15f)
        assertFalse("Pixel should be dynamically thawed by UNFREEZE mode", thawSession.settings.freezeMask!![frozenIndex])
    }

    @Test
    fun touchMajorPalmRejection() {
        val palm = PalmRejection()

        // Normal fine stylus or fingertip touch (touchMajor = 15px) -> NOT palm
        assertFalse(palm.rejectsTouchMajor(15f, thresholdMajorPx = 50f))

        // Broad resting palm contact (touchMajor = 75px) -> REJECTED
        assertTrue(palm.rejectsTouchMajor(75f, thresholdMajorPx = 50f))
    }
}
