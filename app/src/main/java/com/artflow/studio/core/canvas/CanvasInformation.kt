package com.artflow.studio.core.canvas

/**
 * Layer figures for Canvas > Canvas information: how many layers the artwork uses and the most
 * this canvas size can hold on this device.
 *
 * Two limits apply and the smaller one is shown:
 * - the hard count limit across all frames (the repository refuses to add layers past it), passed
 *   in by the caller so this file does not depend on the data layer;
 * - a memory limit that mirrors the check the repository runs when an artwork is opened: the raster
 *   bytes of its layers must not exceed one third of the heap. Each layer is one ARGB raster of
 *   `width * height * 4` bytes. The open check also counts each layer mask file as a raster, so
 *   masks reduce the real room; this figure counts layer rasters only and is an upper bound.
 *
 * Layers are counted across every frame, because the limits apply to the whole artwork.
 *
 * Not included: a stroke count. Committed strokes are rasterised into their layer and the vector
 * copy is cleared, so the layer model does not hold the strokes an artist drew. A count from it
 * would under-report, so the panel does not show one.
 */
object CanvasInformation {
    /** Bytes per pixel of an ARGB layer raster. */
    const val BYTES_PER_PIXEL = 4L

    /** The open-time memory check allows the layers' raster bytes up to this fraction of the heap, as 1 / N. */
    const val OPEN_HEAP_DIVISOR = 3L

    /** Bytes of one ARGB layer raster of the given size; zero for an empty canvas. */
    fun layerBytes(
        width: Int,
        height: Int,
    ): Long = if (width <= 0 || height <= 0) 0L else width.toLong() * height * BYTES_PER_PIXEL

    /**
     * The most layers this canvas can hold: [hardLimit], or fewer when the memory budget
     * (`maxHeapBytes / OPEN_HEAP_DIVISOR`) runs out first. An empty canvas has no size to limit
     * by, so it reports [hardLimit]. A canvas too large for even one layer reports 0.
     *
     * @param maxHeapBytes the heap ceiling, as `Runtime.getRuntime().maxMemory()` reports it.
     */
    fun layerLimit(
        width: Int,
        height: Int,
        maxHeapBytes: Long,
        hardLimit: Int,
    ): Int {
        val bytes = layerBytes(width, height)
        if (bytes <= 0L) return hardLimit.coerceAtLeast(0)
        val budget = maxHeapBytes.coerceAtLeast(0L) / OPEN_HEAP_DIVISOR
        val byMemory = (budget / bytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return minOf(hardLimit, byMemory).coerceAtLeast(0)
    }

    /** Layers across every frame of the artwork, as the hard limit counts them. */
    fun layerTotal(layerCountsPerFrame: List<Int>): Int = layerCountsPerFrame.sumOf { it.coerceAtLeast(0) }
}
