package com.artflow.studio.data.renderer

import com.artflow.studio.core.pixels.PixelBuffer

/** EXIF's eight row/column orientations. Native artwork rasters never pass through this transform. */
internal object ImageOrientation {
    fun dimensions(
        width: Int,
        height: Int,
        orientation: Int,
    ): Pair<Int, Int> = if (orientation in 5..8) height to width else width to height

    /** The decoder's bitmap is already recycled; only the source and destination IntArrays coexist. */
    fun apply(
        source: PixelBuffer,
        orientation: Int,
    ): PixelBuffer {
        if (orientation !in 2..8) return source
        val (width, height) = dimensions(source.width, source.height, orientation)
        val result = PixelBuffer(width, height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val destinationX =
                    when (orientation) {
                        2, 3 -> source.width - 1 - x
                        5, 8 -> y
                        6, 7 -> source.height - 1 - y
                        else -> x
                    }
                val destinationY =
                    when (orientation) {
                        3, 4 -> source.height - 1 - y
                        5, 6 -> x
                        7, 8 -> source.width - 1 - x
                        else -> y
                    }
                result.pixels[destinationY * width + destinationX] = source.pixels[y * source.width + x]
            }
        }
        return result
    }
}
