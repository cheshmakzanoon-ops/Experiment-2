package com.artflow.studio.presentation.ui.viewmodel

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.color.ColorHarmony
import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.PaletteLibrary
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.perspective.PerspectiveGuide
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.core.tool.FillTool
import com.artflow.studio.core.tool.GradientTool
import com.artflow.studio.core.tool.LiquifyTool
import com.artflow.studio.core.tool.PixelBrushes
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerRaster
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.animation.AnimationSettings
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import com.artflow.studio.presentation.ui.components.canvas.SelectionCombineMode
import com.artflow.studio.presentation.ui.components.canvas.ShapeKind
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** Where the editor is in its lifecycle. */
sealed class CanvasUiState {
    object Loading : CanvasUiState()

    data class Ready(
        val projectId: Long,
        val projectName: String,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val frameCount: Int,
        val recoveryAvailable: Boolean = false,
    ) : CanvasUiState()

    data class Error(
        val message: String,
    ) : CanvasUiState()
}

/** Undo/redo counters mirrored out of the repository so the toolbar can enable its buttons. */
data class HistoryState(
    val undoDepth: Int = 0,
    val redoDepth: Int = 0,
) {
    val canUndo: Boolean get() = undoDepth > 0
    val canRedo: Boolean get() = redoDepth > 0
}

/** Progress/result of the last export, driven by the export dialog. */
sealed class ExportUiState {
    object Idle : ExportUiState()

    object Running : ExportUiState()

    data class Done(
        val result: ExportResult,
    ) : ExportUiState()

    data class Failed(
        val message: String,
    ) : ExportUiState()
}

/** A text run waiting for the text dialog to be confirmed. */
data class PendingText(
    val x: Float,
    val y: Float,
    val id: Long = System.nanoTime(),
)

/**
 * The editor's state holder.
 *
 * The document itself lives in `CanvasRepository`; this ViewModel owns the *editing session* around
 * it: the active tool and its options, the selection and history counters the UI mirrors, the save
 * and autosave lifecycle, and the export pipeline.
 */
@HiltViewModel
class CanvasViewModel
    @Inject
    constructor(
        private val canvasRepository: CanvasRepository,
        private val projectRepository: ProjectRepository,
        private val settingsRepository: SettingsRepository,
        private val exporter: ArtworkExporter,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<CanvasUiState>(CanvasUiState.Loading)
        val uiState: StateFlow<CanvasUiState> = _uiState.asStateFlow()

        private val _input = MutableStateFlow(EditorInput())
        val input: StateFlow<EditorInput> = _input.asStateFlow()

        private val _layers = MutableStateFlow<List<Layer>>(emptyList())
        val layers: StateFlow<List<Layer>> = _layers.asStateFlow()

        private val _history = MutableStateFlow(HistoryState())
        val history: StateFlow<HistoryState> = _history.asStateFlow()

        private val _selection = MutableStateFlow<SelectionMask?>(null)
        val selection: StateFlow<SelectionMask?> = _selection.asStateFlow()

        private val _selectionCount = MutableStateFlow(0)
        val selectionCount: StateFlow<Int> = _selectionCount.asStateFlow()

        private val _timeline = MutableStateFlow(AnimationTimeline.State())
        val timeline: StateFlow<AnimationTimeline.State> = _timeline.asStateFlow()

        private val _settings = MutableStateFlow(AppSettings())
        val settings: StateFlow<AppSettings> = _settings.asStateFlow()

        private val _exportState = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
        val exportState: StateFlow<ExportUiState> = _exportState.asStateFlow()

        private val _pendingText = MutableStateFlow<PendingText?>(null)
        val pendingText: StateFlow<PendingText?> = _pendingText.asStateFlow()

        private val _cloneSource = MutableStateFlow<Pair<Float, Float>?>(null)
        val cloneSource: StateFlow<Pair<Float, Float>?> = _cloneSource.asStateFlow()

        private val _recentColors = MutableStateFlow<List<Int>>(emptyList())
        val recentColors: StateFlow<List<Int>> = _recentColors.asStateFlow()

        private val _palettes = MutableStateFlow<List<Palette>>(emptyList())
        val palettes: StateFlow<List<Palette>> = _palettes.asStateFlow()

        private val _viewScale = MutableStateFlow(1f)
        val viewScale: StateFlow<Float> = _viewScale.asStateFlow()

        private val _viewOffsetX = MutableStateFlow(0f)
        val viewOffsetX: StateFlow<Float> = _viewOffsetX.asStateFlow()

        private val _viewOffsetY = MutableStateFlow(0f)
        val viewOffsetY: StateFlow<Float> = _viewOffsetY.asStateFlow()

        private val _viewRotation = MutableStateFlow(0f)
        val viewRotation: StateFlow<Float> = _viewRotation.asStateFlow()

        private val _activeLayerId = MutableStateFlow(0L)
        val activeLayerId: StateFlow<Long> = _activeLayerId.asStateFlow()

        private val _dirty = MutableStateFlow(false)
        val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

        private val messages = Channel<String>(Channel.BUFFERED)
        val messageFlow: Flow<String> = messages.receiveAsFlow()

        private var project: Project? = null
        private var playbackJob: Job? = null
        private var autosaveJob: Job? = null
        private var observationJob: Job? = null
        private var currentProjectId: Long = 0L

        init {
            viewModelScope.launch {
                settingsRepository.settings.collect { stored ->
                    _settings.value = stored
                    _recentColors.value = stored.recentColors
                    _palettes.value = stored.customPalettes + PaletteLibrary.BUILT_IN
                    _input.value =
                        _input.value.copy(
                            symmetry = _input.value.symmetry,
                            snapToGuides = stored.snapToGuides,
                            fingerPainting = stored.fingerPainting,
                        )
                }
            }
            viewModelScope.launch {
                canvasRepository.timeline.collect { _timeline.value = it }
            }
        }

        // -----------------------------------------------------------------------------------------
        // Opening a document
        // -----------------------------------------------------------------------------------------

        fun open(projectId: Long) {
            if (currentProjectId == projectId && _uiState.value is CanvasUiState.Ready) return
            currentProjectId = projectId
            viewModelScope.launch {
                _uiState.value = CanvasUiState.Loading
                try {
                    val existing = projectRepository.getProjectById(projectId)
                    project = existing
                    val state =
                        if (existing != null) {
                            canvasRepository.loadOrCreate(projectId, existing.width, existing.height, existing.dpi)
                        } else {
                            val summary = CanvasOperations.presetByName(_settings.value.defaultPresetName)
                            canvasRepository.loadOrCreate(
                                projectId,
                                summary?.width ?: DEFAULT_WIDTH,
                                summary?.height ?: DEFAULT_HEIGHT,
                                summary?.dpi ?: DEFAULT_DPI,
                            )
                        }
                    if (state == null) {
                        _uiState.value = CanvasUiState.Error("This project could not be opened")
                        return@launch
                    }
                    val recoveryAvailable = canvasRepository.hasRecovery(projectId)
                    _uiState.value =
                        CanvasUiState.Ready(
                            projectId = projectId,
                            projectName = existing?.name ?: "Untitled artwork",
                            width = state.width,
                            height = state.height,
                            dpi = state.dpi,
                            frameCount = state.frameCount,
                            recoveryAvailable = recoveryAvailable,
                        )
                    refreshLayers()
                    refreshHistory()
                    refreshSelection()
                    startObserving()
                    startAutosave()
                    Timber.d("Opened project $projectId (${state.width}x${state.height})")
                } catch (error: Throwable) {
                    Timber.e(error, "Failed to open project $projectId")
                    _uiState.value = CanvasUiState.Error(error.message ?: "Failed to open the project")
                }
            }
        }

        /** Creates a project record and opens it; the gallery calls this for "New artwork". */
        fun createProject(
            name: String,
            width: Int,
            height: Int,
            dpi: Int,
        ) {
            viewModelScope.launch {
                val now = System.currentTimeMillis()
                val project =
                    Project(
                        name = name.ifBlank { "Untitled artwork" },
                        filePath = "",
                        thumbnailPath = null,
                        width = width,
                        height = height,
                        dpi = dpi,
                        createdAt = now,
                        modifiedAt = now,
                    )
                val id = projectRepository.saveProject(project)
                open(id)
            }
        }

        private fun startObserving() {
            observationJob?.cancel()
            observationJob =
                viewModelScope.launch {
                    canvasRepository.observeCanvasInvalidation().collect { event ->
                        when (event) {
                            is CanvasInvalidationEvent.LayersChanged -> refreshLayers()
                            is CanvasInvalidationEvent.FrameChanged -> {
                                refreshLayers()
                                refreshUiStateFrames()
                            }
                            else -> refreshLayers()
                        }
                        refreshHistory()
                        _dirty.value = canvasRepository.hasUnsavedChanges()
                        _selection.value = canvasRepository.selection()
                        _selectionCount.value = canvasRepository.selection()?.selectedPixelCount() ?: 0
                        refreshUiStateFrames()
                    }
                }
        }

        private fun startAutosave() {
            autosaveJob?.cancel()
            autosaveJob =
                viewModelScope.launch {
                    while (true) {
                        val settings = _settings.value
                        delay(settings.autosaveIntervalMs.coerceAtLeast(5_000L))
                        if (!settings.autosaveEnabled) continue
                        if (currentProjectId == 0L) continue
                        if (!canvasRepository.hasUnsavedChanges()) continue
                        try {
                            canvasRepository.autosave(currentProjectId)
                        } catch (error: Throwable) {
                            Timber.e(error, "Autosave failed")
                        }
                    }
                }
        }

        private fun refreshLayers() {
            _layers.value = canvasRepository.getAllLayers()
            _activeLayerId.value = canvasRepository.getActiveLayerId()
        }

        private fun refreshHistory() {
            _history.value = HistoryState(canvasRepository.undoDepth, canvasRepository.redoDepth)
        }

        private fun refreshSelection() {
            _selection.value = canvasRepository.selection()
            _selectionCount.value = canvasRepository.selection()?.selectedPixelCount() ?: 0
        }

        private fun refreshUiStateFrames() {
            val state = _uiState.value
            if (state is CanvasUiState.Ready) {
                val frames = canvasRepository.frames().size
                if (frames != state.frameCount) _uiState.value = state.copy(frameCount = frames)
            }
        }

        // -----------------------------------------------------------------------------------------
        // Tool state
        // -----------------------------------------------------------------------------------------

        fun setTool(tool: ToolType) = updateInput { it.copy(tool = tool) }

        fun setBrushSize(size: Float) = updateInput { it.copy(brushParams = it.brushParams.copy(size = size.coerceIn(1f, 512f))) }

        fun setBrushOpacity(opacity: Float) =
            updateInput { it.copy(brushParams = it.brushParams.copy(opacity = opacity.coerceIn(0.01f, 1f))) }

        fun setBrushParams(params: com.artflow.studio.domain.model.brush.BrushParams) = updateInput { it.copy(brushParams = params) }

        fun setEraserSize(size: Float) = updateInput { it.copy(eraserSize = size.coerceIn(1f, 512f)) }

        fun setColor(color: Int) {
            updateInput { it.copy(brushColor = color) }
            viewModelScope.launch { settingsRepository.pushRecentColor(color) }
        }

        fun onColorPicked(color: Int) = setColor(color)

        fun setSymmetry(
            type: SymmetryEngine.SymmetryType,
            radialCount: Int = 6,
        ) = updateInput {
            it.copy(symmetry = SymmetryEngine.sanitize(it.symmetry.copy(type = type, radialCount = radialCount)))
        }

        fun setSymmetrySettings(settings: SymmetryEngine.Settings) = updateInput { it.copy(symmetry = SymmetryEngine.sanitize(settings)) }

        fun setPerspectiveSettings(settings: PerspectiveGuide.Settings) =
            updateInput { it.copy(perspective = PerspectiveGuide.sanitize(settings)) }

        fun setSnapToGuides(enabled: Boolean) {
            updateInput { it.copy(snapToGuides = enabled) }
            viewModelScope.launch { settingsRepository.setSnapToGuides(enabled) }
        }

        fun setFingerPainting(enabled: Boolean) = updateInput { it.copy(fingerPainting = enabled) }

        fun setGradient(
            gradient: GradientTool.Gradient,
            type: GradientTool.GradientType,
        ) = updateInput { it.copy(gradient = gradient, gradientType = type) }

        fun setFillSettings(
            tolerance: Int,
            contiguous: Boolean,
        ) = updateInput { it.copy(fillTolerance = tolerance.coerceIn(0, 255), fillContiguous = contiguous) }

        fun setShapeSettings(
            kind: ShapeKind,
            filled: Boolean,
        ) = updateInput { it.copy(shapeKind = kind, shapeFilled = filled) }

        fun setSelectionMode(mode: SelectionCombineMode) = updateInput { it.copy(selectionMode = mode) }

        fun setText(
            text: String,
            style: TextLayout.TextStyle,
        ) = updateInput { it.copy(text = text, textStyle = style) }

        fun setSmudgeSettings(settings: PixelBrushes.SmudgeSettings) = updateInput { it.copy(smudge = settings) }

        fun setCloneSettings(settings: PixelBrushes.CloneSettings) = updateInput { it.copy(clone = settings) }

        fun setHealingSettings(settings: PixelBrushes.HealingSettings) = updateInput { it.copy(healing = settings) }

        fun setLiquifySettings(settings: LiquifyTool.Settings) = updateInput { it.copy(liquify = settings) }

        private fun updateInput(transform: (EditorInput) -> EditorInput) {
            _input.value = transform(_input.value)
        }

        fun onViewChanged(
            scale: Float,
            offsetX: Float,
            offsetY: Float,
            rotation: Float,
        ) {
            _viewScale.value = scale
            _viewOffsetX.value = offsetX
            _viewOffsetY.value = offsetY
            _viewRotation.value = rotation
        }

        fun onCloneSourceChanged(source: Pair<Float, Float>) {
            _cloneSource.value = source
        }

        fun notify(message: String) {
            viewModelScope.launch { messages.send(message) }
        }

        // -----------------------------------------------------------------------------------------
        // History
        // -----------------------------------------------------------------------------------------

        fun undo() {
            if (canvasRepository.undo()) {
                refreshLayers()
                refreshHistory()
                refreshSelection()
            } else {
                notify("Nothing to undo")
            }
        }

        fun redo() {
            if (canvasRepository.redo()) {
                refreshLayers()
                refreshHistory()
                refreshSelection()
            } else {
                notify("Nothing to redo")
            }
        }

        // -----------------------------------------------------------------------------------------
        // Selection
        // -----------------------------------------------------------------------------------------

        fun selectionChanged(
            mask: SelectionMask?,
            count: Int,
        ) {
            _selection.value = mask
            _selectionCount.value = count
        }

        fun selectAll() {
            val mask = SelectionMask(canvasSize().first, canvasSize().second).apply { selectAll() }
            canvasRepository.setSelection(mask)
            refreshSelection()
        }

        fun clearSelection() {
            canvasRepository.clearSelection()
            refreshSelection()
        }

        fun invertSelection() {
            val mask =
                canvasRepository.selection()?.copy()
                    ?: SelectionMask(canvasSize().first, canvasSize().second).apply { selectAll() }
            mask.invert()
            canvasRepository.setSelection(mask)
            refreshSelection()
        }

        fun featherSelection(radius: Int) {
            val current = canvasRepository.selection() ?: return
            canvasRepository.setSelection(current.feathered(radius))
            refreshSelection()
        }

        fun selectionFromAlphaOfActiveLayer() {
            viewModelScope.launch {
                val layerId = canvasRepository.getActiveLayerId()
                val buffer = canvasRepository.layerPixels(layerId)
                if (buffer == null) {
                    notify("This layer has no pixels yet")
                    return@launch
                }
                canvasRepository.setSelection(SelectionMask.fromAlphaOf(buffer))
                refreshSelection()
            }
        }

        fun selectionFromColorRange(
            color: Int,
            tolerance: Int,
        ) {
            viewModelScope.launch {
                val buffer = canvasRepository.compositeBuffer()
                if (buffer == null) {
                    notify("Nothing to sample")
                    return@launch
                }
                canvasRepository.setSelection(SelectionMask.colorRange(buffer, color, tolerance))
                refreshSelection()
            }
        }

        // -----------------------------------------------------------------------------------------
        // Layers
        // -----------------------------------------------------------------------------------------

        fun addLayer() {
            viewModelScope.launch {
                canvasRepository.addLayer()
                refreshLayers()
            }
        }

        fun removeLayer(layerId: Long) {
            viewModelScope.launch {
                if (!canvasRepository.removeLayer(layerId)) notify("The last layer cannot be deleted")
                refreshLayers()
            }
        }

        fun duplicateLayer(layerId: Long) {
            viewModelScope.launch {
                canvasRepository.duplicateLayer(layerId)
                refreshLayers()
            }
        }

        fun mergeLayerDown(layerId: Long) {
            viewModelScope.launch {
                if (!canvasRepository.mergeLayerDown(layerId)) notify("Nothing to merge into")
                refreshLayers()
            }
        }

        fun flattenAllLayers() {
            viewModelScope.launch {
                canvasRepository.flattenAllLayers()
                refreshLayers()
                notify("Layers flattened")
            }
        }

        fun mergeVisibleLayers() {
            viewModelScope.launch {
                canvasRepository.mergeVisibleLayers()
                refreshLayers()
                notify("Visible layers merged")
            }
        }

        fun setLayerVisibility(
            layerId: Long,
            visible: Boolean,
        ) = layerOp {
            canvasRepository.setLayerVisibility(layerId, visible)
        }

        fun setLayerOpacity(
            layerId: Long,
            opacity: Float,
        ) = layerOp {
            canvasRepository.setLayerOpacity(layerId, opacity)
        }

        fun setLayerName(
            layerId: Long,
            name: String,
        ) = layerOp {
            canvasRepository.setLayerName(layerId, name)
        }

        fun setLayerLock(
            layerId: Long,
            locked: Boolean,
        ) = layerOp {
            canvasRepository.setLayerLock(layerId, locked)
        }

        fun setLayerAlphaLock(
            layerId: Long,
            locked: Boolean,
        ) = layerOp {
            canvasRepository.setLayerAlphaLock(layerId, locked)
        }

        fun setLayerClippingMask(
            layerId: Long,
            clipping: Boolean,
        ) = layerOp {
            canvasRepository.setLayerClippingMask(layerId, clipping)
        }

        fun setLayerBlendMode(
            layerId: Long,
            mode: BlendMode,
        ) = layerOp {
            canvasRepository.setLayerBlendMode(layerId, mode)
        }

        fun setLayerReference(
            layerId: Long,
            isReference: Boolean,
        ) = layerOp {
            canvasRepository.setLayerReference(layerId, isReference)
        }

        fun setActiveLayer(layerId: Long) {
            canvasRepository.setActiveLayer(layerId)
            refreshLayers()
        }

        fun reorderLayer(
            layerId: Long,
            newIndex: Int,
        ) = layerOp {
            canvasRepository.reorderLayer(layerId, newIndex)
        }

        fun linkLayers(layerIds: List<Long>) = layerOp { canvasRepository.linkLayers(layerIds) }

        fun unlinkLayer(layerId: Long) = layerOp { canvasRepository.unlinkLayer(layerId) }

        fun addLayerMask(fromSelection: Boolean) =
            layerOp {
                val layerId = canvasRepository.getActiveLayerId()
                canvasRepository.addLayerMask(layerId, if (fromSelection) canvasRepository.selection() else null)
            }

        fun removeLayerMask() =
            layerOp {
                canvasRepository.removeLayerMask(canvasRepository.getActiveLayerId())
            }

        fun invertLayerMask() =
            layerOp {
                canvasRepository.invertLayerMask(canvasRepository.getActiveLayerId())
            }

        fun setLayerMaskEnabled(enabled: Boolean) =
            layerOp {
                canvasRepository.setLayerMaskEnabled(canvasRepository.getActiveLayerId(), enabled)
            }

        fun setLayerMaskDensity(density: Float) =
            layerOp {
                canvasRepository.setLayerMaskDensity(canvasRepository.getActiveLayerId(), density)
            }

        fun setLayerMaskFeather(radius: Float) =
            layerOp {
                canvasRepository.setLayerMaskFeather(canvasRepository.getActiveLayerId(), radius)
            }

        private fun layerOp(block: suspend () -> Unit) {
            viewModelScope.launch {
                try {
                    block()
                    refreshLayers()
                } catch (error: Throwable) {
                    Timber.e(error, "Layer operation failed")
                    notify(error.message ?: "The layer operation failed")
                }
            }
        }

        // -----------------------------------------------------------------------------------------
        // Adjustments and filters
        // -----------------------------------------------------------------------------------------

        fun addAdjustmentLayer(type: AdjustmentType) {
            viewModelScope.launch {
                canvasRepository.addAdjustmentLayer(type)
                refreshLayers()
            }
        }

        fun setAdjustmentParameter(
            layerId: Long,
            key: String,
            value: Float,
        ) {
            viewModelScope.launch {
                canvasRepository.setAdjustmentParameter(layerId, key, value)
                refreshLayers()
            }
        }

        fun resetAdjustment(layerId: Long) {
            viewModelScope.launch {
                canvasRepository.resetAdjustment(layerId)
                refreshLayers()
            }
        }

        fun addFilterLayer(type: FilterType) {
            viewModelScope.launch {
                canvasRepository.addFilterLayer(type)
                refreshLayers()
            }
        }

        fun setFilterAmount(
            layerId: Long,
            amount: Float,
        ) {
            viewModelScope.launch {
                canvasRepository.setFilterAmount(layerId, amount)
                refreshLayers()
            }
        }

        fun rasterizeFilterLayer(layerId: Long) {
            viewModelScope.launch {
                canvasRepository.rasterizeFilterLayer(layerId)
                refreshLayers()
                notify("Filter baked into the layer below")
            }
        }

        fun applyAdjustmentToCanvas(
            type: AdjustmentType,
            parameters: Map<String, Float>,
            toAllLayers: Boolean,
        ) {
            viewModelScope.launch {
                canvasRepository.applyAdjustmentToCanvas(type, parameters, toAllLayers)
                refreshLayers()
                notify("Adjustment applied")
            }
        }

        // -----------------------------------------------------------------------------------------
        // Canvas operations
        // -----------------------------------------------------------------------------------------

        fun resizeCanvas(
            width: Int,
            height: Int,
            resample: Boolean,
            anchor: CanvasOperations.Anchor,
        ) {
            viewModelScope.launch {
                if (canvasRepository.resizeCanvas(width, height, resample, anchor)) {
                    refreshUiStateSize(width, height, canvasSize().third)
                    notify("Canvas resized to ${width}x$height")
                } else {
                    notify("That canvas size is not supported")
                }
            }
        }

        fun cropCanvas(bounds: IntBounds) {
            viewModelScope.launch {
                canvasRepository.cropCanvas(bounds)
                refreshUiStateSize(bounds.width, bounds.height, canvasSize().third)
            }
        }

        fun rotateCanvas(degrees: Int) {
            viewModelScope.launch {
                canvasRepository.rotateCanvas(degrees)
                if (degrees % 180 != 0) {
                    val (width, height, dpi) = canvasSize()
                    refreshUiStateSize(height, width, dpi)
                }
                notify("Canvas rotated $degrees°")
            }
        }

        fun flipCanvas(vertical: Boolean) {
            viewModelScope.launch {
                canvasRepository.flipCanvas(vertical)
                notify(if (vertical) "Canvas flipped vertically" else "Canvas flipped horizontally")
            }
        }

        fun trimTransparent() {
            viewModelScope.launch {
                if (canvasRepository.trimTransparent()) {
                    val (width, height, dpi) = canvasSize()
                    refreshUiStateSize(width, height, dpi)
                    notify("Trimmed to content")
                } else {
                    notify("Nothing to trim")
                }
            }
        }

        fun trimToSelection() {
            val mask =
                canvasRepository.selection() ?: run {
                    notify("Make a selection first")
                    return
                }
            val bounds =
                mask.bounds() ?: run {
                    notify("The selection is empty")
                    return
                }
            cropCanvas(bounds)
        }

        fun setCanvasDpi(dpi: Int) {
            viewModelScope.launch {
                canvasRepository.setCanvasDpi(dpi)
                refreshUiStateSize(canvasSize().first, canvasSize().second, dpi)
            }
        }

        fun setCanvasBackgroundColor(color: Int) {
            viewModelScope.launch { canvasRepository.setCanvasBackgroundColor(color) }
        }

        fun clearCanvas(color: Int = 0) {
            viewModelScope.launch {
                canvasRepository.clearCanvas(color)
                notify("Canvas cleared")
            }
        }

        private fun refreshUiStateSize(
            width: Int,
            height: Int,
            dpi: Int,
        ) {
            val state = _uiState.value
            if (state is CanvasUiState.Ready) {
                _uiState.value = state.copy(width = width, height = height, dpi = dpi)
            }
            refreshLayers()
        }

        private fun canvasSize(): Triple<Int, Int, Int> {
            val size = canvasRepository.getCanvasSize()
            return Triple(size.width, size.height, size.dpi)
        }

        // -----------------------------------------------------------------------------------------
        // Animation
        // -----------------------------------------------------------------------------------------

        fun addFrame(duplicateCurrent: Boolean) {
            viewModelScope.launch {
                canvasRepository.addFrame(duplicateCurrent)
                refreshLayers()
                refreshUiStateFrames()
            }
        }

        fun deleteFrame(index: Int) {
            viewModelScope.launch {
                if (!canvasRepository.deleteFrame(index)) notify("The last frame cannot be deleted")
                refreshLayers()
                refreshUiStateFrames()
            }
        }

        fun moveFrame(
            from: Int,
            to: Int,
        ) {
            viewModelScope.launch {
                canvasRepository.moveFrame(from, to)
                refreshUiStateFrames()
            }
        }

        fun selectFrame(index: Int) {
            viewModelScope.launch { canvasRepository.selectFrame(index) }
        }

        fun setFrameDuration(
            index: Int,
            durationMs: Int,
        ) {
            viewModelScope.launch { canvasRepository.setFrameDuration(index, durationMs) }
        }

        fun updateAnimationSettings(settings: AnimationSettings) {
            viewModelScope.launch {
                canvasRepository.updateAnimationSettings(settings)
                settingsRepository.setOnionSkin(settings.onionSkinFrames > 0)
            }
        }

        fun toggleOnionSkin(enabled: Boolean) {
            viewModelScope.launch { settingsRepository.setOnionSkin(enabled) }
        }

        // -----------------------------------------------------------------------------------------
        // Text placement
        // -----------------------------------------------------------------------------------------

        fun requestTextAt(
            x: Float,
            y: Float,
        ) {
            _pendingText.value = PendingText(x, y)
        }

        fun cancelText() {
            _pendingText.value = null
        }

        // -----------------------------------------------------------------------------------------
        // Saving and exporting
        // -----------------------------------------------------------------------------------------

        fun save() {
            val projectId = currentProjectId
            if (projectId == 0L) return
            viewModelScope.launch {
                try {
                    val thumbnail = canvasRepository.saveCanvas(projectId)
                    val existing = project ?: projectRepository.getProjectById(projectId)
                    if (existing != null) {
                        val size = canvasRepository.getCanvasSize()
                        val updated =
                            existing.copy(
                                thumbnailPath = thumbnail ?: existing.thumbnailPath,
                                width = size.width,
                                height = size.height,
                                dpi = size.dpi,
                                layerCount = canvasRepository.getAllLayers().size,
                                modifiedAt = System.currentTimeMillis(),
                            )
                        projectRepository.updateProject(updated)
                        project = updated
                    }
                    _dirty.value = false
                    notify("Saved")
                } catch (error: Throwable) {
                    Timber.e(error, "Save failed")
                    notify("Save failed: ${error.message ?: "unknown error"}")
                }
            }
        }

        /** Restores the autosave copy after a crash. */
        fun recoverAutosave() {
            val projectId = currentProjectId
            if (projectId == 0L) return
            viewModelScope.launch {
                val state = canvasRepository.recoverAutosave(projectId)
                if (state == null) {
                    notify("There was nothing to recover")
                } else {
                    _uiState.value = (
                        CanvasUiState.Ready(
                            projectId = projectId,
                            projectName = project?.name ?: "Untitled artwork",
                            width = state.width,
                            height = state.height,
                            dpi = state.dpi,
                            frameCount = state.frameCount,
                        )
                    )
                    refreshLayers()
                    refreshHistory()
                    notify("Recovered the autosaved version")
                }
            }
        }

        fun dismissRecovery() {
            val state = _uiState.value
            if (state is CanvasUiState.Ready) _uiState.value = state.copy(recoveryAvailable = false)
        }

        /** Runs an export through the shared pipeline and keeps the result for the dialog. */
        fun export(options: ExportOptions) {
            val projectId = currentProjectId
            if (projectId == 0L) return
            viewModelScope.launch {
                _exportState.value = ExportUiState.Running
                try {
                    val name = project?.name ?: "Artwork"
                    val result =
                        if (options.format.requiresAnimation) {
                            val frames = canvasRepository.compositeAllFrames(maxFrames = MAX_EXPORT_FRAMES)
                            val delays = canvasRepository.frames().map { it.durationMs }
                            exporter.exportAnimation(projectId, name, frames, delays, options)
                        } else {
                            val composite =
                                canvasRepository.compositeBuffer(
                                    includeHidden = options.includeHiddenLayers,
                                ) ?: run {
                                    _exportState.value = ExportUiState.Failed("There is nothing to export")
                                    return@launch
                                }
                            val layers =
                                canvasRepository.layerBuffers().map { (layer, buffer) ->
                                    LayerRaster(name = layer.name, buffer = buffer, layer = layer)
                                }
                            exporter.exportStill(projectId, name, composite, layers, options)
                        }
                    _exportState.value =
                        result.fold(
                            onSuccess = { ExportUiState.Done(it) },
                            onFailure = { ExportUiState.Failed(it.message ?: "Export failed") },
                        )
                } catch (error: Exception) {
                    // Export runs over file/codec/IO code; anything else is a programming error.
                    Timber.e(error, "Export failed")
                    _exportState.value = ExportUiState.Failed(error.message ?: "Export failed")
                }
            }
        }

        fun exportToGallery(result: ExportResult) {
            viewModelScope.launch {
                val path = exporter.publishToGallery(result, project?.name ?: "Artwork")
                notify(if (path != null) "Saved to the device gallery" else "Could not write to the gallery")
            }
        }

        fun shareIntent(result: ExportResult): Intent = exporter.shareIntent(result)

        fun viewIntent(result: ExportResult): Intent = exporter.viewIntent(result)

        fun resetExportState() {
            _exportState.value = ExportUiState.Idle
        }

        /** Exports offered for the current document. */
        fun availableFormats(): List<ExportFormat> =
            if ((_uiState.value as? CanvasUiState.Ready)?.frameCount ?: 1 > 1) {
                ExportFormat.stillFormats() + ExportFormat.animationFormats()
            } else {
                ExportFormat.stillFormats()
            }

        /** The selection, ready to be baked into a new layer or used by a filter. */
        fun activeSelection(): SelectionMask? = canvasRepository.selection()

        fun previewComposite() {
            // Compositing happens in the canvas view; this exists so the export dialog can force a
            // fresh composite before it opens with a thumbnail.
            viewModelScope.launch { canvasRepository.compositeBuffer() }
        }

        fun palettesFor(category: String? = null): List<Palette> =
            if (category == null) _palettes.value else _palettes.value.filter { it.category == category }

        fun addPaletteFromColors(
            name: String,
            colors: List<Int>,
        ) {
            viewModelScope.launch {
                val palette =
                    Palette(
                        id = System.currentTimeMillis(),
                        name = name,
                        colors = colors,
                        category = "Custom",
                    )
                settingsRepository.addPalette(palette)
                notify("Palette saved")
            }
        }

        fun removePalette(paletteId: Long) {
            viewModelScope.launch { settingsRepository.removePalette(paletteId) }
        }

        fun harmonyFor(
            baseColor: Int,
            harmony: ColorHarmony.Harmony,
        ): List<Int> = ColorHarmony.harmony(baseColor, harmony)

        fun clearRecentColors() {
            viewModelScope.launch { settingsRepository.clearRecentColors() }
        }

        fun setSymmetryGuidesVisible(visible: Boolean) {
            viewModelScope.launch { settingsRepository.setSymmetryGuides(visible) }
        }

        fun setPerspectiveGuidesVisible(visible: Boolean) {
            viewModelScope.launch { settingsRepository.setPerspectiveGuides(visible) }
        }

        /**
         * Playback with real timing.
         *
         * Frames are advanced by their own duration (not by the fps field) so an animation with
         * non-uniform exposure plays exactly as it will export; ping-pong reverses at the ends.
         */
        fun togglePlayback() {
            if (playbackJob?.isActive == true) {
                playbackJob?.cancel()
                playbackJob = null
                return
            }
            playbackJob =
                viewModelScope.launch {
                    var index = canvasRepository.activeFrameIndex()
                    var direction = 1
                    while (true) {
                        val frames = canvasRepository.frames()
                        if (frames.size <= 1) return@launch
                        val current = frames.getOrNull(index) ?: frames.first()
                        delay(current.durationMs.toLong().coerceAtLeast(16L))
                        index += direction
                        if (index > frames.lastIndex) {
                            if (_timeline.value.settings.pingPong) {
                                direction = -1
                                index = (frames.size - 2).coerceAtLeast(0)
                            } else if (_timeline.value.settings.loop) {
                                index = 0
                            } else {
                                return@launch
                            }
                        } else if (index < 0) {
                            direction = 1
                            index = 1.coerceAtMost(frames.lastIndex)
                        }
                        canvasRepository.selectFrame(index)
                    }
                }
        }

        fun quickFill(color: Int) {
            val projectId = currentProjectId
            if (projectId == 0L) return
            viewModelScope.launch {
                canvasRepository.applyRasterEdit(
                    canvasRepository.getActiveLayerId(),
                    "Fill layer",
                ) { buffer ->
                    FillTool.fillAll(buffer, color)
                }
                refreshLayers()
            }
        }

        override fun onCleared() {
            super.onCleared()
            observationJob?.cancel()
            autosaveJob?.cancel()
            playbackJob?.cancel()
            canvasRepository.dispose()
        }

        companion object {
            const val DEFAULT_WIDTH = 1920
            const val DEFAULT_HEIGHT = 1080
            const val DEFAULT_DPI = 72
            private const val MAX_EXPORT_FRAMES = 240
        }
    }
