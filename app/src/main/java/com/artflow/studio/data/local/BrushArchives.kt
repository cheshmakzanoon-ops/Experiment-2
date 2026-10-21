package com.artflow.studio.data.local

import com.artflow.studio.core.export.AbrReader
import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.ProcreateBrush
import com.artflow.studio.core.export.ProcreateBrushWriter
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.SavedBrush
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Procreate `.brush` and `.brushset` files: zip archives holding, per brush, its settings
 * (`Brush.archive`) with a `Shape.png` and a `Grain.png`; a set lists its brushes, in order, in
 * `brushset.plist`. The images become ArtFlow tip and grain images and the settings are carried
 * over by [ProcreateBrush]. Photoshop `.abr` files bring their sampled tips ([readAbr]).
 */
object BrushArchives {
    /** One brush: its name and settings when the archive has them, and its images as ArtFlow tiles. */
    class Imported(
        val shape: CustomGrains.Tile?,
        val grain: CustomGrains.Tile?,
        val settings: ProcreateBrush.Settings? = null,
        val name: String? = settings?.name,
        /** Sub01 belongs to this brush, never a separate entry in the user's library. */
        val secondary: Imported? = null,
    ) {
        /** The caller stores both components' images under content-addressed identities. */
        fun parameters(store: (CustomGrains.Tile) -> String): BrushParams {
            val primary = BrushArchives.parameters(shape?.let(store), grain?.let(store), settings)
            val second = secondary ?: return primary
            val sourceMode = settings?.dualBlendMode
            val mode =
                when (sourceMode) {
                    3 -> DualBrush.Mode.ADD
                    7 -> DualBrush.Mode.SUBTRACT
                    else -> DualBrush.Mode.MULTIPLY
                }
            return primary.copy(
                dual = DualBrush(second.parameters(store).copy(dual = null), mode, sourceBlendMode = sourceMode),
            )
        }
    }

    /** Photoshop brush files: each sampled tip becomes a brush shape, named as the file names it. */
    fun readAbr(bytes: ByteArray): List<Imported> =
        AbrReader.read(bytes).map { tip ->
            // Tips are padded to a square, centred, so long tips keep their whole shape.
            val side = maxOf(tip.width, tip.height)
            val square = PixelBuffer(side, side, IntArray(side * side) { OPAQUE_BLACK })
            val left = (side - tip.width) / 2
            val top = (side - tip.height) / 2
            for (y in 0 until tip.height) {
                for (x in 0 until tip.width) {
                    val value = tip.values[y * tip.width + x].toInt() and 0xFF
                    square.pixels[(top + y) * side + left + x] = OPAQUE_BLACK or (value shl 16) or (value shl 8) or value
                }
            }
            Imported(CustomGrains.tileFrom(square), null, name = tip.name)
        }

    /**
     * A Procreate `.brush` file for a saved brush: its settings and its shape and grain images, which are
     * looked up among the grains in memory (an image that is not loaded is left out).
     */
    fun procreateBrush(brush: SavedBrush): ByteArray =
        ProcreateBrushWriter.brush(
            ProcreateBrushWriter.Brush(
                name = brush.name,
                parameters = brush.parameters,
                shape = CustomGrains.get(brush.parameters.shapeId),
                grain = CustomGrains.get(brush.parameters.textureId),
            ),
        )

    /** Zip files start with the local-file signature `PK\u0003\u0004`. */
    fun isArchive(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_SIGNATURE.size && ZIP_SIGNATURE.indices.all { bytes[it] == ZIP_SIGNATURE[it] }

    /** Read a user-selected file without first allocating an unbounded byte array. */
    fun readInput(input: InputStream): ByteArray = readBounded(input, MAX_ARCHIVE_BYTES)

    /** Primary brushes, in the set's order, with any Sub01 component attached to its parent. */
    fun read(
        bytes: ByteArray,
        decode: (ByteArray) -> PixelBuffer?,
    ): List<Imported> {
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "This brush archive is too large" }
        val found = sortedMapOf<String, MutableMap<String, ByteArray>>()
        var expanded = 0
        val seen = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entries = 0
            var entry = zip.nextEntry
            while (entry != null) {
                require(++entries <= MAX_ENTRIES) { "This brush file has too many entries" }
                val path = entry.name.removeSuffix("/")
                require(path.isNotEmpty() && '\\' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                    "This brush file contains an invalid path"
                }
                require(seen.add(path.lowercase())) { "This brush file contains duplicate paths" }
                val kind =
                    when (path.substringAfterLast('/').lowercase()) {
                        "shape.png" -> SHAPE
                        "grain.png" -> GRAIN
                        "brush.archive" -> SETTINGS
                        "brushset.plist" -> SET
                        else -> null
                    }
                // Drain ignored members too: ZipInputStream.closeEntry otherwise inflates them without a budget.
                val limit = minOf(MAX_EXPANDED_BYTES - expanded, if (kind == SETTINGS) MAX_SETTINGS_BYTES else MAX_IMAGE_BYTES)
                val content = readBounded(zip, limit)
                expanded += content.size
                if (!entry.isDirectory && kind != null) {
                    val folder = path.substringBeforeLast('/', "")
                    require(found.size < MAX_FOLDERS || folder in found) { "This brush set has too many folders" }
                    found.getOrPut(folder) { mutableMapOf() }[kind] = content
                }
                entry = zip.nextEntry
            }
        }
        val components = found.filterValues { files -> files.keys.any { it != SET } }
        val secondaries = components.keys.filter(::isSecondary)
        secondaries.forEach { folder ->
            val parent = folder.removeSuffix(SECONDARY).removeSuffix("/")
            require(parent in components && !isSecondary(parent)) { "A secondary brush has no primary brush" }
        }
        val primaries = components.keys.filterNot(::isSecondary)
        require(primaries.size <= MAX_BRUSHES) { "This brush set has too many brushes" }
        val listed =
            found[""]
                ?.get(SET)
                ?.let(::listedFolders)
                .orEmpty()
                .distinct()
        val folders = listed.filter { it in primaries } + primaries.filter { it !in listed }
        val brushes =
            folders.mapNotNull { folder ->
                val secondaryPath = if (folder.isEmpty()) SECONDARY else "$folder/$SECONDARY"
                val secondary =
                    components[secondaryPath]?.let {
                        requireNotNull(component(it, decode)) { "The secondary brush cannot be read" }
                    }
                val primary = component(components.getValue(folder), decode)
                require(primary != null || secondary == null) { "A secondary brush has no readable primary brush" }
                primary?.let { Imported(it.shape, it.grain, it.settings, it.name, secondary) }
            }
        require(brushes.isNotEmpty()) { "No brushes were found in this file" }
        return brushes
    }

    private fun isSecondary(folder: String): Boolean = folder == SECONDARY || folder.endsWith("/$SECONDARY")

    private fun component(
        files: Map<String, ByteArray>,
        decode: (ByteArray) -> PixelBuffer?,
    ): Imported? {
        val settings = files[SETTINGS]?.let(::settings)
        val shape = files[SHAPE]?.let(decode)?.let { tile(it, settings?.shapeInverted == true) }
        val grain = files[GRAIN]?.let(decode)?.let { tile(it, settings?.grainInverted == true) }
        return if (shape == null && grain == null && settings == null) null else Imported(shape, grain, settings)
    }

    /** The brush's settings, or null when its archive cannot be read (the images still come in). */
    private fun settings(archive: ByteArray): ProcreateBrush.Settings? =
        try {
            ProcreateBrush.read(archive)
        } catch (unreadable: IllegalArgumentException) {
            null
        }

    private fun tile(
        image: PixelBuffer,
        inverted: Boolean,
    ): CustomGrains.Tile {
        val tile = CustomGrains.tileFrom(image)
        if (!inverted) return tile
        return CustomGrains.Tile(tile.size, ByteArray(tile.values.size) { (255 - (tile.values[it].toInt() and 0xFF)).toByte() })
    }

    /** The brush folder names a set's property list (XML or binary) gives under "brushes". */
    internal fun listedFolders(plist: ByteArray): List<String> {
        if (BinaryPlist.isBinaryPlist(plist)) {
            val brushes = (runCatching { BinaryPlist.read(plist) }.getOrNull() as? Map<*, *>)?.get("brushes") as? List<*>
            return brushes.orEmpty().filterIsInstance<String>()
        }
        val array =
            Regex("<key>\\s*brushes\\s*</key>\\s*<array>(.*?)</array>", RegexOption.DOT_MATCHES_ALL).find(plist.decodeToString())
                ?: return emptyList()
        return Regex("<string>(.*?)</string>").findAll(array.groupValues[1]).map { it.groupValues[1].trim() }.toList()
    }

    /**
     * ArtFlow settings for an imported brush whose images are stored as [shapeId] and [grainId]:
     * its own settings when the archive had them, otherwise ArtFlow's defaults for imported tips.
     */
    fun parameters(
        shapeId: String?,
        grainId: String?,
        settings: ProcreateBrush.Settings? = null,
    ): BrushParams =
        (settings?.parameters ?: BrushParams(size = DEFAULT_SIZE, spacing = DEFAULT_SPACING)).copy(
            shapeId = shapeId,
            textureId = grainId,
            blendTexture = grainId != null,
        )

    private fun readBounded(
        input: InputStream,
        limit: Int,
    ): ByteArray {
        require(limit >= 0) { "This brush archive exceeds its expanded size limit" }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - out.size() + 1))
            if (count < 0) break
            require(count > 0) { "The brush stream stopped making progress" }
            require(count <= limit - out.size()) { "This brush file exceeds its size limit" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private val ZIP_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private const val SHAPE = "shape"
    private const val GRAIN = "grain"
    private const val SETTINGS = "settings"
    private const val OPAQUE_BLACK = 0xFF000000.toInt()
    private const val SET = "set"
    private const val MAX_ENTRIES = 2_000
    private const val MAX_BRUSHES = 100
    private const val MAX_FOLDERS = MAX_BRUSHES * 2 + 1
    private const val MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
    private const val MAX_EXPANDED_BYTES = 64 * 1024 * 1024
    private const val MAX_SETTINGS_BYTES = 2 * 1024 * 1024
    private const val SECONDARY = "Sub01"
    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val BUFFER = 32 * 1024
    private const val DEFAULT_SIZE = 40f
    private const val DEFAULT_SPACING = 0.08f
}
