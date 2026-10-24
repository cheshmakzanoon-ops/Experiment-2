package com.artflow.studio.core.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.roundToInt

class KnownIccProfilesTest {
    @Test
    fun canonicalParametric3And4ProfilesAreRecognisedWithoutTrustingTheirNames() {
        listOf("display-p3-parametric3.icc", "display-p3-parametric4.icc").forEach { name ->
            val bytes = fixture(name)
            assertEquals(ColorProfile.DISPLAY_P3, KnownIccProfiles.recognize(bytes))
        }
    }

    @Test
    fun canonicalSrgbColourantsOverrideAMisleadingDisplayP3Description() {
        val bytes = fixture()
        val matrix =
            listOf(
                "rXYZ" to doubleArrayOf(0.4360747, 0.2225045, 0.0139322),
                "gXYZ" to doubleArrayOf(0.3850649, 0.7168786, 0.0971045),
                "bXYZ" to doubleArrayOf(0.1430804, 0.0606169, 0.7141733),
            )
        matrix.forEach { (name, values) ->
            val offset = tag(bytes, name).first
            values.forEachIndexed { index, value -> ByteBuffer.wrap(bytes).putInt(offset + 8 + index * 4, (value * 65536).roundToInt()) }
        }
        assertEquals(ColorProfile.SRGB, KnownIccProfiles.recognize(bytes))
    }

    @Test
    fun theGeneratedExportProfileIsRecognisedByItsSampledTransferCurves() {
        assertEquals(ColorProfile.DISPLAY_P3, KnownIccProfiles.recognize(IccProfile.displayP3))
    }

    @Test
    fun differentPrimariesOrGammaDoNotBecomeDisplayP3BecauseOfADescription() {
        val wrongPrimary = fixture()
        ByteBuffer.wrap(wrongPrimary).putInt(tag(wrongPrimary, "rXYZ").first + 8, (0.6 * 65536).roundToInt())
        assertNull(KnownIccProfiles.recognize(wrongPrimary))

        val wrongGamma = fixture()
        ByteBuffer.wrap(wrongGamma).putInt(tag(wrongGamma, "rTRC").first + 12, (2.2 * 65536).roundToInt())
        assertNull(KnownIccProfiles.recognize(wrongGamma))

        val wrongSample = IccProfile.displayP3.copyOf()
        ByteBuffer.wrap(wrongSample).putShort(tag(wrongSample, "rTRC").first + 12 + 512 * 2, 0)
        assertNull(KnownIccProfiles.recognize(wrongSample))
    }

    @Test
    fun lutProfilesAndNonRgbProfilesStayUnsupported() {
        val lut = fixture()
        val entry = tagEntry(lut, "desc")
        "A2B0".toByteArray().copyInto(lut, entry)
        assertNull(KnownIccProfiles.recognize(lut))

        val cmyk = fixture()
        "CMYK".toByteArray().copyInto(cmyk, 16)
        assertNull(KnownIccProfiles.recognize(cmyk))
    }

    @Test
    fun malformedIccLengthsOffsetsAndDuplicateTagsAreRefusedWithoutReadingOutsideThePayload() {
        val original = fixture()
        for (length in original.indices) assertNull("length=$length", KnownIccProfiles.recognize(original.copyOf(length)))
        val invalid =
            listOf<(ByteArray) -> Unit>(
                { ByteBuffer.wrap(it).putInt(128, Int.MAX_VALUE) },
                { ByteBuffer.wrap(it).putInt(tagEntry(it, "rXYZ") + 4, Int.MAX_VALUE) },
                { ByteBuffer.wrap(it).putInt(tagEntry(it, "rXYZ") + 8, Int.MAX_VALUE) },
                { ByteBuffer.wrap(it).putInt(tagEntry(it, "rXYZ") + 4, 0) },
                { "rXYZ".toByteArray().copyInto(it, tagEntry(it, "gXYZ")) },
            )
        invalid.forEachIndexed { index, mutate ->
            val bytes = original.copyOf().also(mutate)
            assertNull("corruption=$index", KnownIccProfiles.recognize(bytes))
        }
    }

    private fun fixture(name: String = "display-p3-parametric3.icc"): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/procreate/color/$name")).use { it.readBytes() }

    private fun tag(
        bytes: ByteArray,
        name: String,
    ): Pair<Int, Int> {
        val entry = tagEntry(bytes, name)
        val data = ByteBuffer.wrap(bytes)
        return data.getInt(entry + 4) to data.getInt(entry + 8)
    }

    private fun tagEntry(
        bytes: ByteArray,
        name: String,
    ): Int {
        val count = ByteBuffer.wrap(bytes).getInt(128)
        return (0 until count).map { 132 + it * 12 }.single { String(bytes, it, 4, Charsets.US_ASCII) == name }
    }
}
