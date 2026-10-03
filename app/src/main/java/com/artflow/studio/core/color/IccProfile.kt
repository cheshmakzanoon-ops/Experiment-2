package com.artflow.studio.core.color

import java.nio.ByteBuffer
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** ICC v2 display profiles written from primaries, so exports carry their colour space. */
object IccProfile {
    /** Display P3: P3 primaries, D65 white and the sRGB curve; colorants adapted to D50. */
    val displayP3: ByteArray by lazy {
        build(
            "Display P3",
            floatArrayOf(0.515102f, 0.241182f, -0.001050f),
            floatArrayOf(0.291965f, 0.692236f, 0.041882f),
            floatArrayOf(0.157153f, 0.066574f, 0.784073f),
        )
    }

    fun forProfile(profile: ColorProfile): ByteArray? = if (profile == ColorProfile.DISPLAY_P3) displayP3 else null

    private fun build(
        description: String,
        red: FloatArray,
        green: FloatArray,
        blue: FloatArray,
    ): ByteArray {
        val curve = curve()
        val tags =
            listOf(
                "desc" to descriptionTag(description),
                "cprt" to textTag("No copyright, use freely"),
                "wtpt" to xyzTag(D50),
                "rXYZ" to xyzTag(red),
                "gXYZ" to xyzTag(green),
                "bXYZ" to xyzTag(blue),
                "rTRC" to curve,
                "gTRC" to curve,
                "bTRC" to curve,
            )
        // Shared tag data (the three curves) is stored once.
        val offsets = IdentityHashMap<ByteArray, Int>()
        var end = HEADER + 4 + tags.size * 12
        tags.forEach { (_, data) ->
            if (data !in offsets) {
                offsets[data] = end
                end = align(end + data.size)
            }
        }
        val out = ByteBuffer.allocate(end)
        out.putInt(end).putInt(0).putInt(VERSION)
        out.put("mntrRGB XYZ ".toByteArray(Charsets.US_ASCII))
        intArrayOf(2024, 1, 1, 0, 0, 0).forEach { out.putShort(it.toShort()) }
        out.put("acsp".toByteArray(Charsets.US_ASCII))
        out.position(68) // platform, flags, device and rendering intent stay zero
        D50.forEach { out.putInt(fixed(it)) }
        out.position(HEADER)
        out.putInt(tags.size)
        tags.forEach { (signature, data) ->
            out.put(signature.toByteArray(Charsets.US_ASCII)).putInt(offsets.getValue(data)).putInt(data.size)
        }
        offsets.forEach { (data, offset) ->
            out.position(offset)
            out.put(data)
        }
        return out.array()
    }

    private fun xyzTag(xyz: FloatArray): ByteArray =
        ByteBuffer
            .allocate(20)
            .put("XYZ ".toByteArray(Charsets.US_ASCII))
            .putInt(0)
            .putInt(fixed(xyz[0]))
            .putInt(fixed(xyz[1]))
            .putInt(fixed(xyz[2]))
            .array()

    private fun curve(): ByteArray {
        val out = ByteBuffer.allocate(12 + CURVE_POINTS * 2)
        out.put("curv".toByteArray(Charsets.US_ASCII)).putInt(0).putInt(CURVE_POINTS)
        repeat(CURVE_POINTS) { out.putShort((ColorProfiles.toLinear(it / (CURVE_POINTS - 1f)) * 65535f).roundToInt().toShort()) }
        return out.array()
    }

    private fun textTag(text: String): ByteArray {
        val ascii = text.toByteArray(Charsets.US_ASCII)
        return ByteBuffer
            .allocate(8 + ascii.size + 1)
            .put("text".toByteArray(Charsets.US_ASCII))
            .putInt(0)
            .put(ascii)
            .array()
    }

    private fun descriptionTag(text: String): ByteArray {
        val ascii = text.toByteArray(Charsets.US_ASCII)
        // ASCII count and text, then empty Unicode and ScriptCode records (67 bytes of script data).
        return ByteBuffer
            .allocate(12 + ascii.size + 1 + 8 + 3 + 67)
            .put("desc".toByteArray(Charsets.US_ASCII))
            .putInt(0)
            .putInt(ascii.size + 1)
            .put(ascii)
            .array()
    }

    private fun fixed(value: Float): Int = (value * 65536f).roundToInt()

    private fun align(value: Int): Int = (value + 3) and 3.inv()

    private val D50 = floatArrayOf(0.9642f, 1f, 0.8249f)
    private const val HEADER = 128
    private const val VERSION = 0x02100000
    private const val CURVE_POINTS = 1024
}
