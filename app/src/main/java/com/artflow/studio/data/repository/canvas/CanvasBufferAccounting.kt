package com.artflow.studio.data.repository.canvas

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.render.PhysicalPaintPlane
import java.util.IdentityHashMap

/**
 * Counts the backing arrays retained by a set of canvas states. Copy-on-write snapshots may share
 * a buffer, or distinct buffer wrappers may share the same array; both retain that allocation once.
 * Use one instance for all history stacks so moving an entry between undo and redo does not change
 * its accounted cost. A new selection has its own coverage array even when its raster is shared.
 */
internal class CanvasBufferAccounting {
    private val arrays = IdentityHashMap<Any, Boolean>()

    var bytes: Long = 0L
        private set

    fun includeRaster(buffer: PixelBuffer?) {
        val pixels = buffer?.pixels ?: return
        if (arrays.put(pixels, true) == null) bytes += pixels.size.toLong() * Int.SIZE_BYTES
    }

    /** A physical plane holds three float arrays per allocated 64 x 64 tile, shared by reference between snapshots. */
    fun includePhysical(plane: PhysicalPaintPlane?) {
        plane ?: return
        if (arrays.put(plane, true) == null) bytes += plane.estimatedBytes()
    }

    fun includeSelection(mask: SelectionMask?) {
        val coverage = mask?.coverage ?: return
        if (arrays.put(coverage, true) == null) bytes += coverage.size.toLong()
    }
}
