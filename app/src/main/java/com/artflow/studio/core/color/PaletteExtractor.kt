package com.artflow.studio.core.color

import com.artflow.studio.core.pixels.PixelBuffer
import kotlin.math.max

/**
 * Procreate's New from Photo: the main colours of a picture, found by splitting its colour space
 * (median cut) on a sample of opaque pixels, ordered from light to dark.
 */
object PaletteExtractor {
    fun colors(
        image: PixelBuffer,
        count: Int = 12,
    ): List<Int> {
        val step = max(1, image.pixels.size / MAX_SAMPLES)
        val samples =
            image.pixels
                .filterIndexed { index, pixel -> index % step == 0 && (pixel ushr 24) >= OPAQUE }
                .map { it or OPAQUE_MASK }
        if (samples.isEmpty()) return emptyList()
        val boxes = mutableListOf(samples)
        // Split the box with the widest colour range until there are enough, or every box is one colour.
        var index = widestBox(boxes)
        while (boxes.size < count && index != null) {
            val (channel, _) = spread(boxes[index])
            val sorted = boxes.removeAt(index).sortedBy { channelOf(it, channel) }
            boxes += sorted.subList(0, sorted.size / 2)
            boxes += sorted.subList(sorted.size / 2, sorted.size)
            index = widestBox(boxes)
        }
        return boxes
            .map(::average)
            .distinct()
            .sortedByDescending { ((it shr 16) and 0xFF) * 299 + ((it shr 8) and 0xFF) * 587 + (it and 0xFF) * 114 }
    }

    /** The box with the widest colour range, or null when every box holds a single colour. */
    private fun widestBox(boxes: List<List<Int>>): Int? =
        boxes.indices
            .filter { boxes[it].size > 1 }
            .maxByOrNull { spread(boxes[it]).second }
            ?.takeIf { spread(boxes[it]).second > 0 }

    /** The channel (0 red, 1 green, 2 blue) with the widest range, and that range. */
    private fun spread(box: List<Int>): Pair<Int, Int> =
        (0..2)
            .map { channel -> channel to (box.maxOf { channelOf(it, channel) } - box.minOf { channelOf(it, channel) }) }
            .maxBy { it.second }

    private fun channelOf(
        argb: Int,
        channel: Int,
    ): Int = (argb shr (16 - channel * 8)) and 0xFF

    private fun average(box: List<Int>): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        box.forEach {
            r += (it shr 16) and 0xFF
            g += (it shr 8) and 0xFF
            b += it and 0xFF
        }
        val n = box.size
        return OPAQUE_MASK or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private const val MAX_SAMPLES = 20_000
    private const val OPAQUE = 128
    private const val OPAQUE_MASK = 0xFF shl 24
}
