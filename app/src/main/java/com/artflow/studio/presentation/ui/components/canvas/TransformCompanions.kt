package com.artflow.studio.presentation.ui.components.canvas

import com.artflow.studio.core.canvas.LayerLinks
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.LayerTransform
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.Quad
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.TransformQuad
import com.artflow.studio.core.pixels.WarpMesh
import com.artflow.studio.core.render.PhysicalPaintSnapshot
import com.artflow.studio.domain.repository.canvas.CanvasRepository

/**
 * Layers that move with the active layer: multi-selected layers and all their persistent links.
 * Each gets its own edit session so the canvas previews it live,
 * and all of them commit with the active layer as one undoable step.
 */
internal class TransformCompanions {
    class Layer(
        val layerId: Long,
        // A paintable layer can have no raster or strokes yet. Keep that transparent source
        // implicit until beginRasterEdit has checked whether an edit buffer fits the budget.
        val base: PixelBuffer?,
        val physicalSource: PhysicalPaintSnapshot = PhysicalPaintSnapshot.EMPTY,
    )

    /** Layers chosen besides the active one. */
    var ids: Set<Long> = emptySet()

    private var open: List<Pair<Layer, CanvasRepository.RasterEditSession>> = emptyList()

    /** Companion pixels; a selection keeps the transform to the active layer. */
    suspend fun load(
        repository: CanvasRepository,
        activeLayerId: Long,
        selection: SelectionMask?,
    ): List<Layer>? {
        if (selection != null) return emptyList()
        val layers = repository.getAllLayers()
        val members = LayerLinks.expand(layers, ids + activeLayerId) ?: return null
        // Quad gestures only move pixel planes. Precision transforms also move editable masks.
        if (!LayerLinks.editable(layers, members) || members.any { it.hasMask() }) return null
        return members.filter { it.id != activeLayerId }.map { member ->
            Layer(member.id, repository.layerPixels(member.id), repository.physicalPaintSource(member.id))
        }
    }

    /** Opens an edit on each companion for a drag. */
    suspend fun open(
        repository: CanvasRepository,
        layers: List<Layer>,
    ): Boolean {
        cancel(repository)
        for (layer in layers) {
            val session = repository.beginRasterEdit(layer.layerId)
            if (session == null) {
                cancel(repository)
                return false
            }
            session.markGeometricPreview()
            open = open + (layer to session)
        }
        return true
    }

    val sessions: List<CanvasRepository.RasterEditSession> get() = open.map { it.second }

    fun hasChanges(): Boolean =
        open.any { (layer, session) ->
            val base = layer.base
            session.physicalPaintChanged ||
                if (base == null) session.buffer.pixels.any { it != 0 } else !base.pixels.contentEquals(session.buffer.pixels)
        }

    /** Only final placement transforms physical fields; each source remains fixed for Reset. */
    fun transformPhysical(
        bounds: IntBounds,
        quad: Quad,
        mesh: WarpMesh?,
        interpolation: TransformQuad.Interpolation,
    ) {
        open.forEach { (layer, session) ->
            session.transformPhysical(layer.physicalSource) { source, target ->
                TransformQuad.renderShape(source, target, bounds, quad, mesh, null, interpolation)
            }
        }
    }

    /** Places every open companion the way the active layer is placed. */
    fun render(
        bounds: IntBounds,
        quad: Quad,
        mesh: WarpMesh?,
        interpolation: TransformQuad.Interpolation,
    ) {
        open.forEach { (layer, session) ->
            val base = layer.base
            if (base == null) {
                session.buffer.clear()
            } else {
                TransformQuad.renderShape(base, session.buffer, bounds, quad, mesh, null, interpolation)
            }
        }
    }

    fun move(
        dx: Int,
        dy: Int,
    ) {
        open.forEach { (layer, session) ->
            val base = layer.base
            if (base == null) {
                session.buffer.clear()
            } else {
                LayerTransform.render(base, session.buffer, LayerTransform.Params(0f, 0f, dx.toFloat(), dy.toFloat()))
            }
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
                val base = layer.base ?: return@fold union
                val own = LayerTransform.floatingBounds(base, null) ?: return@fold union
                union?.union(own) ?: own
            }
    }
}
