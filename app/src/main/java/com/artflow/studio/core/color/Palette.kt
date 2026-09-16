package com.artflow.studio.core.color

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Colour palettes (Phase 32).
 *
 * A palette is a named, categorised list of colours plus the import/export codecs artists expect.
 * All codecs are pure Kotlin over `ByteArray`/`String`, so they run (and are tested) on the JVM.
 */
@Serializable
data class Palette(
    val id: Long = 0,
    val name: String,
    val colors: List<Int>,
    val category: String = "Custom",
    val isFavorite: Boolean = false,
    val createdAt: Long = 0L,
    /** Where the palette came from, so the UI can label imported ones. */
    val source: String = "ArtFlow",
) {
    val size: Int get() = colors.size

    /** Colour at [index] wrapped into range, so callers can cycle through a palette. */
    fun colorAt(index: Int): Int = if (colors.isEmpty()) 0 else colors[index.mod(colors.size)]

    /** A palette with a colour appended, ignoring colours that are already present. */
    fun withColor(
        color: Int,
        threshold: Float = 6f,
    ): Palette {
        if (colors.any { ColorHarmony.distance(it, color) < threshold }) return this
        return copy(colors = colors + color)
    }

    /** A palette with the colour at [index] removed. */
    fun withoutColor(index: Int): Palette {
        if (index !in colors.indices) return this
        return copy(colors = colors.filterIndexed { i, _ -> i != index })
    }

    /** Duplicates the palette under a new name. */
    fun renamed(newName: String): Palette = copy(id = 0, name = newName, createdAt = 0L)
}

/** Bundled starter palettes so the app is useful before the artist saves anything. */
object PaletteLibrary {
    val BUILT_IN: List<Palette> =
        listOf(
            Palette(
                name = "Grayscale 9",
                colors =
                    listOf(
                        0xFF000000.toInt(),
                        0xFF333333.toInt(),
                        0xFF555555.toInt(),
                        0xFF777777.toInt(),
                        0xFF999999.toInt(),
                        0xFFBBBBBB.toInt(),
                        0xFFDDDDDD.toInt(),
                        0xFFEEEEEE.toInt(),
                        0xFFFFFFFF.toInt(),
                    ),
                category = "Essentials",
                source = "ArtFlow",
            ),
            Palette(
                name = "Skin Tones",
                colors =
                    listOf(
                        0xFFFFE0BD.toInt(),
                        0xFFF1C27D.toInt(),
                        0xFFE0AC69.toInt(),
                        0xFFC68642.toInt(),
                        0xFF8D5524.toInt(),
                        0xFF5C3317.toInt(),
                        0xFFFFDBAC.toInt(),
                        0xFFD9A066.toInt(),
                        0xFFA9714B.toInt(),
                        0xFF7B4B2A.toInt(),
                    ),
                category = "Portrait",
                source = "ArtFlow",
            ),
            Palette(
                name = "Landscape Greens",
                colors =
                    listOf(
                        0xFF0B3D0B.toInt(),
                        0xFF1E5631.toInt(),
                        0xFF4C9A2A.toInt(),
                        0xFF77B255.toInt(),
                        0xFFA8C97F.toInt(),
                        0xFF8B6F47.toInt(),
                        0xFF5C4033.toInt(),
                        0xFF2E8B57.toInt(),
                    ),
                category = "Landscape",
                source = "ArtFlow",
            ),
            Palette(
                name = "Sunset Sky",
                colors =
                    listOf(
                        0xFF2B1B4B.toInt(),
                        0xFF5C3A6E.toInt(),
                        0xFFB23A48.toInt(),
                        0xFFE06C55.toInt(),
                        0xFFF79D65.toInt(),
                        0xFFFFD6A5.toInt(),
                        0xFFFFF3C4.toInt(),
                    ),
                category = "Landscape",
                source = "ArtFlow",
            ),
            Palette(
                name = "Ocean Deep",
                colors =
                    listOf(
                        0xFF001219.toInt(),
                        0xFF005F73.toInt(),
                        0xFF0A9396.toInt(),
                        0xFF94D2BD.toInt(),
                        0xFFE9D8A6.toInt(),
                        0xFFEE9B00.toInt(),
                        0xFFCA6702.toInt(),
                        0xFF9B2226.toInt(),
                    ),
                category = "Landscape",
                source = "ArtFlow",
            ),
            Palette(
                name = "Ink & Marker",
                colors =
                    listOf(
                        0xFF1A1A1A.toInt(),
                        0xFFEF476F.toInt(),
                        0xFFFFD166.toInt(),
                        0xFF06D6A0.toInt(),
                        0xFF118AB2.toInt(),
                        0xFF073B4C.toInt(),
                        0xFF8338EC.toInt(),
                    ),
                category = "Illustration",
                source = "ArtFlow",
            ),
            Palette(
                name = "Pastel Set",
                colors =
                    listOf(
                        0xFFFFADAD.toInt(),
                        0xFFFFD6A5.toInt(),
                        0xFFFDFFB6.toInt(),
                        0xFFCAFFBF.toInt(),
                        0xFF9BF6FF.toInt(),
                        0xFFA0C4FF.toInt(),
                        0xFFBDB2FF.toInt(),
                        0xFFFFC6FF.toInt(),
                    ),
                category = "Illustration",
                source = "ArtFlow",
            ),
            Palette(
                name = "Comic Palette",
                colors =
                    listOf(
                        0xFF000000.toInt(),
                        0xFFFFFFFF.toInt(),
                        0xFFFF0000.toInt(),
                        0xFF00AAFF.toInt(),
                        0xFFFFD800.toInt(),
                        0xFF00C853.toInt(),
                        0xFFFF6D00.toInt(),
                        0xFF8E24AA.toInt(),
                    ),
                category = "Illustration",
                source = "ArtFlow",
            ),
        )

    fun byCategory(): Map<String, List<Palette>> = BUILT_IN.groupBy { it.category }

    fun categories(): List<String> = BUILT_IN.map { it.category }.distinct()
}

/**
 * Palette import/export (Phase 32).
 *
 * Supports:
 * - **GPL** (GIMP palette): the most widely supported plain-text interchange format
 * - **HEX** (one colour per line, `#RRGGBB` or bare) - what most online palette sites emit
 * - **ASE** (Adobe Swatch Exchange): the binary format Photoshop/Illustrator use
 * - **JSON** (ArtFlow's own format, for backup and sharing between devices)
 */
object PaletteCodec {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    // ---------------------------------------------------------------------------------------
    // GPL
    // ---------------------------------------------------------------------------------------

    fun exportGpl(palette: Palette): String =
        buildString {
            appendLine("GIMP Palette")
            appendLine("Name: ${palette.name}")
            appendLine("Columns: ${palette.colors.size.coerceAtMost(16)}")
            appendLine("# Exported by ArtFlow")
            palette.colors.forEach { color ->
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                appendLine("%3d %3d %3d\t${ColorHarmony.nameOf(color)}".format(r, g, b))
            }
        }

    fun importGpl(
        text: String,
        fallbackName: String = "Imported Palette",
    ): Palette? {
        val lines = text.split('\n')
        if (lines.isEmpty()) return null
        var name = fallbackName
        val colors = mutableListOf<Int>()
        lines.forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#") -> Unit
                line.startsWith("Name:", ignoreCase = true) ->
                    name = line.substringAfter(':').trim().ifEmpty { fallbackName }
                line.startsWith("GIMP Palette", ignoreCase = true) -> Unit
                line.startsWith("Columns:", ignoreCase = true) -> Unit
                else -> parseRgbTriplet(line)?.let { colors += it }
            }
        }
        if (colors.isEmpty()) return null
        return Palette(name = name, colors = colors, source = "GPL import")
    }

    /** Parses a `r g b [name]` line from a GPL file. */
    private fun parseRgbTriplet(line: String): Int? {
        val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val r = parts[0].toIntOrNull() ?: return null
        val g = parts[1].toIntOrNull() ?: return null
        val b = parts[2].toIntOrNull() ?: return null
        if (r !in 0..255 || g !in 0..255 || b !in 0..255) return null
        return ColorHarmony.fromRgb(r, g, b)
    }

    // ---------------------------------------------------------------------------------------
    // HEX / text
    // ---------------------------------------------------------------------------------------

    fun exportHex(palette: Palette): String = palette.colors.joinToString("\n") { ColorHarmony.toHex(it) }

    fun importHex(
        text: String,
        name: String = "Imported Palette",
    ): Palette? {
        val colors =
            text
                .split(Regex("[,\\s]+"))
                .mapNotNull { ColorHarmony.parseHex(it) }
        if (colors.isEmpty()) return null
        return Palette(name = name, colors = colors, source = "Hex import")
    }

    /** Accepts anything the user might paste: CSS, GPL, hex lists or a JSON palette. */
    fun importAuto(
        text: String,
        name: String = "Imported Palette",
    ): Palette? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            importJson(trimmed)?.let { return it.renamed(name) }
        }
        if (trimmed.startsWith("GIMP Palette", ignoreCase = true)) {
            return importGpl(trimmed, name)
        }
        importHex(trimmed, name)?.let { return it }
        return importGpl(trimmed, name)
    }

    // ---------------------------------------------------------------------------------------
    // JSON
    // ---------------------------------------------------------------------------------------

    fun exportJson(palette: Palette): String = json.encodeToString(Palette.serializer(), palette)

    fun exportJson(palettes: List<Palette>): String =
        json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Palette.serializer()), palettes)

    fun importJson(text: String): Palette? =
        try {
            if (text.trim().startsWith("[")) {
                val list =
                    json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(Palette.serializer()),
                        text,
                    )
                list.firstOrNull()
            } else {
                json.decodeFromString(Palette.serializer(), text)
            }
        } catch (e: Exception) {
            null
        }

    fun importJsonAll(text: String): List<Palette> =
        try {
            if (text.trim().startsWith("[")) {
                json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(Palette.serializer()),
                    text,
                )
            } else {
                listOf(json.decodeFromString(Palette.serializer(), text))
            }
        } catch (e: Exception) {
            emptyList()
        }

    // ---------------------------------------------------------------------------------------
    // ASE (Adobe Swatch Exchange)
    // ---------------------------------------------------------------------------------------

    private const val ASE_HEADER = "ASEF"
    private const val ASE_BLOCK_COLOR = 0x0001
    private const val ASE_BLOCK_GROUP_START = 0xC001
    private const val ASE_BLOCK_GROUP_END = 0xC002

    /** Palette names are capped at 256 UTF-16 characters, so 64 KiB per block is ample. */
    private const val MAX_BLOCK_BYTES = 64 * 1024

    /** Writes an ASE file. RGB colours use the "RGB " model, which every Adobe app understands. */
    fun exportAse(palette: Palette): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(ASE_HEADER.toByteArray(Charsets.US_ASCII))
        output.writeShortBE(1)
        output.writeShortBE(0)

        // Group start block so the palette shows up as a named folder in Photoshop.
        output.writeBlock(ASE_BLOCK_GROUP_START) { buffer ->
            buffer.writeUtf16BeString(palette.name)
        }
        // Blocks written: group start + one per colour + group end.
        val blockCount = 2 + palette.colors.size

        palette.colors.forEach { color ->
            output.writeBlock(ASE_BLOCK_COLOR) { buffer ->
                buffer.writeUtf16BeString(ColorHarmony.nameOf(color))
                buffer.put("RGB ".toByteArray(Charsets.US_ASCII))
                buffer.putFloatBE(((color shr 16) and 0xFF) / 255f)
                buffer.putFloatBE(((color shr 8) and 0xFF) / 255f)
                buffer.putFloatBE((color and 0xFF) / 255f)
                buffer.putShort(0) // global colour type
            }
        }
        output.writeBlock(ASE_BLOCK_GROUP_END) { /* empty */ }

        val payload = output.toByteArray()
        // Patch the block count into the header (bytes 8..11).
        val final = payload.copyOf()
        ByteBuffer.wrap(final).order(ByteOrder.BIG_ENDIAN).putInt(8, blockCount)
        return final
    }

    fun importAse(
        bytes: ByteArray,
        fallbackName: String = "Imported Swatch",
    ): Palette? {
        if (bytes.size < 12) return null
        val header = String(bytes, 0, 4, Charsets.US_ASCII)
        if (header != ASE_HEADER) return null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        buffer.position(8)
        val blockCount = buffer.int
        val colors = mutableListOf<Int>()
        var groupName: String? = null

        var block = 0
        while (block < blockCount && buffer.remaining() >= 6) {
            val type = buffer.short.toInt() and 0xFFFF
            val length = buffer.int
            if (length < 0 || buffer.remaining() < length) break
            val blockStart = buffer.position()
            when (type) {
                ASE_BLOCK_COLOR -> readColorBlock(buffer)?.let { colors += it }
                ASE_BLOCK_GROUP_START -> groupName = readUtf16BeString(buffer)
                ASE_BLOCK_GROUP_END -> Unit
            }
            buffer.position(blockStart + length)
            block++
        }

        if (colors.isEmpty()) return null
        return Palette(
            name = groupName?.trim()?.ifEmpty { fallbackName } ?: fallbackName,
            colors = colors,
            source = "ASE import",
        )
    }

    private fun readColorBlock(buffer: ByteBuffer): Int? {
        readUtf16BeString(buffer) ?: return null
        if (buffer.remaining() < 4) return null
        val modelBytes = ByteArray(4)
        buffer.get(modelBytes)
        val model = String(modelBytes, Charsets.US_ASCII)
        return when (model) {
            "RGB " -> {
                val r = buffer.float.coerceIn(0f, 1f)
                val g = buffer.float.coerceIn(0f, 1f)
                val b = buffer.float.coerceIn(0f, 1f)
                ColorHarmony.fromRgb((r * 255f).toInt(), (g * 255f).toInt(), (b * 255f).toInt())
            }
            "Gray" -> {
                val gray = buffer.float.coerceIn(0f, 1f)
                val value = (gray * 255f).toInt()
                ColorHarmony.fromRgb(value, value, value)
            }
            "CMYK" -> {
                val c = buffer.float
                val m = buffer.float
                val y = buffer.float
                val k = buffer.float
                ColorHarmony.fromCmyk(c, m, y, k)
            }
            // LAB swatches are converted through their L*a*b* values via XYZ and sRGB.
            "LAB " -> {
                val l = buffer.float
                val a = buffer.float
                val b = buffer.float
                labToArgb(l, a, b)
            }
            else -> null
        }
    }

    /** CIE L*a*b* (D65) to sRGB, used so LAB swatches survive an ASE import. */
    fun labToArgb(
        l: Float,
        a: Float,
        b: Float,
    ): Int {
        val fy = (l + 16f) / 116f
        val fx = fy + a / 500f
        val fz = fy - b / 200f

        fun pivot(t: Float): Float = if (t > 0.206897f) t * t * t else (t - 16f / 116f) / 7.787f

        val x = 0.95047f * pivot(fx)
        val y = 1.00000f * pivot(fy)
        val z = 1.08883f * pivot(fz)

        // sRGB D65 matrix.
        var r = 3.2404542f * x - 1.5371385f * y - 0.4985314f * z
        var g = -0.9692660f * x + 1.8760108f * y + 0.0415560f * z
        var bl = 0.0556434f * x - 0.2040259f * y + 1.0572252f * z

        fun gamma(channel: Float): Float =
            if (channel <= 0.0031308f) {
                12.92f * channel
            } else {
                1.055f * Math.pow(channel.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
            }

        r = gamma(r).coerceIn(0f, 1f)
        g = gamma(g).coerceIn(0f, 1f)
        bl = gamma(bl).coerceIn(0f, 1f)
        return ColorHarmony.fromRgb((r * 255f).toInt(), (g * 255f).toInt(), (bl * 255f).toInt())
    }

    /** Builds a palette from an image by picking its most representative colours. */
    fun paletteFromImage(
        pixels: IntArray,
        maxColors: Int = 16,
        name: String = "From Image",
    ): Palette {
        // Uniform histogram over a 4-bit-per-channel quantised colour cube, then pick the most
        // frequent buckets. Simple, fast and good enough for a palette picker.
        val buckets = HashMap<Int, Int>()
        pixels.forEach { color ->
            if ((color ushr 24) < 128) return@forEach
            val r = ((color shr 16) and 0xFF) shr 4
            val g = ((color shr 8) and 0xFF) shr 4
            val b = (color and 0xFF) shr 4
            val key = (r shl 8) or (g shl 4) or b
            buckets[key] = (buckets[key] ?: 0) + 1
        }

        val colors =
            buckets.entries
                .sortedByDescending { it.value }
                .take(maxColors)
                .map { entry ->
                    val r = ((entry.key shr 8) and 0xF) * 17
                    val g = ((entry.key shr 4) and 0xF) * 17
                    val b = (entry.key and 0xF) * 17
                    ColorHarmony.fromRgb(r, g, b)
                }
        return Palette(name = name, colors = colors, source = "Image extract")
    }

    /** Suggests a file extension for a given format. */
    enum class Format(
        val extension: String,
        val displayName: String,
    ) {
        GPL("gpl", "GIMP palette"),
        ASE("ase", "Adobe swatch"),
        HEX("txt", "Hex list"),
        JSON("json", "ArtFlow palette"),
    }

    /** Encodes a palette in the requested format. */
    fun encode(
        palette: Palette,
        format: Format,
    ): ByteArray =
        when (format) {
            Format.GPL -> exportGpl(palette).toByteArray()
            Format.ASE -> exportAse(palette)
            Format.HEX -> exportHex(palette).toByteArray()
            Format.JSON -> exportJson(palette).toByteArray()
        }

    /** Decodes a palette from raw bytes, sniffing the format. */
    fun decode(
        bytes: ByteArray,
        fallbackName: String = "Imported Palette",
    ): Palette? {
        if (bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == ASE_HEADER) {
            return importAse(bytes, fallbackName)
        }
        val text = String(bytes, Charsets.UTF_8)
        return importAuto(text, fallbackName)
    }

    // ---------------------------------------------------------------------------------------
    // Byte-level helpers
    // ---------------------------------------------------------------------------------------

    private fun ByteArrayOutputStream.writeShortBE(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    /**
     * Writes one ASE block: a 16-bit type, a 32-bit big-endian payload length, then the payload.
     * The staging buffer is large enough for the longest palette name we allow plus an RGB entry.
     */
    private fun ByteArrayOutputStream.writeBlock(
        type: Int,
        fill: (ByteBuffer) -> Unit,
    ) {
        val body = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(MAX_BLOCK_BYTES).order(ByteOrder.BIG_ENDIAN)
        fill(buffer)
        buffer.flip()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        body.write(bytes)

        val payload = body.toByteArray()
        writeShortBE(type)
        write((payload.size shr 24) and 0xFF)
        write((payload.size shr 16) and 0xFF)
        write((payload.size shr 8) and 0xFF)
        write(payload.size and 0xFF)
        write(payload)
    }

    private fun ByteBuffer.writeUtf16BeString(value: String) {
        val chars = value.toCharArray()
        putShort((chars.size + 1).toShort())
        chars.forEach { putShort(it.code.toShort()) }
        putShort(0)
    }

    private fun readUtf16BeString(buffer: ByteBuffer): String? {
        if (buffer.remaining() < 2) return null
        val length = buffer.short.toInt() and 0xFFFF
        if (length == 0) return ""
        if (buffer.remaining() < length * 2) return null
        val chars = CharArray(length)
        for (i in 0 until length) {
            chars[i] = (buffer.short.toInt() and 0xFFFF).toChar()
        }
        // The last character is the null terminator.
        return String(chars).trimEnd('\u0000')
    }

    private fun ByteBuffer.putFloatBE(value: Float) = putFloat(value)
}
