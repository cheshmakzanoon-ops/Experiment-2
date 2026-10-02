package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class QuickShapeTest {
    @Test fun wobblyLineBecomesStraight() {
        val points = List(50) { i -> i * 4f to 100f + if (i % 2 == 0) 0.5f else -0.5f }
        val result = QuickShape.recognize(points)!!
        assertEquals(QuickShape.Kind.LINE, result.kind)
        assertEquals(points.first(), result.points.first())
        assertEquals(points.last(), result.points.last())
    }

    @Test fun roughCircleSnapsToCircle() {
        val points =
            List(80) { i ->
                val t = 2 * PI * i / 79
                val r = 100 + if (i % 3 == 0) 4 else -3
                (200 + r * cos(t)).toFloat() to (200 + r * sin(t)).toFloat()
            }
        val result = QuickShape.recognize(points)!!
        assertEquals(QuickShape.Kind.ELLIPSE, result.kind)
        result.points.forEach { (x, y) ->
            val r = hypot(x - 200f, y - 200f)
            assertTrue("radius $r", r in 95f..106f)
        }
    }

    @Test fun scribbleIsRejected() {
        val points = listOf(0f to 0f, 50f to 50f, 0f to 50f, 50f to 0f, 10f to 10f, 40f to 45f, 5f to 2f)
        assertNull(QuickShape.recognize(points))
    }

    @Test fun stabilizerLagsThenCatchesUp() {
        val stabilizer = StrokeStabilizer(1f)
        stabilizer.start(0f, 0f)
        val (x, _) = stabilizer.add(100f, 0f)
        assertTrue(x < 20f)
        val tail = stabilizer.finish(100f, 0f)
        assertEquals(100f to 0f, tail.last())
        val none = StrokeStabilizer(0f)
        none.start(0f, 0f)
        assertEquals(100f to 0f, none.add(100f, 0f))
        assertTrue(none.finish(100f, 0f).isEmpty())
    }
}
