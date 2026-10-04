package com.artflow.studio.data.local

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.domain.model.brush.BrushParams
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Brush shapes and grains from Procreate `.brush` and `.brushset` files, which are zip archives
 * holding a `Shape.png` and a `Grain.png` per brush. The images become ArtFlow tip and grain
 * images; the archived dynamics are not read, so imported brushes start from ArtFlow's defaults.
 */
object BrushArchives {
    /** One brush's images, as ArtFlow tiles. */
    class Imported(
        val shape: CustomGrains.Tile?,
        val grain: CustomGrains.Tile?,
    )

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
        val brushes =
            found.values
                .map { images ->
                    Imported(
                        images[SHAPE]?.let(decode)?.let { CustomGrains.tileFrom(it) },
                        images[GRAIN]?.let(decode)?.let { CustomGrains.tileFrom(it) },
                    )
                }.filter { it.shape != null || it.grain != null }
        require(brushes.isNotEmpty()) { "No brush shapes or grains were found in this file" }
        return brushes
    }

    /** ArtFlow settings for an imported brush whose images are stored as [shapeId] and [grainId]. */
    fun parameters(
        shapeId: String?,
        grainId: String?,
    ): BrushParams =
        BrushParams(
            size = DEFAULT_SIZE,
            spacing = DEFAULT_SPACING,
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
    private const val MAX_ENTRIES = 2_000
    private const val MAX_BRUSHES = 100
    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val BUFFER = 32 * 1024
    private const val DEFAULT_SIZE = 40f
    private const val DEFAULT_SPACING = 0.08f
}
