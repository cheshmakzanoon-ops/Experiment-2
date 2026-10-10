package com.artflow.studio.core.pixels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CheckpointedBlurTest {
    private class Superseded : RuntimeException()

    private fun canvas(size: Int) = PixelBuffer(size, size).also { it.pixels.fill(0xFF336699.toInt()) }

    @Test
    fun aGaussianBlurChecksOncePerRowOfEachPassOfEachChannel() {
        var calls = 0
        ImageFilters.gaussianBlur(canvas(40), 2f) { calls++ }
        // Four channels, two passes each, forty rows per pass.
        assertEquals(4 * 2 * 40, calls)
    }

    @Test
    fun aMotionBlurChecksOncePerRow() {
        var calls = 0
        ImageFilters.motionBlur(canvas(40), 6f, 0f) { calls++ }
        assertEquals(40, calls)
    }

    @Test
    fun aThrowFromTheCheckpointAbandonsTheGaussianBlurPartWay() {
        var calls = 0
        assertThrows(Superseded::class.java) {
            ImageFilters.gaussianBlur(canvas(400), 2f) { if (++calls == 10) throw Superseded() }
        }
        assertEquals(10, calls)
    }

    @Test
    fun aThrowFromTheCheckpointAbandonsTheMotionBlurPartWay() {
        var calls = 0
        assertThrows(Superseded::class.java) {
            ImageFilters.motionBlur(canvas(400), 6f, 0f) { if (++calls == 10) throw Superseded() }
        }
        assertEquals(10, calls)
    }

    @Test
    fun liveAdjustmentsPassesItsCheckpointToTheBlur() {
        var calls = 0
        assertThrows(Superseded::class.java) {
            LiveAdjustments.apply(
                LiveAdjustments.Kind.GAUSSIAN_BLUR,
                canvas(400),
                LiveAdjustments.Settings(amount = 1f),
                checkpoint = { if (++calls == 10) throw Superseded() },
            )
        }
        assertEquals(10, calls)
    }
}
