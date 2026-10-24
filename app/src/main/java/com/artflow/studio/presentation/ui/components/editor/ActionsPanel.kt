package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.core.canvas.StylusAction
import com.artflow.studio.core.canvas.StylusButtons
import com.artflow.studio.core.canvas.StylusEraserEnd
import com.artflow.studio.core.color.CmykProof
import com.artflow.studio.core.color.ViewFilter
import com.artflow.studio.core.pixels.AreaSample
import com.artflow.studio.domain.model.brush.PressureResponse
import com.artflow.studio.domain.model.settings.PressureAndSmoothing
import com.artflow.studio.presentation.ui.components.canvas.GestureControls
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

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
    /** Approximate process-ink preview; a view setting that never changes the artwork. */
    val proof: CmykProof.Mode = CmykProof.Mode.OFF,
    val onProof: (CmykProof.Mode) -> Unit = {},
    /** On-screen greyscale or colour-vision check; never changes the artwork. */
    val viewFilter: ViewFilter = ViewFilter.OFF,
    val onViewFilter: (ViewFilter) -> Unit = {},
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
    /** Time-lapse Recording: whether new edits are recorded. */
    val recording: Boolean = true,
    val onRecording: (Boolean) -> Unit = {},
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
    val selectionMaskVisibility: Float = 0.28f,
    val projectCanvas: Boolean = false,
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
    val onSelectionMaskVisibility: (Float) -> Unit = {},
    val onProjectCanvas: (Boolean) -> Unit = {},
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
    /** Most layers this canvas size can hold on this device; worked out in core/canvas CanvasInformation. */
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
                    PrefSwitch("Time-lapse Recording", video.recording, video.onRecording)
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
    PrefSwitch("Simulate CMYK inks", canvas.proof != CmykProof.Mode.OFF) { on ->
        canvas.onProof(if (on) CmykProof.Mode.PROOF else CmykProof.Mode.OFF)
    }
    if (canvas.proof != CmykProof.Mode.OFF) {
        Text(
            "Approximate preview; no printer colour profile is applied.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        PrefSwitch("Grey out simulated colour shifts", canvas.proof == CmykProof.Mode.GAMUT_WARNING) { on ->
            canvas.onProof(if (on) CmykProof.Mode.GAMUT_WARNING else CmykProof.Mode.PROOF)
        }
    }
    Text("View check", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ViewFilter.entries.forEach { filter ->
            FilterChip(
                selected = canvas.viewFilter == filter,
                onClick = { canvas.onViewFilter(filter) },
                label = { Text(filter.displayName) },
            )
        }
    }
    HorizontalDivider()
    Text(
        stringResource(R.string.actionspanel_canvas_information),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(8.dp),
    )
    Text(
        "${info.width} × ${info.height} px · ${info.dpi} dpi · " +
            "${stringResource(R.string.actionspanel_layers_of_limit, info.layers, info.layerLimit)} · ${info.frames} frame(s) · " +
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
    PrefSwitch("Project canvas on a second screen", prefs.projectCanvas, actions.onProjectCanvas)
    PrefSlider(
        label = "Selection mask",
        value = prefs.selectionMaskVisibility,
        range = 0f..1f,
        readout = "${(prefs.selectionMaskVisibility * 100).roundToInt()}%",
        onChange = actions.onSelectionMaskVisibility,
    )
    HorizontalDivider()
    Text(
        stringResource(R.string.actionspanel_pressure_and_smoothing),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(8.dp),
    )
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
    PrefSlider("Pulled string", smoothing.pulledString, 0f..1f, percent(smoothing.pulledString)) {
        change(smoothing.copy(pulledString = it))
    }
    // The slider reads soft (left) to firm (right) on a logarithmic scale centred on linear.
    PrefSlider(
        label = stringResource(R.string.actionspanel_pressure),
        value = ln(smoothing.pressureCurve),
        range = ln(0.3f)..ln(3f),
        readout =
            when {
                smoothing.pressureCurve < 0.95f -> "Soft"
                smoothing.pressureCurve > 1.05f -> "Firm"
                else -> "Linear"
            },
    ) { change(smoothing.copy(pressureCurve = exp(it))) }
    // The curve's three points stay in order: moving one pushes its neighbours along.
    val response = smoothing.pressureResponse
    PrefSlider("Light press", response.low, 0f..1f, percent(response.low)) { value ->
        change(smoothing.copy(pressureResponse = PressureResponse(value, maxOf(value, response.middle), maxOf(value, response.high))))
    }
    PrefSlider("Medium press", response.middle, 0f..1f, percent(response.middle)) { value ->
        change(smoothing.copy(pressureResponse = PressureResponse(minOf(response.low, value), value, maxOf(value, response.high))))
    }
    PrefSlider("Firm press", response.high, 0f..1f, percent(response.high)) { value ->
        change(smoothing.copy(pressureResponse = PressureResponse(minOf(response.low, value), minOf(response.middle, value), value)))
    }
    HorizontalDivider()
    Text(
        stringResource(R.string.actionspanel_gesture_controls),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(8.dp),
    )
    val gestures = prefs.gestures
    val changeGestures = actions.onGestures
    PrefSwitch(stringResource(R.string.actionspanel_gesture_two_finger_undo), gestures.twoFingerTapUndo) {
        changeGestures(gestures.copy(twoFingerTapUndo = it))
    }
    PrefSwitch(stringResource(R.string.actionspanel_gesture_three_finger_redo), gestures.threeFingerTapRedo) {
        changeGestures(gestures.copy(threeFingerTapRedo = it))
    }
    PrefSwitch("Touch and hold selects the layer under your finger", gestures.holdSelectsLayer) {
        actions.onGestures(gestures.copy(holdSelectsLayer = it))
    }
    PrefSlider(
        label = "Rapid undo delay",
        value = gestures.rapidUndoDelayMs.toFloat(),
        range = 200f..1500f,
        readout = "%.2f s".format(gestures.rapidUndoDelayMs / 1000f),
    ) { actions.onGestures(gestures.copy(rapidUndoDelayMs = (it / 50f).roundToInt() * 50)) }
    PrefSlider(
        label = "Eyedropper delay",
        value = gestures.eyedropperDelayMs.toFloat(),
        range = 200f..1500f,
        readout = "%.2f s".format(gestures.eyedropperDelayMs / 1000f),
    ) { actions.onGestures(gestures.copy(eyedropperDelayMs = (it / 50f).roundToInt() * 50)) }
    Text("Eyedropper sample", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AreaSample.SIZES.forEach { size ->
            FilterChip(
                selected = gestures.eyedropperSample == size,
                onClick = { actions.onGestures(gestures.copy(eyedropperSample = size)) },
                label = { Text(if (size == 1) "1 px" else "$size×$size") },
            )
        }
    }
    PrefSlider(
        label = "QuickShape delay",
        value = gestures.quickShapeDelayMs.toFloat(),
        range = 200f..1500f,
        readout = "%.2f s".format(gestures.quickShapeDelayMs / 1000f),
    ) { actions.onGestures(gestures.copy(quickShapeDelayMs = (it / 50f).roundToInt() * 50)) }
    StylusPreferences(prefs.stylus, actions.onStylus)
    HorizontalDivider()
    ActionRow("Full screen", actions.onFullScreen)
    ActionRow("More preferences…", actions.onMoreSettings)
}

@Composable
private fun StylusPreferences(
    buttons: StylusButtons,
    onChange: (StylusButtons) -> Unit,
) {
    HorizontalDivider()
    Text(stringResource(R.string.actionspanel_stylus), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
    ChoiceRow(
        stringResource(R.string.actionspanel_stylus_primary_button),
        buttons.primary,
        StylusAction.entries,
        StylusAction::labelRes,
    ) { onChange(buttons.copy(primary = it)) }
    ChoiceRow(
        stringResource(R.string.actionspanel_stylus_secondary_button),
        buttons.secondary,
        StylusAction.entries,
        StylusAction::labelRes,
    ) { onChange(buttons.copy(secondary = it)) }
    ChoiceRow(
        stringResource(R.string.actionspanel_stylus_eraser_end),
        buttons.eraserEnd,
        StylusEraserEnd.entries,
        StylusEraserEnd::labelRes,
    ) { onChange(buttons.copy(eraserEnd = it)) }
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
    Text(
        stringResource(R.string.actionspanel_recording_quality),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(8.dp),
    )
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

/** The menu label for each stylus button action. */
private fun StylusAction.labelRes(): Int =
    when (this) {
        StylusAction.EYEDROPPER -> R.string.actionspanel_stylus_eyedropper
        StylusAction.ERASER -> R.string.actionspanel_stylus_eraser_while_held
        StylusAction.SMUDGE -> R.string.actionspanel_stylus_smudge_while_held
        StylusAction.PREVIOUS_TOOL -> R.string.actionspanel_stylus_previous_tool_while_held
        StylusAction.QUICK_MENU -> R.string.actionspanel_stylus_quick_menu
        StylusAction.UNDO -> R.string.actionspanel_stylus_undo
        StylusAction.NOTHING -> R.string.actionspanel_stylus_nothing
    }

/** The menu label for each eraser-end choice. */
private fun StylusEraserEnd.labelRes(): Int =
    when (this) {
        StylusEraserEnd.ERASER -> R.string.actionspanel_stylus_eraser
        StylusEraserEnd.NOTHING -> R.string.actionspanel_stylus_nothing
    }

/** A label with the current choice on a button; tapping it opens the choices, as the canvas preset menu does. */
@Composable
private fun <T> ChoiceRow(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> Int,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(stringResource(optionLabel(selected))) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(stringResource(optionLabel(option))) },
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
