package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.perspective.PerspectiveGuide
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.presentation.ui.components.canvas.DragPreview
import com.artflow.studio.presentation.ui.theme.LocalArtFlowFlags
import kotlin.math.cos
import kotlin.math.sin

/** Icon for each tool, kept in the UI layer so `core` stays free of Android types. */
fun ToolType.icon(): androidx.compose.ui.graphics.vector.ImageVector =
    when (this) {
        ToolType.BRUSH -> Icons.Default.Brush
        ToolType.ERASER -> Icons.Default.AutoFixOff
        ToolType.SMUDGE -> Icons.Default.BlurOn
        ToolType.CLONE_STAMP -> Icons.Default.ContentCopy
        ToolType.HEALING -> Icons.Default.Healing
        ToolType.LIQUIFY -> Icons.Default.Waves
        ToolType.PAINT_BUCKET -> Icons.Default.FormatColorFill
        ToolType.GRADIENT -> Icons.Default.Gradient
        ToolType.TEXT -> Icons.Default.TextFields
        ToolType.SHAPE -> Icons.Default.Category
        ToolType.SELECT_RECTANGLE -> Icons.Default.CropSquare
        ToolType.SELECT_ELLIPSE -> Icons.Default.Circle
        ToolType.SELECT_FREEHAND -> Icons.Default.Gesture
        ToolType.SELECT_LASSO -> Icons.Default.Polyline
        ToolType.SELECT_MAGIC_WAND -> Icons.Default.AutoFixHigh
        ToolType.TRANSFORM -> Icons.Default.OpenWith
        ToolType.EYEDROPPER -> Icons.Default.Colorize
        ToolType.MOVE -> Icons.Default.PanTool
        ToolType.ZOOM -> Icons.Default.ZoomIn
    }

/** Size / opacity controls, shown for the tools where they mean something. */
@Composable
fun BrushOptionsRow(
    tool: ToolType,
    size: Float,
    opacity: Float,
    eraserSize: Float,
    tolerance: Int,
    onSizeChanged: (Float) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    onEraserSizeChanged: (Float) -> Unit,
    onToleranceChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            tool == ToolType.ERASER ->
                LabeledSlider(
                    label = "Size",
                    value = eraserSize,
                    range = 1f..400f,
                    onChange = onEraserSizeChanged,
                    modifier = Modifier.weight(1f),
                )
            tool.usesBrushSize() -> {
                LabeledSlider(
                    label = "Size",
                    value = size,
                    range = 1f..400f,
                    onChange = onSizeChanged,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                LabeledSlider(
                    label = "Opacity",
                    value = opacity,
                    range = 0.01f..1f,
                    onChange = onOpacityChanged,
                    modifier = Modifier.weight(1f),
                )
            }
            tool == ToolType.PAINT_BUCKET || tool == ToolType.SELECT_MAGIC_WAND ->
                LabeledSlider(
                    label = "Tolerance",
                    value = tolerance.toFloat(),
                    range = 0f..255f,
                    onChange = { onToleranceChanged(it.toInt()) },
                    modifier = Modifier.weight(1f),
                )
            else ->
                Text(
                    text = "Adjust ${tool.displayName.lowercase()} in the tool panel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                text =
                    if (range.endInclusive <= 1.01f) {
                        "${(value * 100).toInt()}%"
                    } else {
                        value.toInt().toString()
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Canvas overlay: symmetry and perspective guides, the selection mask and the live drag preview.
 *
 * Everything is drawn inside one transform that mirrors the renderer's matrix
 * (`translate(viewCentre + offset) · rotate · scale · translate(-canvasCentre)`), so guides stay
 * glued to the artwork at any zoom, pan or rotation.
 */
@Composable
fun GuidesOverlay(
    canvasWidth: Int,
    canvasHeight: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    rotationDegrees: Float,
    symmetry: SymmetryEngine.Settings,
    showSymmetry: Boolean,
    perspective: PerspectiveGuide.Settings,
    showPerspective: Boolean,
    selection: SelectionMask?,
    preview: DragPreview?,
    modifier: Modifier = Modifier,
) {
    val dashPhase =
        if (selection != null && !LocalArtFlowFlags.current.reduceMotion) {
            val phase by rememberInfiniteTransition(label = "ants").animateFloat(
                initialValue = 0f,
                targetValue = 24f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 900)),
                label = "dash",
            )
            phase
        } else {
            0f
        }

    var maskBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(selection) {
        maskBitmap =
            selection?.let { mask ->
                val buffer: PixelBuffer = mask.toMaskBitmap(0xFFFF3B30.toInt())
                BitmapPixelBridge.toBitmap(buffer).asImageBitmap()
            }
    }

    Canvas(modifier = modifier) {
        val viewCentre = Offset(size.width / 2f, size.height / 2f)
        val canvasCentre = Offset(canvasWidth / 2f, canvasHeight / 2f)

        withTransform({
            translate(viewCentre.x + offsetX, viewCentre.y + offsetY)
            rotate(rotationDegrees, pivot = Offset.Zero)
            scale(scale, scale, pivot = Offset.Zero)
            translate(-canvasCentre.x, -canvasCentre.y)
        }) {
            val hairline = (1.2f / scale).coerceAtLeast(0.2f)

            if (showSymmetry && symmetry.isActive()) {
                SymmetryEngine.guideLines(canvasWidth, canvasHeight, symmetry).forEach { line ->
                    drawLine(
                        color = Color(0xCC2D9CDB),
                        start = Offset(line.startX, line.startY),
                        end = Offset(line.endX, line.endY),
                        strokeWidth = if (line.isPrimary) hairline * 1.4f else hairline,
                    )
                }
            }

            if (showPerspective && perspective.isActive()) {
                PerspectiveGuide.guideLines(perspective, canvasWidth, canvasHeight).forEach { line ->
                    val clipped = PerspectiveGuide.clipToCanvas(line, canvasWidth, canvasHeight) ?: return@forEach
                    drawLine(
                        color = Color(0x9988CC66),
                        start = Offset(clipped.startX, clipped.startY),
                        end = Offset(clipped.endX, clipped.endY),
                        strokeWidth = hairline,
                    )
                }
            }

            maskBitmap?.let { bitmap ->
                drawImage(
                    image = bitmap,
                    dstOffset =
                        androidx.compose.ui.unit
                            .IntOffset(0, 0),
                    dstSize =
                        androidx.compose.ui.unit
                            .IntSize(canvasWidth, canvasHeight),
                    alpha = 0.28f,
                )
            }

            preview?.let { band ->
                val points = band.points.map { Offset(it.first, it.second) }
                when (band.tool) {
                    ToolType.SELECT_RECTANGLE, ToolType.SELECT_ELLIPSE ->
                        if (points.size >= 2) {
                            val topLeft =
                                Offset(
                                    minOf(points.first().x, points.last().x),
                                    minOf(points.first().y, points.last().y),
                                )
                            val size =
                                androidx.compose.ui.geometry.Size(
                                    kotlin.math.abs(points.last().x - points.first().x),
                                    kotlin.math.abs(points.last().y - points.first().y),
                                )
                            if (band.tool == ToolType.SELECT_RECTANGLE) {
                                drawRect(
                                    color = Color(0x66FFFFFF),
                                    topLeft = topLeft,
                                    size = size,
                                )
                                drawRect(
                                    color = Color.White,
                                    topLeft = topLeft,
                                    size = size,
                                    style = Stroke(width = hairline * 1.2f),
                                )
                            } else {
                                drawOval(
                                    color = Color(0x66FFFFFF),
                                    topLeft = topLeft,
                                    size = size,
                                )
                                drawOval(
                                    color = Color.White,
                                    topLeft = topLeft,
                                    size = size,
                                    style = Stroke(width = hairline * 1.2f),
                                )
                            }
                        }
                    ToolType.SELECT_FREEHAND, ToolType.SELECT_LASSO -> {
                        for (i in 1 until points.size) {
                            drawLine(
                                color = Color.White,
                                start = points[i - 1],
                                end = points[i],
                                strokeWidth = hairline * 2f,
                            )
                        }
                    }
                    ToolType.SHAPE ->
                        if (points.size >= 2) {
                            drawLine(
                                color = Color(0xCCFFFFFF),
                                start = points.first(),
                                end = points.last(),
                                strokeWidth = hairline * 2f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f), dashPhase),
                            )
                        }
                    ToolType.GRADIENT ->
                        if (points.size >= 2) {
                            drawLine(
                                color = Color.White,
                                start = points.first(),
                                end = points.last(),
                                strokeWidth = hairline * 2f,
                            )
                            drawCircle(Color.White, radius = hairline * 6f, center = points.first())
                            drawCircle(
                                Color.White,
                                radius = hairline * 6f,
                                center = points.last(),
                                style = Stroke(width = hairline * 2f),
                            )
                        }
                    else -> Unit
                }
            }

            if (selection != null) {
                selection.bounds()?.let { bounds ->
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(bounds.left.toFloat(), bounds.top.toFloat()),
                        size =
                            androidx.compose.ui.geometry.Size(
                                bounds.width.toFloat(),
                                bounds.height.toFloat(),
                            ),
                        style =
                            Stroke(
                                width = (2f / scale).coerceAtLeast(0.3f),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 12f), dashPhase),
                            ),
                    )
                }
            }

            // Canvas border, so the artwork edge is always visible against the workspace.
            drawRect(
                color = Color(0x55FFFFFF),
                topLeft = Offset.Zero,
                size =
                    androidx.compose.ui.geometry
                        .Size(canvasWidth.toFloat(), canvasHeight.toFloat()),
                style = Stroke(width = hairline),
            )
        }
    }
}

/**
 * The quick menu: the app's most-used canvas actions in one radial-free list, reachable with the
 * thumb. It mirrors the desktop "quick actions" strip without hiding anything from the menus.
 */
@Composable
fun QuickMenuSheet(
    scalePercent: Int,
    rotation: Int,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    onResetView: () -> Unit,
    onRotate: (Float) -> Unit,
    onFlipCanvas: (Boolean) -> Unit,
    onRotateCanvas: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onInvertSelection: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("View", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onZoomOut, label = { Text("Zoom out") })
            AssistChip(onClick = onZoomIn, label = { Text("Zoom in") })
            AssistChip(onClick = onFit, label = { Text("Fit") })
            AssistChip(onClick = onResetView, label = { Text("100%") })
        }
        Text(
            "Zoom $scalePercent% · Rotation $rotation°",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onRotate(15f) }, label = { Text("View +15°") })
            AssistChip(onClick = { onRotate(-15f) }, label = { Text("View -15°") })
        }

        Text("History", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onUndo, enabled = canUndo, label = { Text("Undo") })
            AssistChip(onClick = onRedo, enabled = canRedo, label = { Text("Redo") })
        }

        Text("Canvas", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onRotateCanvas(90) }, label = { Text("Rotate 90°") })
            AssistChip(onClick = { onRotateCanvas(-90) }, label = { Text("Rotate -90°") })
            AssistChip(onClick = { onFlipCanvas(false) }, label = { Text("Flip H") })
            AssistChip(onClick = { onFlipCanvas(true) }, label = { Text("Flip V") })
        }

        Text("Selection", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = onSelectAll, label = { Text("Select all") })
            AssistChip(onClick = onInvertSelection, label = { Text("Invert") })
            AssistChip(onClick = onClearSelection, label = { Text("Clear") })
        }
    }
}

/** Small round colour button used by the toolbar. */
@Composable
fun ColorChip(
    color: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(color))
                .clickable(onClick = onClick),
    )
}

/** Kept for callers that want a polar helper when drawing radial guides. */
internal fun polarOffset(
    centre: Offset,
    radius: Float,
    degrees: Float,
): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return Offset(centre.x + cos(radians).toFloat() * radius, centre.y + sin(radians).toFloat() * radius)
}
