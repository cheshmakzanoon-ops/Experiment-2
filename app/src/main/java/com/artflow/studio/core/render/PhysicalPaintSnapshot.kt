package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask

/** Immutable ownership boundary for physical paint retained by an interactive transform. */
class PhysicalPaintSnapshot internal constructor(
    internal val plane: PhysicalPaintPlane?,
    private val workingLimit: Long = Runtime.getRuntime().maxMemory() / 3,
) {
    /** Applies geometry to selected scalar contributions; [render] must not apply the selection again. */
    fun transformed(
        selection: SelectionMask? = null,
        render: (PixelBuffer, PixelBuffer) -> Unit,
    ): PhysicalPaintSnapshot {
        val source = plane ?: return this
        require(selection == null || (selection.width == source.width && selection.height == source.height)) {
            "Selection and physical-paint dimensions differ"
        }
        requireTransformCapacity(listOf(this))
        val packed = source.toPackedPixels()
        if (selection != null) {
            for (index in packed.pixels.indices) {
                packed.pixels[index] = (packed.pixels[index] and 0x00FFFFFF) or ((selection.coverage[index].toInt() and 0xFF) shl 24)
            }
        }
        val moved = PixelBuffer(source.width, source.height)
        render(packed, moved)
        // Reset must restore the original floats, not repeatedly quantize an unchanged plane.
        if (packed.pixels.contentEquals(moved.pixels)) return this
        val transformed = PhysicalPaintPlane.fromPackedPixels(moved)
        // These are amounts of water/pigment and signed relief, not opacity-composited colours.
        // Preserve the unselected amount and add the remapped amount without source-over loss.
        if (selection != null) source.addUnselectedTo(transformed, selection)
        val result = transformed.takeUnless { it.isEmpty() }
        return PhysicalPaintSnapshot(result, workingLimit)
    }

    internal fun sameContent(other: PhysicalPaintSnapshot): Boolean =
        if (plane == null) other.plane == null else plane === other.plane || plane.contentEquals(other.plane)

    private fun workingBytes(): Long {
        val source = plane ?: return 0L
        val pixels = source.width.toLong() * source.height
        val side = PhysicalPaintPlane.TILE_SIZE.toLong()
        val tiles = ((source.width + side - 1) / side) * ((source.height + side - 1) / side)
        // Retained source, packed buffers and selection workspace, plus a dense result plane.
        return source.estimatedBytes() + pixels * PACKED_WORKSPACE_BYTES + tiles * side * side * PHYSICAL_BYTES
    }

    companion object {
        val EMPTY = PhysicalPaintSnapshot(null)

        /** Admit every companion before any physical result is allocated. */
        fun requireTransformCapacity(sources: List<PhysicalPaintSnapshot>) {
            val active = sources.filter { it.plane != null }
            val limit = active.minOfOrNull { it.workingLimit } ?: return
            require(active.sumOf { it.workingBytes() } <= limit) {
                "This physical-paint transform needs too much memory. Use fewer layers or a smaller canvas."
            }
        }

        private const val PACKED_WORKSPACE_BYTES = 16L
        private const val PHYSICAL_BYTES = 12L
    }
}
