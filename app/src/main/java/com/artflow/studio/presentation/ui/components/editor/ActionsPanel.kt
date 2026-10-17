package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.canvas.CanvasInformation
import com.artflow.studio.core.canvas.StylusAction
import com.artflow.studio.core.canvas.StylusButtons
import com.artflow.studio.core.canvas.StylusEraserEnd
import com.artflow.studio.core.color.CmykProof
import com.artflow.studio.domain.model.settings.PressureAndSmoothing
import com.artflow.studio.presentation.ui.components.canvas.GestureControls
import kotlin.math.exp
import kotlin.math.ln

data class AddActions(
    val onInsertFile: () -> Unit,
    val onInsertPhoto: () -> Unit,
    val onAddText: () -> Unit,
    val onCut: () -> Unit,
    val onCopy: () -> Unit,
    val onCopyCanvas: () -> Unit,
    val onPaste: () -> Unit,
    val onTakePhoto: () -> Unit = {},
    val onCutAndPaste: () -> Unit = {},
    val onDuplicate: () -> Unit = {},
    val onInsertPrivatePhoto: () -> Unit = {},
)

data class CanvasActions(
    val onCropResize: () -> Unit,
    val onAnimationAssist: () -> Unit,
    val onDrawingGuide: () -> Unit,
    val onReference: () -> Unit,
    val onFlip: (vertical: Boolean) -> Unit,
    val onPageAssist: () -> Unit = {},
    /** True when the canvas uses the Display P3 profile instead of sRGB. */
    val wideColor: Boolean = false,
    val onWideColor: (Boolean) -> Unit = {},
    /** On-screen print proof; a view setting that never changes the artwork. */
    val proof: CmykProof.Mode = CmykProof.Mode.OFF,
    val onProof: (CmykProof.Mode) -> Unit = {},
    /** Reopens the 3D window; null when the artwork has no model. */
    val onModelView: (() -> Unit)? = null,
)

data class VideoActions(
    val onReplay: () -> Unit,
    /** Exports the recording; true for full length, false for a 30-second version. */
    val onExport: (Boolean) -> Unit,
    val onClear: () -> Unit,
    /** Longest side of recorded frames, and how to change it. */
    val quality: Int = 1280,
    val onQuality: (Int) -> Unit = {},
)

data class StudioPrefs(
    val rightHanded: Boolean,
    val quickShape: Boolean,
    val holdEyedropper: Boolean,
    val fingerPainting: Boolean,
    val smoothing: PressureAndSmoothing = PressureAndSmoothing(),
    val gestures: GestureControls = GestureControls(),
    val lightInterface: Boolean = false,
    val brushCursor: Boolean = true,
    val dynamicBrushScaling: Boolean = false,
    val stylus: StylusButtons = StylusButtons(),
)

data class PrefActions(
    val onRightHanded: (Boolean) -> Unit,
    val onQuickShape: (Boolean) -> Unit,
    val onHoldEyedropper: (Boolean) -> Unit,
    val onFingerPainting: (Boolean) -> Unit,
    val onFullScreen: () -> Unit,
    val onMoreSettings: () -> Unit,
    /** Prefs > Pressure and Smoothing, saved together. */
    val onPressureAndSmoothing: (PressureAndSmoothing) -> Unit = {},
    val onGestures: (GestureControls) -> Unit = {},
    val onLightInterface: (Boolean) -> Unit = {},
    val onBrushCursor: (Boolean) -> Unit = {},
    val onDynamicBrushScaling: (Boolean) -> Unit = {},
    /** Prefs > Stylus: the primary and secondary button actions, and the eraser end, saved together. */
    val onStylus: (StylusButtons) -> Unit = {},
)

/** Canvas facts shown under Canvas > Canvas information. */
data class CanvasInfo(
    val width: Int,
    val height: Int,
    val dpi: Int,
    /** Layers used across every frame. */
    val layers: Int,
    val frames: Int,
    val profile: String = "sRGB IEC61966-2.1",
    val trackedMs: Long = 0L,
    /** Most layers this canvas size can hold on this device, see [CanvasInformation]. */
    val layerLimit: Int = 0,
)

private enum class ActionsTab { Add, Canvas, Share, Video, Prefs, Help }

/** Procreate's Actions menu: Add, Canvas, Share, Video, Prefs and Help tabs. */
@Composable
fun ActionsPanel(
    info: CanvasInfo,
    prefs: StudioPrefs,
    add: AddActions,
    canvas: CanvasActions,
    video: VideoActions,
    prefActions: PrefActions,
    onShare: () -> Unit,
    canPaste: Boolean,
    onHelp: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(ActionsTab.Add) }
    Column(modifier.fillMaxWidth()) {
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            ActionsTab.entries.forEach { entry ->
                Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.name) })
            }
        }
        // Only the space left under the tabs, so the end of the list stays on short screens.
        Column(
            modifier =
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
        ) {
            when (tab) {
                ActionsTab.Add -> {
                    AddTab(add, canPaste)
                }

                ActionsTab.Canvas -> {
                    CanvasTab(canvas, info)
                }

                ActionsTab.Share -> {
                    ActionRow("Share or export artwork…", onShare)
                }

                ActionsTab.Video -> {
                    ActionRow("Time-lapse Replay", video.onReplay)
                    ActionRow("Export Time-lapse (Full length)", onClick = { video.onExport(true) })
                    ActionRow("Export Time-lapse (30 seconds)", onClick = { video.onExport(false) })
                    ActionRow("Clear Time-lapse Recording", video.onClear)
                    TimelapseQuality(video.quality, video.onQuality)
                }

                ActionsTab.Prefs -> {
                    PrefsTab(prefs, prefActions)
                }

                ActionsTab.Help -> {
                    HelpTab(onHelp)
                }
            }
        }
    }
}

@Composable
private fun AddTab(
    add: AddActions,
    canPaste: Boolean,
) {
    ActionRow("Insert a file (PSD)", add.onInsertFile)
    ActionRow("Insert a photo", add.onInsertPhoto)
    ActionRow("Insert a private photo", add.onInsertPrivatePhoto)
    ActionRow("Take a photo", add.onTakePhoto)
    ActionRow("Add text", add.onAddText)
    HorizontalDivider()
    ActionRow("Cut", add.onCut)
    ActionRow("Copy", add.onCopy)
    ActionRow("Copy canvas", add.onCopyCanvas)
    ActionRow("Paste", add.onPaste, enabled = canPaste)
    ActionRow("Cut & Paste", add.onCutAndPaste)
    ActionRow("Duplicate", add.onDuplicate)
}

@Composable
private fun CanvasTab(
    canvas: CanvasActions,
    info: CanvasInfo,
) {
    ActionRow("Crop & Resize", canvas.onCropResize)
    ActionRow("Animation Assist", canvas.onAnimationAssist)
    ActionRow("Page Assist", canvas.onPageAssist)
    ActionRow("Drawing Guide", canvas.onDrawingGuide)
    ActionRow("Reference", canvas.onReference)
    canvas.onModelView?.let { ActionRow("3D view", it) }
    ActionRow("Flip canvas horizontally", onClick = { canvas.onFlip(false) })
    ActionRow("Flip canvas vertically", onClick = { canvas.onFlip(true) })
    PrefSwitch("Display P3 colour profile", canvas.wideColor, canvas.onWideColor)
    PrefSwitch("CMYK print proof", canvas.proof != CmykProof.Mode.OFF) { on ->
        canvas.onProof(if (on) CmykProof.Mode.PROOF else CmykProof.Mode.OFF)
    }
    if (canvas.proof != CmykProof.Mode.OFF) {
        PrefSwitch("Grey out colours that won't print", canvas.proof == CmykProof.Mode.GAMUT_WARNING) { on ->
            canvas.onProof(if (on) CmykProof.Mode.GAMUT_WARNING else CmykProof.Mode.PROOF)
        }
    }
    HorizontalDivider()
    Text("Canvas information", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
    Text(
        "${info.width} × ${info.height} px · ${info.dpi} dpi · " +
            "${CanvasInformation.layerSummary(info.layers, info.layerLimit)} · ${info.frames} frame(s) · " +
            "${info.profile} · tracked time ${trackedLabel(info.trackedMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** Tracked time as hours and minutes, the way Procreate lists it. */
internal fun trackedLabel(ms: Long): String {
    val minutes = ms / 60_000L
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}

@Composable
private fun PrefsTab(
    prefs: StudioPrefs,
    actions: PrefActions,
) {
    PrefSwitch("Light interface", prefs.lightInterface, actions.onLightInterface)
    PrefSwitch("Right-hand interface", prefs.rightHanded, actions.onRightHanded)
    PrefSwitch("Brush cursor", prefs.brushCursor, actions.onBrushCursor)
    PrefSwitch("Dynamic brush scaling", prefs.dynamicBrushScaling, actions.onDynamicBrushScaling)
    PrefSwitch("QuickShape (hold at the end of a stroke)", prefs.quickShape, actions.onQuickShape)
    PrefSwitch("Paint with a finger", prefs.fingerPainting, actions.onFingerPainting)
    HorizontalDivider()
    Text("Pressure and Smoothing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
    val smoothing = prefs.smoothing
    val change = actions.onPressureAndSmoothing
    PrefSlider("Stabilization", smoothing.stabilization, 0f..1f, percent(smoothing.stabilization)) {
        change(smoothing.copy(stabilization = it))
    }
    PrefSlider("Motion filtering", smoothing.motionFiltering, 0f..1f, percent(smoothing.motionFiltering)) {
        change(smoothing.copy(motionFiltering = it))
    }
    PrefSlider("Expression", smoothing.motionExpression, 0f..1f, percent(smoothing.motionExpression)) {
        change(smoothing.copy(motionExpression = it))
    }
    PrefSlider("Pressure smoothing", smoothing.pressureSmoothing, 0f..1f, percent(smoothing.pressureSmoothing)) {
        change(smoothing.copy(pressureSmoothing = it))
    }
    // The slider reads soft (left) to firm (right) on a logarithmic scale centred on linear.
    PrefSlider(
        label = "Pressure",
        value = ln(smoothing.pressureCurve),
        range = ln(0.3f)..ln(3f),
        readout =
            when {
                smoothing.pressureCurve < 0.95f -> "Soft"
                smoothing.pressureCurve > 1.05f -> "Firm"
                else -> "Linear"
            },
    ) { change(smoothing.copy(pressureCurve = exp(it))) }
    HorizontalDivider()
    Text("Gesture controls", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
    val gestures = prefs.gestures
    val changeGestures = actions.onGestures
    PrefSwitch("Two-finger tap undoes", gestures.twoFingerTapUndo) {
        changeGestures(gestures.copy(twoFingerTapUndo = it))
    }
    PrefSwitch("Three-finger tap redoes", gestures.threeFingerTapRedo) {
        changeGestures(gestures.copy(threeFingerTapRedo = it))
    }
    PrefSwitch("Hold two or three fingers to repeat undo or redo", gestures.holdRapidUndoRedo) {
        changeGestures(gestures.copy(holdRapidUndoRedo = it))
    }
    PrefSwitch("Pinch, rotate and drag move the canvas", gestures.pinchNavigation) {
        changeGestures(gestures.copy(pinchNavigation = it))
    }
    PrefSwitch("Quick pinch fits the canvas to the screen", gestures.quickPinchFit) {
        changeGestures(gestures.copy(quickPinchFit = it))
    }
    PrefSwitch("Touch and hold for eyedropper", prefs.holdEyedropper, actions.onHoldEyedropper)
    PrefSwitch("Three-finger scrub clears the layer", gestures.scrubToClear) {
        changeGestures(gestures.copy(scrubToClear = it))
    }
    PrefSwitch("Three-finger swipe opens Copy & Paste", gestures.swipeCopyPaste) {
        changeGestures(gestures.copy(swipeCopyPaste = it))
    }
    PrefSwitch("Four-finger tap toggles full screen", gestures.fourFingerFullScreen) {
        changeGestures(gestures.copy(fourFingerFullScreen = it))
    }
    PrefSwitch("Layer: two-finger tap, then slide, sets opacity", gestures.layerTwoFingerOpacity) {
        changeGestures(gestures.copy(layerTwoFingerOpacity = it))
    }
    PrefSwitch("Layer: two-finger swipe right toggles Alpha Lock", gestures.layerTwoFingerAlphaLock) {
        changeGestures(gestures.copy(layerTwoFingerAlphaLock = it))
    }
    PrefSwitch("Layer: two-finger touch and hold selects contents", gestures.layerTwoFingerSelect) {
        changeGestures(gestures.copy(layerTwoFingerSelect = it))
    }
    PrefSwitch("Hold the modify button for QuickMenu", gestures.modifyHoldQuickMenu) {
        changeGestures(gestures.copy(modifyHoldQuickMenu = it))
    }
    HorizontalDivider()
    Text("Stylus", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
    val stylus = prefs.stylus
    ChoiceRow(
        label = "Primary button",
        selected = stylus.primary,
        options = StylusAction.entries,
        optionLabel = { it.label },
        onSelect = { actions.onStylus(stylus.copy(primary = it)) },
    )
    ChoiceRow(
        label = "Secondary button",
        selected = stylus.secondary,
        options = StylusAction.entries,
        optionLabel = { it.label },
        onSelect = { actions.onStylus(stylus.copy(secondary = it)) },
    )
    ChoiceRow(
        label = "Eraser end",
        selected = stylus.eraserEnd,
        options = StylusEraserEnd.entries,
        optionLabel = { it.label },
        onSelect = { actions.onStylus(stylus.copy(eraserEnd = it)) },
    )
    HorizontalDivider()
    ActionRow("Full screen", actions.onFullScreen)
    ActionRow("More preferences…", actions.onMoreSettings)
}

@Composable
private fun HelpTab(onHandbook: () -> Unit) {
    ActionRow("Open the ArtFlow handbook", onHandbook)
    HorizontalDivider()
    listOf(
        "Two-finger tap: undo · Three-finger tap: redo",
        "Four-finger tap: full screen",
        "Pinch to zoom and rotate · Two-finger drag to pan",
        "Three-finger swipe down: Copy & Paste",
        "Three-finger scrub: clear layer",
        "Touch and hold: eyedropper · Hold at stroke end: QuickShape",
        "Drag the colour swatch onto the canvas: ColorDrop",
        "Tap the selected layer for its options",
    ).forEach { line ->
        Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

/** Recording size for new time-lapse frames; earlier frames keep their size. */
@Composable
private fun TimelapseQuality(
    quality: Int,
    onQuality: (Int) -> Unit,
) {
    Text("Recording quality", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(1280 to "720p", 1920 to "1080p", 2560 to "1440p").forEach { (side, label) ->
            FilterChip(selected = quality == side, onClick = { onQuality(side) }, label = { Text(label) })
        }
    }
}

@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

private fun percent(value: Float) = "${(value * 100).toInt()}%"

@Composable
private fun PrefSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    readout: String,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(readout, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
    }
}

/** A label with the current choice on a button; tapping it opens the choices, as the canvas preset menu does. */
@Composable
private fun <T> ChoiceRow(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(optionLabel(selected)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(optionLabel(option)) },
                        onClick = {
                            open = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrefSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
