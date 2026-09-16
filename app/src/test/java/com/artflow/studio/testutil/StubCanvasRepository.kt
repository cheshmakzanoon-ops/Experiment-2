package com.artflow.studio.testutil

import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasSize
import com.artflow.studio.domain.repository.canvas.CanvasState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A no-op [CanvasRepository] for tests that only need a repository to exist.
 *
 * Use cases that take the repository as a constructor dependency but never call it can use this
 * instead of hand-writing a fake; tests that actually exercise document behaviour should use the
 * real implementation with a temporary storage directory.
 */
open class StubCanvasRepository : CanvasRepository {

    override suspend fun createCanvas(width: Int, height: Int, dpi: Int): Long = 1L

    override suspend fun loadOrCreate(
        projectId: Long,
        width: Int,
        height: Int,
        dpi: Int
    ): CanvasState? = null

    override suspend fun loadCanvas(projectId: Long): CanvasState? = null

    override suspend fun hasRecovery(projectId: Long): Boolean = false

    override suspend fun recoverAutosave(projectId: Long): CanvasState? = null

    override suspend fun saveCanvas(projectId: Long): String? = null

    override suspend fun autosave(projectId: Long) = Unit

    override fun hasUnsavedChanges(): Boolean = false

    override val undoDepth: Int = 0

    override val redoDepth: Int = 0

    override fun projectId(): Long = 0L

    override fun beginStroke(
        x: Float,
        y: Float,
        pressure: Float,
        brushParams: BrushParams,
        layerId: Long,
        isEraser: Boolean
    ): Long = 1L

    override fun continueStroke(
        strokeId: Long,
        x: Float,
        y: Float,
        pressure: Float,
        tiltX: Float,
        tiltY: Float
    ) = Unit

    override fun endStroke(strokeId: Long) = Unit

    override fun activeStroke(strokeId: Long): Stroke? = null

    override fun setSymmetry(settings: SymmetryEngine.Settings) = Unit

    override fun requestPreviewRefresh() = Unit

    override suspend fun replaceLayerStrokes(layerId: Long, strokes: List<Stroke>): Boolean = false

    override suspend fun layerPixels(layerId: Long): PixelBuffer? = null

    override suspend fun beginRasterEdit(layerId: Long): CanvasRepository.RasterEditSession? = null

    override suspend fun commitRasterEdit(
        session: CanvasRepository.RasterEditSession,
        description: String
    ): Boolean = false

    override suspend fun cancelRasterEdit(session: CanvasRepository.RasterEditSession) = Unit

    override suspend fun applyRasterEdit(
        layerId: Long,
        description: String,
        edit: (PixelBuffer) -> Unit
    ): Boolean = false

    override suspend fun setLayerPixels(
        layerId: Long,
        buffer: PixelBuffer,
        description: String
    ): Boolean = false

    override fun setStrokeColor(color: Int) = Unit

    override fun getStrokeColor(): Int = 0

    override fun selection(): SelectionMask? = null

    override fun setSelection(mask: SelectionMask?) = Unit

    override fun clearSelection() = Unit

    override suspend fun addLayer(name: String?, index: Int?, opacity: Float): Layer =
        Layer(id = 1L, name = name ?: "Layer 1", index = index ?: 0, opacity = opacity)

    override suspend fun removeLayer(layerId: Long): Boolean = false

    override suspend fun reorderLayer(layerId: Long, newIndex: Int): Boolean = false

    override suspend fun duplicateLayer(layerId: Long): Long? = null

    override suspend fun mergeLayers(sourceLayerId: Long, targetLayerId: Long): Boolean = false

    override suspend fun mergeVisibleLayers(keepOriginals: Boolean): Long? = null

    override suspend fun mergeLayerDown(layerId: Long): Boolean = false

    override suspend fun flattenAllLayers(): Long? = null

    override suspend fun setLayerVisibility(layerId: Long, isVisible: Boolean?): Boolean = false

    override suspend fun setLayerOpacity(layerId: Long, opacity: Float): Boolean = false

    override suspend fun setLayerName(layerId: Long, newName: String): Boolean = false

    override suspend fun setLayerLock(layerId: Long, isLocked: Boolean): Boolean = false

    override suspend fun setLayerBlendMode(layerId: Long, blendMode: BlendMode): Boolean = false

    override suspend fun setLayerAlphaLock(layerId: Long, isLocked: Boolean?): Boolean = false

    override suspend fun setLayerClippingMask(layerId: Long, isClipping: Boolean?): Boolean = false

    override suspend fun setLayerReference(layerId: Long, isReference: Boolean): Boolean = false

    override suspend fun linkLayers(layerIds: List<Long>): Boolean = false

    override suspend fun unlinkLayer(layerId: Long): Boolean = false

    override fun getAllLayers(): List<Layer> = emptyList()

    override fun getActiveLayer(): Layer? = null

    override fun setActiveLayer(layerId: Long): Boolean = false

    override fun getActiveLayerId(): Long = 0L

    override suspend fun addLayerMask(layerId: Long, fromSelection: SelectionMask?): Boolean = false

    override suspend fun removeLayerMask(layerId: Long): Boolean = false

    override suspend fun invertLayerMask(layerId: Long): Boolean = false

    override suspend fun setLayerMaskEnabled(layerId: Long, enabled: Boolean): Boolean = false

    override suspend fun setLayerMaskDensity(layerId: Long, density: Float): Boolean = false

    override suspend fun setLayerMaskFeather(layerId: Long, radius: Float): Boolean = false

    override suspend fun paintLayerMask(
        layerId: Long,
        x: Float,
        y: Float,
        radius: Float,
        reveal: Boolean
    ): Boolean = false

    override suspend fun addAdjustmentLayer(type: AdjustmentType, index: Int?): Layer? = null

    override suspend fun setAdjustmentParameter(layerId: Long, key: String, value: Float): Boolean = false

    override suspend fun setAdjustmentParameters(layerId: Long, values: Map<String, Float>): Boolean = false

    override suspend fun resetAdjustment(layerId: Long): Boolean = false

    override suspend fun addFilterLayer(type: FilterType, index: Int?): Layer? = null

    override suspend fun setFilterAmount(layerId: Long, amount: Float): Boolean = false

    override suspend fun rasterizeFilterLayer(layerId: Long): Boolean = false

    override suspend fun resizeCanvas(
        width: Int,
        height: Int,
        resample: Boolean,
        anchor: CanvasOperations.Anchor
    ): Boolean = false

    override suspend fun cropCanvas(bounds: IntBounds): Boolean = false

    override suspend fun rotateCanvas(degrees: Int): Boolean = false

    override suspend fun flipCanvas(vertical: Boolean): Boolean = false

    override suspend fun trimTransparent(): Boolean = false

    override suspend fun setCanvasDpi(dpi: Int): Boolean = false

    override suspend fun setCanvasBackgroundColor(color: Int): Boolean = false

    override suspend fun clearCanvas(color: Int) = Unit

    override suspend fun applyAdjustmentToCanvas(
        type: AdjustmentType,
        parameters: Map<String, Float>,
        toAllLayers: Boolean
    ): Boolean = false

    override val timeline: MutableStateFlow<AnimationTimeline.State> =
        MutableStateFlow(AnimationTimeline.State())

    override fun frames(): List<AnimationFrame> = emptyList()

    override fun activeFrameIndex(): Int = 0

    override suspend fun addFrame(duplicateCurrent: Boolean) = Unit

    override suspend fun deleteFrame(index: Int): Boolean = false

    override suspend fun moveFrame(from: Int, to: Int): Boolean = false

    override suspend fun selectFrame(index: Int) = Unit

    override suspend fun setFrameDuration(index: Int, durationMs: Int): Boolean = false

    override suspend fun updateAnimationSettings(settings: AnimationSettings) = Unit

    override suspend fun compositeAllFrames(maxFrames: Int): List<PixelBuffer> = emptyList()

    override suspend fun compositeBuffer(
        includeHidden: Boolean,
        applyAdjustments: Boolean
    ): PixelBuffer? = null

    override suspend fun layerBuffers(): List<Pair<Layer, PixelBuffer>> = emptyList()

    override suspend fun getCanvasBitmap(): ByteArray? = null

    override fun getCanvasSize(): CanvasSize = CanvasSize(100, 100, 72)

    override fun getBackgroundColor(): Int = 0

    override fun observeCanvasInvalidation(): Flow<CanvasInvalidationEvent> = emptyFlow()

    override fun undo(): Boolean = false

    override fun redo(): Boolean = false

    override fun dispose() = Unit
}
