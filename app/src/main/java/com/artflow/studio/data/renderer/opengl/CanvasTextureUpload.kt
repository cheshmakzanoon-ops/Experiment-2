package com.artflow.studio.data.renderer.opengl

/** Preview pixels only; document dimensions and input coordinates never use these sizes. */
internal data class CanvasTextureSize(
    val width: Int,
    val height: Int,
) {
    val longest: Int get() = maxOf(width, height)
}

/**
 * GL's queried edge limit is an upper bound, not a promise that an allocation fits GPU memory.
 * Retry smaller previews after an upload failure, retaining the full source for editing/export.
 */
internal object CanvasTextureUpload {
    fun fit(
        width: Int,
        height: Int,
        maxEdge: Int,
    ): CanvasTextureSize? {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return null
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return CanvasTextureSize(width, height)
        // Long arithmetic keeps very wide/tall input within the device's exact queried bound.
        return CanvasTextureSize(
            (width.toLong() * maxEdge / longest).toInt().coerceAtLeast(1),
            (height.toLong() * maxEdge / longest).toInt().coerceAtLeast(1),
        )
    }

    fun upload(
        width: Int,
        height: Int,
        maxEdge: Int,
        attempt: (CanvasTextureSize) -> Boolean,
    ): CanvasTextureSize? {
        var size = fit(width, height, maxEdge) ?: return null
        while (true) {
            if (attempt(size)) return size
            if (size.longest == 1) return null
            size = fit(width, height, size.longest / 2) ?: return null
        }
    }
}
