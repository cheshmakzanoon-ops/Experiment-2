package com.artflow.studio.presentation.ui.components.canvas

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.LayerTransform
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.Quad
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.TransformQuad
import com.artflow.studio.core.pixels.WarpMesh
import com.artflow.studio.domain.repository.canvas.CanvasRepository

/**
 * Layers that move with the active layer during a transform: the ones multi-selected in the
 * Layers panel, as in Procreate. Each gets its own edit session so the canvas previews it live,
 * and all of them commit with the active layer as one undoable step.
 */
internal class TransformCompanions {
    class Layer(
        val layerId: Long,
        val base: PixelBuffer,
    )

    /** Layers chosen besides the active one. */
    var ids: Set<Long> = emptySet()

    private var open: List<Pair<Layer, CanvasRepository.RasterEditSession>> = emptyList()

    /** Companion pixels; a selection keeps the transform to the active layer. */
    suspend fun load(
        repository: CanvasRepository,
        activeLayerId: Long,
        selection: SelectionMask?,
    ): List<Layer> {
        if (selection != null) return emptyList()
        return ids.filter { it != activeLayerId }.mapNotNull { id -> repository.layerPixels(id)?.let { Layer(id, it) } }
    }

    /** Opens an edit on each companion for a drag. */
    suspend fun open(
        repository: CanvasRepository,
        layers: List<Layer>,
    ) {
        cancel(repository)
        open = layers.mapNotNull { layer -> repository.beginRasterEdit(layer.layerId)?.let { layer to it } }
    }

    val sessions: List<CanvasRepository.RasterEditSession> get() = open.map { it.second }

    /** Places every open companion the way the active layer is placed. */
    fun render(
        bounds: IntBounds,
        quad: Quad,
        mesh: WarpMesh?,
        highQuality: Boolean,
    ) {
        open.forEach { (layer, session) ->
            TransformQuad.renderShape(layer.base, session.buffer, bounds, quad, mesh, null, highQuality)
        }
    }

    /** Closes the companions' edits; committed ones are already gone, so this only discards the rest. */
    suspend fun cancel(repository: CanvasRepository) {
        val closing = open
        open = emptyList()
        closing.forEach { repository.cancelRasterEdit(it.second) }
    }

    companion object {
        /** What the whole group moves: the active layer's content and every companion's. */
        fun bounds(
            active: IntBounds?,
            layers: List<Layer>,
        ): IntBounds? =
            layers.fold(active) { union, layer ->
                val own = LayerTransform.floatingBounds(layer.base, null) ?: return@fold union
                union?.union(own) ?: own
            }
    }
}
