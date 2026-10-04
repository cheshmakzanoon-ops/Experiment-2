package com.artflow.studio.core.three

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceStrokeTest {
    // Two quads side by side sharing the edge x = 0. In space they meet; in the texture the left
    // one covers u 0..0.4 and the right one u 0.6..1, so the shared edge is a seam.
    private val seamed =
        """
        v -1 -1 0
        v 0 -1 0
        v 0 1 0
        v -1 1 0
        v 1 -1 0
        v 1 1 0
        vt 0 0
        vt 0.4 0
        vt 0.4 1
        vt 0 1
        vt 0.6 0
        vt 1 0
        vt 1 1
        vt 0.6 1
        f 1/1 2/2 3/3 4/4
        f 2/5 5/6 6/7 3/8
        """.trimIndent()

    @Test fun trianglesThatShareTextureEdgesFormOneIslandAndSeamsSplitThem() {
        val mesh = ObjParser.parse(seamed)
        val islands = mesh.islands
        assertEquals(4, islands.size)
        assertEquals(islands[0], islands[1])
        assertEquals(islands[2], islands[3])
        assertTrue(islands[0] != islands[2])
        val joined = ObjParser.parse(seamed.replace("f 2/5 5/6 6/7 3/8", "f 2/2 5/6 6/7 3/3"))
        assertEquals(1, joined.islands.distinct().size)
    }

    @Test fun aStrokeAcrossASeamStopsAtTheSeamAndCarriesOnFromIt() {
        val mesh = ObjParser.parse(seamed)
        val camera = OrbitCamera(yaw = 0f, pitch = 0f, distance = 3f)
        val calls = mutableListOf<String>()
        val points = mutableListOf<Pair<Float, Float>>()
        val sink =
            object : SurfaceSink {
                override fun begin(
                    u: Float,
                    v: Float,
                    pressure: Float,
                ) {
                    calls += "begin"
                    points += u to v
                }

                override fun move(
                    u: Float,
                    v: Float,
                    pressure: Float,
                ) {
                    calls += "move"
                    points += u to v
                }

                override fun end() {
                    calls += "end"
                }
            }
        val stroker =
            SurfaceStroker(
                pick = { x, y ->
                    val (origin, direction) = camera.ray(x, y, 200f, 200f)
                    MeshPicker.hit(mesh, origin, direction)
                },
                sink = sink,
            )
        stroker.down(70f, 100f, 1f)
        stroker.move(130f, 100f, 1f)
        stroker.up()
        assertEquals(2, calls.count { it == "begin" })
        assertEquals(2, calls.count { it == "end" })
        val lastLeft = points.filter { it.first <= 0.4f }.maxOf { it.first }
        val firstRight = points.filter { it.first >= 0.6f }.minOf { it.first }
        assertEquals(0.4f, lastLeft, 0.01f)
        assertEquals(0.6f, firstRight, 0.01f)
        assertTrue(points.none { it.first > 0.41f && it.first < 0.59f })
    }

    @Test fun leavingTheModelEndsTheStrokeAndComingBackStartsANewOne() {
        val mesh = ObjParser.parse(seamed)
        val camera = OrbitCamera(yaw = 0f, pitch = 0f, distance = 3f)
        var begins = 0
        var ends = 0
        val stroker =
            SurfaceStroker(
                pick = { x, y ->
                    val (origin, direction) = camera.ray(x, y, 200f, 200f)
                    MeshPicker.hit(mesh, origin, direction)
                },
                sink =
                    object : SurfaceSink {
                        override fun begin(
                            u: Float,
                            v: Float,
                            pressure: Float,
                        ) {
                            begins++
                        }

                        override fun move(
                            u: Float,
                            v: Float,
                            pressure: Float,
                        ) = Unit

                        override fun end() {
                            ends++
                        }
                    },
            )
        stroker.down(80f, 100f, 1f)
        stroker.move(80f, 2f, 1f)
        stroker.move(80f, 100f, 1f)
        stroker.up()
        assertEquals(2, begins)
        assertEquals(2, ends)
    }
}
