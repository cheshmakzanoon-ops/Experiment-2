package com.artflow.studio.data.local

import com.artflow.studio.core.export.AbrReader
import com.artflow.studio.core.export.BinaryPlist
import com.artflow.studio.core.export.ProcreateBrush
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    )

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

    /** Zip files start with the local-file signature `PK\u0003\u0004`. */
    fun isArchive(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_SIGNATURE.size && ZIP_SIGNATURE.indices.all { bytes[it] == ZIP_SIGNATURE[it] }

    /** Every brush in the archive that has a shape or a grain image, in folder order. */
    fun read(
        bytes: ByteArray,
        decode: (ByteArray) -> PixelBuffer?,
    ): List<Imported> {
        val found = sortedMapOf<String, MutableMap<String, ByteArray>>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entries = 0
            var entry = zip.nextEntry
            while (entry != null) {
                require(++entries <= MAX_ENTRIES) { "This brush file has too many entries" }
                val kind =
                    when (entry.name.substringAfterLast('/').lowercase()) {
                        "shape.png" -> SHAPE
                        "grain.png" -> GRAIN
                        "brush.archive" -> SETTINGS
                        "brushset.plist" -> SET
                        else -> null
                    }
                if (kind != null) {
                    val folder = entry.name.substringBeforeLast('/', "")
                    require(found.size < MAX_BRUSHES || folder in found) { "This brush set has too many brushes" }
                    found.getOrPut(folder) { mutableMapOf() }[kind] = readBounded(zip)
                }
                entry = zip.nextEntry
            }
        }
        // A set lists its brush folders in library order; anything it leaves out follows.
        val listed = found[""]?.get(SET)?.let(::listedFolders).orEmpty()
        val folders = listed.filter { it in found } + found.keys.filter { it !in listed }
        val brushes =
            folders
                .mapNotNull { found[it] }
                .map { files ->
                    val settings = files[SETTINGS]?.let(::settings)
                    Imported(
                        files[SHAPE]?.let(decode)?.let { tile(it, settings?.shapeInverted == true) },
                        files[GRAIN]?.let(decode)?.let { tile(it, settings?.grainInverted == true) },
                        settings,
                    )
                }.filter { it.shape != null || it.grain != null || it.settings != null }
        require(brushes.isNotEmpty()) { "No brushes were found in this file" }
        return brushes
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

    private fun readBounded(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            require(out.size() <= MAX_IMAGE_BYTES) { "A brush image is too large" }
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
    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val BUFFER = 32 * 1024
    private const val DEFAULT_SIZE = 40f
    private const val DEFAULT_SPACING = 0.08f
}
