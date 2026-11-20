package com.artflow.studio.core.color

import com.artflow.studio.core.export.readWithProgress
import com.artflow.studio.core.pixels.ImportMemoryBudget
import com.artflow.studio.core.serialization.JsonLimitException
import com.artflow.studio.core.serialization.JsonReadBudget
import com.artflow.studio.core.serialization.JsonReadLimits
import com.artflow.studio.core.serialization.escapeJsonSurrogates
import com.artflow.studio.domain.model.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
 * unreadable swatches skipped). Every visited member, including ignored members, shares an expansion
 * limit. JSON structure and allocation work are admitted before the serializer builds its tree.
 */
object ProcreateSwatches {
    const val EXTENSION = "swatches"
    const val MAX_ARCHIVE_BYTES = 2 * 1024 * 1024
    private const val MAX_JSON_BYTES = 4L * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 8L * 1024 * 1024
    private const val MAX_ENTRIES = 16
    private const val MAX_PALETTES = 64
    private const val MAX_SWATCHES = 65_536

    /** Procreate shows a palette as thirty slots; shorter palettes are padded with empty ones. */
    private const val SLOTS = 30
    private const val ENTRY = "Swatches.json"
    private const val REFERENCE_DATE_OFFSET_SECONDS = 978_307_200.0
    private val json = Json { ignoreUnknownKeys = true }
    private val ZIP_SIGNATURE = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    private const val MEMORY_MESSAGE = "This palette needs too much memory. Try fewer colours or a smaller palette."
    private val jsonLimits =
        object : JsonReadLimits {
            override val message = "This palette exceeds the supported data limits. Try fewer colours or a smaller palette."

            override fun arrayEntries(property: String?): Int =
                when (property) {
                    null -> MAX_PALETTES
                    "swatches" -> MAX_SWATCHES
                    else -> 256
                }

            override fun stringCharacters(
                property: String?,
                owner: String?,
            ): Int = MAX_TEXT_CHARACTERS
        }

    internal class ReadOptions(
        val memoryBudgetBytes: Long = ImportMemoryBudget.currentBytes(),
        val maximumExpandedBytes: Long = MAX_EXPANDED_BYTES,
        val checkActive: () -> Unit = {},
        val optionalProbe: Boolean = false,
    )

    private class ArchiveIdentity {
        var palette = false
    }

    /** True for the ZIP signature every `.swatches` file starts with. */
    fun isArchive(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_SIGNATURE.size && ZIP_SIGNATURE.indices.all { bytes[it] == ZIP_SIGNATURE[it] }

    /** The palettes in [bytes], in file order; empty when the archive is not a readable swatches file. */
    fun read(
        bytes: ByteArray,
        fallbackName: String = "Procreate palette",
    ): List<Palette> = read(bytes, ReadOptions(), fallbackName)

    /** A missing palette identity must not make a palette-only limit reject another archive format. */
    internal fun probeForImport(
        bytes: ByteArray,
        checkActive: () -> Unit,
    ): Boolean = read(bytes, ReadOptions(checkActive = checkActive, optionalProbe = true)).isNotEmpty()

    internal fun readForImport(
        bytes: ByteArray,
        fallbackName: String,
        checkActive: () -> Unit,
    ): List<Palette> = read(bytes, ReadOptions(checkActive = checkActive), fallbackName)

    internal fun read(
        bytes: ByteArray,
        options: ReadOptions,
        fallbackName: String = "Procreate palette",
        parse: (String) -> JsonElement = json::parseToJsonElement,
        openArchive: (InputStream) -> ZipInputStream = ::ZipInputStream,
    ): List<Palette> {
        options.checkActive()
        if (bytes.size > MAX_ARCHIVE_BYTES || !isArchive(bytes)) return emptyList()
        require(options.maximumExpandedBytes in 1..MAX_EXPANDED_BYTES) { "Invalid palette expansion allowance" }
        require(options.memoryBudgetBytes in 1..(Runtime.getRuntime().maxMemory() / 3)) { "Invalid palette memory allowance" }
        val identity = ArchiveIdentity()
        return try {
            val budget = JsonReadBudget(options.memoryBudgetBytes, MEMORY_MESSAGE)
            budget.reserve(bytes.size.toLong() + ZIP_WORKSPACE_BYTES)
            val encoded = readEntry(bytes, options, budget, identity, openArchive) ?: return emptyList()
            budget.reserve(encoded.size.toLong() * Char.SIZE_BYTES)
            val text = encoded.toString(Charsets.UTF_8)
            options.checkActive()
            budget.check(text, jsonLimits)
            options.checkActive()
            val root = parse(text)
            options.checkActive()
            palettesOf(root, fallbackName, options.checkActive)
        } catch (limited: JsonLimitException) {
            if (!identity.palette && options.optionalProbe) emptyList() else throw limited
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    private fun palettesOf(
        root: JsonElement,
        fallbackName: String,
        checkActive: () -> Unit,
    ): List<Palette> {
        val palettes =
            when (root) {
                is JsonArray -> root
                is JsonObject -> listOf(root)
                else -> emptyList()
            }
        return palettes.mapNotNull {
            checkActive()
            (it as? JsonObject)?.let { palette -> paletteOf(palette, fallbackName, checkActive) }
        }
    }

    /** A `.swatches` archive holding [palette]. */
    fun write(
        palette: Palette,
        nowEpochSeconds: Double = System.currentTimeMillis() / 1000.0,
    ): ByteArray = write(palette, nowEpochSeconds, ImportMemoryBudget.currentBytes())

    internal fun write(
        palette: Palette,
        nowEpochSeconds: Double,
        memoryBudgetBytes: Long,
    ): ByteArray {
        require(palette.colors.size <= MAX_SWATCHES) { "This palette has too many colours to share" }
        require(palette.name.length <= MAX_NAME) { "Use a palette name of 80 characters or fewer" }
        val timestamp = nowEpochSeconds - REFERENCE_DATE_OFFSET_SECONDS
        require(timestamp.isFinite()) { "The palette timestamp is invalid" }
        val padding = (SLOTS - palette.colors.size).coerceAtLeast(0)
        val budget = JsonReadBudget(memoryBudgetBytes, MEMORY_MESSAGE)
        budget.reserve(ZIP_WORKSPACE_BYTES)
        // A root array, one palette, two keys/values and fifteen tokens per written swatch.
        budget.reserveTokens(6L + palette.colors.size.toLong() * 15 + padding)
        val output = ArchiveOutput(budget)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY))
            val member = JsonOutput(zip, budget)
            val name = escapeJsonSurrogates(JsonPrimitive(palette.name).toString(), MAX_NAME * 6 + 2)
            member.text("[{\"name\":$name,\"swatches\":[")
            var separated = false
            palette.colors.forEach { color ->
                if (separated) member.text(",")
                member.text(swatchOf(color, timestamp).toString())
                separated = true
            }
            repeat(padding) {
                if (separated) member.text(",")
                member.text("null")
                separated = true
            }
            member.text("]}]")
            zip.closeEntry()
        }
        return output.bytes()
    }

    /** A file name for [name] that is safe on every platform. */
    fun fileName(name: String): String =
        name
            .filter { it.isLetterOrDigit() || it in " -_" }
            .trim()
            .ifEmpty { "Palette" }
            .take(60) + ".$EXTENSION"

    private fun readEntry(
        bytes: ByteArray,
        options: ReadOptions,
        budget: JsonReadBudget,
        identity: ArchiveIdentity,
        openArchive: (InputStream) -> ZipInputStream,
    ): ByteArray? {
        openArchive(ByteArrayInputStream(bytes)).use { zip ->
            val reader = ArchiveReader(zip, options, budget)
            var selected: ByteArray? = null
            var entries = 0
            while (true) {
                options.checkActive()
                val entry = zip.nextEntry ?: return selected
                options.checkActive()
                if (++entries > MAX_ENTRIES || entry.name.length > MAX_ENTRY_CHARACTERS) return null
                budget.reserve(entry.name.length * 2L + 128)
                val palette = !entry.isDirectory && entry.name.substringAfterLast('/') == ENTRY
                if (palette && identity.palette) return null
                if (palette) identity.palette = true
                val content = reader.member(palette) ?: return null
                if (palette) selected = content
            }
        }
    }

    private class ArchiveReader(
        private val zip: ZipInputStream,
        private val options: ReadOptions,
        private val budget: JsonReadBudget,
    ) {
        private val buffer = ByteArray(BUFFER_BYTES)
        private var expanded = 0L

        /** Ignored members are explicitly consumed here, never implicitly by nextEntry. */
        fun member(selected: Boolean): ByteArray? {
            val output = if (selected) ByteArrayOutputStream() else null
            while (true) {
                val remaining =
                    minOf(
                        options.maximumExpandedBytes - expanded,
                        if (selected) MAX_JSON_BYTES - requireNotNull(output).size() else Long.MAX_VALUE,
                    )
                val requested = minOf(buffer.size.toLong(), remaining + 1).toInt()
                val count = zip.readWithProgress(buffer, requested, options.checkActive)
                if (count < 0) return output?.toByteArray() ?: EMPTY
                if (count > remaining) return null
                expanded += count
                if (output != null) {
                    budget.reserve(count.toLong() * 3)
                    output.write(buffer, 0, count)
                }
            }
        }
    }

    private class ArchiveOutput(
        private val budget: JsonReadBudget,
    ) : OutputStream() {
        private val output = ByteArrayOutputStream()

        override fun write(value: Int) {
            admit(1)
            output.write(value)
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            admit(length)
            output.write(bytes, offset, length)
        }

        private fun admit(count: Int) {
            if (count < 0 || count > MAX_ARCHIVE_BYTES - output.size()) throw JsonLimitException(MEMORY_MESSAGE)
            // The archive's growth buffer and returned byte copy may coexist.
            budget.reserve(count.toLong() * 3)
        }

        fun bytes(): ByteArray = output.toByteArray()
    }

    private class JsonOutput(
        private val zip: ZipOutputStream,
        private val budget: JsonReadBudget,
    ) {
        private var written = 0L

        fun text(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_JSON_BYTES - written) throw JsonLimitException(MEMORY_MESSAGE)
            // Admit this file's later read: inflated growth/copy, UTF16 text and DOM strings.
            budget.reserve(bytes.size.toLong() * 7)
            written += bytes.size
            zip.write(bytes)
        }
    }

    private fun paletteOf(
        palette: JsonObject,
        fallbackName: String,
        checkActive: () -> Unit,
    ): Palette? {
        val swatches = palette["swatches"] as? JsonArray ?: return null
        val colors =
            swatches.mapNotNull {
                checkActive()
                (it as? JsonObject)?.let(::colorOf)
            }
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
    private const val MAX_TEXT_CHARACTERS = 4096
    private const val MAX_ENTRY_CHARACTERS = 4096
    private const val ZIP_WORKSPACE_BYTES = 256L * 1024
    private const val BUFFER_BYTES = 8192
    private val EMPTY = ByteArray(0)
}
