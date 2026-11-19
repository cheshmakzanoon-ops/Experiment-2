package com.artflow.studio.data.repository.canvas

/**
 * The eager document loader retains one ARGB plane per saved raster or mask, including duplicates.
 * Editing must reserve that same cost before adding a plane, even while copy-on-write shares pixels.
 * The remaining heap is reserved for history, rendering, codecs and other application state.
 */
internal class CanvasRasterBudget(
    val heapBytes: Long = Runtime.getRuntime().maxMemory(),
) {
    init {
        require(heapBytes > 0) { "Heap budget must be positive" }
    }

    fun allows(
        width: Int,
        height: Int,
        planes: Int,
    ): Boolean {
        if (width <= 0 || height <= 0 || planes < 0) return false
        if (planes == 0) return true
        // Division avoids overflow even for hostile dimensions/counts. Each plane uses four bytes.
        return width.toLong() * height <= heapBytes / 3 / 4 / planes
    }

    fun requireFits(
        width: Int,
        height: Int,
        planes: Int,
    ) {
        require(allows(width, height, planes)) { EDIT_LIMIT_MESSAGE }
    }

    companion object {
        const val EDIT_LIMIT_MESSAGE =
            "This edit would exceed this device's artwork memory limit. Reduce layers, masks, frames or canvas size."
    }
}
