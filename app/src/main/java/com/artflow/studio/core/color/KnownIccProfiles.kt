package com.artflow.studio.core.color

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Recognises the two RGB encodings ArtFlow can store; this is not an arbitrary ICC transform.
 * Names are not evidence of a colour space. Matrix colourants, white point and all three transfer
 * curves must describe sRGB or Display P3. LUT profiles and other encodings remain unsupported.
 */
object KnownIccProfiles {
    fun recognize(bytes: ByteArray): ColorProfile? {
        val reader = Reader(bytes)
        val tags = reader.tags() ?: return null
        if (tags.keys.any { it.take(3) in LUT_PREFIXES }) return null
        if (!reader.xyz(tags["wtpt"]).matches(D50)) return null
        val black = tags["bkpt"]
        if (black != null && !reader.xyz(black).matches(doubleArrayOf(0.0, 0.0, 0.0))) return null
        if (listOf("rTRC", "gTRC", "bTRC").any { !reader.srgbCurve(tags[it]) }) return null
        val matrix = listOf("rXYZ", "gXYZ", "bXYZ").map { reader.xyz(tags[it]) ?: return null }.flatMap { it.toList() }
        return when {
            matrix.toDoubleArray().matches(DISPLAY_P3) -> ColorProfile.DISPLAY_P3
            matrix.toDoubleArray().matches(SRGB) -> ColorProfile.SRGB
            else -> null
        }
    }

    private data class Tag(
        val offset: Int,
        val size: Int,
    )

    private class Reader(
        private val bytes: ByteArray,
    ) {
        private val data = ByteBuffer.wrap(bytes)

        fun tags(): Map<String, Tag>? {
            if (bytes.size < 132 || data.getInt(0) != bytes.size) return null
            if (signature(36) != "acsp" || signature(12) != "mntr") return null
            if (signature(16) != "RGB " || signature(20) != "XYZ ") return null
            if (bytes[8].toInt() !in listOf(2, 4)) return null
            val count = data.getInt(128)
            if (count !in 1..64) return null
            val start = 132 + count * 12
            if (start > bytes.size) return null
            val result = linkedMapOf<String, Tag>()
            repeat(count) { index ->
                val entry = 132 + index * 12
                val offset = data.getInt(entry + 4)
                val size = data.getInt(entry + 8)
                if (offset < start || offset % 4 != 0 || size < 8) return null
                if (offset.toLong() + size > bytes.size) return null
                val name = signature(entry)
                if (result.put(name, Tag(offset, size)) != null) return null
            }
            return result
        }

        fun xyz(tag: Tag?): DoubleArray? = fixedArray(tag, "XYZ ", 3)

        fun fixedArray(
            tag: Tag?,
            type: String,
            count: Int,
        ): DoubleArray? {
            if (tag == null || tag.size != 8 + count * 4) return null
            if (signature(tag.offset) != type || data.getInt(tag.offset + 4) != 0) return null
            return DoubleArray(count) { data.getInt(tag.offset + 8 + it * 4) / 65536.0 }
        }

        fun srgbCurve(tag: Tag?): Boolean {
            if (tag == null || tag.size < 12 || data.getInt(tag.offset + 4) != 0) return false
            return when (signature(tag.offset)) {
                "para" -> parametricCurve(tag)
                "curv" -> sampledCurve(tag)
                else -> false
            }
        }

        private fun parametricCurve(tag: Tag): Boolean {
            val kind = data.getShort(tag.offset + 8).toInt()
            val parameters =
                when (kind) {
                    3 -> SRGB_PARAMETERS
                    4 -> SRGB_PARAMETERS + doubleArrayOf(0.0, 0.0)
                    else -> return false
                }
            if (data.getShort(tag.offset + 10).toInt() != 0 || tag.size != 12 + parameters.size * 4) return false
            return parameters.indices.all { index ->
                abs(data.getInt(tag.offset + 12 + index * 4) / 65536.0 - parameters[index]) <= FIXED_TOLERANCE
            }
        }

        private fun sampledCurve(tag: Tag): Boolean {
            val count = data.getInt(tag.offset + 8)
            if (count !in 256..4096 || tag.size != 12 + count * 2) return false
            return (0 until count).all { index ->
                val actual = (data.getShort(tag.offset + 12 + index * 2).toInt() and 0xFFFF) / 65535.0
                val expected = ColorProfiles.toLinear(index / (count - 1f)).toDouble()
                abs(actual - expected) <= CURVE_TOLERANCE
            }
        }

        private fun signature(offset: Int) = String(bytes, offset, 4, Charsets.US_ASCII)
    }

    private fun DoubleArray?.matches(expected: DoubleArray): Boolean =
        this != null && size == expected.size && indices.all { abs(this[it] - expected[it]) <= MATRIX_TOLERANCE }

    // ICC matrix/TRC colourants are D50-adapted. These allow their s15Fixed16 quantisation and
    // the small published Bradford-matrix rounding differences, not different primaries or gamma.
    private val D50 = doubleArrayOf(0.9642, 1.0, 0.8249)
    private val DISPLAY_P3 =
        doubleArrayOf(0.515102, 0.241182, -0.001050, 0.291965, 0.692236, 0.041882, 0.157153, 0.066574, 0.784073)
    private val SRGB =
        doubleArrayOf(0.4360747, 0.2225045, 0.0139322, 0.3850649, 0.7168786, 0.0971045, 0.1430804, 0.0606169, 0.7141733)
    private val SRGB_PARAMETERS = doubleArrayOf(2.4, 1.0 / 1.055, 0.055 / 1.055, 1.0 / 12.92, 0.04045)
    private val LUT_PREFIXES = setOf("A2B", "B2A", "D2B", "B2D")
    private const val MATRIX_TOLERANCE = 0.0002
    private const val FIXED_TOLERANCE = 2.0 / 65536.0
    private const val CURVE_TOLERANCE = 2.0 / 65535.0
}
