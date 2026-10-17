package com.artflow.studio.core.color

import com.artflow.studio.core.export.readAtMost
import com.artflow.studio.domain.model.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Procreate's `.swatches` palette files: a ZIP archive holding one `Swatches.json`, whose palette lists
 * its swatches as hue, saturation, brightness and alpha in 0..1, with `null` for an empty slot.
 *
 * Reading is tolerant (a list of palettes or a single palette, unknown keys ignored, empty slots and
 * unreadable swatches skipped). Everything is read through a byte budget, so a hostile archive cannot
 * inflate past it.
 */
object ProcreateSwatches {
    const val EXTENSION = "swatches"
    const val MAX_ARCHIVE_BYTES = 2 * 1024 * 1024
    private const val MAX_JSON_BYTES = 4L * 1024 * 1024
    private const val MAX_ENTRIES = 16
    private const val MAX_PALETTES = 64
    private const val MAX_SWATCHES = 65_536

    /** Procreate shows a palette as thirty slots; shorter palettes are padded with empty ones. */
    private const val SLOTS = 30
    private const val ENTRY = "Swatches.json"
    private const val REFERENCE_DATE_OFFSET_SECONDS = 978_307_200.0
    private val json = Json { ignoreUnknownKeys = true }
    private val ZIP_SIGNATURE = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)

    /** True for the ZIP signature every `.swatches` file starts with. */
    fun isArchive(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_SIGNATURE.size && ZIP_SIGNATURE.indices.all { bytes[it] == ZIP_SIGNATURE[it] }

    /** The palettes in [bytes], in file order; empty when the archive is not a readable swatches file. */
    fun read(
        bytes: ByteArray,
        fallbackName: String = "Procreate palette",
    ): List<Palette> {
        if (bytes.size > MAX_ARCHIVE_BYTES || !isArchive(bytes)) return emptyList()
        val text = readEntry(bytes) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return emptyList()
        val palettes =
            when (root) {
                is JsonArray -> root.take(MAX_PALETTES)
                is JsonObject -> listOf(root)
                else -> emptyList()
            }
        return palettes.mapNotNull { (it as? JsonObject)?.let { palette -> paletteOf(palette, fallbackName) } }
    }

    /** A `.swatches` archive holding [palette]. */
    fun write(
        palette: Palette,
        nowEpochSeconds: Double = System.currentTimeMillis() / 1000.0,
    ): ByteArray {
        val timestamp = nowEpochSeconds - REFERENCE_DATE_OFFSET_SECONDS
        val swatches =
            buildJsonArray {
                palette.colors.forEach { add(swatchOf(it, timestamp)) }
                repeat((SLOTS - palette.colors.size).coerceAtLeast(0)) { add(JsonNull) }
            }
        val document =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("name", palette.name)
                        put("swatches", swatches)
                    },
                )
            }
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY))
            zip.write(document.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    /** A file name for [name] that is safe on every platform. */
    fun fileName(name: String): String =
        name
            .filter { it.isLetterOrDigit() || it in " -_" }
            .trim()
            .ifEmpty { "Palette" }
            .take(60) + ".$EXTENSION"

    private fun readEntry(bytes: ByteArray): String? {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            repeat(MAX_ENTRIES) {
                val entry = zip.nextEntry ?: return null
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == ENTRY) {
                    return zip.readAtMost(MAX_JSON_BYTES)?.toString(Charsets.UTF_8)
                }
            }
        }
        return null
    }

    private fun paletteOf(
        palette: JsonObject,
        fallbackName: String,
    ): Palette? {
        val swatches = palette["swatches"] as? JsonArray ?: return null
        val colors = swatches.take(MAX_SWATCHES).mapNotNull { (it as? JsonObject)?.let(::colorOf) }
        if (colors.isEmpty()) return null
        val label = (palette["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val name = label?.trim().orEmpty()
        return Palette(name = name.ifEmpty { fallbackName }.take(MAX_NAME), colors = colors, source = "Procreate import")
    }

    private fun colorOf(swatch: JsonObject): Int? {
        val hue = number(swatch, "hue") ?: return null
        val saturation = number(swatch, "saturation") ?: return null
        val brightness = number(swatch, "brightness") ?: return null
        val alpha = number(swatch, "alpha") ?: 1.0
        // Hue wraps around the wheel; a stray huge value must not reach the float conversion.
        return ColorHarmony.fromHsv(
            ((hue - floor(hue)) * 360.0).toFloat(),
            saturation.coerceIn(0.0, 1.0).toFloat(),
            brightness.coerceIn(0.0, 1.0).toFloat(),
            (alpha.coerceIn(0.0, 1.0) * 255.0).roundToInt(),
        )
    }

    private fun number(
        swatch: JsonObject,
        key: String,
    ): Double? = (swatch[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }

    private fun swatchOf(
        argb: Int,
        timestamp: Double,
    ): JsonElement {
        val hsv = Color.rgbToHsv(argb)
        return buildJsonObject {
            put("hue", round(hsv[0] / 360f))
            put("saturation", round(hsv[1]))
            put("brightness", round(hsv[2]))
            put("alpha", round(((argb ushr 24) and 0xFF) / 255f))
            put("origin", 0)
            put("colorSpace", 0)
            put("timestamp", timestamp)
        }
    }

    private fun round(value: Float): Double = (value.coerceIn(0f, 1f) * 1_000_000.0).roundToInt() / 1_000_000.0

    private const val MAX_NAME = 80
}
