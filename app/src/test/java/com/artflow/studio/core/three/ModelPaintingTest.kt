package com.artflow.studio.core.three

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPaintingTest {
    // A square facing the camera at +z, from (-1,-1) to (1,1), with UVs covering the whole texture.
    private val square =
        """
        # a quad
        v -1 -1 0
        v 1 -1 0
        v 1 1 0
        v -1 1 0
        vt 0 0
        vt 1 0
        vt 1 1
        vt 0 1
        f 1/1 2/2 3/3 4/4
        """.trimIndent()

    @Test fun quadsBecomeTrianglesWithNormalsAndAFittedSize() {
        val mesh = ObjParser.parse(square)
        assertEquals(2, mesh.triangleCount)
        // Face normals point out of the square, towards +z.
        assertEquals(1f, mesh.normals[2], 1e-5f)
        // The square's corners sit on the unit sphere after fitting.
        val x = mesh.positions[0]
        val y = mesh.positions[1]
        assertEquals(1f, x * x + y * y, 1e-4f)
        val negative = ObjParser.parse(square.replace("f 1/1 2/2 3/3 4/4", "f -4/-4 -3/-3 -2/-2"))
        assertEquals(1, negative.triangleCount)
    }

    @Test fun modelsWithoutTextureCoordinatesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { ObjParser.parse("v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3") }
        assertThrows(IllegalArgumentException::class.java) { ObjParser.parse("v 0 0 0\nf 1 2 3") }
        assertThrows(IllegalArgumentException::class.java) { ObjParser.parse("# nothing") }
    }

    @Test fun aRayThroughTheCentreHitsTheMiddleOfTheTexture() {
        val mesh = ObjParser.parse(square)
        val camera = OrbitCamera(yaw = 0f, pitch = 0f, distance = 3f)
        val (origin, direction) = camera.ray(50f, 50f, 100f, 100f)
        val uv = requireNotNull(MeshPicker.pick(mesh, origin, direction))
        assertEquals(0.5f, uv.first, 1e-3f)
        assertEquals(0.5f, uv.second, 1e-3f)
        // Up and to the right on screen is towards u = 1, v = 1.
        val (o2, d2) = camera.ray(60f, 40f, 100f, 100f)
        val corner = requireNotNull(MeshPicker.pick(mesh, o2, d2))
        assertTrue(corner.first > 0.5f && corner.second > 0.5f)
        // Looking from behind the model's edge-on side misses it.
        val (o3, d3) = OrbitCamera(yaw = 1.5708f, pitch = 0f, distance = 3f).ray(0f, 0f, 100f, 100f)
        assertNull(MeshPicker.pick(mesh, o3, d3))
    }
}
