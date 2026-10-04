@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.artflow.studio.presentation.ui.screens.canvas

import android.content.ActivityNotFoundException
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.artflow.studio.core.canvas.CropBox
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.LayerMaskSource
import com.artflow.studio.core.pixels.LiveAdjustments
import com.artflow.studio.core.pixels.Quad
import com.artflow.studio.core.pixels.WarpMesh
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.tool.ToolGroup
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.local.FontLibrary
import com.artflow.studio.data.local.ReferenceImages
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.brush.StrokeDestination
import com.artflow.studio.domain.model.layer.AdjustmentType
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.presentation.ui.components.brush.BrushStudioDialog
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.components.canvas.BrushCursor
import com.artflow.studio.presentation.ui.components.canvas.DragPreview
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import com.artflow.studio.presentation.ui.components.canvas.GestureControls
import com.artflow.studio.presentation.ui.components.color.ColorPanel
import com.artflow.studio.presentation.ui.components.editor.ActionsPanel
import com.artflow.studio.presentation.ui.components.editor.AddActions
import com.artflow.studio.presentation.ui.components.editor.AdjustmentOverlay
import com.artflow.studio.presentation.ui.components.editor.AdjustmentOverlayActions
import com.artflow.studio.presentation.ui.components.editor.AnimationSheet
import com.artflow.studio.presentation.ui.components.editor.BackgroundControls
import com.artflow.studio.presentation.ui.components.editor.BrushOptionsRow
import com.artflow.studio.presentation.ui.components.editor.CanvasActions
import com.artflow.studio.presentation.ui.components.editor.CanvasInfo
import com.artflow.studio.presentation.ui.components.editor.CanvasOpsSheet
import com.artflow.studio.presentation.ui.components.editor.CanvasReference
import com.artflow.studio.presentation.ui.components.editor.ColorChip
import com.artflow.studio.presentation.ui.components.editor.CopyPasteActions
import com.artflow.studio.presentation.ui.components.editor.CopyPasteMenu
import com.artflow.studio.presentation.ui.components.editor.CropBar
import com.artflow.studio.presentation.ui.components.editor.CropOverlay
import com.artflow.studio.presentation.ui.components.editor.GuideAssist
import com.artflow.studio.presentation.ui.components.editor.GuidesOverlay
import com.artflow.studio.presentation.ui.components.editor.GuidesSheet
import com.artflow.studio.presentation.ui.components.editor.LayerMaskActions
import com.artflow.studio.presentation.ui.components.editor.LayerOpacityOverlay
import com.artflow.studio.presentation.ui.components.editor.LayerOptionActions
import com.artflow.studio.presentation.ui.components.editor.LayerRowActions
import com.artflow.studio.presentation.ui.components.editor.LayerStackActions
import com.artflow.studio.presentation.ui.components.editor.LayersSheet
import com.artflow.studio.presentation.ui.components.editor.PageAssistActions
import com.artflow.studio.presentation.ui.components.editor.PageAssistBar
import com.artflow.studio.presentation.ui.components.editor.PrefActions
import com.artflow.studio.presentation.ui.components.editor.QuickAction
import com.artflow.studio.presentation.ui.components.editor.QuickMenuSheet
import com.artflow.studio.presentation.ui.components.editor.RadialQuickMenu
import com.artflow.studio.presentation.ui.components.editor.ReferenceCompanion
import com.artflow.studio.presentation.ui.components.editor.SelectionSheet
import com.artflow.studio.presentation.ui.components.editor.SelectionToolbar
import com.artflow.studio.presentation.ui.components.editor.SelectionToolbarActions
import com.artflow.studio.presentation.ui.components.editor.StudioBottomPanel
import com.artflow.studio.presentation.ui.components.editor.StudioButton
import com.artflow.studio.presentation.ui.components.editor.StudioPopover
import com.artflow.studio.presentation.ui.components.editor.StudioPrefs
import com.artflow.studio.presentation.ui.components.editor.StudioSidebar
import com.artflow.studio.presentation.ui.components.editor.StudioSidebarActions
import com.artflow.studio.presentation.ui.components.editor.StudioSidebarState
import com.artflow.studio.presentation.ui.components.editor.StudioToolDock
import com.artflow.studio.presentation.ui.components.editor.StudioTopBar
import com.artflow.studio.presentation.ui.components.editor.TextSheet
import com.artflow.studio.presentation.ui.components.editor.TimelapseReplay
import com.artflow.studio.presentation.ui.components.editor.TransformOverlay
import com.artflow.studio.presentation.ui.components.editor.TransformToolbar
import com.artflow.studio.presentation.ui.components.editor.TransformToolbarActions
import com.artflow.studio.presentation.ui.components.editor.VideoActions
import com.artflow.studio.presentation.ui.components.editor.ViewTransform
import com.artflow.studio.presentation.ui.components.editor.WarpOverlay
import com.artflow.studio.presentation.ui.components.editor.icon
import com.artflow.studio.presentation.ui.components.editor.isWideLayout
import com.artflow.studio.presentation.ui.components.editor.studioButton
import com.artflow.studio.presentation.ui.components.export.ExportSheet
import com.artflow.studio.presentation.ui.components.export.rememberExportActions
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Which panel is open above the canvas. */
private enum class EditorPanel(
    val title: String,
) {
    NONE(""),
    TOOLS("Tool options"),
    COLOUR("Colour"),
    LAYERS("Layers"),
    SELECTION("Selection"),
    GUIDES("Guides"),
    ANIMATION("Animation"),
    CANVAS("Canvas"),
    TEXT("Text"),
    EXPORT("Export"),
    QUICK("Quick menu"),
    ACTIONS("Actions"),
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
    onOpenHelp: () -> Unit = {},
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
    var focusMode by rememberSaveable(projectId) { mutableStateOf(false) }
    var toolsExpanded by rememberSaveable(projectId) { mutableStateOf(false) }
    var openMenu by remember { mutableStateOf<StudioButton?>(null) }
    var showCopyPaste by remember { mutableStateOf(false) }
    var transformQuad by remember { mutableStateOf<Quad?>(null) }
    var warpMesh by remember { mutableStateOf<WarpMesh?>(null) }
    var brushCursor by remember { mutableStateOf<BrushCursor?>(null) }
    var colorDropThreshold by remember { mutableStateOf<Float?>(null) }
    var editingText by remember { mutableStateOf<Layer?>(null) }
    val adjustment by viewModel.adjustments.state.collectAsState()
    val layerThumbnails by viewModel.layerThumbnails.thumbnails.collectAsState()
    val pickedLayers by viewModel.layerBatch.picked.collectAsState()
    LaunchedEffect(canvasView, pickedLayers, activeLayerId) { canvasView?.setTransformCompanions(pickedLayers - activeLayerId) }
    LaunchedEffect(panel, layers, history) {
        if (panel == EditorPanel.LAYERS) viewModel.layerThumbnails.refresh()
    }
    var replayFrames by remember { mutableStateOf<List<java.io.File>?>(null) }
    val hasClipboard by viewModel.clipboard.hasContent.collectAsState()
    val wide = isWideLayout()
    var colorDropPosition by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    var colorChipOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var contentOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var showReference by rememberSaveable(projectId) { mutableStateOf(false) }
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // The reference image is remembered per artwork; the picker grants lasting read access.
    var referenceUri by rememberSaveable(projectId) { mutableStateOf(ReferenceImages.get(appContext, projectId)) }
    var referenceCanvas by rememberSaveable(projectId) { mutableStateOf(false) }
    var pageAssist by rememberSaveable(projectId) { mutableStateOf(false) }
    var cropBox by remember { mutableStateOf<CropBox.Box?>(null) }
    var opacityLayer by remember { mutableStateOf<Long?>(null) }
    var quickMenu by remember { mutableStateOf(false) }
    val pageThumbnails by viewModel.pageThumbnails.pages.collectAsState()
    val pageImages =
        remember(pageThumbnails) {
            pageThumbnails.map { page -> page?.let { BitmapPixelBridge.toBitmap(it).asImageBitmap() } }
        }
    LaunchedEffect(pageAssist, history, timeline.frameCount) {
        if (pageAssist) viewModel.pageThumbnails.refresh()
    }
    val canvasPreview by viewModel.canvasPreview.preview.collectAsState()
    val canvasPreviewBitmap =
        remember(canvasPreview) { canvasPreview?.let { BitmapPixelBridge.toBitmap(it) } }
    LaunchedEffect(showReference, referenceCanvas, history) {
        if (showReference && referenceCanvas) viewModel.canvasPreview.refresh()
    }
    var referenceImportProject by rememberSaveable(projectId) { mutableStateOf<Long?>(null) }
    val referencePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (referenceImportProject == projectId && uri != null) {
                if (uri.scheme == "content") {
                    runCatching {
                        appContext.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    referenceUri = uri.toString()
                    ReferenceImages.set(appContext, projectId, referenceUri)
                    showReference = true
                } else {
                    viewModel.notify("Choose a reference image from an Android document provider")
                }
            }
            referenceImportProject = null
        }
    val context = androidx.compose.ui.platform.LocalContext.current
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val image =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            com.artflow.studio.data.renderer.BitmapPixelBridge
                                .decodeUri(context.contentResolver, uri)
                        }.getOrNull()
                    }
                if (image == null) viewModel.notify("That image could not be opened") else viewModel.insertImageLayer(image)
            }
        }
    val takePhoto = rememberCameraCapture(onImage = viewModel::insertImageLayer, onError = viewModel::notify)
    val paletteImports = rememberPaletteImports(onPalette = viewModel::addPaletteFromColors, onError = viewModel::notify)
    var importedFonts by remember { mutableStateOf(FontLibrary.families(context)) }
    val fontPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val family =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { FontLibrary.import(context, uri) }.getOrNull()
                    }
                if (family == null) {
                    viewModel.notify("That file is not a TrueType or OpenType font")
                } else {
                    importedFonts = FontLibrary.families(context)
                    viewModel.setText(input.text, input.textStyle.copy(fontFamily = family))
                }
            }
        }
    val importFont = {
        try {
            fontPicker.launch(arrayOf("font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/octet-stream"))
        } catch (missing: ActivityNotFoundException) {
            viewModel.notify("No file picker is available on this device")
        }
    }
    val psdPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val bytes =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                val data = stream.readBytes()
                                data.takeIf { it.size <= MAX_PSD_IMPORT_BYTES }
                            }
                        }.getOrNull()
                    }
                if (bytes == null) viewModel.notify("That PSD could not be opened (maximum 256 MB)") else viewModel.importPsd(bytes)
            }
        }
    val importPsd = {
        canvasView?.cancelActiveGesture()
        try {
            psdPicker.launch("*/*")
        } catch (missing: ActivityNotFoundException) {
            viewModel.notify("No file picker is available on this device")
        }
    }
    val insertPhoto = {
        canvasView?.cancelActiveGesture()
        try {
            photoPicker.launch("image/*")
        } catch (missing: ActivityNotFoundException) {
            viewModel.notify("No image picker is available on this device")
        }
    }
    val importReference = {
        canvasView?.cancelActiveGesture()
        referenceImportProject = projectId
        try {
            referencePicker.launch(arrayOf("image/*"))
        } catch (missing: ActivityNotFoundException) {
            referenceImportProject = null
            viewModel.notify("No image picker is available on this device")
        }
    }

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

    val dismissPanel = {
        if (panel == EditorPanel.TEXT) {
            viewModel.cancelText()
            editingText = null
        }
        panel = EditorPanel.NONE
    }
    LaunchedEffect(pendingText) {
        if (pendingText != null) {
            canvasView?.cancelActiveGesture()
            openMenu = null
            panel = EditorPanel.TEXT
        }
    }

    // One Back handler with an explicit order: the innermost open thing closes first, then focus
    // mode ends, and only then does Back offer to leave an artwork with unsaved changes.
    val backTarget =
        when {
            panel != EditorPanel.NONE -> BackTarget.PANEL
            cropBox != null -> BackTarget.CROP
            opacityLayer != null -> BackTarget.OPACITY
            quickMenu -> BackTarget.QUICK_MENU
            showReference -> BackTarget.REFERENCE
            focusMode -> BackTarget.FOCUS
            dirty -> BackTarget.LEAVE
            else -> null
        }
    BackHandler(enabled = backTarget != null) {
        when (backTarget) {
            BackTarget.PANEL -> dismissPanel()
            BackTarget.CROP -> cropBox = null
            BackTarget.OPACITY -> opacityLayer = null
            BackTarget.QUICK_MENU -> quickMenu = false
            BackTarget.REFERENCE -> showReference = false
            BackTarget.FOCUS -> {
                canvasView?.cancelActiveGesture()
                focusMode = false
            }
            BackTarget.LEAVE -> showExitConfirm = true
            null -> Unit
        }
    }

    val panelContent: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                panel.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = dismissPanel) {
                Icon(Icons.Default.Close, contentDescription = "Close ${panel.title}")
            }
        }
        when (panel) {
            EditorPanel.TOOLS -> {
                StudioToolDock(
                    activeTool = input.tool,
                    expanded = toolsExpanded,
                    onToolSelected = { viewModel.setTool(it) },
                    onExpandedChange = { toolsExpanded = it },
                )
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
                ToolOptionsPanel(viewModel, input, canvasView)
            }
            EditorPanel.COLOUR ->
                ColorPanel(
                    color = input.brushColor,
                    recentColors = recentColors,
                    palettes = palettes,
                    onColorSelected = { viewModel.setColor(it) },
                    onClearRecents = { scope.launch { viewModel.clearRecentColors() } },
                    onSavePalette = { name, colors -> viewModel.addPaletteFromColors(name, colors) },
                    onImportPalette = paletteImports.fromFile,
                    onPaletteFromPhoto = paletteImports.fromPhoto,
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
                            onSelectContents = { id ->
                                viewModel.setActiveLayer(id)
                                viewModel.selectionFromAlphaOfActiveLayer()
                            },
                            onOpacityMode = { id ->
                                viewModel.setActiveLayer(id)
                                panel = EditorPanel.NONE
                                opacityLayer = id
                            },
                        ),
                    stackActions =
                        LayerStackActions(
                            onAddLayer = { viewModel.addLayer() },
                            onReorder = viewModel::reorderLayer,
                            onFlatten = { viewModel.flattenAllLayers() },
                            onMergeVisible = { viewModel.mergeVisibleLayers() },
                            onAddAdjustment = { type: AdjustmentType -> viewModel.addAdjustmentLayer(type) },
                            onAddFilter = { type: FilterType -> viewModel.addFilterLayer(type) },
                            onAdjustmentParameter = { id, key, value -> viewModel.setAdjustmentParameter(id, key, value) },
                            onFilterAmount = { id, amount -> viewModel.setFilterAmount(id, amount) },
                            onInsertPhoto = insertPhoto,
                            onImportPsd = importPsd,
                            onGroupWithBelow = viewModel::groupWithLayerBelow,
                            onUngroup = viewModel::ungroup,
                            onGroupLayers = viewModel.layerBatch::group,
                            onDeleteLayers = viewModel.layerBatch::delete,
                            onMergeLayers = viewModel.layerBatch::merge,
                            background =
                                BackgroundControls(
                                    color = ready?.backgroundColor ?: 0xFFFFFFFF.toInt(),
                                    onUseCurrentColour = {
                                        viewModel.canvasOps.setCanvasBackgroundColor(input.brushColor or 0xFF000000.toInt())
                                    },
                                    onVisible = { shown ->
                                        // Hiding keeps the colour, so showing it again brings the same one back.
                                        val color = ready?.backgroundColor ?: 0xFFFFFFFF.toInt()
                                        viewModel.canvasOps.setCanvasBackgroundColor(
                                            if (shown) color or 0xFF000000.toInt() else color and 0x00FFFFFF,
                                        )
                                    },
                                ),
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
                    thumbnails = layerThumbnails,
                    optionActions =
                        LayerOptionActions(
                            onSelectContents = viewModel::selectionFromAlphaOfActiveLayer,
                            onCopy = { viewModel.clipboard.copy() },
                            onFill = { viewModel.clipboard.fill(input.brushColor) },
                            onClear = { viewModel.clipboard.clear() },
                            onInvert = { viewModel.applyAdjustmentToCanvas(AdjustmentType.INVERT, emptyMap(), toAllLayers = false) },
                            onReference = viewModel::setLayerReference,
                            onFillReference = viewModel::setLayerFillReference,
                            onDrawingAssist = viewModel::setLayerDrawingAssist,
                            onMask = { viewModel.createLayerMask(LayerMaskSource.REVEAL_ALL) },
                            onCombineDown = viewModel::groupWithLayerBelow,
                            onEditText = { layer ->
                                layer.textContent?.let { content ->
                                    viewModel.setText(content.text, content.style)
                                    viewModel.setColor(content.color)
                                    editingText = layer
                                    panel = EditorPanel.TEXT
                                }
                            },
                        ),
                    picked = pickedLayers,
                    onPicked = viewModel.layerBatch::pick,
                )
            EditorPanel.SELECTION ->
                Column {
                    ClipboardActions(viewModel, selectionCount > 0)
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
                        onTrimToSelection = { viewModel.canvasOps.trimToSelection() },
                        onColorRange = { viewModel.selectionFromColorRange(it, input.fillTolerance) },
                    )
                }
            EditorPanel.GUIDES ->
                GuidesSheet(
                    symmetry = input.symmetry,
                    perspective = input.perspective,
                    showSymmetryGuides = settings.showSymmetryGuides,
                    showPerspectiveGuides = settings.showPerspectiveGuides,
                    onSymmetry = {
                        // Turning a guide on assists the current layer, like Procreate's Assisted Drawing.
                        if (it.isActive() && !input.symmetry.isActive()) viewModel.setLayerDrawingAssist(activeLayerId, true)
                        viewModel.setSymmetrySettings(it)
                    },
                    onPerspective = {
                        if (it.isActive() && !input.perspective.isActive()) viewModel.setLayerDrawingAssist(activeLayerId, true)
                        viewModel.setPerspectiveSettings(it)
                    },
                    onShowSymmetry = { scope.launch { viewModel.setSymmetryGuidesVisible(it) } },
                    onShowPerspective = { scope.launch { viewModel.setPerspectiveGuidesVisible(it) } },
                    assist =
                        GuideAssist(
                            snapToGuides = input.snapToGuides,
                            onSnap = { viewModel.setSnapToGuides(it) },
                            assisted = layers.firstOrNull { it.id == activeLayerId }?.drawingAssist == true,
                            onAssisted = { viewModel.setLayerDrawingAssist(activeLayerId, it) },
                        ),
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
                    backgroundColor = ready?.backgroundColor ?: 0xFFFFFFFF.toInt(),
                    onResize = { w, h, resample, anchor -> viewModel.canvasOps.resizeCanvas(w, h, resample, anchor) },
                    onRotate = { viewModel.canvasOps.rotateCanvas(it) },
                    onFlip = { viewModel.canvasOps.flipCanvas(it) },
                    onTrim = { viewModel.canvasOps.trimTransparent() },
                    onDpi = { viewModel.canvasOps.setCanvasDpi(it) },
                    onBackgroundColor = { viewModel.canvasOps.setCanvasBackgroundColor(it) },
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
                        val editing = editingText
                        val editingContent = editing?.textContent
                        when {
                            editing != null && editingContent != null -> {
                                val updated = editingContent.copy(text = input.text, style = input.textStyle, color = input.brushColor)
                                viewModel.textLayers.edit(editing.id, updated)
                                editingText = null
                            }
                            pending != null -> {
                                // Text goes on its own layer and stays editable, as in Procreate.
                                val content = TextLayerContent(input.text, input.textStyle, input.brushColor, pending.x, pending.y)
                                viewModel.textLayers.place(content)
                                viewModel.cancelText()
                                viewModel.setTool(ToolType.BRUSH)
                            }
                            else -> {
                                viewModel.setTool(ToolType.TEXT)
                                viewModel.notify("Tap the canvas to choose where the text goes")
                            }
                        }
                        panel = EditorPanel.NONE
                    },
                    importedFonts = importedFonts,
                    onImportFont = importFont,
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
                    onFlipCanvas = { viewModel.canvasOps.flipCanvas(it) },
                    onRotateCanvas = { viewModel.canvasOps.rotateCanvas(it) },
                    onSelectAll = { viewModel.selectAll() },
                    onClearSelection = { viewModel.clearSelection() },
                    onInvertSelection = { viewModel.invertSelection() },
                    onUndo = { viewModel.undo() },
                    onRedo = { viewModel.redo() },
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                )
            EditorPanel.ACTIONS ->
                ActionsPanel(
                    info =
                        CanvasInfo(
                            ready?.width ?: 0,
                            ready?.height ?: 0,
                            ready?.dpi ?: 72,
                            layers.size,
                            ready?.frameCount ?: 1,
                            (ready?.colorProfile ?: ColorProfile.SRGB).label,
                            viewModel.trackedTimeMs(),
                        ),
                    prefs =
                        StudioPrefs(
                            settings.rightHandedInterface,
                            input.quickShape,
                            input.touchHoldEyedropper,
                            input.fingerPainting,
                            settings.pressureCurve,
                            settings.stabilization,
                            GestureControls(settings.scrubToClear, settings.swipeCopyPaste, settings.fourFingerFullScreen),
                            lightInterface = settings.themeMode == ThemeMode.LIGHT,
                            brushCursor = settings.brushCursor,
                            dynamicBrushScaling = settings.dynamicBrushScaling,
                        ),
                    add =
                        AddActions(
                            onInsertFile = importPsd,
                            onInsertPhoto = insertPhoto,
                            onAddText = {
                                viewModel.setTool(ToolType.TEXT)
                                panel = EditorPanel.TEXT
                            },
                            onCut = { viewModel.clipboard.copy(cut = true) },
                            onCopy = { viewModel.clipboard.copy() },
                            onCopyCanvas = viewModel.clipboard::copyMerged,
                            onPaste = viewModel.clipboard::paste,
                            onTakePhoto = takePhoto,
                            onCutAndPaste = viewModel.clipboard::cutAndPaste,
                            onDuplicate = viewModel.clipboard::copyAndPaste,
                        ),
                    canvas =
                        CanvasActions(
                            wideColor = ready?.colorProfile == ColorProfile.DISPLAY_P3,
                            onWideColor = { wide ->
                                viewModel.canvasOps.setColorProfile(if (wide) ColorProfile.DISPLAY_P3 else ColorProfile.SRGB)
                            },
                            onCropResize = {
                                // Crop & Resize starts with the box on the canvas; Settings has the exact sizes.
                                panel = EditorPanel.NONE
                                canvasView?.cancelActiveGesture()
                                cropBox = ready?.let { CropBox.Box.of(it.width, it.height) }
                            },
                            onAnimationAssist = { panel = EditorPanel.ANIMATION },
                            onDrawingGuide = { panel = EditorPanel.GUIDES },
                            onReference = {
                                panel = EditorPanel.NONE
                                showReference = true
                            },
                            onFlip = { viewModel.canvasOps.flipCanvas(it) },
                            onPageAssist = {
                                panel = EditorPanel.NONE
                                pageAssist = true
                            },
                        ),
                    video =
                        VideoActions(
                            onReplay = { scope.launch { replayFrames = viewModel.timelapseFrames() } },
                            onExport = { fullLength ->
                                viewModel.exportTimelapse(fullLength)
                                panel = EditorPanel.EXPORT
                            },
                            onClear = viewModel::clearTimelapse,
                            quality = settings.timelapseMaxSide,
                            onQuality = { side -> viewModel.updateSettings { it.copy(timelapseMaxSide = side) } },
                        ),
                    prefActions =
                        PrefActions(
                            onRightHanded = viewModel::setRightHandedInterface,
                            onQuickShape = viewModel::setQuickShape,
                            onHoldEyedropper = viewModel::setTouchHoldEyedropper,
                            onFingerPainting = viewModel::setFingerPainting,
                            onFullScreen = {
                                panel = EditorPanel.NONE
                                focusMode = true
                            },
                            onMoreSettings = onOpenSettings,
                            onPressureAndSmoothing = viewModel::setPressureAndSmoothing,
                            onGestures = viewModel::setGestureControls,
                            onLightInterface = { light ->
                                viewModel.updateSettings { it.copy(themeMode = if (light) ThemeMode.LIGHT else ThemeMode.DARK) }
                            },
                            onBrushCursor = { on -> viewModel.updateSettings { it.copy(brushCursor = on) } },
                            onDynamicBrushScaling = { on -> viewModel.updateSettings { it.copy(dynamicBrushScaling = on) } },
                        ),
                    onShare = { panel = EditorPanel.EXPORT },
                    canPaste = hasClipboard,
                    onHelp = onOpenHelp,
                    // Within the panel's column, the list takes only the height that is left.
                    modifier = Modifier.weight(1f, fill = false),
                )
            EditorPanel.NONE -> Unit
        }
        Spacer(Modifier.height(24.dp))
    }

    Scaffold(
        topBar = {
            if (!focusMode) {
                StudioTopBar(
                    highlighted = setOfNotNull(input.tool.studioButton(), StudioButton.LAYERS.takeIf { panel == EditorPanel.LAYERS }),
                    openMenu = openMenu,
                    onButton = { button ->
                        canvasView?.cancelActiveGesture()
                        if (adjustment != null) viewModel.adjustments.apply()
                        when (button) {
                            StudioButton.GALLERY -> if (dirty) showExitConfirm = true else onNavigateBack()
                            StudioButton.ACTIONS -> panel = if (panel == EditorPanel.ACTIONS) EditorPanel.NONE else EditorPanel.ACTIONS
                            StudioButton.ADJUSTMENTS -> openMenu = button
                            StudioButton.SELECTION ->
                                viewModel.setTool(if (input.tool.group == ToolGroup.SELECTION) ToolType.BRUSH else ToolType.SELECT_FREEHAND)
                            StudioButton.TRANSFORM ->
                                viewModel.setTool(if (input.tool == ToolType.TRANSFORM) ToolType.BRUSH else ToolType.TRANSFORM)
                            StudioButton.PAINT ->
                                if (input.tool == ToolType.BRUSH) showBrushEditor = true else viewModel.setTool(ToolType.BRUSH)
                            StudioButton.SMUDGE ->
                                if (input.tool == ToolType.SMUDGE) showBrushEditor = true else viewModel.setTool(ToolType.SMUDGE)
                            StudioButton.ERASE ->
                                if (input.tool == ToolType.ERASER) panel = EditorPanel.TOOLS else viewModel.setTool(ToolType.ERASER)
                            StudioButton.LAYERS -> panel = if (panel == EditorPanel.LAYERS) EditorPanel.NONE else EditorPanel.LAYERS
                        }
                    },
                    onDismissMenu = { openMenu = null },
                    menuFor = { button ->
                        val choose: (() -> Unit) -> () -> Unit = { action ->
                            {
                                openMenu = null
                                action()
                            }
                        }
                        listOf(ToolType.LIQUIFY, ToolType.CLONE_STAMP, ToolType.HEALING, ToolType.GRADIENT).forEach { tool ->
                            DropdownMenuItem(
                                text = { Text(tool.displayName) },
                                leadingIcon = { Icon(tool.icon(), contentDescription = null) },
                                onClick = choose { viewModel.setTool(tool) },
                            )
                        }
                        HorizontalDivider()
                        LiveAdjustments.Kind.entries.forEach { kind ->
                            DropdownMenuItem(
                                text = { Text(kind.displayName) },
                                onClick = choose { viewModel.adjustments.start(kind, input.brushColor) },
                            )
                        }
                    },
                    colorSwatch = {
                        ColorChip(
                            color = input.brushColor,
                            onClick = { panel = if (panel == EditorPanel.COLOUR) EditorPanel.NONE else EditorPanel.COLOUR },
                            modifier =
                                Modifier
                                    .onGloballyPositioned { colorChipOrigin = it.positionInWindow() }
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { colorDropPosition = colorChipOrigin + it },
                                            onDrag = { change, delta ->
                                                change.consume()
                                                colorDropPosition = colorDropPosition?.plus(delta)
                                            },
                                            onDragEnd = {
                                                val drop = colorDropPosition
                                                colorDropPosition = null
                                                if (drop != null && canvasView?.colorDrop(drop.x, drop.y) == true) {
                                                    colorDropThreshold = input.fillTolerance.toFloat()
                                                }
                                            },
                                            onDragCancel = { colorDropPosition = null },
                                        )
                                    },
                        )
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .onGloballyPositioned { contentOrigin = it.positionInWindow() },
        ) {
            colorDropPosition?.let { drop ->
                val half = with(LocalDensity.current) { 20.dp.toPx() }
                val dropOffset =
                    androidx.compose.ui.unit.IntOffset(
                        (drop.x - contentOrigin.x - half).toInt(),
                        (drop.y - contentOrigin.y - half).toInt(),
                    )
                Box(
                    Modifier
                        .zIndex(10f)
                        .offset { dropOffset }
                        .size(40.dp)
                        .background(Color(input.brushColor), CircleShape),
                )
            }
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
                                attachToCanvas(state.width, state.height, state.dpi, state.backgroundColor)

                                onColorPicked = { viewModel.onColorPicked(it) }
                                onSelectionChanged = { mask, count -> viewModel.selectionChanged(mask, count) }
                                onDragPreview = { dragPreview = it }
                                onHistoryChanged = { _, _ -> }
                                onUndoRequested = { viewModel.undo() }
                                onRedoRequested = { viewModel.redo() }
                                onFullscreenRequested = { focusMode = !focusMode }
                                onCopyPasteMenuRequested = { showCopyPaste = true }
                                onClearLayerRequested = { viewModel.clipboard.clear() }
                                onTransformQuadChanged = { transformQuad = it }
                                onWarpMeshChanged = { warpMesh = it }
                                onBrushCursorChanged = { brushCursor = it }
                                onViewChanged = { s, ox, oy, r -> viewModel.onViewChanged(s, ox, oy, r) }
                                onTextPlacementRequested = { x, y -> viewModel.requestTextAt(x, y) }
                                onCloneSourceChanged = { viewModel.onCloneSourceChanged(it) }
                                onStatusMessage = { viewModel.notify(it) }
                                onFillToleranceChanged = viewModel::setFillTolerance
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
                            view.setWideColor(state.colorProfile == ColorProfile.DISPLAY_P3)
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

                    if (!focusMode && (settings.showSymmetryGuides || settings.showPerspectiveGuides)) {
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
            if (showReference && ready != null) {
                ReferenceCompanion(
                    projectId = projectId,
                    uri = referenceUri,
                    onImport = importReference,
                    onClose = { showReference = false },
                    onColorPicked = viewModel::onColorPicked,
                    canvas = CanvasReference(referenceCanvas, canvasPreviewBitmap) { referenceCanvas = it },
                )
            }
            if (ready != null && !focusMode) {
                StudioSidebar(
                    state =
                        StudioSidebarState(
                            size = if (input.tool == ToolType.ERASER) input.eraserSize else input.brushParams.size,
                            opacity = input.brushParams.opacity,
                            canUndo = history.canUndo,
                            canRedo = history.canRedo,
                            eyedropperActive = input.tool == ToolType.EYEDROPPER,
                        ),
                    actions =
                        StudioSidebarActions(
                            onSize = if (input.tool == ToolType.ERASER) viewModel::setEraserSize else viewModel::setBrushSize,
                            onOpacity = viewModel::setBrushOpacity,
                            onModify = viewModel::startEyedropper,
                            onUndo = viewModel::undo,
                            onRedo = viewModel::redo,
                            onQuickMenu = {
                                canvasView?.cancelActiveGesture()
                                quickMenu = true
                            },
                        ),
                    modifier =
                        if (settings.rightHandedInterface) {
                            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)
                        } else {
                            Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                        },
                )
                val toolbarLift = if (pageAssist) PAGE_ASSIST_HEIGHT else 0.dp
                ContextToolbar(viewModel, input, canvasView, Modifier.align(Alignment.BottomCenter).padding(bottom = toolbarLift)) {
                    panel = it
                }
                if (pageAssist) {
                    PageAssistBar(
                        pageCount = timeline.frameCount,
                        activePage = timeline.activeIndex,
                        thumbnails = pageImages,
                        actions =
                            PageAssistActions(
                                onSelect = { viewModel.selectFrame(it) },
                                onAdd = { viewModel.addFrame(false) },
                                onDuplicate = { viewModel.addFrame(true) },
                                onDelete = { viewModel.deleteFrame(it) },
                                onMove = { from, to -> viewModel.moveFrame(from, to) },
                                onClose = { pageAssist = false },
                            ),
                        modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 720.dp),
                    )
                }
            }
            val quad = transformQuad
            if (quad != null && ready != null && input.tool == ToolType.TRANSFORM) {
                TransformOverlay(
                    quad = quad,
                    canvasWidth = ready.width,
                    canvasHeight = ready.height,
                    view = ViewTransform(viewScale, viewOffsetX, viewOffsetY, viewRotation),
                    modifier = Modifier.fillMaxSize(),
                    snapping = input.transformAssist.snapping,
                )
            }
            colorDropThreshold?.let { threshold ->
                ColorDropThresholdBar(
                    threshold = threshold,
                    onChange = { colorDropThreshold = it },
                    // Read the state itself: the slider can finish before the latest value recomposes.
                    onCommit = { colorDropThreshold?.let { latest -> canvasView?.adjustColorDrop(latest.roundToInt()) } },
                    onDone = { colorDropThreshold = null },
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(4f),
                )
            }
            brushCursor?.let { cursor ->
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(cursor.x, cursor.y)
                    val radius = cursor.radius.coerceAtLeast(2f)
                    drawCircle(Color.White, radius = radius + 1f, center = center, style = Stroke(1.5f))
                    drawCircle(Color.Black.copy(alpha = 0.7f), radius = radius, center = center, style = Stroke(1f))
                }
            }
            val mesh = warpMesh
            if (mesh != null && ready != null && input.tool == ToolType.TRANSFORM) {
                WarpOverlay(
                    mesh = mesh,
                    canvasWidth = ready.width,
                    canvasHeight = ready.height,
                    view = ViewTransform(viewScale, viewOffsetX, viewOffsetY, viewRotation),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (quickMenu) {
                // Every action a QuickMenu slot can hold; settings keep which six are on the ring.
                val catalogue =
                    listOf(
                        QuickAction("New layer", Icons.Default.Add) { viewModel.addLayer() },
                        QuickAction("Merge down", Icons.Default.MergeType) { viewModel.mergeLayerDown(activeLayerId) },
                        QuickAction("Flip horizontal", Icons.Default.Flip) { viewModel.canvasOps.flipCanvas(false) },
                        QuickAction("Flip vertical", Icons.Default.Flip) { viewModel.canvasOps.flipCanvas(true) },
                        QuickAction("Clear layer", Icons.Default.LayersClear) { viewModel.clipboard.clear() },
                        QuickAction("Copy", Icons.Default.ContentCopy) { viewModel.clipboard.copy() },
                        QuickAction("Paste", Icons.Default.ContentPaste) { viewModel.clipboard.paste() },
                        QuickAction("Duplicate layer", Icons.Default.ContentCopy) { viewModel.duplicateActiveLayer() },
                        QuickAction("Undo", Icons.Default.Undo) { viewModel.undo() },
                        QuickAction("Redo", Icons.Default.Redo) { viewModel.redo() },
                        QuickAction("Full screen", Icons.Default.Fullscreen) { focusMode = true },
                        QuickAction("Layers", Icons.Default.Layers) { panel = EditorPanel.LAYERS },
                    )
                RadialQuickMenu(
                    actions = settings.quickMenu.map { label -> catalogue.firstOrNull { it.label == label } ?: catalogue.first() },
                    choices = catalogue,
                    onAssign = { slot, choice ->
                        viewModel.updateSettings { current ->
                            val slots = current.quickMenu.toMutableList()
                            slots[slot] = choice.label
                            current.copy(quickMenu = slots)
                        }
                    },
                    onMore = {
                        quickMenu = false
                        panel = EditorPanel.QUICK
                    },
                    onDismiss = { quickMenu = false },
                    modifier = Modifier.zIndex(7f),
                )
            }
            opacityLayer?.let { id ->
                val layer = layers.firstOrNull { it.id == id }
                if (layer == null) {
                    opacityLayer = null
                } else {
                    LayerOpacityOverlay(
                        opacity = layer.opacity,
                        onChange = { viewModel.setLayerOpacity(id, it) },
                        onDone = { opacityLayer = null },
                        modifier = Modifier.zIndex(5f),
                    )
                }
            }
            val crop = cropBox
            if (crop != null && ready != null) {
                CropOverlay(
                    box = crop,
                    canvasWidth = ready.width,
                    canvasHeight = ready.height,
                    view = ViewTransform(viewScale, viewOffsetX, viewOffsetY, viewRotation),
                    onChange = { cropBox = it },
                    modifier = Modifier.fillMaxSize().zIndex(5f),
                )
                CropBar(
                    box = crop,
                    onSettings = {
                        cropBox = null
                        panel = EditorPanel.CANVAS
                    },
                    onReset = { cropBox = CropBox.Box.of(ready.width, ready.height) },
                    onCancel = { cropBox = null },
                    onDone = {
                        cropBox = null
                        if (crop != CropBox.Box.of(ready.width, ready.height)) viewModel.canvasOps.cropCanvas(crop.toBounds())
                    },
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(6f),
                )
            }
            adjustment?.let { active ->
                AdjustmentOverlay(
                    state = active,
                    actions =
                        AdjustmentOverlayActions(
                            onAmount = viewModel.adjustments::setAmount,
                            onAngle = viewModel.adjustments::setAngle,
                            onParameter = viewModel.adjustments::setParameter,
                            onCancel = viewModel.adjustments::cancel,
                            onApply = viewModel.adjustments::apply,
                            onPencil = viewModel.adjustments::setPencil,
                            onPaint = { point, width, height ->
                                val size = ready
                                if (size != null) {
                                    val view = ViewTransform(viewScale, viewOffsetX, viewOffsetY, viewRotation)
                                    val at = view.toCanvas(point, width, height, size.width, size.height)
                                    if (active.kind.usesPoint) {
                                        viewModel.adjustments.setPoint(at.x, at.y)
                                    } else {
                                        viewModel.adjustments.paintAt(at.x, at.y, input.brushParams.size / 2f)
                                    }
                                }
                            },
                        ),
                    modifier = Modifier.zIndex(5f),
                )
            }
            if (showCopyPaste && ready != null) {
                CopyPasteMenu(
                    canPaste = hasClipboard,
                    actions =
                        CopyPasteActions(
                            onCut = { viewModel.clipboard.copy(cut = true) },
                            onCopy = { viewModel.clipboard.copy() },
                            onCopyAll = viewModel.clipboard::copyMerged,
                            onDuplicate = viewModel::duplicateActiveLayer,
                            onCutAndPaste = viewModel.clipboard::cutAndPaste,
                            onCopyAndPaste = viewModel.clipboard::copyAndPaste,
                            onPaste = viewModel.clipboard::paste,
                            onDismiss = { showCopyPaste = false },
                        ),
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            if (panel != EditorPanel.NONE && wide) {
                StudioPopover(
                    alignEnd = panel == EditorPanel.COLOUR || panel == EditorPanel.LAYERS,
                    onDismiss = dismissPanel,
                    content = panelContent,
                )
            }
            if (panel != EditorPanel.NONE && !wide) {
                // Phones: the panel rises over the canvas inside the editor window, below the top bar.
                StudioBottomPanel(onDismiss = dismissPanel, content = panelContent)
            }
            // Notices appear at the top centre, as in Procreate, so they never cover the panel or sidebar.
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.TopCenter).zIndex(8f))
            if (focusMode) {
                FilledTonalIconButton(
                    onClick = {
                        canvasView?.cancelActiveGesture()
                        focusMode = false
                    },
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .size(if (settings.largeTouchTargets) 56.dp else 48.dp),
                ) {
                    Icon(Icons.Default.FullscreenExit, contentDescription = "Exit focus mode")
                }
            }
        }
    }

    replayFrames?.let { frames ->
        TimelapseReplay(
            frames = frames,
            onExport = {
                replayFrames = null
                panel = EditorPanel.EXPORT
                viewModel.exportTimelapse()
            },
            onDismiss = { replayFrames = null },
        )
    }

    if (showBrushEditor) {
        BrushStudioDialog(
            initial = input.brushParams,
            onApply = {
                viewModel.setBrushParams(it)
                if (input.tool != ToolType.BRUSH) viewModel.setTool(ToolType.BRUSH)
            },
            onDismiss = { showBrushEditor = false },
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
    canvasView: ArtFlowCanvasView?,
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
                ToolType.MOVE -> "Drag to move the active layer's pixels."
                ToolType.TRANSFORM ->
                    "Drag to move, scale or rotate the active layer — or only the selection when one is active."

                else -> "Drag on the canvas to use this tool."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (input.tool == ToolType.GRADIENT) {
            Text("Gradient direction", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                com.artflow.studio.core.tool.GradientTool.GradientType.entries.forEach { type ->
                    FilterChip(
                        selected = input.gradientType == type,
                        onClick = { viewModel.setGradient(input.gradient, type) },
                        label = { Text(type.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Text("Ramp", style = MaterialTheme.typography.labelMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                com.artflow.studio.core.tool.LiquifyTool.Mode.entries.forEach { mode ->
                    FilterChip(
                        selected = input.liquify.mode == mode,
                        onClick = { viewModel.setLiquifySettings(input.liquify.copy(mode = mode)) },
                        label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Text("Size ${input.liquify.size.toInt()} px", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = input.liquify.size.coerceIn(8f, 400f),
                onValueChange = { viewModel.setLiquifySettings(input.liquify.copy(size = it)) },
                valueRange = 8f..400f,
            )
            Text("Distortion ${(input.liquify.strength * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = input.liquify.strength.coerceIn(0f, 1f),
                onValueChange = { viewModel.setLiquifySettings(input.liquify.copy(strength = it)) },
            )
            TextButton(onClick = viewModel::resetLiquify) { Text("Reset liquify") }
        }
        if (input.tool == ToolType.TRANSFORM) TransformOptions(viewModel, input, canvasView)
        if (input.tool == ToolType.BRUSH) BrushAssistOptions(viewModel, input)
        if (input.tool == ToolType.CLONE_STAMP) {
            Text(
                "Tap to set the clone source, then drag. Blend mode: aligned.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val MAX_PSD_IMPORT_BYTES = 256 * 1024 * 1024

/** After a ColorDrop, slide to fill more or less of the area, as with Procreate's drop threshold. */
@Composable
private fun ColorDropThresholdBar(
    threshold: Float,
    onChange: (Float) -> Unit,
    onCommit: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 4.dp, modifier = modifier.padding(12.dp).widthIn(max = 420.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ColorDrop threshold ${(threshold / 255f * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = threshold,
                onValueChange = onChange,
                onValueChangeFinished = onCommit,
                valueRange = 0f..255f,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

/** Non-modal bar for the active tool: selection kinds, transform modes or the mask-painting exit. */
@Composable
private fun ContextToolbar(
    viewModel: CanvasViewModel,
    input: EditorInput,
    canvasView: ArtFlowCanvasView?,
    modifier: Modifier,
    openPanel: (EditorPanel) -> Unit,
) {
    val savedSelections by viewModel.savedSelections.saved.collectAsState()
    when {
        input.tool.group == ToolGroup.SELECTION ->
            SelectionToolbar(
                tool = input.tool,
                mode = input.selectionMode,
                actions =
                    SelectionToolbarActions(
                        onTool = viewModel::setTool,
                        onMode = viewModel::setSelectionMode,
                        onInvert = viewModel::invertSelection,
                        onCopyPaste = viewModel.clipboard::copyAndPaste,
                        onMore = { openPanel(EditorPanel.SELECTION) },
                        onClear = viewModel::clearSelection,
                        onColorFill = { viewModel.clipboard.fill(input.brushColor) },
                        onSave = {
                            if (!viewModel.savedSelections.save()) viewModel.notify("Make a selection first")
                        },
                        onLoad = viewModel.savedSelections::load,
                        onDelete = viewModel.savedSelections::delete,
                    ),
                modifier = modifier,
                savedCount = savedSelections.size,
            )
        input.tool == ToolType.TRANSFORM ->
            TransformToolbar(
                mode = input.transformMode,
                interpolation = input.transformInterpolation,
                actions =
                    TransformToolbarActions(
                        onMode = viewModel::setTransformMode,
                        onFlip = { horizontal ->
                            canvasView?.transformActiveLayer(flipHorizontal = horizontal, flipVertical = !horizontal)
                        },
                        onRotate = { canvasView?.transformActiveLayer(rotation = it) },
                        onFit = { canvasView?.fitTransformToCanvas() },
                        onReset = { canvasView?.resetTransform() },
                        onInterpolation = viewModel::setTransformInterpolation,
                        onAssist = viewModel::setTransformAssist,
                    ),
                modifier = modifier,
                assist = input.transformAssist,
            )
        input.strokeDestination.isMask ->
            AssistChip(
                onClick = { viewModel.setTool(ToolType.BRUSH) },
                label = {
                    Text(if (input.strokeDestination == StrokeDestination.MASK_REVEAL) "Mask: reveal · Done" else "Mask: hide · Done")
                },
                modifier = modifier.padding(16.dp),
            )
    }
}

@Composable
private fun ClipboardActions(
    viewModel: CanvasViewModel,
    hasSelection: Boolean,
) {
    val hasClipboard by viewModel.clipboard.hasContent.collectAsState()
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(onClick = { viewModel.clipboard.copy(cut = true) }, label = { Text(if (hasSelection) "Cut" else "Cut layer") })
        AssistChip(onClick = { viewModel.clipboard.copy() }, label = { Text(if (hasSelection) "Copy" else "Copy layer") })
        AssistChip(onClick = viewModel.clipboard::paste, enabled = hasClipboard, label = { Text("Paste as layer") })
    }
}

@Composable
private fun BrushAssistOptions(
    viewModel: CanvasViewModel,
    input: EditorInput,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "StreamLine ${(input.brushParams.smoothing * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = input.brushParams.smoothing,
            onValueChange = { viewModel.setBrushParams(input.brushParams.copy(smoothing = it)) },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = input.quickShape, onCheckedChange = viewModel::setQuickShape)
            Spacer(Modifier.width(8.dp))
            Text("QuickShape — hold at the end of a stroke to snap a line or ellipse", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TransformOptions(
    viewModel: CanvasViewModel,
    input: EditorInput,
    canvasView: ArtFlowCanvasView?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Drag mode", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            com.artflow.studio.core.pixels.TransformQuad.Mode.entries.forEach { mode ->
                FilterChip(
                    selected = input.transformMode == mode,
                    onClick = { viewModel.setTransformMode(mode) },
                    label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AssistChip(onClick = { canvasView?.transformActiveLayer(flipHorizontal = true) }, label = { Text("Flip H") })
            AssistChip(onClick = { canvasView?.transformActiveLayer(flipVertical = true) }, label = { Text("Flip V") })
            AssistChip(onClick = { canvasView?.transformActiveLayer(rotation = 45f) }, label = { Text("Rotate 45°") })
            AssistChip(onClick = { canvasView?.transformActiveLayer(rotation = 90f) }, label = { Text("Rotate 90°") })
            AssistChip(onClick = { canvasView?.transformActiveLayer(scaleFactor = 0.5f) }, label = { Text("Half size") })
            AssistChip(onClick = { canvasView?.transformActiveLayer(scaleFactor = 2f) }, label = { Text("Double size") })
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

/** Room the Page Assist strip takes along the bottom of the canvas. */
private val PAGE_ASSIST_HEIGHT = 150.dp

/** What the editor's Back handler closes, most specific first. */
private enum class BackTarget { PANEL, CROP, OPACITY, QUICK_MENU, REFERENCE, FOCUS, LEAVE }
