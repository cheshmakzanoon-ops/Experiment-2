package com.artflow.studio.core.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream
import kotlin.math.abs

class ProcreateColorProfileTest {
    @Test
    fun taggedSrgbCalibrationRecoversNativeP3ChannelsFromTurnedMirroredBgraTiles() {
        val files = files()
        val document = ProcreateReader.open(files, TestPng::decodeSrgb)
        assertEquals(64, document.width)
        assertEquals(48, document.height)
        assertEquals(144, document.dpi)
        assertEquals(ColorProfile.DISPLAY_P3, document.colorProfile)
        assertFalse(document.hasUnsupportedColorProfile)
        assertEquals(0xFFF01E5A.toInt(), document.background)
        assertEquals(0.0, document.calibrationError, 0.000001)
        val expected = expectedNative()
        assertArrayEquals(expected, document.pixels(document.layers().single()).pixels)
        assertArrayEquals(expected, requireNotNull(document.composite()).pixels)
        val wrongContract = ProcreateReader.open(files, TestPng::decode)
        assertTrue("Raw tagged P3 is not an sRGB thumbnail callback", wrongContract.calibrationError > 1.0)
    }

    @Test
    fun theSrgbThumbnailAdapterMatchesIndependentIccColourExpectations() {
        val encoded = requireNotNull(files().read("QuickLook/Thumbnail.png"))
        val raw = requireNotNull(TestPng.decode(encoded))
        val srgb = requireNotNull(TestPng.decodeSrgb(encoded))
        assertEquals(0xFFE6462D.toInt(), raw.pixels[12 * 64 + 12])
        // LittleCMS 2.19, the fixture's actual ICC payload, relative-colorimetric intent.
        assertColour(0xFFFA3117.toInt(), srgb, 12, 12)
        assertColour(0xFF00B773.toInt(), srgb, 40, 12)
        assertColour(0xFF1D47EF.toInt(), srgb, 12, 36)
        assertColour(0xFFD000BF.toInt(), srgb, 40, 28)
    }

    @Test
    fun unsupportedOrMalformedPresentProfilesUseTheDisclosedLegacySrgbFallback() {
        val descriptors =
            listOf(
                mapOf("SiColorProfileArchiveICCNameKey" to "Display P3", "SiColorProfileArchiveICCDataKey" to byteArrayOf(1, 2)),
                mapOf("SiColorProfileArchiveICCNameKey" to "Display P3"),
                "Display P3",
                BinaryPlist.Uid(Int.MAX_VALUE),
            )
        descriptors.forEach { descriptor ->
            val document = ProcreateReader.open(files(descriptor)) { null }
            assertEquals(ColorProfile.SRGB, document.colorProfile)
            assertTrue("descriptor=$descriptor", document.hasUnsupportedColorProfile)
        }
    }

    @Test
    fun absentProfilesKeepTheHistoricalSrgbDefaultWithoutAnUnsupportedNotice() {
        listOf(null, BinaryPlist.Uid(0), "\$null").forEach { descriptor ->
            val document = ProcreateReader.open(files(descriptor)) { null }
            assertEquals(ColorProfile.SRGB, document.colorProfile)
            assertFalse(document.hasUnsupportedColorProfile)
        }
    }

    private fun assertColour(
        expected: Int,
        image: PixelBuffer,
        x: Int,
        y: Int,
    ) {
        val actual = image.pixels[y * image.width + x]
        assertEquals(expected ushr 24, actual ushr 24)
        for (shift in listOf(16, 8, 0)) {
            val difference = abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255))
            assertTrue("($x,$y) channel=$shift expected=$expected actual=$actual", difference <= 1)
        }
    }

    private fun resource(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/procreate/color/$name")).use { it.readBytes() }

    private fun expectedNative(): IntArray {
        val bytes = ByteBuffer.wrap(resource("display-p3-native.argb"))
        return IntArray(bytes.remaining() / 4) { bytes.int }
    }

    private fun files(descriptor: Any? = KEEP_PROFILE): ProcreateReader.Files {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(resource("display-p3-derived.procreate").inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes() }
        }
        if (descriptor !== KEEP_PROFILE) entries["Document.archive"] = replaceProfile(entries.getValue("Document.archive"), descriptor)
        return object : ProcreateReader.Files {
            override val names = entries.keys

            override fun read(name: String): ByteArray? = entries[name]
        }
    }

    private fun replaceProfile(
        bytes: ByteArray,
        descriptor: Any?,
    ): ByteArray {
        val archive = dictionary(BinaryPlist.read(bytes))
        val objects = (archive.getValue("\$objects") as List<*>).toMutableList()
        val top = dictionary(archive.getValue("\$top"))
        val root = (top.getValue("root") as BinaryPlist.Uid).index
        val document = dictionary(objects[root]).toMutableMap()
        if (descriptor == null) document.remove("colorProfile") else document["colorProfile"] = descriptor
        objects[root] = document
        return BinaryPlist.write(archive + ("\$objects" to objects))
    }

    private fun dictionary(value: Any?): Map<String, Any?> = (value as Map<*, *>).entries.associate { (key, item) -> key as String to item }

    private companion object {
        val KEEP_PROFILE = Any()
    }
}
