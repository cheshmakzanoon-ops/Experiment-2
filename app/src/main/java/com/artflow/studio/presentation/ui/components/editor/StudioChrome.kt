package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.pixels.Quad
import com.artflow.studio.core.pixels.TransformQuad
import com.artflow.studio.core.pixels.WarpMesh
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.presentation.ui.components.canvas.SelectionCombineMode
import kotlin.math.sqrt

/** Buttons of the studio top bar, in Procreate's order: workspace menus left, painting tools right. */
enum class StudioButton(
    val label: String,
) {
    GALLERY("Gallery"),
    ACTIONS("Actions"),
    ADJUSTMENTS("Adjustments"),
    SELECTION("Selection"),
    TRANSFORM("Transform"),
    PAINT("Paint"),
    SMUDGE("Smudge"),
    ERASE("Erase"),
    LAYERS("Layers"),
}

private fun StudioButton.icon(): ImageVector =
    when (this) {
        StudioButton.GALLERY -> Icons.Default.Collections
        StudioButton.ACTIONS -> Icons.Default.Build
        StudioButton.ADJUSTMENTS -> Icons.Default.AutoFixHigh
        StudioButton.SELECTION -> Icons.Default.HighlightAlt
        StudioButton.TRANSFORM -> Icons.Default.NearMe
        StudioButton.PAINT -> ToolType.BRUSH.icon()
        StudioButton.SMUDGE -> ToolType.SMUDGE.icon()
        StudioButton.ERASE -> ToolType.ERASER.icon()
        StudioButton.LAYERS -> Icons.Default.Layers
    }

/** Which top-bar button reflects the active tool. */
fun ToolType.studioButton(): StudioButton? =
    when (this) {
        ToolType.BRUSH -> StudioButton.PAINT
        ToolType.SMUDGE -> StudioButton.SMUDGE
        ToolType.ERASER -> StudioButton.ERASE
        ToolType.TRANSFORM, ToolType.MOVE -> StudioButton.TRANSFORM
        ToolType.SELECT_RECTANGLE, ToolType.SELECT_ELLIPSE, ToolType.SELECT_FREEHAND,
        ToolType.SELECT_LASSO, ToolType.SELECT_MAGIC_WAND,
        -> StudioButton.SELECTION
        ToolType.LIQUIFY, ToolType.CLONE_STAMP, ToolType.HEALING -> StudioButton.ADJUSTMENTS
        else -> null
    }

/**
 * Slim bar over the full-bleed canvas. [menuFor] supplies the drop-down content for a button
 * that opens a menu (Actions, Adjustments); [openMenu] names the one currently shown.
 */
@Composable
fun StudioTopBar(
    highlighted: Set<StudioButton>,
    openMenu: StudioButton?,
    onButton: (StudioButton) -> Unit,
    onDismissMenu: () -> Unit,
    menuFor: @Composable ColumnScope.(StudioButton) -> Unit,
    colorSwatch: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onButton(StudioButton.GALLERY) }) { Text(StudioButton.GALLERY.label) }
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(StudioButton.ACTIONS, StudioButton.ADJUSTMENTS, StudioButton.SELECTION, StudioButton.TRANSFORM).forEach { button ->
                    Box {
                        StudioIconButton(button, button in highlighted || openMenu == button) { onButton(button) }
                        DropdownMenu(expanded = openMenu == button, onDismissRequest = onDismissMenu) { menuFor(button) }
                    }
                }
            }
            listOf(StudioButton.PAINT, StudioButton.SMUDGE, StudioButton.ERASE, StudioButton.LAYERS).forEach { button ->
                StudioIconButton(button, button in highlighted) { onButton(button) }
            }
            Spacer(Modifier.width(4.dp))
            colorSwatch()
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun StudioIconButton(
    button: StudioButton,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconToggleButton(checked = active, onCheckedChange = { onClick() }) {
        Icon(
            button.icon(),
            contentDescription = button.label,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** State shown by the floating sidebar. Size and opacity are the active tool's values. */
data class StudioSidebarState(
    val size: Float,
    val opacity: Float,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val eyedropperActive: Boolean,
)

data class StudioSidebarActions(
    val onSize: (Float) -> Unit,
    val onOpacity: (Float) -> Unit,
    val onModify: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
)

/** Brush size slider, modify (eyedropper) button, opacity slider, undo and redo. */
@Composable
fun StudioSidebar(
    state: StudioSidebarState,
    actions: StudioSidebarActions,
    modifier: Modifier = Modifier,
) {
    val tall = LocalConfiguration.current.screenHeightDp >= 560
    val sliderHeight: Dp = if (tall) 170.dp else 110.dp
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 3.dp,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VerticalSlider(
                value = sizeToSlider(state.size),
                onValueChange = { actions.onSize(sliderToSize(it)) },
                label = "Brush size ${state.size.toInt()} px",
                height = sliderHeight,
            )
            Box(
                modifier =
                    Modifier
                        .size(28.dp)
                        .border(
                            width = 2.dp,
                            color = if (state.eyedropperActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(6.dp),
                        ).semantics { contentDescription = "Modify: pick a colour from the canvas" }
                        .clickable(onClick = actions.onModify),
            )
            VerticalSlider(
                value = state.opacity,
                onValueChange = { actions.onOpacity(it.coerceAtLeast(0.01f)) },
                label = "Opacity ${(state.opacity * 100).toInt()}%",
                height = sliderHeight,
            )
            IconButton(onClick = actions.onUndo, enabled = state.canUndo, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = actions.onRedo, enabled = state.canRedo, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Redo, contentDescription = "Redo")
            }
        }
    }
}

private const val MAX_BRUSH_SIZE = 512f

/** Square-root mapping gives fine control over small brushes, like Procreate's size slider. */
internal fun sizeToSlider(size: Float): Float = sqrt(((size - 1f) / (MAX_BRUSH_SIZE - 1f)).coerceIn(0f, 1f))

internal fun sliderToSize(value: Float): Float = 1f + value.coerceIn(0f, 1f).let { it * it } * (MAX_BRUSH_SIZE - 1f)

/** A tall rounded slider: drag or tap anywhere along it; top is 1, bottom is 0. */
@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(onValueChange)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val thumb = MaterialTheme.colorScheme.onSurface
    Canvas(
        modifier =
            modifier
                .width(26.dp)
                .height(height)
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                    setProgress { target ->
                        latest(target.coerceIn(0f, 1f))
                        true
                    }
                }.pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        change.consume()
                        latest((1f - change.position.y / size.height).coerceIn(0f, 1f))
                    }
                }.pointerInput(Unit) {
                    detectTapGestures { latest((1f - it.y / size.height).coerceIn(0f, 1f)) }
                },
    ) {
        val radius = CornerRadius(size.width / 2f)
        drawRoundRect(track, cornerRadius = radius)
        val travel = size.height - size.width
        val top = (1f - value.coerceIn(0f, 1f)) * travel
        drawRoundRect(fill, topLeft = Offset(0f, top), size = Size(size.width, size.height - top), cornerRadius = radius)
        drawRoundRect(thumb, topLeft = Offset(2f, top + 2f), size = Size(size.width - 4f, size.width - 4f), cornerRadius = radius)
    }
}

data class SelectionToolbarActions(
    val onTool: (ToolType) -> Unit,
    val onMode: (SelectionCombineMode) -> Unit,
    val onInvert: () -> Unit,
    val onCopyPaste: () -> Unit,
    val onMore: () -> Unit,
    val onClear: () -> Unit,
    val onColorFill: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onLoad: (Int) -> Unit = {},
    val onDelete: (Int) -> Unit = {},
)

/** Procreate-style selection bar: selection kind, combine mode and the common follow-up actions. */
@Composable
fun SelectionToolbar(
    tool: ToolType,
    mode: SelectionCombineMode,
    actions: SelectionToolbarActions,
    modifier: Modifier = Modifier,
    savedCount: Int = 0,
) {
    var savedMenu by remember { mutableStateOf(false) }
    FloatingBar(modifier) {
        listOf(
            ToolType.SELECT_MAGIC_WAND to "Automatic",
            ToolType.SELECT_FREEHAND to "Freehand",
            ToolType.SELECT_RECTANGLE to "Rectangle",
            ToolType.SELECT_ELLIPSE to "Ellipse",
        ).forEach { (kind, name) ->
            FilterChip(selected = tool == kind, onClick = { actions.onTool(kind) }, label = { Text(name) })
        }
        VerticalDivider(Modifier.height(24.dp))
        listOf(SelectionCombineMode.ADD to "Add", SelectionCombineMode.SUBTRACT to "Remove").forEach { (combine, name) ->
            FilterChip(
                selected = mode == combine,
                onClick = { actions.onMode(if (mode == combine) SelectionCombineMode.REPLACE else combine) },
                label = { Text(name) },
            )
        }
        AssistChip(onClick = actions.onInvert, label = { Text("Invert") })
        AssistChip(onClick = actions.onCopyPaste, label = { Text("Copy & Paste") })
        AssistChip(onClick = actions.onMore, label = { Text("Feather…") })
        Box {
            AssistChip(onClick = { savedMenu = true }, label = { Text("Save & Load") })
            DropdownMenu(expanded = savedMenu, onDismissRequest = { savedMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Save selection") },
                    onClick = {
                        actions.onSave()
                        savedMenu = false
                    },
                )
                repeat(savedCount) { index ->
                    DropdownMenuItem(
                        text = { Text("Selection ${index + 1}") },
                        onClick = {
                            actions.onLoad(index)
                            savedMenu = false
                        },
                        trailingIcon = {
                            IconButton(onClick = { actions.onDelete(index) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete selection ${index + 1}")
                            }
                        },
                    )
                }
            }
        }
        AssistChip(onClick = actions.onColorFill, label = { Text("Colour Fill") })
        AssistChip(onClick = actions.onClear, label = { Text("Clear") })
    }
}

data class TransformToolbarActions(
    val onMode: (TransformQuad.Mode) -> Unit,
    val onFlip: (horizontal: Boolean) -> Unit,
    val onRotate: (degrees: Float) -> Unit,
    val onFit: () -> Unit,
    val onReset: () -> Unit,
    val onInterpolation: (TransformQuad.Interpolation) -> Unit,
)

/** Transform bar: how corner handles behave, plus Procreate's one-tap transform buttons. */
@Composable
fun TransformToolbar(
    mode: TransformQuad.Mode,
    interpolation: TransformQuad.Interpolation,
    actions: TransformToolbarActions,
    modifier: Modifier = Modifier,
) {
    FloatingBar(modifier) {
        TransformQuad.Mode.entries.forEach { entry ->
            FilterChip(selected = mode == entry, onClick = { actions.onMode(entry) }, label = { Text(entry.displayName) })
        }
        VerticalDivider(Modifier.height(24.dp))
        AssistChip(onClick = { actions.onFlip(true) }, label = { Text("Flip Horizontal") })
        AssistChip(onClick = { actions.onFlip(false) }, label = { Text("Flip Vertical") })
        AssistChip(onClick = { actions.onRotate(45f) }, label = { Text("Rotate 45°") })
        AssistChip(onClick = actions.onFit, label = { Text("Fit to Screen") })
        AssistChip(onClick = actions.onReset, label = { Text("Reset") })
        FilterChip(
            selected = interpolation == TransformQuad.Interpolation.BILINEAR,
            onClick = {
                actions.onInterpolation(
                    if (interpolation == TransformQuad.Interpolation.BILINEAR) {
                        TransformQuad.Interpolation.NEAREST
                    } else {
                        TransformQuad.Interpolation.BILINEAR
                    },
                )
            },
            label = { Text("Interpolation: ${interpolation.displayName}") },
        )
    }
}

/**
 * The transform box drawn over the artwork: outline, corner and edge handles and the rotation
 * knob. Uses the renderer's view matrix so it stays glued to the pixels at any zoom or rotation.
 */
@Composable
fun TransformOverlay(
    quad: Quad,
    canvasWidth: Int,
    canvasHeight: Int,
    view: ViewTransform,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val scale = view.scale.coerceAtLeast(0.01f)
        withTransform({
            translate(size.width / 2f + view.offsetX, size.height / 2f + view.offsetY)
            rotate(view.rotationDegrees, pivot = Offset.Zero)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-canvasWidth / 2f, -canvasHeight / 2f)
        }) {
            val line = 1.5f / scale
            val handle = 7f / scale
            val points = (0 until 4).map { Offset(quad.x(it), quad.y(it)) }
            for (i in 0 until 4) drawLine(accent, points[i], points[(i + 1) % 4], strokeWidth = line)
            val (kx, ky) = TransformQuad.rotationKnob(quad, KNOB_DISTANCE_PX / scale)
            val topMid = Offset((quad.x0 + quad.x1) / 2f, (quad.y0 + quad.y1) / 2f)
            drawLine(accent, topMid, Offset(kx, ky), strokeWidth = line)
            drawCircle(Color.White, radius = handle, center = Offset(kx, ky))
            drawCircle(accent, radius = handle, center = Offset(kx, ky), style = Stroke(line))
            points.forEach {
                drawCircle(Color.White, radius = handle, center = it)
                drawCircle(accent, radius = handle, center = it, style = Stroke(line))
            }
            for (i in 0 until 4) {
                val mid = Offset((points[i].x + points[(i + 1) % 4].x) / 2f, (points[i].y + points[(i + 1) % 4].y) / 2f)
                drawCircle(accent, radius = handle * 0.6f, center = mid)
            }
        }
    }
}

/** Warp's Bézier mesh: grid curves through the patch and its sixteen control points. */
@Composable
fun WarpOverlay(
    mesh: WarpMesh,
    canvasWidth: Int,
    canvasHeight: Int,
    view: ViewTransform,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val scale = view.scale.coerceAtLeast(0.01f)
        withTransform({
            translate(size.width / 2f + view.offsetX, size.height / 2f + view.offsetY)
            rotate(view.rotationDegrees, pivot = Offset.Zero)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-canvasWidth / 2f, -canvasHeight / 2f)
        }) {
            val line = 1.2f / scale
            for (k in 0 until WarpMesh.SIDE) {
                val t = k / (WarpMesh.SIDE - 1f)
                val rows = (0..WARP_CURVE_STEPS).map { mesh.evaluate(it / WARP_CURVE_STEPS.toFloat(), t) }
                val columns = (0..WARP_CURVE_STEPS).map { mesh.evaluate(t, it / WARP_CURVE_STEPS.toFloat()) }
                listOf(rows, columns).forEach { curve ->
                    curve.zipWithNext { a, b -> drawLine(accent, Offset(a.first, a.second), Offset(b.first, b.second), strokeWidth = line) }
                }
            }
            for (i in 0 until WarpMesh.POINTS) {
                val center = Offset(mesh.x(i), mesh.y(i))
                drawCircle(Color.White, radius = 6f / scale, center = center)
                drawCircle(accent, radius = 6f / scale, center = center, style = Stroke(line))
            }
        }
    }
}

private const val WARP_CURVE_STEPS = 24

/** What the adjustment overlay reports while an adjustment is open. */
data class AdjustmentOverlayActions(
    val onAmount: (Float) -> Unit,
    val onAngle: (Float) -> Unit,
    val onParameter: (String, Float) -> Unit,
    val onCancel: () -> Unit,
    val onApply: () -> Unit,
)

/** The canvas view's pan, zoom and rotation, as reported to the compose layer. */
data class ViewTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val rotationDegrees: Float,
)

/** Must match the canvas view's rotation-knob distance, in screen pixels. */
private const val KNOB_DISTANCE_PX = 48f

@Composable
private fun FloatingBar(
    modifier: Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        tonalElevation = 3.dp,
        modifier = modifier.padding(12.dp),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

data class CopyPasteActions(
    val onCut: () -> Unit,
    val onCopy: () -> Unit,
    val onCopyAll: () -> Unit,
    val onDuplicate: () -> Unit,
    val onCutAndPaste: () -> Unit,
    val onCopyAndPaste: () -> Unit,
    val onPaste: () -> Unit,
    val onDismiss: () -> Unit,
)

/** The menu a three-finger swipe down opens. Each choice closes it. */
@Composable
fun CopyPasteMenu(
    canPaste: Boolean,
    actions: CopyPasteActions,
    modifier: Modifier = Modifier,
) {
    fun run(action: () -> Unit): () -> Unit =
        {
            action()
            actions.onDismiss()
        }
    FloatingBar(modifier) {
        Text("Copy & Paste", style = MaterialTheme.typography.titleSmall)
        AssistChip(onClick = run(actions.onCut), label = { Text("Cut") })
        AssistChip(onClick = run(actions.onCopy), label = { Text("Copy") })
        AssistChip(onClick = run(actions.onCopyAll), label = { Text("Copy All") })
        AssistChip(onClick = run(actions.onDuplicate), label = { Text("Duplicate") })
        AssistChip(onClick = run(actions.onCutAndPaste), label = { Text("Cut & Paste") })
        AssistChip(onClick = run(actions.onCopyAndPaste), label = { Text("Copy & Paste") })
        AssistChip(onClick = run(actions.onPaste), enabled = canPaste, label = { Text("Paste") })
        IconButton(onClick = actions.onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close Copy & Paste") }
    }
}

/**
 * Tablet panels float as popovers anchored under the top bar, leaving the canvas visible, the way
 * Procreate presents Layers and Colours. Tapping outside the popover closes it.
 */
@Composable
fun StudioPopover(
    alignEnd: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier =
                Modifier
                    .align(if (alignEnd) Alignment.TopEnd else Alignment.TopStart)
                    .padding(top = 8.dp, start = 12.dp, end = 12.dp)
                    .width(400.dp)
                    .heightIn(max = screenHeight * 0.78f),
        ) {
            Column(content = content)
        }
    }
}

/** Whether the device is wide enough for popovers instead of bottom sheets. */
@Composable
fun isWideLayout(): Boolean = LocalConfiguration.current.screenWidthDp >= 600
