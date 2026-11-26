package com.artflow.studio.core.animation

import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.domain.model.layer.Layer

/** Conservative admission for the full compositor and every overlapping ghost representation. */
internal object OnionSkinMemory {
    const val LIMIT_MESSAGE = "Not enough memory for onion skins. Reduce the number of ghost frames or the canvas size."
    const val RENDERER_BYTES_PER_PIXEL = 12L // Retained bitmap, texture, and one upload/resize allowance.
    private const val MAX_WORK_BYTES = 128L * 1024 * 1024
    private const val FIXED_BYTES = 2L * 1024 * 1024
    private const val MAX_EDGE = 1024
    private const val MIN_EDGE = 64

    class Plan internal constructor(
        val width: Int,
        val height: Int,
        private val scratchBytes: List<Long>,
    ) {
        private val outputBytes = width.toLong() * height * 4L
        val rendererBytes = width.toLong() * height * RENDERER_BYTES_PER_PIXEL * scratchBytes.size

        /** Current headroom already includes completed ghost arrays; reserve only the remaining ones. */
        fun fitsFrame(
            index: Int,
            availableBytes: Long,
            retainedRendererBytes: Long,
        ): Boolean {
            val scratch = scratchBytes.getOrNull(index) ?: return false
            val remaining = scratchBytes.size - index
            // Two extra outputs cover scaling and a separately owned tint silhouette.
            return fits(scratch + outputBytes * (remaining + 2L) + rendererBytes, availableBytes, retainedRendererBytes)
        }

        fun fitsPublication(
            availableBytes: Long,
            retainedRendererBytes: Long,
        ): Boolean = fits(rendererBytes, availableBytes, retainedRendererBytes)

        fun fitsPreparation(
            index: Int,
            availableBytes: Long,
            retainedRendererBytes: Long,
        ): Boolean {
            val remaining = scratchBytes.size - index
            if (index !in scratchBytes.indices) return false
            return fits(outputBytes * (remaining + 2L) + rendererBytes, availableBytes, retainedRendererBytes)
        }
    }

    fun plan(
        width: Int,
        height: Int,
        frames: List<List<Layer>>,
        availableBytes: Long,
        retainedRendererBytes: Long,
    ): Plan? {
        if (!CanvasOperations.isSizeSafe(width, height) || frames.isEmpty()) return null
        val scratch = frames.map { compositorBytes(width, height, it) }
        var edge = MAX_EDGE
        while (edge >= MIN_EDGE) {
            val size = CanvasOperations.fitInside(width, height, edge, edge)
            val candidate = Plan(size.first, size.second, scratch)
            if (scratch.indices.all { candidate.fitsFrame(it, availableBytes, retainedRendererBytes) }) return candidate
            edge /= 2
        }
        return null
    }

    private fun fits(
        additionalBytes: Long,
        availableBytes: Long,
        retainedRendererBytes: Long,
    ): Boolean {
        if (availableBytes <= 0 || retainedRendererBytes < 0) return false
        val allowance = minOf(availableBytes, MAX_WORK_BYTES)
        if (retainedRendererBytes >= allowance) return false
        return additionalBytes <= allowance - retainedRendererBytes - FIXED_BYTES
    }

    /**
     * Count full-size planes, including nested group/clipping targets, filter/effect work,
     * feathered masks and stroke/physical scratch. Sum effect costs instead of relying on GC
     * between layers. Existing source rasters are already reflected in current heap headroom.
     * These are admission estimates, not measured native-driver or total-process peaks.
     */
    private fun compositorBytes(
        width: Int,
        height: Int,
        layers: List<Layer>,
    ): Long {
        val groups = layers.count { it.isGroup }.toLong()
        val effects = layers.count { it.effects != null }.toLong()
        val filters = layers.count { it.filterType != null || it.adjustmentType != null }.toLong()
        val feathers = layers.count { it.hasActiveMask() && it.maskFeather > 0f }.toLong()
        val strokes = if (layers.any { it.strokes.isNotEmpty() }) 10L else 0L
        val planes = 6L + groups * 2L + effects * 10L + filters * 6L + feathers * 3L + strokes
        return width.toLong() * height * 4L * planes
    }
}
