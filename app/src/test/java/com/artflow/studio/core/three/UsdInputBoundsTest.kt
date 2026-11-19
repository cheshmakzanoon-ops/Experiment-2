package com.artflow.studio.core.three

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UsdInputBoundsTest {
    private fun triangle(
        counts: String = "3",
        indices: String = "0, 1, 2",
        extra: String = "",
    ): ByteArray =
        """
        #usda 1.0
        def Mesh "Triangle" {
            point3f[] points = [(0, 0, 0), (1, 0, 0), (0, 1, 0)]
            int[] faceVertexCounts = [$counts]
            int[] faceVertexIndices = [$indices]
            texCoord2f[] primvars:st = [(0, 0), (1, 0), (0, 1)]
            $extra
        }
        """.trimIndent().toByteArray()

    @Test
    fun trailingFaceCornersAreRefusedInsteadOfSilentlyDropped() {
        assertThrows(IllegalArgumentException::class.java) {
            UsdReader.read(triangle(indices = "0, 1, 2, 0"))
        }
    }

    @Test
    fun fractionalTopologyValuesAreRefusedInsteadOfRounded() {
        assertThrows(IllegalArgumentException::class.java) {
            UsdReader.read(triangle(counts = "3.75"))
        }
    }

    @Test
    fun missingIndexedUvEntriesCannotFallBackToUnindexedCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            UsdReader.read(triangle(extra = "int[] primvars:st:indices = [0]"))
        }
    }

    @Test
    fun shortCrateTokenDecompressionCannotInventAnEmptyToken() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/models/ops.usdc")).use { it.readBytes() }
        val words = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val toc = words.getLong(16).toInt()
        val section =
            (0 until words.getLong(toc).toInt()).first { index ->
                String(bytes, toc + 8 + index * 32, 16).trimEnd('\u0000') == "TOKENS"
            }
        val start = words.getLong(toc + 8 + section * 32 + 16).toInt()
        // The compressed block is unchanged. Previously the extra byte was an invented zero,
        // accepted as another empty token, while the rest of the real producer scene still loaded.
        words.putLong(start, words.getLong(start) + 1)
        words.putLong(start + 8, words.getLong(start + 8) + 1)
        assertThrows(IllegalArgumentException::class.java) { UsdReader.read(bytes) }
    }

    @Test
    fun rawUsdSharesThePackageAllocationBudget() {
        val bytes = triangle()
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ModelPackage.read(bytes, bytes.size.toLong() + 64)
            }
        assertTrue(error.message.orEmpty().contains("memory"))
    }

    @Test
    fun usdzExpansionAndSceneDecodingSpendOneBudget() {
        val scene = triangle()
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { out ->
            out.putNextEntry(ZipEntry("scene.usda"))
            out.write(scene)
            out.closeEntry()
        }
        val bytes = archive.toByteArray()
        // Enough for the encoded archive, the ZIP workspace and expansion, but not the USD tree.
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                ModelPackage.read(bytes, bytes.size + 64 * 1024L + scene.size * 3L + 256)
            }
        assertTrue(error.message.orEmpty().contains("memory"))
    }

    @Test
    fun smallValidSceneStillKeepsEveryVertexUvAndFace() {
        val result = ModelPackage.read(triangle(), 128 * 1024L)
        assertEquals(3, result.objText.lineSequence().count { it.startsWith("v ") })
        assertEquals(3, result.objText.lineSequence().count { it.startsWith("vt ") })
        assertEquals(1, result.objText.lineSequence().count { it.startsWith("f ") })
    }
}
