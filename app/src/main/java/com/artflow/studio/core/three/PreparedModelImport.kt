package com.artflow.studio.core.three

import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.pixels.PixelBuffer
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** A complete, validated model import. Every present plane uses this one canvas and UV layout. */
class PreparedModelImport private constructor(
    val objText: String,
    val color: PixelBuffer?,
    val metallic: PixelBuffer?,
    val roughness: PixelBuffer?,
    val width: Int,
    val height: Int,
) {
    companion object {
        /**
         * Decoding is supplied by the platform. Scalar maps are accepted only through the bounded
         * OBJ contract; existing color-only OBJ/glTF/USD imports retain their shared-atlas behavior.
         */
        fun prepare(
            contents: ModelPackage.Contents,
            decode: (ByteArray) -> PixelBuffer?,
            maxSize: Int = DEFAULT_MAX_SIZE,
            defaultSize: Int = DEFAULT_SIZE,
            memoryBudgetBytes: Long = Runtime.getRuntime().maxMemory() * 3 / 5,
        ): PreparedModelImport {
            require(maxSize in 1..CanvasOperations.MAX_DIMENSION) { "Invalid model texture size limit" }
            require(defaultSize in 1..CanvasOperations.MAX_DIMENSION) { "Invalid default model texture size" }
            require(memoryBudgetBytes > 0) { "Invalid model import memory budget" }
            ObjParser.parse(contents.objText)
            return if (contents.scalarMaps != null) {
                scalar(contents, decode, maxSize, memoryBudgetBytes)
            } else {
                colorOnly(contents, decode, maxSize, defaultSize)
            }
        }

        private fun scalar(
            contents: ModelPackage.Contents,
            decode: (ByteArray) -> PixelBuffer?,
            maxSize: Int,
            memoryBudgetBytes: Long,
        ): PreparedModelImport {
            val materials = TextureAtlas.materials(contents.objText)
            require(materials.size == 1 && materials.single().isNotBlank()) {
                "Scalar maps require one used OBJ material with one shared UV atlas"
            }
            val colorBytes = requireNotNull(contents.texture) { "The scalar material needs its color texture" }
            val maps = requireNotNull(contents.scalarMaps)
            val source = pngSize(colorBytes, "Color")
            val planes = listOfNotNull(colorBytes, maps.metallic, maps.roughness)
            listOf("Metallic" to maps.metallic, "Roughness" to maps.roughness).forEach { (name, bytes) ->
                bytes?.let {
                    require(pngSize(it, name) == source) { "The $name map must match the color texture dimensions" }
                    requireRawScalarPng(it, name)
                }
            }
            val target = fittedSize(source, maxSize)
            requireMemory(source, target, planes, memoryBudgetBytes)
            // Each helper returns only the fitted plane. A full-size decoded source never remains
            // referenced while the next texture is decoded, and no plane changes the shared UVs.
            val color = plane(colorBytes, "Color", source, target, decode, scalar = false)
            val metallic = maps.metallic?.let { plane(it, "Metallic", source, target, decode, scalar = true) }
            val roughness = maps.roughness?.let { plane(it, "Roughness", source, target, decode, scalar = true) }
            return PreparedModelImport(
                contents.objText,
                color,
                metallic,
                roughness,
                target.width,
                target.height,
            )
        }

        private data class ImageSize(
            val width: Int,
            val height: Int,
        ) {
            val bytes: Long get() = width.toLong() * height * 4
        }

        /** Inspect the bounded PNG header before asking a platform decoder to allocate pixels. */
        private fun pngSize(
            bytes: ByteArray,
            label: String,
        ): ImageSize {
            require(bytes.size >= PNG_HEADER_BYTES && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }) {
                "The $label texture is not a readable PNG"
            }
            val header = ByteBuffer.wrap(bytes)
            require(header.getInt(8) == 13 && header.getInt(12) == PNG_IHDR) { "The $label PNG header is damaged" }
            val crc = CRC32().apply { update(bytes, 12, 17) }.value
            require(crc == (header.getInt(29).toLong() and 0xFFFFFFFFL)) { "The $label PNG header checksum is damaged" }
            require(bytes[24].toInt() == 8) { "The $label texture must use 8-bit PNG samples" }
            val size = ImageSize(header.getInt(16), header.getInt(20))
            require(CanvasOperations.isSizeSafe(size.width, size.height)) { "The $label texture exceeds the canvas limits" }
            return size
        }

        private fun requireMemory(
            source: ImageSize,
            target: ImageSize,
            planes: List<ByteArray>,
            budget: Long,
        ) {
            // Android decoding can hold both a Bitmap and its copied pixels. Installation retains
            // prepared planes and private layer copies, with canvas/color-conversion temporaries.
            val decodePeak = source.bytes * 2 + target.bytes * (planes.size - 1)
            val installPeak = target.bytes * maxOf(6, planes.size * 2 + 2)
            val required = maxOf(decodePeak, installPeak) + planes.sumOf { it.size.toLong() }
            require(required <= budget) { "This model's material maps need more memory than the import budget provides" }
        }

        /** Platform color decoding may transform tagged samples; our scalar exports carry no color metadata. */
        private fun requireRawScalarPng(
            bytes: ByteArray,
            label: String,
        ) {
            val data = ByteBuffer.wrap(bytes)
            var offset = PNG_SIGNATURE.size
            while (offset <= bytes.size - PNG_CHUNK_OVERHEAD) {
                val length = data.getInt(offset)
                require(length >= 0 && length.toLong() + offset + PNG_CHUNK_OVERHEAD <= bytes.size) {
                    "The $label PNG chunk is damaged"
                }
                val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
                require(type !in PNG_COLOR_METADATA) {
                    "The $label map uses unsupported PNG color metadata ($type); scalar maps need raw samples"
                }
                offset += length + PNG_CHUNK_OVERHEAD
                if (type == "IEND") {
                    require(length == 0 && offset == bytes.size) { "The $label PNG end marker is damaged" }
                    return
                }
            }
            throw IllegalArgumentException("The $label PNG chunks are incomplete")
        }

        private fun plane(
            bytes: ByteArray,
            label: String,
            source: ImageSize,
            target: ImageSize,
            decode: (ByteArray) -> PixelBuffer?,
            scalar: Boolean,
        ): PixelBuffer {
            val image = requireNotNull(decode(bytes)) { "The $label texture could not be decoded" }
            require(image.width == source.width && image.height == source.height) { "The decoded $label texture size is inconsistent" }
            if (scalar) require(image.pixels.all(::isOpaqueGray)) { "The $label map must be opaque grayscale data" }
            return sized(image, target.width, target.height)
        }

        private fun colorOnly(
            contents: ModelPackage.Contents,
            decode: (ByteArray) -> PixelBuffer?,
            maxSize: Int,
            defaultSize: Int,
        ): PreparedModelImport {
            val textures =
                contents.textures
                    .mapNotNull { (name, bytes) ->
                        decode(bytes)?.also(::requireSafe)?.let { name to it }
                    }.toMap()
            val atlas = if (textures.isNotEmpty()) TextureAtlas.pack(contents.objText, textures, maxSize) else null
            val color =
                atlas?.image ?: contents.texture
                    ?.let(decode)
                    ?.also(::requireSafe)
                    ?.let { fitted(it, maxSize) }
            val width = color?.width ?: defaultSize
            val height = color?.height ?: defaultSize
            require(CanvasOperations.isSizeSafe(width, height)) { "The model texture exceeds the canvas limits" }
            return PreparedModelImport(atlas?.objText ?: contents.objText, color, null, null, width, height)
        }

        private fun fitted(
            image: PixelBuffer,
            maxSize: Int,
        ): PixelBuffer {
            val target = fittedSize(ImageSize(image.width, image.height), maxSize)
            return sized(image, target.width, target.height)
        }

        private fun fittedSize(
            image: ImageSize,
            maxSize: Int,
        ): ImageSize {
            val scale = minOf(1f, maxSize.toFloat() / maxOf(image.width, image.height))
            val width = (image.width * scale).toInt().coerceAtLeast(1)
            val height = (image.height * scale).toInt().coerceAtLeast(1)
            require(CanvasOperations.isSizeSafe(width, height)) { "The model texture exceeds the canvas limits" }
            return ImageSize(width, height)
        }

        private fun sized(
            image: PixelBuffer,
            width: Int,
            height: Int,
        ): PixelBuffer = if (image.width == width && image.height == height) image else image.scaled(width, height)

        private fun requireSafe(image: PixelBuffer) {
            require(CanvasOperations.isSizeSafe(image.width, image.height)) { "The model texture exceeds the canvas limits" }
        }

        private fun isOpaqueGray(pixel: Int): Boolean =
            pixel ushr 24 == 255 && (pixel ushr 16 and 255) == (pixel ushr 8 and 255) && (pixel ushr 8 and 255) == (pixel and 255)

        private const val DEFAULT_MAX_SIZE = 4096
        private const val DEFAULT_SIZE = 2048
        private const val PNG_HEADER_BYTES = 33
        private const val PNG_CHUNK_OVERHEAD = 12
        private const val PNG_IHDR = 0x49484452
        private val PNG_SIGNATURE = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private val PNG_COLOR_METADATA = setOf("iCCP", "gAMA", "cHRM", "sRGB", "sBIT", "cICP", "mDCV", "cLLI")
    }
}
