package com.artflow.studio.data.renderer.opengl

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MeshWireTest {
    @Test
    fun eachTriangleBecomesThreeEdges() {
        val triangle = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val edges = edgeVertices(triangle, 3)
        val firstEdge = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f)
        val secondEdge = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
        val thirdEdge = floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f)
        val expected = firstEdge + secondEdge + thirdEdge
        assertArrayEquals(expected, edges, 0f)
    }
}
