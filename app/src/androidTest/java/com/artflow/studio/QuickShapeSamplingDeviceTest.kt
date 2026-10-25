package com.artflow.studio

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.canvas.QuickShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Checks pen-cadence-independent shape fitting on the actual Android runtime in each CI lane. */
@RunWith(AndroidJUnit4::class)
class QuickShapeSamplingDeviceTest {
    @Test
    fun clusteredStylusSamplesStillSnapToAccurateOval() {
        val points =
            List(241) { i ->
                val fraction = i / 240.0
                val angle = 2 * PI * fraction * fraction * fraction
                (250 + 120 * cos(angle)).toFloat() to (180 + 80 * sin(angle)).toFloat()
            }
        val result = requireNotNull(QuickShape.recognize(points))
        assertEquals(QuickShape.Kind.ELLIPSE, result.kind)
        val cx = (result.nodes[0].first + result.nodes[2].first) / 2f
        val cy = (result.nodes[0].second + result.nodes[2].second) / 2f
        assertTrue("centre X: $cx", abs(cx - 250f) < 4f)
        assertTrue("centre Y: $cy", abs(cy - 180f) < 4f)
    }
}
