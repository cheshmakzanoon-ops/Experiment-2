package com.artflow.studio.core.export

/**
 * Reads the sampled tips of Photoshop brush files (.abr), the way GIMP and Krita do: version 1
 * and 2 files list brushes one after another (version 2 with names); version 6 and later keep the
 * tips in an "8BIM samp" section and the presets, with their names, in "8BIM desc". Computed
 * (round) brushes and Photoshop's dynamics are not read.
 */
object AbrReader {
    /** A brush tip: coverage per pixel, 255 painting fully. */
    class Tip(
        val name: String?,
        val width: Int,
        val height: Int,
        val values: ByteArray,
    )

    fun isAbr(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER) return false
        val reader = Reader(bytes)
        return when (reader.short(0)) {
            1, 2 -> reader.short(2) in 1..MAX_TIPS
            in 6..10 -> reader.short(2) in 1..2 && reader.ascii(4, 4) == "8BIM"
            else -> false
        }
    }

    fun read(bytes: ByteArray): List<Tip> {
        require(isAbr(bytes)) { "This is not a Photoshop brush file" }
        val reader = Reader(bytes)
        val tips = if (reader.short(0) <= 2) classic(reader) else sectioned(reader)
        require(tips.isNotEmpty()) { "This brush file has no sampled brush tips" }
        return tips
    }

    /** Versions 1 and 2: a count, then each brush's type and size; type 2 is a sampled tip. */
    private fun classic(reader: Reader): List<Tip> {
        val version = reader.short(0)
        val count = reader.short(2)
        val tips = mutableListOf<Tip>()
        var at = HEADER
        repeat(count) {
            require(at + 6 <= reader.size) { DAMAGED }
            val type = reader.short(at)
            val size = reader.int(at + 2)
            require(size in 0..reader.size - at - 6) { DAMAGED }
            val next = at + 6 + size
            if (type == SAMPLED) {
                var p = at + 6 + 4 + 2 // misc, spacing
                var name: String? = null
                if (version == 2) {
                    val characters = reader.int(p)
                    require(characters in 0..MAX_NAME && p + 4L + characters.toLong() * 2L <= next) { DAMAGED }
                    name = reader.utf16(p + 4, characters)
                    p += 4 + characters * 2
                }
                p += 1 + 4 * 2 // antialiasing, short bounds
                require(p + 16L <= next) { DAMAGED }
                val top = reader.int(p)
                val left = reader.int(p + 4)
                val bottom = reader.int(p + 8)
                val right = reader.int(p + 12)
                tips += pixels(reader, p + 16, right.toLong() - left, bottom.toLong() - top, name)
            }
            at = next
        }
        return tips
    }

    /** Versions 6 and later: 8BIM sections; tips in "samp", their names in the "desc" presets. */
    private fun sectioned(reader: Reader): List<Tip> {
        val subversion = reader.short(2)
        var samples: List<Pair<String, Tip>> = emptyList()
        var names: Map<String, String> = emptyMap()
        var at = HEADER
        while (at + SECTION_HEADER <= reader.size && reader.ascii(at, 4) == "8BIM") {
            val tag = reader.ascii(at + 4, 4)
            val size = reader.int(at + 8)
            val body = at + SECTION_HEADER
            require(size in 0..reader.size - body) { DAMAGED }
            when (tag) {
                "samp" -> samples = samples(reader, body, body + size, subversion)
                "desc" -> names = presetNames(reader, body, body + size)
            }
            at = body + size
        }
        return samples.map { (key, tip) -> names[key]?.let { Tip(it, tip.width, tip.height, tip.values) } ?: tip }
    }

    /** Each sample: its size, its key (a UUID the presets refer to), bounds, depth and pixels. */
    private fun samples(
        reader: Reader,
        start: Int,
        end: Int,
        subversion: Int,
    ): List<Pair<String, Tip>> {
        val found = mutableListOf<Pair<String, Tip>>()
        var at = start
        while (at + 4 <= end && found.size < MAX_TIPS) {
            val size = reader.int(at)
            require(size in 0..end - at - 4) { DAMAGED }
            val padded = size.toLong() + (4 - size % 4) % 4
            val nextLong = at.toLong() + 4L + padded
            require(nextLong <= end.toLong()) { DAMAGED }
            val next = nextLong.toInt()
            val keyLength = reader.byte(at + 4)
            val key = reader.ascii(at + 5, keyLength)
            val pLong = at.toLong() + 4L + if (subversion == 1) V6_KEY_SHORT else V6_KEY_LONG
            require(pLong + 16L <= nextLong) { DAMAGED }
            val p = pLong.toInt()
            val top = reader.int(p)
            val left = reader.int(p + 4)
            val bottom = reader.int(p + 8)
            val right = reader.int(p + 12)
            found += normalised(key) to pixels(reader, p + 16, right.toLong() - left, bottom.toLong() - top, null)
            at = next
        }
        return found
    }

    /** Depth, compression, then raw or PackBits rows; 16-bit tips keep their high byte. */
    private fun pixels(
        reader: Reader,
        start: Int,
        widthLong: Long,
        heightLong: Long,
        name: String?,
    ): Tip {
        require(widthLong in 1L..MAX_SIDE.toLong() && heightLong in 1L..MAX_SIDE.toLong()) { "A brush tip has an unexpected size" }
        val width = widthLong.toInt()
        val height = heightLong.toInt()
        reader.spend(width, height)
        require(start >= 0 && start.toLong() + 3L <= reader.size) { DAMAGED }
        val depth = reader.short(start)
        require(depth == 8 || depth == 16) { "A brush tip has an unsupported depth" }
        val bytesPerSample = depth / 8
        val rowBytes = width * bytesPerSample
        val raw = ByteArray(rowBytes * height)
        val compressed = reader.byte(start + 2) != 0
        if (compressed) packBits(reader, start + 3, height, rowBytes, raw) else reader.copy(start + 3, raw)
        val values = ByteArray(width * height) { raw[it * bytesPerSample] }
        return Tip(name, width, height, values)
    }

    /** Row byte counts, then each row as PackBits runs. */
    private fun packBits(
        reader: Reader,
        start: Int,
        rows: Int,
        rowBytes: Int,
        out: ByteArray,
    ) {
        val headerEndLong = start.toLong() + rows.toLong() * 2L
        require(start >= 0 && headerEndLong <= reader.size) { DAMAGED }
        var at = headerEndLong.toInt()
        for (row in 0 until rows) {
            val rowLength = reader.short(start + row * 2)
            require(rowLength <= reader.size - at) { DAMAGED }
            val end = at + rowLength
            var d = row * rowBytes
            val rowEnd = d + rowBytes
            while (at < end) {
                val n = reader.byte(at++).toByte().toInt()
                when {
                    n == -128 -> Unit
                    n < 0 -> {
                        require(at < end) { DAMAGED }
                        val value = reader.byte(at++).toByte()
                        require(1 - n <= rowEnd - d) { DAMAGED }
                        repeat(1 - n) { out[d++] = value }
                    }
                    else -> {
                        require(n + 1 <= end - at && n + 1 <= rowEnd - d) { DAMAGED }
                        repeat(n + 1) { out[d++] = reader.byte(at++).toByte() }
                    }
                }
            }
            at = end
        }
    }

    /**
     * Preset names by the key of the tip they use: in each preset descriptor a "sampledData"
     * string names the tip, and the nearest "Nm  " string before it is the brush's name.
     */
    private fun presetNames(
        reader: Reader,
        start: Int,
        end: Int,
    ): Map<String, String> {
        val names = HashMap<String, String>()
        var lastName: String? = null
        var at = start
        while (at + KEY_TEXT <= end) {
            when {
                reader.matches(at, NAME_KEY) -> lastName = reader.unicode(at + NAME_KEY.length)
                reader.matches(at, SAMPLED_KEY) -> {
                    val key = reader.unicode(at + SAMPLED_KEY.length)
                    if (key != null && lastName != null) names.putIfAbsent(normalised(key), lastName)
                }
            }
            at++
        }
        return names
    }

    private fun normalised(key: String) =
        key
            .trim()
            .trimStart('$')
            .trimEnd('\u0000')
            .lowercase()

    /** Big-endian, bounds-checked access to the file. */
    private class Reader(
        private val bytes: ByteArray,
    ) {
        val size = bytes.size

        // Every tip in a file shares one pixel budget, so many small allocations cannot add up to gigabytes.
        private var spentPixels = 0L

        fun spend(
            width: Int,
            height: Int,
        ) {
            spentPixels += width.toLong() * height
            require(spentPixels <= MAX_TOTAL_PIXELS) { DAMAGED }
        }

        fun byte(at: Int): Int {
            require(at in 0 until size) { DAMAGED }
            return bytes[at].toInt() and 0xFF
        }

        fun short(at: Int): Int = (byte(at) shl 8) or byte(at + 1)

        fun int(at: Int): Int = (short(at) shl 16) or short(at + 2)

        fun ascii(
            at: Int,
            length: Int,
        ): String {
            require(at >= 0 && length in 0..size - at) { DAMAGED }
            return String(bytes, at, length, Charsets.ISO_8859_1)
        }

        fun utf16(
            at: Int,
            characters: Int,
        ): String {
            require(at >= 0 && characters in 0..(size - at) / 2) { DAMAGED }
            return String(bytes, at, characters * 2, Charsets.UTF_16BE).trimEnd('\u0000')
        }

        /** A descriptor's Unicode string: a character count, then UTF-16; null when it does not fit. */
        fun unicode(at: Int): String? {
            if (at.toLong() + 4L > size) return null
            val characters = int(at)
            if (characters !in 0..MAX_NAME || at.toLong() + 4L + characters.toLong() * 2L > size) return null
            return utf16(at + 4, characters).trim().takeIf { it.isNotEmpty() }
        }

        fun matches(
            at: Int,
            text: String,
        ): Boolean = at + text.length <= size && text.indices.all { bytes[at + it] == text[it].code.toByte() }

        fun copy(
            at: Int,
            out: ByteArray,
        ) {
            require(at >= 0 && out.size <= size - at) { DAMAGED }
            System.arraycopy(bytes, at, out, 0, out.size)
        }
    }

    private const val DAMAGED = "The brush file is damaged"
    private const val HEADER = 4
    private const val SECTION_HEADER = 12
    private const val SAMPLED = 2
    private const val MAX_TIPS = 1000
    private const val MAX_TOTAL_PIXELS = 64_000_000L
    private const val MAX_NAME = 1024
    private const val MAX_SIDE = 5000

    /** Bytes before a version 6 tip's bounds: its key and, for subversion 2, further fields. */
    private const val V6_KEY_SHORT = 47
    private const val V6_KEY_LONG = 301
    private const val KEY_TEXT = 8
    private const val NAME_KEY = "Nm  TEXT"
    private const val SAMPLED_KEY = "sampledDataTEXT"
}
