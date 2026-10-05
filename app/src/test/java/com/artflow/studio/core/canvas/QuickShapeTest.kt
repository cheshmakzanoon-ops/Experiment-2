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

    @Test fun roughRectangleSnapsToExactRectangle() {
        val corners = listOf(0f to 0f, 120f to 3f, 118f to 80f, -2f to 78f, 1f to 1f)
        val points =
            corners.zipWithNext().flatMap { (a, b) ->
                List(20) { i ->
                    val t = i / 20f
                    (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
                }
            }
        val result = QuickShape.recognize(points)!!
        assertEquals(QuickShape.Kind.POLYGON, result.kind)
        val xs = result.points.map { it.first }
        val ys = result.points.map { it.second }
        assertTrue("about 120 wide", (xs.max() - xs.min()) in 110f..130f)
        assertTrue("about 80 tall", (ys.max() - ys.min()) in 70f..90f)
    }

    @Test fun gentleArcIsNotForcedIntoCorners() {
        val points =
            List(60) { i ->
                val t = PI * i / 59
                (200 + 150 * cos(t)).toFloat() to (200 - 40 * sin(t)).toFloat()
            }
        val result = QuickShape.recognize(points)
        assertTrue(result == null || result.kind != QuickShape.Kind.POLYLINE)
    }

    @Test fun snappedLineFollowsThePenAndSnapsToRightAngles() {
        val line = QuickShape.recognize(List(30) { i -> i * 5f to 50f })!!
        // Dragging nearly straight down from the start snaps the line to vertical.
        val adjusted = QuickShape.adjust(line, line.points.last(), 3f to 148f)
        assertEquals(0f to 50f, adjusted.points.first())
        val end = adjusted.points.last()
        assertEquals(0f, end.first, 0.5f)
    }

    private fun roughCircle(
        cx: Float,
        cy: Float,
        r: Float,
    ): List<Pair<Float, Float>> =
        List(80) { i ->
            val t = 2 * PI * i / 79
            (cx + (r + if (i % 3 == 0) 2 else -2) * cos(t)).toFloat() to (cy + (r + if (i % 3 == 0) 2 else -2) * sin(t)).toFloat()
        }

    private fun near(
        expected: Pair<Float, Float>,
        actual: Pair<Float, Float>,
        tolerance: Float = 0.01f,
    ) {
        assertEquals("x of $actual", expected.first, actual.first, tolerance)
        assertEquals("y of $actual", expected.second, actual.second, tolerance)
    }

    @Test fun recognisedShapesCarryTheirEditNodes() {
        val line = QuickShape.recognize(List(50) { i -> i * 4f to 100f })!!
        assertEquals(listOf(0f to 100f, 196f to 100f), line.nodes)
        val circle = QuickShape.recognize(roughCircle(200f, 200f, 100f))!!
        assertEquals(4, circle.nodes.size)
        // Opposite nodes sit across the fitted centre, on the drawn outline; neighbours are a quarter turn apart.
        val cx = (circle.nodes[0].first + circle.nodes[2].first) / 2
        val cy = (circle.nodes[0].second + circle.nodes[2].second) / 2
        near(200f to 200f, cx to cy, 3f)
        val radius = hypot(circle.points[0].first - cx, circle.points[0].second - cy)
        circle.nodes.forEach { assertEquals(radius, hypot(it.first - cx, it.second - cy), 0.01f) }
        val a = circle.nodes[0].first - cx to circle.nodes[0].second - cy
        val b = circle.nodes[1].first - cx to circle.nodes[1].second - cy
        assertEquals(0f, a.first * b.first + a.second * b.second, 1f)
    }

    @Test fun movingALineEndRedrawsTheLine() {
        val line = QuickShape.recognize(List(50) { i -> i * 4f to 100f })!!
        val moved = QuickShape.moveNode(line, 1, 100f to 300f)
        assertEquals(QuickShape.Kind.LINE, moved.kind)
        assertEquals(0f to 100f, moved.points.first())
        assertEquals(100f to 300f, moved.points.last())
        assertEquals(listOf(0f to 100f, 100f to 300f), moved.nodes)
    }

    @Test fun movingAPolygonCornerKeepsItClosedThroughTheNewCorner() {
        val square = QuickShape.Result(QuickShape.Kind.POLYGON, emptyList(), listOf(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f))
        val moved = QuickShape.moveNode(square, 2, 150f to 160f)
        assertEquals(moved.points.first(), moved.points.last())
        assertTrue(moved.points.any { it == 150f to 160f })
        // Every outline point lies on one of the four new sides.
        val corners = moved.nodes + moved.nodes.first()
        moved.points.forEach { p ->
            val onSide =
                corners.zipWithNext().any { (s, e) ->
                    val cross = (e.first - s.first) * (p.second - s.second) - (e.second - s.second) * (p.first - s.first)
                    kotlin.math.abs(cross) / hypot(e.first - s.first, e.second - s.second) < 0.01f
                }
            assertTrue("$p is off the outline", onSide)
        }
    }

    @Test fun draggingAnEllipseNodeKeepsTheOppositeEndAndTheOtherAxis() {
        val ellipse = QuickShape.Result(QuickShape.Kind.ELLIPSE, emptyList(), listOf(150f to 100f, 100f to 130f, 50f to 100f, 100f to 70f))
        // Pull the right end of the long axis up and out: the shape grows and turns about its left end.
        val moved = QuickShape.moveNode(ellipse, 0, 210f to 20f)
        near(210f to 20f, moved.nodes[0])
        near(50f to 100f, moved.nodes[2])
        val cx = 130f
        val cy = 60f
        listOf(1, 3).forEach { assertEquals(30f, hypot(moved.nodes[it].first - cx, moved.nodes[it].second - cy), 0.01f) }
        val u = moved.nodes[0].first - cx to moved.nodes[0].second - cy
        val v = moved.nodes[1].first - cx to moved.nodes[1].second - cy
        assertEquals(0f, u.first * v.first + u.second * v.second, 0.05f)
        // Every outline point is on the new ellipse.
        val ru = hypot(u.first, u.second)
        moved.points.forEach { (x, y) ->
            val along = ((x - cx) * u.first + (y - cy) * u.second) / ru
            val across = ((x - cx) * v.first + (y - cy) * v.second) / 30f
            assertEquals(1f, (along / ru) * (along / ru) + (across / 30f) * (across / 30f), 0.01f)
        }
        near(moved.nodes[0], moved.points.first(), 0.01f)
    }

    @Test fun nodesAreFoundWithinReachOnly() {
        val line = QuickShape.Result(QuickShape.Kind.LINE, emptyList(), listOf(0f to 0f, 100f to 0f))
        assertEquals(1, QuickShape.nodeAt(line, 92f to 4f, 12f))
        assertEquals(0, QuickShape.nodeAt(line, 5f to -5f, 12f))
        assertNull(QuickShape.nodeAt(line, 50f to 0f, 12f))
    }

    @Test fun adjustingWhileHoldingMovesTheNodesWithTheOutline() {
        val circle = QuickShape.recognize(roughCircle(200f, 200f, 100f))!!
        val adjusted = QuickShape.adjust(circle, 300f to 200f, 400f to 200f)
        val cx = (adjusted.nodes[0].first + adjusted.nodes[2].first) / 2
        val cy = (adjusted.nodes[0].second + adjusted.nodes[2].second) / 2
        val radius = hypot(adjusted.points[0].first - cx, adjusted.points[0].second - cy)
        assertTrue("doubled radius $radius", radius > 190f)
        adjusted.nodes.forEach { assertEquals(radius, hypot(it.first - cx, it.second - cy), 0.05f) }
    }
}
