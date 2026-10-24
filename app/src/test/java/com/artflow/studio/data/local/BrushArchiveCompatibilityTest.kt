package com.artflow.studio.data.local

import com.artflow.studio.core.export.ProcreateBrush
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.PressureMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BrushArchiveCompatibilityTest {
    private val image: (ByteArray) -> PixelBuffer? = { bytes ->
        PixelBuffer(8, 8, IntArray(64) { i -> if (i % 8 < bytes[0].toInt()) -1 else 0xFF000000.toInt() })
    }

    @Test fun compoundArchiveImportsAsOneBrushWithBothImagePairs() {
        val result =
            BrushArchives.read(
                zip(
                    "Brush.archive" to primary(),
                    "Shape.png" to byteArrayOf(2),
                    "Grain.png" to byteArrayOf(3),
                    "Sub01/Brush.archive" to secondary(),
                    "Sub01/Shape.png" to byteArrayOf(4),
                    "Sub01/Grain.png" to byteArrayOf(5),
                ),
                image,
            )
        assertEquals(1, result.size)
        val imported = result.single()
        assertEquals("Synthetic primary", imported.name)
        assertEquals("Synthetic secondary", imported.secondary?.name)
        val stored = mutableListOf<String>()
        val params = imported.parameters { tile -> CustomGrains.idFor(tile).also { stored.add(it) } }
        assertEquals(4, stored.distinct().size)
        assertEquals(stored.toSet(), params.imageIds.toSet())
        assertNotNull(params.dual)
        assertEquals(28, params.dual?.sourceBlendMode)
        assertEquals(0.25f, requireNotNull(params.dual).params.opacity, 0.00001f)
        assertEquals(0.1f, params.dual.params.textureScale, 0.00001f)
        assertNull(params.dual.params.dual)
    }

    @Test fun setOrderIncludesPrimariesOnlyAndDoesNotPromoteSub01() {
        val order = "<plist><dict><key>brushes</key><array><string>B</string><string>A</string><string>B</string></array></dict></plist>"
        val brushes =
            BrushArchives.read(
                zip(
                    "brushset.plist" to order.toByteArray(),
                    "A/Brush.archive" to primary(),
                    "A/Sub01/Brush.archive" to secondary(),
                    "B/Brush.archive" to secondary(),
                ),
            ) { null }
        assertEquals(listOf("Synthetic secondary", "Synthetic primary"), brushes.map { it.name })
        assertNull(brushes[0].secondary)
        assertNotNull(brushes[1].secondary)
    }

    @Test fun signedValuesNormalizedAnglesAndIndependentCurvesSurviveParsing() {
        val params = ProcreateBrush.read(primary()).parameters
        assertEquals(-0.75f, params.pressureToSize, 0.00001f)
        assertEquals(-0.25f, params.pressureToFlow, 0.00001f)
        assertEquals(-0.4f, params.velocityToSize, 0.00001f)
        assertEquals(-0.5f, params.velocityToOpacity, 0.00001f)
        assertEquals(270f, params.rotation, 0.0001f)
        assertTrue(params.tipFlipXJitter && params.tipFlipYJitter)
        assertFalse(params.tipFlipX || params.tipFlipY)
        val response = requireNotNull(params.importedPressure)
        assertEquals(listOf(0f, 0.2f, 1f), response.overall?.points?.map { it.input })
        val overall = requireNotNull(response.overall)
        val size = requireNotNull(response.size)
        val flow = requireNotNull(response.flow)
        for (mapping in listOf(overall, size, flow)) {
            assertEquals(PressureMapping.Interpolation.NATIVE_SPLINE_1024, mapping.interpolation)
        }
        // Independent rational model in tools/native_pressure_spline.py, using the decoded
        // fixture points: overall[(0,.2),(.2,.7),(1,.9)], size[(0,1),(1,0)], flow[(0,.4),(1,.8)].
        assertEquals(Float.fromBits(0x3f3345b8), overall.map(0.2f), 0.00001f)
        assertEquals(Float.fromBits(0x3f4d0cdd), size.map(0.2f), 0.00001f)
        assertEquals(Float.fromBits(0x3ef5f5d0), flow.map(0.2f), 0.00001f)
    }

    @Test fun malformedCurvesDoNotBecomeNonFiniteDynamics() {
        val params = ProcreateBrush.read(Base64.getDecoder().decode(INVALID_CURVES)).parameters
        assertNull(params.importedPressure?.size)
        assertNull(params.importedPressure?.opacity)
        assertNull(params.importedPressure?.flow)
        assertTrue(params.calculateEffectiveSize(0.5f).isFinite())
    }

    @Test fun orphanNestedOrUnreadableSecondaryIsRejected() {
        val invalid =
            listOf(
                zip("Sub01/Brush.archive" to secondary()),
                zip("Brush.archive" to primary(), "Sub01/Sub01/Brush.archive" to secondary()),
                zip("Brush.archive" to primary(), "Sub01/Brush.archive" to byteArrayOf(1, 2)),
            )
        for (bytes in invalid) assertThrows(IllegalArgumentException::class.java) { BrushArchives.read(bytes) { null } }
    }

    @Test fun ambiguousAndTraversalPathsAreRejected() {
        for (path in listOf("../Brush.archive", "/Brush.archive", "A/../Brush.archive", "A//Brush.archive", "A\\Brush.archive")) {
            assertThrows(IllegalArgumentException::class.java) { BrushArchives.read(zip(path to primary())) { null } }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrushArchives.read(zip("Brush.archive" to primary(), "brush.archive" to secondary())) { null }
        }
    }

    @Test fun ignoredZipMembersCannotBypassTheExpandedBudget() {
        val oversized = zip("Brush.archive" to primary(), "QuickLook/ignored.bin" to ByteArray(16 * 1024 * 1024 + 1))
        assertTrue(oversized.size < 32_000)
        assertThrows(IllegalArgumentException::class.java) { BrushArchives.read(oversized) { null } }
        assertThrows(IllegalArgumentException::class.java) {
            BrushArchives.read(zip("Brush.archive" to ByteArray(2 * 1024 * 1024 + 1))) { null }
        }
    }

    @Test fun pickerInputIsBoundedBeforeArchiveDecoding() {
        val bytes = byteArrayOf(1, 2, 3)
        assertTrue(BrushArchives.readInput(ByteArrayInputStream(bytes)).contentEquals(bytes))
        val excessive =
            object : java.io.InputStream() {
                override fun read(): Int = 0

                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = length
            }
        assertThrows(IllegalArgumentException::class.java) { BrushArchives.readInput(excessive) }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun primary(): ByteArray = Base64.getDecoder().decode(PRIMARY)

    private fun secondary(): ByteArray = Base64.getDecoder().decode(SECONDARY)

    companion object {
        // Original synthetic NSKeyedArchiver fixtures, not reference application assets.
        private const val PRIMARY =
            "YnBsaXN0MDDUAQIDBAUGBwpZJGFyY2hpdmVyWCR2ZXJzaW9uVCR0b3BYJG9iamVjdHNfEA9OU0tleWVkQXJjaGl2ZXISAAGGoNEI" +
                "CVRyb290gA+vEBALDA0ODxAWGRobHyEiIycpVSRudWxsXxARU3ludGhldGljIHByaW1hcnlYezAsIDAuMn1YezEsIDAuOX1aezAu" +
                "MiwgMC43fdERElpOUy5vYmplY3RzoxMUFYACgAOABNEXGFZwb2ludHOABVZ7MCwgMX1WezEsIDB90REcoh0egAeACNEXIIAJWHsw" +
                "LCAwLjR9WHsxLCAwLjh90REkoiUmgAuADNEXKIAN3CorLC0uLzAxMjM0NTY3ODk6Ozw9PT4/QFRuYW1lXxAUZHluYW1pY3NQcmVz" +
                "c3VyZVNpemVfEBdkeW5hbWljc1ByZXNzdXJlT3BhY2l0eV8QEWR5bmFtaWNzU3BlZWRTaXplXxAUZHluYW1pY3NTcGVlZE9wYWNp" +
                "dHlac2hhcGVBbmdsZV1kdWFsQmxlbmRNb2RlXxAQc2hhcGVGbGlwWEppdHRlcl8QEHNoYXBlRmxpcFlKaXR0ZXJfEBdkeW5hbWlj" +
                "c1ByZXNzdXJlT3ZlcmFsbF8QGWR5bmFtaWNzUHJlc3N1cmVTaXplQ3VydmVfEBxkeW5hbWljc1ByZXNzdXJlT3BhY2l0eUN1cnZl" +
                "gAEjv+gAAAAAAAAjv9AAAAAAAAAjv9mZmZmZmZojv+AAAAAAAAAjv/kh+1RELRgQHAmABoAKgA4ACAARABsAJAApADIARABJAEwA" +
                "UQBTAGYAbACAAIkAkgCdAKAAqwCvALEAswC1ALgAvwDBAMgAzwDSANUA1wDZANwA3gDnAPAA8wD2APgA+gD9AP8BGAEdATQBTgFi" +
                "AXkBhAGSAaUBuAHSAe4CDQIPAhgCIQIqAjMCPAI+Aj8CQQJDAAAAAAAAAgEAAAAAAAAAQQAAAAAAAAAAAAAAAAAAAkU="
        private const val SECONDARY =
            "YnBsaXN0MDDUAQIDBAUGBwpZJGFyY2hpdmVyWCR2ZXJzaW9uVCR0b3BYJG9iamVjdHNfEA9OU0tleWVkQXJjaGl2ZXISAAGGoNEI" +
                "CVRyb290gAKjCwwNVSRudWxsXxATU3ludGhldGljIHNlY29uZGFyedUODxAREhMUFRYXVG5hbWVccGFpbnRPcGFjaXR5WnNoYXBl" +
                "QW5nbGVcdGV4dHVyZVNjYWxlWnNoYXBlQ291bnSAASM/0AAAAAAAACO/0zMzMzMzMyM/uZmZmZmZmiM/6ZmZmZmZmggRGyQpMkRJ" +
                "TFFTV11zfoOQm6iztb7H0AAAAAAAAAEBAAAAAAAAABgAAAAAAAAAAAAAAAAAAADZ"
        private const val INVALID_CURVES =
            "YnBsaXN0MDDUAQIDBAUGBwpZJGFyY2hpdmVyWCR2ZXJzaW9uVCR0b3BYJG9iamVjdHNfEA9OU0tleWVkQXJjaGl2ZXISAAGGoNEI" +
                "CVRyb290gAqrCwwNDg8UFxgZHR9VJG51bGxfEBlTeW50aGV0aWMgbWFsZm9ybWVkIGN1cnZlVnswLCAwfVZ7MCwgMX3REBFaTlMu" +
                "b2JqZWN0c6ISE4ACgAPRFRZWcG9pbnRzgARYezAsIE5hTn1WezEsIDF90RAaohscgAaAB9EVHoAI0yAhIiMkJVRuYW1lXxAZZHlu" +
                "YW1pY3NQcmVzc3VyZVNpemVDdXJ2ZV8QHGR5bmFtaWNzUHJlc3N1cmVPcGFjaXR5Q3VydmWAAYAFgAkACAARABsAJAApADIARABJ" +
                "AEwAUQBTAF8AZQCBAIgAjwCSAJ0AoACiAKQApwCuALAAuQDAAMMAxgDIAMoAzQDPANYA2wD3ARYBGAEaAAAAAAAAAgEAAAAAAAAA" +
                "JgAAAAAAAAAAAAAAAAAAARw="
    }
}
