package com.artflow.studio.core.three

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    @Test
    fun textUsdReadsLikeItsBinaryForm() {
        for (name in listOf("ops", "orient")) {
            assertEquals(name, ModelPackage.read(fixture("$name.usdc")).objText, ModelPackage.read(fixture("$name.usda")).objText)
        }
        val binary = ModelPackage.read(fixture("two.usdz"))
        val text = ModelPackage.read(fixture("two_text.usdz"))
        assertEquals(binary.objText, text.objText)
        assertEquals(binary.textures.keys, text.textures.keys)
        binary.textures.forEach { (material, image) -> assertArrayEquals(material, image, text.textures[material]) }
    }

    @Test
    fun textUsdFollowsVariantsActivationTimeSamplesAndRelativePaths() {
        val contents = ModelPackage.read(fixture("scene.usdz"))
        val vertices = records(contents.objText, "v")
        // Only the selected variant's quad: the class, the inactive mesh and the other variant are left out.
        assertEquals(4, vertices.size)
        // Turned a quarter about Z by the orient, moved by the earliest translate sample, then made Y-up.
        assertNear(listOf(1f, 3f, -2f), vertices[0])
        assertNear(listOf(1f, 3f, -3f), vertices[1])
        assertNear(listOf(0f, 3f, -3f), vertices[2])
        assertNear(listOf(0f, 1f), records(contents.objText, "vt")[0])
        assertNear(listOf(0f, 1f, 0f), records(contents.objText, "vn")[0])
        assertEquals(2, ObjParser.parse(contents.objText).triangleCount)
        // The binding and the diffuse connection are relative paths.
        assertArrayEquals(entry(fixture("scene.usdz"), "textures/wood.png"), contents.texture)
        assertEquals(setOf("/World/Looks/Wood"), contents.textures.keys)
    }

    @Test
    fun aTruncatedTextFileIsRefused() {
        val bytes = entry(fixture("scene.usdz"), "scene.usda")
        for (length in listOf(10, bytes.size / 3, bytes.size / 2, bytes.size - 3)) {
            val failure = runCatching { ModelPackage.read(bytes.copyOf(length)) }.exceptionOrNull()
            assertTrue("length $length was read: $failure", failure is IllegalArgumentException)
        }
    }

    @Test
    fun aTruncatedFileIsRefused() {
        val bytes = fixture("ops.usdc")
        for (length in listOf(40, bytes.size / 2, bytes.size - 9)) {
            val failure = runCatching { ModelPackage.read(bytes.copyOf(length)) }.exceptionOrNull()
            assertTrue("length $length was read", failure != null)
        }
    }

    @Test
    fun aSectionClaimingFarMoreTextThanItsBlockCanHoldIsRefusedBeforeItIsAllocated() {
        // A TOKENS section that declares 10 MB of text from a two-byte LZ4 block. LZ4 cannot expand that far,
        // so the reader refuses it before allocating. Without the check it reads on and fails at the missing FIELDS.
        val crate = ByteArray(122)
        "PXR-USDC".toByteArray().copyInto(crate)
        crate[9] = 4 // minor version
        val words = ByteBuffer.wrap(crate).order(ByteOrder.LITTLE_ENDIAN)
        words.putLong(16, 64L) // table of contents
        words.putLong(64, 1L) // one section
        "TOKENS".toByteArray().copyInto(crate, 72)
        words.putLong(88, 96L) // TOKENS begins at 96
        words.putLong(96, 0L) // token count
        words.putLong(104, 10_000_000L) // uncompressed size
        words.putLong(112, 2L) // compressed size: a chunk byte and one empty LZ4 token
        val error = assertThrows(IllegalArgumentException::class.java) { UsdReader.read(crate) }
        assertEquals("The USD data is cut short", error.message)
    }
}
