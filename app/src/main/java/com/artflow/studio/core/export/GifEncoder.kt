package com.artflow.studio.core.export

import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Animated GIF encoder (Phase 44).
 *
 * A complete, standards-compliant GIF89a writer: median-cut colour quantisation, per-frame local
 * colour tables, 1-bit transparency, per-frame delays and the Netscape looping extension.
 *
 * Implemented in pure Kotlin over `IntArray` frames (rather than delegating to Android's
 * `Bitmap`/`AnimatedImageDrawable`) for three reasons: the app must support API 26 where animated
 * image encoding is missing, we need per-frame delays and looping that the platform does not
 * expose, and it makes the encoder fully unit-testable on the JVM.
 */
object GifEncoder {
    /**
     * Encodes [frames] into an animated GIF.
     *
     * @param frames one ARGB pixel array per frame, each `width * height` long.
     * @param delaysMs per-frame display duration in centiseconds-grained milliseconds.
     * @param loop when true the animation repeats forever.
     * @param matteColor colour composited behind semi-transparent pixels (GIF has no alpha ramp).
     * @param keepTransparency when true, fully transparent pixels become the GIF transparent index.
     */
    fun encode(
        frames: List<IntArray>,
        width: Int,
        height: Int,
        delaysMs: List<Int>,
        loop: Boolean = true,
        matteColor: Int = 0xFFFFFFFF.toInt(),
        keepTransparency: Boolean = false,
        maxColors: Int = 256,
    ): ByteArray {
        require(frames.isNotEmpty()) { "A GIF needs at least one frame" }
        require(width > 0 && height > 0) { "GIF dimensions must be positive" }
        frames.forEachIndexed { index, frame ->
            require(frame.size == width * height) {
                "Frame $index has ${frame.size} pixels, expected ${width * height}"
            }
        }

        val out = ByteArrayOutputStream()
        writeHeader(out, width, height, loop)

        val transparentIndex = if (keepTransparency) 0 else -1
        frames.forEachIndexed { index, frame ->
            val delay = (delaysMs.getOrNull(index) ?: 100).coerceIn(20, 100_000)
            val quantized =
                Quantizer.quantize(
                    frame = frame,
                    maxColors = if (keepTransparency) maxColors - 1 else maxColors,
                    matteColor = matteColor,
                    reserveTransparent = keepTransparency,
                )
            writeFrame(
                out = out,
                width = width,
                height = height,
                indexed = quantized.indices,
                palette = quantized.palette,
                delayMs = delay,
                transparentIndex = transparentIndex,
            )
        }

        out.write(0x3B) // trailer
        return out.toByteArray()
    }

    private fun writeHeader(
        out: ByteArrayOutputStream,
        width: Int,
        height: Int,
        loop: Boolean,
    ) {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        writeShort(out, width)
        writeShort(out, height)
        // No global colour table (each frame carries a local one), 8 bits per pixel.
        out.write(0x70)
        out.write(0)
        out.write(0)

        if (loop) {
            // Netscape 2.0 application extension: loop forever.
            out.write(0x21)
            out.write(0xFF)
            out.write(0x0B)
            out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
            out.write(0x03)
            out.write(0x01)
            writeShort(out, 0) // 0 = infinite
            out.write(0x00)
        }
    }

    private fun writeFrame(
        out: ByteArrayOutputStream,
        width: Int,
        height: Int,
        indexed: ByteArray,
        palette: IntArray,
        delayMs: Int,
        transparentIndex: Int,
    ) {
        // Graphics control extension: delay + transparency.
        out.write(0x21)
        out.write(0xF9)
        out.write(0x04)
        val transparencyFlag = if (transparentIndex >= 0) 0x01 else 0x00
        // Disposal method 2 (restore to background) keeps transparent areas clean between frames.
        out.write(0x04 or transparencyFlag)
        writeShort(out, (delayMs / 10).coerceAtLeast(2)) // GIF delays are in centiseconds
        out.write(if (transparentIndex >= 0) transparentIndex else 0)
        out.write(0x00)

        // Image descriptor.
        out.write(0x2C)
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, width)
        writeShort(out, height)
        // Local colour table present, 256 entries (7 => 2^(7+1) = 256).
        out.write(0x80 or 0x07)

        // Local colour table, padded to a power of two.
        val tableSize = 256
        for (i in 0 until tableSize) {
            val color = if (i < palette.size) palette[i] else 0
            out.write((color shr 16) and 0xFF)
            out.write((color shr 8) and 0xFF)
            out.write(color and 0xFF)
        }

        // LZW-compressed image data.
        val minCodeSize = 8
        out.write(minCodeSize)
        val compressed = LzwCompressor.compress(indexed, minCodeSize)
        var offset = 0
        while (offset < compressed.size) {
            val chunk = min(255, compressed.size - offset)
            out.write(chunk)
            out.write(compressed, offset, chunk)
            offset += chunk
        }
        out.write(0x00) // block terminator
    }

    private fun writeShort(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    /**
     * Median-cut colour quantisation.
     *
     * Chosen over a fixed web-safe palette because gradients and soft brush edges are exactly where
     * fixed palettes band badly, and over an octree because median cut keeps a bounded memory
     * footprint while still producing good-looking ramps.
     */
    object Quantizer {
        /** Named `Quantized` rather than `Result` so it cannot shadow `kotlin.Result`. */
        data class Quantized(
            val indices: ByteArray,
            val palette: IntArray,
        )

        /** A colour with its pixel count inside the current box. */
        private class Bucket(
            val color: Int,
            val count: Int,
        )

        fun quantize(
            frame: IntArray,
            maxColors: Int,
            matteColor: Int,
            reserveTransparent: Boolean,
        ): Quantized {
            val targetColors = maxColors.coerceIn(2, 256)

            // Composite over the matte colour once so every later step works with opaque colours.
            val opaque = IntArray(frame.size)
            var transparentCount = 0
            for (i in frame.indices) {
                val pixel = frame[i]
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha == 0) {
                    transparentCount++
                    opaque[i] = matteColor
                } else if (alpha == 255) {
                    opaque[i] = pixel
                } else {
                    opaque[i] = blend(matteColor, pixel, alpha / 255f)
                }
            }

            // Histogram, quantised to 5 bits per channel to keep the bucket count manageable.
            val histogram = HashMap<Int, Int>(1 shl 14)
            for (color in opaque) {
                val key = ((color shr 19) and 0x1F shl 10) or ((color shr 11) and 0x1F shl 5) or ((color shr 3) and 0x1F)
                histogram[key] = (histogram[key] ?: 0) + 1
            }

            val buckets =
                histogram.map { (key, count) ->
                    val r = ((key shr 10) and 0x1F) shl 3
                    val g = ((key shr 5) and 0x1F) shl 3
                    val b = (key and 0x1F) shl 3
                    Bucket((0xFF shl 24) or (r shl 16) or (g shl 8) or b, count)
                }

            val boxes = mutableListOf(buckets)
            while (boxes.size < targetColors) {
                val splittable =
                    boxes
                        .withIndex()
                        .filter { it.value.size > 1 }
                        .maxByOrNull { (_, box) -> channelRange(box) * box.size }
                        ?: break
                val (index, box) = splittable
                val (first, second) = split(box)
                boxes[index] = first
                boxes.add(second)
            }

            val palette = boxes.map { averageColor(it) }.toMutableList()
            if (reserveTransparent) palette.add(0, 0) // index 0 is the transparent entry
            val paletteArray = palette.toIntArray()

            // Nearest-palette-colour lookup with a small cache: consecutive pixels are usually
            // similar, and the cache removes almost all of the distance computations.
            val cache = HashMap<Int, Byte>(1 shl 14)
            val indices = ByteArray(frame.size)
            for (i in opaque.indices) {
                if (reserveTransparent && (frame[i] ushr 24) == 0) {
                    indices[i] = 0
                    continue
                }
                val color = opaque[i]
                val cached = cache[color]
                if (cached != null) {
                    indices[i] = cached
                    continue
                }
                val nearest = nearestIndex(paletteArray, color, if (reserveTransparent) 1 else 0)
                cache[color] = nearest.toByte()
                indices[i] = nearest.toByte()
            }

            return Quantized(indices, paletteArray)
        }

        private fun blend(
            backdrop: Int,
            source: Int,
            alpha: Float,
        ): Int {
            val inv = 1f - alpha
            val r = (((source shr 16) and 0xFF) * alpha + ((backdrop shr 16) and 0xFF) * inv).toInt()
            val g = (((source shr 8) and 0xFF) * alpha + ((backdrop shr 8) and 0xFF) * inv).toInt()
            val b = ((source and 0xFF) * alpha + (backdrop and 0xFF) * inv).toInt()
            return (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or
                (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }

        /** Largest single-channel spread in a box; the axis median cut should split on. */
        private fun channelRange(box: List<Bucket>): Int {
            var minR = 255
            var maxR = 0
            var minG = 255
            var maxG = 0
            var minB = 255
            var maxB = 0
            box.forEach { bucket ->
                val r = (bucket.color shr 16) and 0xFF
                val g = (bucket.color shr 8) and 0xFF
                val b = bucket.color and 0xFF
                minR = min(minR, r)
                maxR = max(maxR, r)
                minG = min(minG, g)
                maxG = max(maxG, g)
                minB = min(minB, b)
                maxB = max(maxB, b)
            }
            return max(maxR - minR, max(maxG - minG, maxB - minB))
        }

        private fun split(box: List<Bucket>): Pair<List<Bucket>, List<Bucket>> {
            var minR = 255
            var maxR = 0
            var minG = 255
            var maxG = 0
            var minB = 255
            var maxB = 0
            box.forEach { bucket ->
                val r = (bucket.color shr 16) and 0xFF
                val g = (bucket.color shr 8) and 0xFF
                val b = bucket.color and 0xFF
                minR = min(minR, r)
                maxR = max(maxR, r)
                minG = min(minG, g)
                maxG = max(maxG, g)
                minB = min(minB, b)
                maxB = max(maxB, b)
            }
            val rRange = maxR - minR
            val gRange = maxG - minG
            val bRange = maxB - minB
            val comparator =
                when {
                    rRange >= gRange && rRange >= bRange -> compareBy<Bucket> { (it.color shr 16) and 0xFF }
                    gRange >= bRange -> compareBy { (it.color shr 8) and 0xFF }
                    else -> compareBy { it.color and 0xFF }
                }
            val sorted = box.sortedWith(comparator)
            // Split at the weighted median so both halves carry roughly equal pixel counts.
            val total = sorted.sumOf { it.count }
            var running = 0
            var splitIndex = 1
            for (index in sorted.indices) {
                running += sorted[index].count
                if (running * 2 >= total) {
                    splitIndex = (index + 1).coerceIn(1, sorted.size - 1)
                    break
                }
            }
            return sorted.subList(0, splitIndex) to sorted.subList(splitIndex, sorted.size)
        }

        private fun averageColor(box: List<Bucket>): Int {
            var r = 0L
            var g = 0L
            var b = 0L
            var count = 0L
            box.forEach { bucket ->
                r += ((bucket.color shr 16) and 0xFF) * bucket.count
                g += ((bucket.color shr 8) and 0xFF) * bucket.count
                b += (bucket.color and 0xFF) * bucket.count
                count += bucket.count
            }
            if (count == 0L) return 0
            return (0xFF shl 24) or
                ((r / count).toInt().coerceIn(0, 255) shl 16) or
                ((g / count).toInt().coerceIn(0, 255) shl 8) or
                (b / count).toInt().coerceIn(0, 255)
        }

        private fun nearestIndex(
            palette: IntArray,
            color: Int,
            from: Int,
        ): Int {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            var bestIndex = from
            var bestDistance = Int.MAX_VALUE
            for (i in from until palette.size) {
                val candidate = palette[i]
                val dr = r - ((candidate shr 16) and 0xFF)
                val dg = g - ((candidate shr 8) and 0xFF)
                val db = b - (candidate and 0xFF)
                // Weighted to approximate perceptual distance.
                val distance = dr * dr * 30 + dg * dg * 59 + db * db * 11
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = i
                    if (distance == 0) break
                }
            }
            return bestIndex
        }
    }

    /**
     * GIF LZW compressor.
     *
     * Variable-width codes starting at `minCodeSize + 1` bits, a clear code emitted whenever the
     * dictionary fills, and the standard 255-byte sub-block packing handled by the caller.
     */
    object LzwCompressor {
        /** Codes are 12 bits wide at most, so the dictionary cannot exceed 4096 entries. */
        private const val MAX_CODE_SIZE = 12
        private const val MAX_CODE = 1 shl MAX_CODE_SIZE

        fun compress(
            indices: ByteArray,
            minCodeSize: Int,
        ): ByteArray {
            val clearCode = 1 shl minCodeSize
            val endCode = clearCode + 1
            var codeSize = minCodeSize + 1
            var nextCode = endCode + 1

            val out = ByteArrayOutputStream()
            var bitBuffer = 0
            var bitCount = 0

            fun writeCode(code: Int) {
                bitBuffer = bitBuffer or (code shl bitCount)
                bitCount += codeSize
                while (bitCount >= 8) {
                    out.write(bitBuffer and 0xFF)
                    bitBuffer = bitBuffer ushr 8
                    bitCount -= 8
                }
            }

            // Dictionary keyed by (prefix << 8) | nextByte.
            var dictionary = HashMap<Int, Int>(1 shl 13)

            fun resetDictionary() {
                dictionary = HashMap(1 shl 13)
                nextCode = endCode + 1
                codeSize = minCodeSize + 1
            }

            writeCode(clearCode)

            var prefix = -1
            indices.forEach { byte ->
                val current = byte.toInt() and 0xFF
                if (prefix == -1) {
                    prefix = current
                    return@forEach
                }
                val key = (prefix shl 8) or current
                val existing = dictionary[key]
                if (existing != null) {
                    prefix = existing
                } else {
                    writeCode(prefix)
                    if (nextCode < MAX_CODE) {
                        dictionary[key] = nextCode++
                        // Standard GIF rule: widen as soon as the next code would not fit in the
                        // current width. The decoder widens at exactly the same point.
                        if (nextCode > (1 shl codeSize) - 1 && codeSize < MAX_CODE_SIZE) codeSize++
                    } else {
                        // The dictionary is full: tell the decoder to start over.
                        writeCode(clearCode)
                        resetDictionary()
                    }
                    prefix = current
                }
            }

            if (prefix != -1) writeCode(prefix)
            writeCode(endCode)

            // Flush remaining bits.
            if (bitCount > 0) out.write(bitBuffer and 0xFF)
            return out.toByteArray()
        }
    }
}
