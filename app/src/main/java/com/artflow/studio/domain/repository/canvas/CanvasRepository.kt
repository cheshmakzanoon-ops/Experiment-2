package com.artflow.studio.domain.repository.canvas

import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The editor's document API.
 *
 * `CanvasRepository` owns the editable document: layer stacks, pixel data, masks, adjustment and
 * filter parameters, animation frames and undo history. Everything in it is expressed in terms of
 * [PixelBuffer] (pure Kotlin pixel data) or immutable domain models, which is what lets the tools
 * stay testable and lets the compositor be shared by the live canvas, the saved file and exports.
 */
interface CanvasRepository {
    // -----------------------------------------------------------------------------------------
    // Canvas lifecycle
    // -----------------------------------------------------------------------------------------

    /** Creates a blank canvas of [width] x [height]. Returns the canvas id. */
    suspend fun createCanvas(
        width: Int,
        height: Int,
        dpi: Int,
    ): Long

    /**
     * Loads the saved project, or creates a blank canvas when there is nothing saved yet.
     * This is the entry point used when the editor screen opens.
     */
    suspend fun loadOrCreate(
        projectId: Long,
        width: Int,
        height: Int,
        dpi: Int,
    ): CanvasState?

    /** Loads a saved project. Returns null when no document exists. */
    suspend fun loadCanvas(projectId: Long): CanvasState?

    /** True when an autosave newer than the last explicit save exists. */
    suspend fun hasRecovery(projectId: Long): Boolean

    /** Restores the autosave copy; used by the crash-recovery prompt. */
    suspend fun recoverAutosave(projectId: Long): CanvasState?

    /**
     * Persists the document: full-fidelity JSON plus a flattened PNG and a gallery thumbnail.
     * @return the thumbnail path, or null when there was nothing to save.
     */
    suspend fun saveCanvas(projectId: Long): String?

    /** Writes the rolling autosave without touching the saved document. */
    suspend fun autosave(projectId: Long)

    /** Explicitly discards only the recovery snapshot, never the saved artwork. */
    suspend fun discardRecovery(projectId: Long)

    /** True when there are unsaved changes. */
    fun hasUnsavedChanges(): Boolean

    /** Total undo steps currently held. */
    val undoDepth: Int

    /** Total redo steps currently held. */
    val redoDepth: Int

    /** The project this canvas belongs to, or 0 when unsaved. */
    fun projectId(): Long

    // -----------------------------------------------------------------------------------------
    // Strokes
    // -----------------------------------------------------------------------------------------

    fun beginStroke(
        x: Float,
        y: Float,
        pressure: Float,
        brushParams: BrushParams,
        layerId: Long,
        isEraser: Boolean = false,
    ): Long

    fun continueStroke(
        strokeId: Long,
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float = 0f,
        tiltY: Float = 0f,
    )

    fun endStroke(strokeId: Long)

    fun cancelStroke(strokeId: Long)

    /** Strokes currently in flight, so the canvas view can render the live stroke. */
    fun activeStroke(strokeId: Long): Stroke?

    /**
     * Symmetry guide applied to every committed stroke (Phase 33).
     *
     * The repository owns this because the mirrored copies have to be baked into the layer pixels
     * together with the original, or the saved file would only contain one of them.
     */
    fun setSymmetry(settings: com.artflow.studio.core.symmetry.SymmetryEngine.Settings)

    /**
     * Asks listeners to re-composite without changing the document.
     *
     * Interactive pixel tools mutate a provisional session buffer. The display uses
     * compositePreview; saving and exporting use only committed layer content.
     */
    fun requestPreviewRefresh()

    /** Replaces the stroke list of a layer (transform commits, symmetry replay). */
    suspend fun replaceLayerStrokes(
        layerId: Long,
        strokes: List<Stroke>,
    ): Boolean

    // -----------------------------------------------------------------------------------------
    // Pixel editing
    // -----------------------------------------------------------------------------------------

    /**
     * An open pixel-editing session on one layer.
     *
     * Interactive tools (smudge, clone, heal, liquify) call [CanvasRepository.beginRasterEdit]
     * once for the gesture and then mutate the buffer repeatedly, which keeps the undo history at
     * one entry per gesture instead of one per pointer sample.
     */
    class RasterEditSession internal constructor(
        val layerId: Long,
        val buffer: PixelBuffer,
        internal val snapshotToken: Long,
    )

    /** A caller-owned copy of the layer's committed pixels. */
    suspend fun layerPixels(layerId: Long): PixelBuffer?

    /** Starts a provisional edit. Exactly one undo entry is created only on a successful commit. */
    suspend fun beginRasterEdit(layerId: Long): RasterEditSession?

    /** Commits a still-valid session into memory; save/autosave handle durable storage. */
    suspend fun commitRasterEdit(
        session: RasterEditSession,
        description: String,
    ): Boolean

    /** Discards only this provisional session without altering committed pixels or history. */
    suspend fun cancelRasterEdit(session: RasterEditSession)

    /** One-shot pixel edit: snapshots, applies [edit], persists and invalidates. */
    suspend fun applyRasterEdit(
        layerId: Long,
        description: String,
        edit: (PixelBuffer) -> Unit,
    ): Boolean

    /** Replaces a layer's pixels wholesale (image import, text rasterisation, paste). */
    suspend fun setLayerPixels(
        layerId: Long,
        buffer: PixelBuffer,
        description: String,
    ): Boolean

    /** Sets the ink colour used by subsequent strokes. */
    fun setStrokeColor(color: Int)

    fun getStrokeColor(): Int

    // -----------------------------------------------------------------------------------------
    // Selection
    // -----------------------------------------------------------------------------------------

    /** The active selection, or null when nothing is selected. */
    fun selection(): SelectionMask?

    /** Replaces the selection (null clears it). */
    fun setSelection(mask: SelectionMask?)

    /** Clears the selection. */
    fun clearSelection()

    // -----------------------------------------------------------------------------------------
    // Layers
    // -----------------------------------------------------------------------------------------

    suspend fun addLayer(
        name: String? = null,
        index: Int? = null,
        opacity: Float = 1.0f,
    ): Layer

    suspend fun removeLayer(layerId: Long): Boolean

    suspend fun reorderLayer(
        layerId: Long,
        newIndex: Int,
    ): Boolean

    suspend fun duplicateLayer(layerId: Long): Long?

    suspend fun mergeLayers(
        sourceLayerId: Long,
        targetLayerId: Long,
    ): Boolean

    suspend fun mergeVisibleLayers(keepOriginals: Boolean = false): Long?

    suspend fun mergeLayerDown(layerId: Long): Boolean

    /** Flattens the layer stack into a single layer (destructive, undoable). */
    suspend fun flattenAllLayers(): Long?

    suspend fun setLayerVisibility(
        layerId: Long,
        isVisible: Boolean? = null,
    ): Boolean

    suspend fun setLayerOpacity(
        layerId: Long,
        opacity: Float,
    ): Boolean

    suspend fun setLayerName(
        layerId: Long,
        newName: String,
    ): Boolean

    suspend fun setLayerLock(
        layerId: Long,
        isLocked: Boolean,
    ): Boolean

    suspend fun setLayerBlendMode(
        layerId: Long,
        blendMode: BlendMode,
    ): Boolean

    suspend fun setLayerAlphaLock(
        layerId: Long,
        isLocked: Boolean? = null,
    ): Boolean

    suspend fun setLayerClippingMask(
        layerId: Long,
        isClipping: Boolean? = null,
    ): Boolean

    /** Marks a layer as a reference image (visible on the reference panel, never composited). */
    suspend fun setLayerReference(
        layerId: Long,
        isReference: Boolean,
    ): Boolean

    /** Links several layers so they move and transform together (Phase 28). */
    suspend fun linkLayers(layerIds: List<Long>): Boolean

    suspend fun unlinkLayer(layerId: Long): Boolean

    fun getAllLayers(): List<Layer>

    fun getActiveLayer(): Layer?

    fun setActiveLayer(layerId: Long): Boolean

    fun getActiveLayerId(): Long

    // -----------------------------------------------------------------------------------------
    // Masks, adjustments and filters
    // -----------------------------------------------------------------------------------------

    /** Adds a layer mask, optionally initialised from a selection. */
    suspend fun addLayerMask(
        layerId: Long,
        fromSelection: SelectionMask? = null,
    ): Boolean

    suspend fun removeLayerMask(layerId: Long): Boolean

    suspend fun invertLayerMask(layerId: Long): Boolean

    suspend fun setLayerMaskEnabled(
        layerId: Long,
        enabled: Boolean,
    ): Boolean

    suspend fun setLayerMaskDensity(
        layerId: Long,
        density: Float,
    ): Boolean

    suspend fun setLayerMaskFeather(
        layerId: Long,
        radius: Float,
    ): Boolean

    /** Paints on a layer mask (white reveals, black hides). */
    suspend fun paintLayerMask(
        layerId: Long,
        x: Float,
        y: Float,
        radius: Float,
        reveal: Boolean,
    ): Boolean

    /** Adds an adjustment layer at [index] (top when null). */
    suspend fun addAdjustmentLayer(
        type: AdjustmentType,
        index: Int? = null,
    ): Layer?

    suspend fun setAdjustmentParameter(
        layerId: Long,
        key: String,
        value: Float,
    ): Boolean

    suspend fun setAdjustmentParameters(
        layerId: Long,
        values: Map<String, Float>,
    ): Boolean

    suspend fun resetAdjustment(layerId: Long): Boolean

    /** Adds a filter layer above the active layer. */
    suspend fun addFilterLayer(
        type: FilterType,
        index: Int? = null,
    ): Layer?

    suspend fun setFilterAmount(
        layerId: Long,
        amount: Float,
    ): Boolean

    /** Bakes a filter layer's effect into the layer below it. */
    suspend fun rasterizeFilterLayer(layerId: Long): Boolean

    // -----------------------------------------------------------------------------------------
    // Canvas operations
    // -----------------------------------------------------------------------------------------

    suspend fun resizeCanvas(
        width: Int,
        height: Int,
        resample: Boolean,
        anchor: com.artflow.studio.core.canvas.CanvasOperations.Anchor = com.artflow.studio.core.canvas.CanvasOperations.Anchor.CENTER,
    ): Boolean

    suspend fun cropCanvas(bounds: IntBounds): Boolean

    suspend fun rotateCanvas(degrees: Int): Boolean

    suspend fun flipCanvas(vertical: Boolean): Boolean

    suspend fun trimTransparent(): Boolean

    suspend fun setCanvasDpi(dpi: Int): Boolean

    suspend fun setCanvasBackgroundColor(color: Int): Boolean

    suspend fun clearCanvas(color: Int = -1)

    /** Applies an adjustment to every layer (destructive, undoable). */
    suspend fun applyAdjustmentToCanvas(
        type: AdjustmentType,
        parameters: Map<String, Float>,
        toAllLayers: Boolean,
    ): Boolean

    // -----------------------------------------------------------------------------------------
    // Animation
    // -----------------------------------------------------------------------------------------

    /** The timeline state; collected by the UI. */
    val timeline: StateFlow<AnimationTimeline.State>

    fun frames(): List<AnimationFrame>

    fun activeFrameIndex(): Int

    suspend fun addFrame(duplicateCurrent: Boolean)

    /** @return true when a frame was actually removed (never removes the last frame). */
    suspend fun deleteFrame(index: Int): Boolean

    suspend fun moveFrame(
        from: Int,
        to: Int,
    ): Boolean

    suspend fun selectFrame(index: Int)

    suspend fun setFrameDuration(
        index: Int,
        durationMs: Int,
    ): Boolean

    suspend fun updateAnimationSettings(settings: AnimationSettings)

    /** Composites every frame; used by animation export and playback preview. */
    suspend fun compositeAllFrames(maxFrames: Int = 240): List<PixelBuffer>

    /** Renders one frame without allocating the entire animation. */
    suspend fun compositeFrame(
        index: Int,
        transparentBackground: Boolean = false,
    ): PixelBuffer?

    /** Provisional pixel-tool preview; never used by persistence or exports. */
    suspend fun compositePreview(): PixelBuffer?

    // -----------------------------------------------------------------------------------------
    // Compositing and invalidation
    // -----------------------------------------------------------------------------------------

    /** Flattens the document. Callers own the returned buffer. */
    suspend fun compositeBuffer(
        includeHidden: Boolean = false,
        applyAdjustments: Boolean = true,
    ): PixelBuffer?

    /** Captures pixels, layer properties, selection and frame timing from one document revision. */
    suspend fun exportSnapshot(
        allFrames: Boolean,
        includeHidden: Boolean,
        includeLayers: Boolean,
    ): CanvasExportSnapshot

    /** Layer rasters alongside their properties, for PSD export. */
    suspend fun layerBuffers(): List<Pair<Layer, PixelBuffer>>

    /** PNG bytes of the flattened document (flattened preview / saving). */
    suspend fun getCanvasBitmap(): ByteArray?

    /** Canvas dimensions. */
    fun getCanvasSize(): CanvasSize

    /** Background colour as ARGB. */
    fun getBackgroundColor(): Int

    /** Observe invalidation events so the canvas view knows when to re-upload its texture. */
    fun observeCanvasInvalidation(): Flow<CanvasInvalidationEvent>

    /** Undo the most recent operation. Returns true when something was undone. */
    fun undo(): Boolean

    /** Redo the most recently undone operation. */
    fun redo(): Boolean

    /** Releases caches. The repository is a singleton, so this only frees memory. */
    fun dispose()
}

/** Complete state of a canvas, returned after creating or loading a project. */
data class CanvasState(
    val id: Long,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val backgroundColor: Int,
    val layerIds: List<Long>,
    val activeLayerId: Long,
    val zoom: Float,
    val offsetX: Float,
    val offsetY: Float,
    val rotation: Float,
    val frameCount: Int = 1,
    val activeFrameIndex: Int = 0,
    val hasUnsavedChanges: Boolean = false,
)

/** Canvas dimensions. */
data class CanvasSize(
    val width: Int,
    val height: Int,
    val dpi: Int,
)

/** What part of the canvas needs redrawing. */
sealed class CanvasInvalidationEvent {
    /** Full canvas redraw needed. */
    object Full : CanvasInvalidationEvent()

    /** Partial redraw for a region. */
    data class Region(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) : CanvasInvalidationEvent()

    /** A stroke was completed. */
    data class StrokeCompleted(
        val strokeId: Long,
    ) : CanvasInvalidationEvent()

    /** The layer stack changed (add/remove/reorder/visibility). */
    object LayersChanged : CanvasInvalidationEvent()

    /** The active frame changed. */
    data class FrameChanged(
        val index: Int,
    ) : CanvasInvalidationEvent()
}

/** Immutable caller-owned export data; later editor changes cannot alter this snapshot. */
data class CanvasExportSnapshot(
    val frames: List<PixelBuffer>,
    val delaysMs: List<Int>,
    val layers: List<Pair<Layer, PixelBuffer>>,
    val selection: SelectionMask?,
    val hasAdjustmentLayers: Boolean,
)
