@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.screens.canvas

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.domain.model.brush.StrokeDestination
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.presentation.ui.components.brush.AdvancedBrushSettingsPanel
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.canvas.DragPreview
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import com.artflow.studio.presentation.ui.components.color.ColorPanel
import com.artflow.studio.presentation.ui.components.editor.AnimationSheet
import com.artflow.studio.presentation.ui.components.editor.BrushOptionsRow
import com.artflow.studio.presentation.ui.components.editor.CanvasOpsSheet
import com.artflow.studio.presentation.ui.components.editor.ColorChip
import com.artflow.studio.presentation.ui.components.editor.GuidesOverlay
import com.artflow.studio.presentation.ui.components.editor.GuidesSheet
import com.artflow.studio.presentation.ui.components.editor.LayerMaskActions
import com.artflow.studio.presentation.ui.components.editor.LayerRowActions
import com.artflow.studio.presentation.ui.components.editor.LayerStackActions
import com.artflow.studio.presentation.ui.components.editor.LayersSheet
import com.artflow.studio.presentation.ui.components.editor.QuickMenuSheet
import com.artflow.studio.presentation.ui.components.editor.SelectionSheet
import com.artflow.studio.presentation.ui.components.editor.TextSheet
import com.artflow.studio.presentation.ui.components.editor.ToolStrip
import com.artflow.studio.presentation.ui.components.editor.TransformSheet
import com.artflow.studio.presentation.ui.components.export.ExportSheet
import com.artflow.studio.presentation.ui.components.export.rememberExportActions
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import kotlinx.coroutines.launch

/** Which panel is open above the canvas. */
private enum class EditorPanel(
    val title: String,
) {
    NONE(""),
    TOOLS("Tool options"),
    COLOUR("Colour"),
    LAYERS("Layers"),
    SELECTION("Selection"),
    TRANSFORM("Transform layer"),
    GUIDES("Guides"),
    ANIMATION("Animation"),
    CANVAS("Canvas"),
    TEXT("Text"),
    EXPORT("Export"),
    QUICK("Quick menu"),
}

/**
 * The editor.
 *
 * The screen is a thin shell: all document state lives in `CanvasRepository`, all editing state in
 * [CanvasViewModel], and the OpenGL surface in [ArtFlowCanvasView]. This composable wires them
 * together and owns only which panel is open.
 */
@Composable
fun CanvasScreen(
    projectId: Long,
    onNavigateBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: CanvasViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val input by viewModel.input.collectAsState()
    val layers by viewModel.layers.collectAsState()
    val history by viewModel.history.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val timeline by viewModel.timeline.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val pendingText by viewModel.pendingText.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val selectionCount by viewModel.selectionCount.collectAsState()
    val dirty by viewModel.dirty.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val viewScale by viewModel.viewScale.collectAsState()
    val viewRotation by viewModel.viewRotation.collectAsState()
    val viewOffsetX by viewModel.viewOffsetX.collectAsState()
    val viewOffsetY by viewModel.viewOffsetY.collectAsState()
    val activeLayerId by viewModel.activeLayerId.collectAsState()
    val recentColors by viewModel.recentColors.collectAsState()
    val palettes by viewModel.palettes.collectAsState()

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val snackbarHostState = remember { SnackbarHostState() }
    val exportActions = rememberExportActions(viewModel)

    var panel by remember { mutableStateOf(EditorPanel.NONE) }
    var canvasView by remember { mutableStateOf<ArtFlowCanvasView?>(null) }
    var dragPreview by remember { mutableStateOf<DragPreview?>(null) }
    var previewBytes by remember { mutableStateOf<ByteArray?>(null) }
    var featherRadius by remember { mutableStateOf(8) }
    var showRecoveryDialog by remember { mutableStateOf(false) }
    var showBrushEditor by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    // A draft belongs to the layer/revision present when the panel opens, not a later frame.
    val transformTarget = remember(panel, projectId) { activeLayerId to viewModel.transformRevision() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, canvasView) {
        val view = canvasView
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> view?.resumeRendering()
                    Lifecycle.Event.ON_STOP -> {
                        view?.cancelActiveGesture()
                        view?.pauseRendering()
                        viewModel.saveRecoveryOnBackground()
                    }
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(projectId) { viewModel.open(projectId) }

    LaunchedEffect(Unit) {
        viewModel.messageFlow.collect { snackbarHostState.showSnackbar(it) }
    }

    val ready = uiState as? CanvasUiState.Ready
    LaunchedEffect(ready?.recoveryAvailable) {
        if (ready?.recoveryAvailable == true) showRecoveryDialog = true
    }

    // Refresh the export preview whenever the canvas changes size or the export panel opens.
    LaunchedEffect(panel, ready?.width, ready?.height) {
        if (panel == EditorPanel.EXPORT) previewBytes = null
    }

    BackHandler(enabled = panel != EditorPanel.NONE) { panel = EditorPanel.NONE }
    BackHandler(enabled = panel == EditorPanel.NONE && dirty) { showExitConfirm = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = ready?.projectName ?: "Loading…",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                        )
                        ready?.let {
                            Text(
                                text =
                                    "${it.width}×${it.height} px · ${it.dpi} dpi" +
                                        if (it.frameCount > 1) " · ${it.frameCount} frames" else "",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (dirty) showExitConfirm = true else onNavigateBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = history.canUndo) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = viewModel::redo, enabled = history.canRedo) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { viewModel.save() }, enabled = !saving && ready != null) {
                        Icon(
                            imageVector = if (dirty) Icons.Default.Save else Icons.Default.CloudDone,
                            contentDescription = "Save",
                        )
                    }
                    IconButton(onClick = { panel = EditorPanel.QUICK }) {
                        Icon(Icons.Default.Bolt, contentDescription = "Quick menu")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            // The app is edge-to-edge from API 35, and the opt-out attribute is ignored from API 36,
            // so the tool strip has to inset itself: without this the navigation bar covers the
            // tool row. Scaffold only insets *content* when a bottomBar is present, not the bar.
            Surface(
                tonalElevation = 4.dp,
                modifier =
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ),
            ) {
                Column {
                    BrushOptionsRow(
                        tool = input.tool,
                        size = input.brushParams.size,
                        opacity = input.brushParams.opacity,
                        eraserSize = input.eraserSize,
                        tolerance = input.fillTolerance,
                        onSizeChanged = viewModel::setBrushSize,
                        onOpacityChanged = viewModel::setBrushOpacity,
                        onEraserSizeChanged = viewModel::setEraserSize,
                        onToleranceChanged = { viewModel.setFillSettings(it, input.fillContiguous) },
                    )
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ColorChip(color = input.brushColor, onClick = { panel = EditorPanel.COLOUR })
                        if (input.strokeDestination.isMask) {
                            TextButton(onClick = { viewModel.setTool(ToolType.BRUSH) }) {
                                Text(
                                    if (input.strokeDestination ==
                                        StrokeDestination.MASK_REVEAL
                                    ) {
                                        "Mask: reveal • Done"
                                    } else {
                                        "Mask: hide • Done"
                                    },
                                )
                            }
                        }
                        TextButton(onClick = { showBrushEditor = true }) { Text("Brush") }
                        TextButton(onClick = { panel = EditorPanel.LAYERS }) {
                            Text("Layers (${layers.size})")
                        }
                        TextButton(onClick = { panel = EditorPanel.SELECTION }) {
                            Text(if (selectionCount > 0) "Select ($selectionCount)" else "Select")
                        }
                        TextButton(onClick = { panel = EditorPanel.TRANSFORM }) { Text("Transform") }
                        TextButton(onClick = { panel = EditorPanel.TOOLS }) { Text("Options") }
                        TextButton(onClick = { panel = EditorPanel.EXPORT }) { Text("Export") }
                    }
                    ToolStrip(
                        activeTool = input.tool,
                        onToolSelected = {
                            viewModel.setTool(it)
                            if (it == ToolType.TRANSFORM) panel = EditorPanel.TRANSFORM
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            when (val state = uiState) {
                is CanvasUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is CanvasUiState.Error -> ErrorState(state.message, onRetry = { viewModel.open(projectId) })
                is CanvasUiState.Ready -> {
                    AndroidView(
                        factory = { ctx ->
                            ArtFlowCanvasView(ctx).apply {
                                layoutParams =
                                    ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                    )
                                attachToCanvas(state.width, state.height, state.dpi, 0xFFFFFFFF.toInt())

                                onColorPicked = { viewModel.onColorPicked(it) }
                                onSelectionChanged = { mask, count -> viewModel.selectionChanged(mask, count) }
                                onDragPreview = { dragPreview = it }
                                onHistoryChanged = { _, _ -> }
                                onUndoRequested = { viewModel.undo() }
                                onRedoRequested = { viewModel.redo() }
                                onViewChanged = { s, ox, oy, r -> viewModel.onViewChanged(s, ox, oy, r) }
                                onTextPlacementRequested = { x, y -> viewModel.requestTextAt(x, y) }
                                onCloneSourceChanged = { viewModel.onCloneSourceChanged(it) }
                                onStatusMessage = { viewModel.notify(it) }
                                setEditorInput(input)
                                setOnionSkinEnabled(settings.onionSkin)
                                setCheckerboardVisible(settings.checkerboard)
                                canvasView = this
                            }
                        },
                        update = { view ->
                            view.setEditorInput(input)
                            view.setActiveLayerId(activeLayerId)
                            view.setOnionSkinEnabled(settings.onionSkin)
                            view.setCheckerboardVisible(settings.checkerboard)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    GuidesOverlay(
                        canvasWidth = state.width,
                        canvasHeight = state.height,
                        scale = viewScale,
                        offsetX = viewOffsetX,
                        offsetY = viewOffsetY,
                        rotationDegrees = viewRotation,
                        symmetry = input.symmetry,
                        showSymmetry = settings.showSymmetryGuides,
                        perspective = input.perspective,
                        showPerspective = settings.showPerspectiveGuides,
                        selection = selection,
                        preview = dragPreview,
                        modifier = Modifier.fillMaxSize(),
                    )

                    if (settings.showSymmetryGuides || settings.showPerspectiveGuides) {
                        // Guides are always available from the quick menu; the chip is a reminder.
                        Text(
                            text = "Guides on",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    if (panel != EditorPanel.NONE) {
        ModalBottomSheet(
            onDismissRequest = { panel = EditorPanel.NONE },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    panel.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
                when (panel) {
                    EditorPanel.TRANSFORM ->
                        TransformSheet(
                            layerName = layers.firstOrNull { it.id == transformTarget.first }?.name ?: "Layer",
                            hasSelection = selection != null,
                            onApply = { viewModel.transformLayer(transformTarget.first, transformTarget.second, it) },
                            onClose = { panel = EditorPanel.NONE },
                        )
                    EditorPanel.TOOLS -> ToolOptionsPanel(viewModel, input)
                    EditorPanel.COLOUR ->
                        ColorPanel(
                            color = input.brushColor,
                            recentColors = recentColors,
                            palettes = palettes,
                            onColorSelected = { viewModel.setColor(it) },
                            onClearRecents = { scope.launch { viewModel.clearRecentColors() } },
                            onSavePalette = { name, colors -> viewModel.addPaletteFromColors(name, colors) },
                            onRemovePalette = { viewModel.removePalette(it) },
                        )
                    EditorPanel.LAYERS ->
                        LayersSheet(
                            layers = layers,
                            activeLayerId = activeLayerId,
                            hasSelection = selection != null,
                            rowActions =
                                LayerRowActions(
                                    onSelect = { viewModel.setActiveLayer(it) },
                                    onVisibility = { id, visible -> viewModel.setLayerVisibility(id, visible) },
                                    onOpacity = { id, opacity -> viewModel.setLayerOpacity(id, opacity) },
                                    onName = { id, name -> viewModel.setLayerName(id, name) },
                                    onLock = { id, locked -> viewModel.setLayerLock(id, locked) },
                                    onAlphaLock = { id, locked -> viewModel.setLayerAlphaLock(id, locked) },
                                    onClipping = { id, clipping -> viewModel.setLayerClippingMask(id, clipping) },
                                    onBlendMode = { id, mode: BlendMode -> viewModel.setLayerBlendMode(id, mode) },
                                    onDuplicate = { viewModel.duplicateLayer(it) },
                                    onDelete = { viewModel.removeLayer(it) },
                                    onMergeDown = { viewModel.mergeLayerDown(it) },
                                ),
                            stackActions =
                                LayerStackActions(
                                    onAddLayer = { viewModel.addLayer() },
                                    onFlatten = { viewModel.flattenAllLayers() },
                                    onMergeVisible = { viewModel.mergeVisibleLayers() },
                                    onAddAdjustment = { type: AdjustmentType -> viewModel.addAdjustmentLayer(type) },
                                    onAddFilter = { type: FilterType -> viewModel.addFilterLayer(type) },
                                    onAdjustmentParameter = { id, key, value -> viewModel.setAdjustmentParameter(id, key, value) },
                                    onFilterAmount = { id, amount -> viewModel.setFilterAmount(id, amount) },
                                ),
                            maskActions =
                                LayerMaskActions(
                                    onAddMask = { viewModel.createLayerMask(it) },
                                    onPaintMask = { reveal ->
                                        viewModel.paintMask(reveal)
                                        panel = EditorPanel.NONE
                                    },
                                    onRemoveMask = { viewModel.removeLayerMask() },
                                    onInvertMask = { viewModel.invertLayerMask() },
                                    onMaskEnabled = { viewModel.setLayerMaskEnabled(it) },
                                    onMaskDensity = { viewModel.setLayerMaskDensity(it) },
                                    onMaskFeather = { viewModel.setLayerMaskFeather(it) },
                                ),
                        )
                    EditorPanel.SELECTION ->
                        SelectionSheet(
                            mode = input.selectionMode,
                            selectionCount = selectionCount,
                            tolerance = input.fillTolerance,
                            featherRadius = featherRadius,
                            hasSelection = selectionCount > 0,
                            onModeChange = { viewModel.setSelectionMode(it) },
                            onToleranceChange = { viewModel.setFillSettings(it, input.fillContiguous) },
                            onFeatherChange = { featherRadius = it },
                            onSelectAll = { viewModel.selectAll() },
                            onClearSelection = { viewModel.clearSelection() },
                            onInvertSelection = { viewModel.invertSelection() },
                            onApplyFeather = { viewModel.featherSelection(featherRadius) },
                            onSelectionFromLayer = { viewModel.selectionFromAlphaOfActiveLayer() },
                            onTrimToSelection = { viewModel.trimToSelection() },
                            onColorRange = { viewModel.selectionFromColorRange(it, input.fillTolerance) },
                        )
                    EditorPanel.GUIDES ->
                        GuidesSheet(
                            symmetry = input.symmetry,
                            perspective = input.perspective,
                            snapToGuides = input.snapToGuides,
                            showSymmetryGuides = settings.showSymmetryGuides,
                            showPerspectiveGuides = settings.showPerspectiveGuides,
                            onSymmetry = { viewModel.setSymmetrySettings(it) },
                            onPerspective = { viewModel.setPerspectiveSettings(it) },
                            onSnap = { viewModel.setSnapToGuides(it) },
                            onShowSymmetry = { scope.launch { viewModel.setSymmetryGuidesVisible(it) } },
                            onShowPerspective = { scope.launch { viewModel.setPerspectiveGuidesVisible(it) } },
                        )
                    EditorPanel.ANIMATION ->
                        AnimationSheet(
                            timeline = timeline,
                            onionEnabled = settings.onionSkin,
                            onSelectFrame = { viewModel.selectFrame(it) },
                            onAddFrame = { viewModel.addFrame(it) },
                            onDeleteFrame = { viewModel.deleteFrame(it) },
                            onMoveFrame = { from, to -> viewModel.moveFrame(from, to) },
                            onFrameDuration = { index, duration -> viewModel.setFrameDuration(index, duration) },
                            onSettings = { viewModel.updateAnimationSettings(it) },
                            onToggleOnion = { viewModel.toggleOnionSkin(it) },
                            onTogglePlayback = { viewModel.togglePlayback() },
                        )
                    EditorPanel.CANVAS ->
                        CanvasOpsSheet(
                            width = ready?.width ?: 0,
                            height = ready?.height ?: 0,
                            dpi = ready?.dpi ?: 72,
                            backgroundColor = 0xFFFFFFFF.toInt(),
                            onResize = { w, h, resample, anchor -> viewModel.resizeCanvas(w, h, resample, anchor) },
                            onRotate = { viewModel.rotateCanvas(it) },
                            onFlip = { viewModel.flipCanvas(it) },
                            onTrim = { viewModel.trimTransparent() },
                            onDpi = { viewModel.setCanvasDpi(it) },
                            onBackgroundColor = { viewModel.setCanvasBackgroundColor(it) },
                            onClear = { viewModel.clearCanvas(it) },
                        )
                    EditorPanel.TEXT ->
                        TextSheet(
                            text = input.text,
                            style = input.textStyle,
                            color = input.brushColor,
                            onTextChange = { viewModel.setText(it, input.textStyle) },
                            onStyleChange = { viewModel.setText(input.text, it) },
                            onColorChange = { viewModel.setColor(it) },
                            onPlace = {
                                val pending = pendingText
                                val view = canvasView
                                if (pending != null && view != null) {
                                    view.placeText(
                                        pending.x,
                                        pending.y,
                                        input.text,
                                        input.textStyle,
                                        input.brushColor,
                                    )
                                    viewModel.cancelText()
                                } else {
                                    viewModel.notify("Tap the canvas to choose where the text goes")
                                }
                            },
                        )
                    EditorPanel.EXPORT ->
                        ExportSheet(
                            availableFormats = viewModel.availableFormats(),
                            frameCount = ready?.frameCount ?: 1,
                            canvasWidth = ready?.width ?: 0,
                            canvasHeight = ready?.height ?: 0,
                            canvasDpi = ready?.dpi ?: 72,
                            previewBytes = previewBytes,
                            exportState = exportState,
                            onExport = { viewModel.export(it) },
                            actions = exportActions,
                            onDismissResult = { viewModel.resetExportState() },
                        )
                    EditorPanel.QUICK ->
                        QuickMenuSheet(
                            scalePercent = (viewScale * 100).toInt(),
                            rotation = viewRotation.toInt(),
                            onZoomIn = { canvasView?.zoomBy(1.25f) },
                            onZoomOut = { canvasView?.zoomBy(0.8f) },
                            onFit = { canvasView?.fitToView() },
                            onResetView = { canvasView?.resetView() },
                            onRotate = { canvasView?.rotateView(it) },
                            onFlipCanvas = { viewModel.flipCanvas(it) },
                            onRotateCanvas = { viewModel.rotateCanvas(it) },
                            onSelectAll = { viewModel.selectAll() },
                            onClearSelection = { viewModel.clearSelection() },
                            onInvertSelection = { viewModel.invertSelection() },
                            onUndo = { viewModel.undo() },
                            onRedo = { viewModel.redo() },
                            canUndo = history.canUndo,
                            canRedo = history.canRedo,
                        )
                    EditorPanel.NONE -> Unit
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showBrushEditor) {
        AlertDialog(
            onDismissRequest = { showBrushEditor = false },
            title = { Text("Brush settings") },
            text = {
                AdvancedBrushSettingsPanel(
                    brushParams = input.brushParams,
                    onBrushParamsChanged = { viewModel.setBrushParams(it) },
                    modifier = Modifier.heightIn(max = 440.dp),
                )
            },
            confirmButton = { TextButton(onClick = { showBrushEditor = false }) { Text("Done") } },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.setBrushParams(
                        com.artflow.studio.domain.model.brush
                            .BrushParams(),
                    )
                }) {
                    Text("Reset")
                }
            },
        )
    }

    if (showRecoveryDialog) {
        AlertDialog(
            // A tap outside the dialog or system Back must never delete recoverable artwork.
            onDismissRequest = {},
            title = { Text("Autosave found") },
            text = {
                Text("ArtFlow closed unexpectedly the last time this project was open. Recover the autosaved version?")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.recoverAutosave()
                    showRecoveryDialog = false
                }) { Text("Recover") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissRecovery()
                    showRecoveryDialog = false
                }) { Text("Discard") }
            },
        )
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { if (!saving) showExitConfirm = false },
            title = { Text("Save changes?") },
            text = { Text("This project has unsaved changes.") },
            confirmButton = {
                TextButton(enabled = !saving, onClick = {
                    viewModel.save {
                        showExitConfirm = false
                        onNavigateBack()
                    }
                }) { Text(if (saving) "Saving…" else "Save and leave") }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = !saving, onClick = { showExitConfirm = false }) { Text("Stay") }
                    TextButton(enabled = !saving, onClick = {
                        viewModel.discardChanges {
                            showExitConfirm = false
                            onNavigateBack()
                        }
                    }) { Text("Discard and leave") }
                }
            },
        )
    }
}

@Composable
private fun ToolOptionsPanel(
    viewModel: CanvasViewModel,
    input: EditorInput,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(input.tool.displayName, style = MaterialTheme.typography.titleMedium)
        Text(
            when (input.tool) {
                ToolType.BRUSH -> "Pressure controls size and opacity; tap Brush for the full dynamics panel."
                ToolType.ERASER -> "Erases to transparency on the active layer. Two fingers to navigate; the stylus keeps painting."
                ToolType.SMUDGE -> "Pull colour along the stroke. Lower strength gives a softer blend."
                ToolType.CLONE_STAMP -> "Tap once to set the source, then drag to stamp."
                ToolType.HEALING -> "Spot-heals blemishes by matching the surrounding texture."
                ToolType.LIQUIFY -> "Push, twirl, pinch or bloat pixels with a displacement map."
                ToolType.PAINT_BUCKET -> "Flood fills the area under the tap within the tolerance."
                ToolType.GRADIENT -> "Drag to set the gradient axis; the ramp is chosen in the colour panel."
                ToolType.TEXT -> "Tap the canvas to place the current text."
                ToolType.SHAPE -> "Drag to draw the selected shape."
                ToolType.SELECT_MAGIC_WAND -> "Tap to select a colour region."
                ToolType.EYEDROPPER -> "Tap to pick a colour from the artwork."
                ToolType.MOVE, ToolType.TRANSFORM -> "Drag to move the active layer's pixels."
                else -> "Drag on the canvas to use this tool."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (input.tool == ToolType.GRADIENT) {
            Text("Gradient direction", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.artflow.studio.core.tool.GradientTool.GradientType.entries.forEach { type ->
                    FilterChip(
                        selected = input.gradientType == type,
                        onClick = { viewModel.setGradient(input.gradient, type) },
                        label = { Text(type.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Text("Ramp", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.artflow.studio.core.tool.GradientTool.Presets.ALL.forEach { preset ->
                    AssistChip(
                        onClick = { viewModel.setGradient(preset, input.gradientType) },
                        label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }

        if (input.tool == ToolType.PAINT_BUCKET) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = input.fillContiguous,
                    onCheckedChange = { viewModel.setFillSettings(input.fillTolerance, it) },
                )
                Spacer(Modifier.width(8.dp))
                Text("Only fill the connected region", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (input.tool == ToolType.SHAPE) {
            Text("Shape", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.artflow.studio.presentation.ui.components.canvas.ShapeKind.entries.forEach { kind ->
                    FilterChip(
                        selected = input.shapeKind == kind,
                        onClick = { viewModel.setShapeSettings(kind, input.shapeFilled) },
                        label = { Text(kind.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = input.shapeFilled,
                    onCheckedChange = { viewModel.setShapeSettings(input.shapeKind, it) },
                )
                Spacer(Modifier.width(8.dp))
                Text("Filled", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (input.tool == ToolType.SMUDGE) {
            Text(
                "Smudge strength ${(input.smudge.strength * 100).toInt()}% · hardness ${(input.smudge.hardness * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (input.tool == ToolType.LIQUIFY) {
            Text("Liquify mode: ${input.liquify.mode.displayName}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.artflow.studio.core.tool.LiquifyTool.Mode.entries.forEach { mode ->
                    FilterChip(
                        selected = input.liquify.mode == mode,
                        onClick = { viewModel.setLiquifySettings(input.liquify.copy(mode = mode)) },
                        label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        if (input.tool == ToolType.CLONE_STAMP) {
            Text(
                "Tap to set the clone source, then drag. Blend mode: aligned.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}
