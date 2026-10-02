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
)

data class CanvasActions(
    val onCropResize: () -> Unit,
    val onAnimationAssist: () -> Unit,
    val onDrawingGuide: () -> Unit,
    val onReference: () -> Unit,
    val onFlip: (vertical: Boolean) -> Unit,
)

data class VideoActions(
    val onReplay: () -> Unit,
    val onExport: () -> Unit,
    val onClear: () -> Unit,
)

data class StudioPrefs(
    val rightHanded: Boolean,
    val quickShape: Boolean,
    val holdEyedropper: Boolean,
    val fingerPainting: Boolean,
    val pressureCurve: Float = 1f,
    val stabilization: Float = 0f,
)

data class PrefActions(
    val onRightHanded: (Boolean) -> Unit,
    val onQuickShape: (Boolean) -> Unit,
    val onHoldEyedropper: (Boolean) -> Unit,
    val onFingerPainting: (Boolean) -> Unit,
    val onFullScreen: () -> Unit,
    val onMoreSettings: () -> Unit,
    /** Pressure curve exponent and stabilization, saved together. */
    val onPressureAndSmoothing: (Float, Float) -> Unit = { _, _ -> },
)

/** Canvas facts shown under Canvas > Canvas information. */
data class CanvasInfo(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val layers: Int,
    val frames: Int,
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
) {
    var tab by rememberSaveable { mutableStateOf(ActionsTab.Add) }
    Column(Modifier.fillMaxWidth()) {
        ScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            ActionsTab.entries.forEach { entry ->
                Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.name) })
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
        ) {
            when (tab) {
                ActionsTab.Add -> AddTab(add, canPaste)
                ActionsTab.Canvas -> CanvasTab(canvas, info)
                ActionsTab.Share -> ActionRow("Share or export artwork…", onShare)
                ActionsTab.Video -> {
                    ActionRow("Time-lapse Replay", video.onReplay)
                    ActionRow("Export Time-lapse Video", video.onExport)
                    ActionRow("Clear Time-lapse Recording", video.onClear)
                }
                ActionsTab.Prefs -> PrefsTab(prefs, prefActions)
                ActionsTab.Help -> HelpTab()
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
    ActionRow("Add text", add.onAddText)
    HorizontalDivider()
    ActionRow("Cut", add.onCut)
    ActionRow("Copy", add.onCopy)
    ActionRow("Copy canvas", add.onCopyCanvas)
    ActionRow("Paste", add.onPaste, enabled = canPaste)
}

@Composable
private fun CanvasTab(
    canvas: CanvasActions,
    info: CanvasInfo,
) {
    ActionRow("Crop & Resize", canvas.onCropResize)
    ActionRow("Animation Assist", canvas.onAnimationAssist)
    ActionRow("Drawing Guide", canvas.onDrawingGuide)
    ActionRow("Reference", canvas.onReference)
    ActionRow("Flip canvas horizontally", onClick = { canvas.onFlip(false) })
    ActionRow("Flip canvas vertically", onClick = { canvas.onFlip(true) })
    HorizontalDivider()
    Text("Canvas information", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
    Text(
        "${info.width} × ${info.height} px · ${info.dpi} dpi · ${info.layers} layers · ${info.frames} frame(s)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

@Composable
private fun PrefsTab(
    prefs: StudioPrefs,
    actions: PrefActions,
) {
    PrefSwitch("Right-hand interface", prefs.rightHanded, actions.onRightHanded)
    PrefSwitch("QuickShape (hold at the end of a stroke)", prefs.quickShape, actions.onQuickShape)
    PrefSwitch("Touch and hold for eyedropper", prefs.holdEyedropper, actions.onHoldEyedropper)
    PrefSwitch("Paint with a finger", prefs.fingerPainting, actions.onFingerPainting)
    HorizontalDivider()
    Text("Pressure and Smoothing", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
    PrefSlider(
        label = "Stabilization",
        value = prefs.stabilization,
        range = 0f..1f,
        readout = "${(prefs.stabilization * 100).toInt()}%",
    ) { actions.onPressureAndSmoothing(prefs.pressureCurve, it) }
    // The slider reads soft (left) to firm (right) on a logarithmic scale centred on linear.
    PrefSlider(
        label = "Pressure",
        value = ln(prefs.pressureCurve),
        range = ln(0.3f)..ln(3f),
        readout =
            when {
                prefs.pressureCurve < 0.95f -> "Soft"
                prefs.pressureCurve > 1.05f -> "Firm"
                else -> "Linear"
            },
    ) { actions.onPressureAndSmoothing(exp(it), prefs.stabilization) }
    HorizontalDivider()
    ActionRow("Full screen", actions.onFullScreen)
    ActionRow("More preferences…", actions.onMoreSettings)
}

@Composable
private fun HelpTab() {
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
