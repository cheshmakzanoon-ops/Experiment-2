package com.artflow.studio.core.pixels

import com.artflow.studio.core.pixels.LiveAdjustments.Kind
import com.artflow.studio.core.pixels.LiveAdjustments.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAdjustmentsTest {
    private fun checker(): PixelBuffer =
        PixelBuffer(32, 32).also { buffer ->
            for (i in buffer.pixels.indices) {
                val x = i % 32
                val y = i / 32
                buffer.pixels[i] = if ((x / 4 + y / 4) % 2 == 0) 0xFFB0B0B0.toInt() else 0xFF404040.toInt()
            }
        }

    @Test fun zeroAmountLeavesPixelsUntouched() {
        val source = checker()
        Kind.entries.filter { it.slidesAmount }.forEach { kind ->
            assertTrue(kind.name, LiveAdjustments.apply(kind, source, Settings(0f)).pixels.contentEquals(source.pixels))
        }
    }

    @Test fun everyAmountEffectChangesTheImage() {
        val source = checker()
        Kind.entries.filter { it.slidesAmount }.forEach { kind ->
            assertFalse(kind.name, LiveAdjustments.apply(kind, source, Settings(0.6f)).pixels.contentEquals(source.pixels))
        }
    }

    @Test fun selectionLimitsTheEffect() {
        val source = checker()
        val mask = SelectionMask(32, 32).also { for (i in 0 until 32 * 16) it.coverage[i] = 255.toByte() }
        val out = LiveAdjustments.apply(Kind.GAUSSIAN_BLUR, source, Settings(0.5f), mask)
        for (i in 32 * 16 until 32 * 32) assertEquals(source.pixels[i], out.pixels[i])
        assertFalse(out.pixels.copyOfRange(0, 32 * 16).contentEquals(source.pixels.copyOfRange(0, 32 * 16)))
    }

    @Test fun colourAdjustmentsUseTheirParameters() {
        val source = PixelBuffer.filled(4, 4, 0xFF808080.toInt())
        val brighter = LiveAdjustments.apply(Kind.HUE_SATURATION_BRIGHTNESS, source, Settings(1f, mapOf("lightness" to 50f)))
        assertTrue(Channels.luminance(brighter.pixels[0]) > Channels.luminance(source.pixels[0]))
    }

    @Test fun bloomOnlyBrightens() {
        val source = checker()
        val out = LiveAdjustments.bloom(source, 0.8f)
        for (i in source.pixels.indices) assertTrue(Channels.luminance(out.pixels[i]) >= Channels.luminance(source.pixels[i]) - 1e-3f)
    }

    @Test fun recolorReplacesTheTouchedColourAndKeepsShading() {
        val red = 0xFFFF0000.toInt()
        val darkRed = 0xFF800000.toInt()
        val blue = 0xFF0000FF.toInt()
        // Red, then dark red, then a wide run of blue that the flood must not reach.
        val source = PixelBuffer(16, 1)
        for (x in 0 until 16) {
            source.pixels[x] =
                when {
                    x < 4 -> red
                    x < 6 -> darkRed
                    else -> blue
                }
        }
        val settings =
            LiveAdjustments.Settings(
                amount = 0.6f,
                parameters =
                    mapOf(
                        LiveAdjustments.RECOLOR_X to 0f,
                        LiveAdjustments.RECOLOR_Y to 0f,
                        LiveAdjustments.RECOLOR_RGB to 0x00FF00.toFloat(),
                    ),
            )
        val out = LiveAdjustments.apply(LiveAdjustments.Kind.RECOLOR, source, settings)
        val touched = out.pixels[0]
        assertTrue("Touched red turns green", ((touched shr 8) and 0xFF) > 200 && ((touched shr 16) and 0xFF) < 60)
        // The darker red stays darker after recolouring.
        assertTrue(((out.pixels[4] shr 8) and 0xFF) in 1 until 200)
        assertEquals("Blue is not within the flood", blue, out.pixels[12])
    }

    @Test fun perspectiveBlurStreaksTowardTheFocusPoint() {
        val source = PixelBuffer(40, 40)
        // A sharp vertical edge away from the centre.
        for (y in 0 until 40) for (x in 30 until 40) source.pixels[y * 40 + x] = 0xFF000000.toInt()
        val out = LiveAdjustments.apply(Kind.PERSPECTIVE_BLUR, source, Settings(1f))
        // Just inside the edge, samples reach back toward the centre and pick up the clear area.
        assertTrue("The edge streaks along the line to the focus", (out.pixels[20 * 40 + 31] ushr 24) < 255)
        assertEquals("The focus point stays sharp", source.pixels[20 * 40 + 20], out.pixels[20 * 40 + 20])
    }
}
