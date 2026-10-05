package com.artflow.studio.core.three

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.zip.ZipInputStream

/** Fixtures were written by Pixar's USD library; expected positions come from its own transform maths. */
class UsdReaderTest {
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/models/$name")).use { it.readBytes() }

    private fun entry(
        zip: ByteArray,
        name: String,
    ): ByteArray =
        ZipInputStream(zip.inputStream()).use { input ->
            generateSequence { input.nextEntry }.first { it.name == name }
            input.readBytes()
        }

    private fun records(
        text: String,
        kind: String,
    ): List<List<Float>> = text.lines().filter { it.startsWith("$kind ") }.map { line -> line.split(' ').drop(1).map(String::toFloat) }

    private fun assertNear(
        expected: List<Float>,
        actual: List<Float>,
    ) {
        assertEquals(expected.size, actual.size)
        expected.indices.forEach { assertEquals("component $it of $actual", expected[it], actual[it], 1e-3f) }
    }

    @Test
    fun aUsdzBringsItsMeshTransformsAndDiffuseTexture() {
        val contents = ModelPackage.read(fixture("box.usdz"))
        val vertices = records(contents.objText, "v")
        // Scaled by 2 around a 5-unit offset, then turned from Z-up to Y-up.
        assertNear(listOf(10f, 0f, 0f), vertices[0])
        assertNear(listOf(12f, 0f, -2f), vertices[2])
        assertNear(listOf(0f, 0f), records(contents.objText, "vt")[0])
        assertEquals(12, ObjParser.parse(contents.objText).triangleCount)
        // The image connected to the surface's diffuse colour, not the roughness one.
        assertArrayEquals(entry(fixture("box.usdz"), "textures/wood.png"), contents.texture)
    }

    @Test
    fun rotationsMatricesAndVertexPrimvarsFollowUsd() {
        val contents = ModelPackage.read(fixture("ops.usdc"))
        val vertices = records(contents.objText, "v")
        assertNear(listOf(1.81195f, 4.81243f, -5.00261f), vertices[0])
        assertNear(listOf(1.63247f, 5.52159f, -5.00853f), vertices[1])
        assertNear(listOf(2.15609f, 3.95048f, -3.34962f), vertices[2])
        assertNear(listOf(-1.24811f, 2.99429f, -3.33422f), vertices[4])
        assertNear(listOf(0.5f / 3f, 0.25f / 3f), records(contents.objText, "vt")[0])
        assertEquals(2, ObjParser.parse(contents.objText).triangleCount)
        assertNull(contents.texture)
    }

    @Test
    fun quaternionOrientMatchesUsd() {
        val contents = ModelPackage.read(fixture("orient.usdc"))
        assertNear(listOf(0.42093f, 1.31927f, -0.80302f), records(contents.objText, "v")[0])
    }
}
