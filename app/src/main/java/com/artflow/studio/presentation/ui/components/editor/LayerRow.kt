package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.presentation.ui.theme.LocalArtFlowFlags
import kotlin.math.max

/** Short blend-mode code shown on each layer, like Procreate's "N" for Normal. */
fun BlendMode.shortCode(): String =
    displayName
        .split(' ', '-', '/')
        .filter { it.isNotBlank() }
        .joinToString("") { it.first().uppercase() }
        .take(2)

/**
 * A layer in the Procreate arrangement: thumbnail, name, blend-mode code and a visibility check.
 * Tap selects; tapping the selected layer opens its options.
 */
@Composable
internal fun LayerRow(
    layer: Layer,
    isActive: Boolean,
    thumbnail: PixelBuffer?,
    actions: LayerRowActions,
    options: LayerOptionActions?,
    onBlendMode: () -> Unit,
    onRename: () -> Unit,
    picked: Boolean = false,
    onPick: () -> Unit = {},
) {
    var menuVisible by remember { mutableStateOf(false) }
    // Swiping a layer left reveals Lock, Duplicate and Delete; swiping right adds it to a
    // multi-selection, as in Procreate.
    var swipeActions by remember { mutableStateOf(false) }
    val swipeDistance = with(LocalDensity.current) { SWIPE_REVEAL.toPx() }
    val touchSize = if (LocalArtFlowFlags.current.largeTouchTargets) 56.dp else 48.dp
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .twoFingerGestures(
                    key = layer.id,
                    onTap = { actions.onOpacityMode(layer.id) },
                    onSwipeRight = { actions.onAlphaLock(layer.id, !layer.isAlphaLocked) },
                    onHold = { actions.onSelectContents(layer.id) },
                ).pointerInput(layer.id) {
                    var travel = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travel = 0f },
                        onDragEnd = {
                            if (travel < -swipeDistance) swipeActions = true
                            if (travel > swipeDistance) {
                                if (swipeActions) swipeActions = false else onPick()
                            }
                        },
                    ) { change, amount ->
                        change.consume()
                        travel += amount
                    }
                }.clickable {
                    when {
                        swipeActions -> swipeActions = false
                        isActive -> menuVisible = true
                        else -> actions.onSelect(layer.id)
                    }
                },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    } else if (picked) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    },
            ),
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LayerThumbnail(layer, thumbnail)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(layer.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LayerStatus(layer)
                }
                if (layer.isLocked) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "Locked",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                if (swipeActions) {
                    SwipeActions(layer, actions) { swipeActions = false }
                } else {
                    TextButton(onClick = onBlendMode, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Text(layer.blendMode.shortCode(), style = MaterialTheme.typography.titleSmall)
                    }
                    Checkbox(
                        checked = layer.isVisible,
                        onCheckedChange = { actions.onVisibility(layer.id, it) },
                        modifier = Modifier.size(touchSize).semantics { contentDescription = "Visibility" },
                    )
                    Box {
                        IconButton(
                            onClick = {
                                // The menu's options act on the active layer, so open it on this one.
                                if (!isActive) actions.onSelect(layer.id)
                                menuVisible = true
                            },
                            modifier = Modifier.size(touchSize),
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Layer menu", modifier = Modifier.size(18.dp))
                        }
                        LayerMenu(layer, menuVisible, actions, options, onBlendMode, onRename) { menuVisible = false }
                    }
                }
            }
            if (isActive) {
                Slider(value = layer.opacity, onValueChange = { actions.onOpacity(layer.id, it) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SwipeActions(
    layer: Layer,
    actions: LayerRowActions,
    done: () -> Unit,
) {
    fun run(action: () -> Unit): () -> Unit =
        {
            action()
            done()
        }
    TextButton(onClick = run { actions.onLock(layer.id, !layer.isLocked) }) { Text(if (layer.isLocked) "Unlock" else "Lock") }
    TextButton(onClick = run { actions.onDuplicate(layer.id) }) { Text("Duplicate") }
    TextButton(onClick = run { actions.onDelete(layer.id) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
}

private val SWIPE_REVEAL = 56.dp

/**
 * Procreate's two-finger layer gestures: a quick tap adjusts opacity, a swipe right toggles Alpha
 * Lock and a touch and hold selects the layer's contents. Two-finger touches are consumed so the
 * row's own tap and swipe handling stays out of the way.
 */
private fun Modifier.twoFingerGestures(
    key: Any,
    onTap: () -> Unit,
    onSwipeRight: () -> Unit,
    onHold: () -> Unit,
): Modifier =
    pointerInput(key) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pointers = 1
            var travel = 0f
            var sideways = 0f
            var last = first.uptimeMillis
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                pointers = max(pointers, event.changes.count { it.pressed })
                event.changes.forEach { travel += it.positionChange().getDistance() }
                // The fingers' average sideways movement.
                sideways += event.changes.sumOf { it.positionChange().x.toDouble() }.toFloat() / event.changes.size.coerceAtLeast(1)
                last = event.changes.maxOf { it.uptimeMillis }
                if (pointers >= 2) event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
            if (pointers != 2) return@awaitEachGesture
            val still = travel < viewConfiguration.touchSlop * 2
            when {
                sideways > TWO_FINGER_SWIPE_DP.dp.toPx() -> onSwipeRight()
                still && last - first.uptimeMillis < TWO_FINGER_TAP_MS -> onTap()
                still && last - first.uptimeMillis >= TWO_FINGER_HOLD_MS -> onHold()
            }
        }
    }

private const val TWO_FINGER_SWIPE_DP = 48
private const val TWO_FINGER_HOLD_MS = 500L
private const val TWO_FINGER_TAP_MS = 400L

@Composable
private fun LayerThumbnail(
    layer: Layer,
    thumbnail: PixelBuffer?,
) {
    val bitmap = remember(thumbnail) { thumbnail?.let { BitmapPixelBridge.toBitmap(it).asImageBitmap() } }
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            layer.isGroup -> Icon(Icons.Default.Folder, contentDescription = null)
            bitmap != null -> Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun LayerStatus(layer: Layer) {
    val status =
        buildList {
            if (layer.opacity < 1f) add("${(layer.opacity * 100).toInt()}%")
            if (layer.isClippingMask) add("clipping")
            if (layer.isAlphaLocked) add("alpha locked")
            if (layer.isFillReference) add("reference")
            if (layer.drawingAssist) add("assisted")
            if (layer.isReference) add("hidden from artwork")
            if (layer.isPrivate) add("private")
            if (layer.effects != null) add("effects")
            if (layer.textContent != null) add("text")
            if (layer.hasMask()) add("mask")
        }
    if (status.isNotEmpty()) {
        Text(
            status.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LayerMenu(
    layer: Layer,
    expanded: Boolean,
    actions: LayerRowActions,
    options: LayerOptionActions?,
    onBlendMode: () -> Unit,
    onRename: () -> Unit,
    dismiss: () -> Unit,
) {
    fun item(action: () -> Unit): () -> Unit =
        {
            action()
            dismiss()
        }
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss) {
        DropdownMenuItem(text = { Text("Rename") }, onClick = item(onRename))
        if (options != null && layer.textContent != null) {
            DropdownMenuItem(text = { Text("Edit Text") }, onClick = item { options.onEditText(layer) })
        }
        if (options != null) {
            DropdownMenuItem(text = { Text("Select") }, onClick = item(options.onSelectContents))
            DropdownMenuItem(text = { Text("Copy") }, onClick = item(options.onCopy))
            DropdownMenuItem(text = { Text("Fill Layer") }, onClick = item(options.onFill))
            DropdownMenuItem(text = { Text("Clear") }, onClick = item(options.onClear))
        }
        DropdownMenuItem(
            text = { Text(if (layer.isAlphaLocked) "Alpha Lock ✓" else "Alpha Lock") },
            onClick = item { actions.onAlphaLock(layer.id, !layer.isAlphaLocked) },
        )
        if (options != null) DropdownMenuItem(text = { Text("Mask") }, onClick = item(options.onMask))
        DropdownMenuItem(
            text = { Text(if (layer.isClippingMask) "Clipping Mask ✓" else "Clipping Mask") },
            onClick = item { actions.onClipping(layer.id, !layer.isClippingMask) },
        )
        if (options != null) {
            DropdownMenuItem(text = { Text("Invert") }, onClick = item(options.onInvert))
            DropdownMenuItem(
                text = { Text(if (layer.isFillReference) "Reference ✓" else "Reference") },
                onClick = item { options.onFillReference(layer.id, !layer.isFillReference) },
            )
            DropdownMenuItem(
                text = { Text(if (layer.drawingAssist) "Drawing Assist ✓" else "Drawing Assist") },
                onClick = item { options.onDrawingAssist(layer.id, !layer.drawingAssist) },
            )
            DropdownMenuItem(
                text = { Text(if (layer.isReference) "Hide from artwork ✓" else "Hide from artwork") },
                onClick = item { options.onReference(layer.id, !layer.isReference) },
            )
            DropdownMenuItem(
                text = { Text(if (layer.isPrivate) "Private (not in time-lapse) ✓" else "Private (not in time-lapse)") },
                onClick = item { options.onPrivate(layer.id, !layer.isPrivate) },
            )
            if (!layer.isGroup) {
                DropdownMenuItem(
                    text = { Text(if (layer.effects != null) "Effects ✓" else "Effects") },
                    onClick = item { options.onEffects(layer) },
                )
            }
        }
        DropdownMenuItem(text = { Text("Blend mode") }, onClick = item(onBlendMode))
        DropdownMenuItem(text = { Text("Merge Down") }, onClick = item { actions.onMergeDown(layer.id) })
        if (options != null) DropdownMenuItem(text = { Text("Combine Down") }, onClick = item { options.onCombineDown(layer.id) })
        DropdownMenuItem(text = { Text("Duplicate") }, onClick = item { actions.onDuplicate(layer.id) })
        DropdownMenuItem(
            text = { Text(if (layer.isLocked) "Unlock" else "Lock") },
            onClick = item { actions.onLock(layer.id, !layer.isLocked) },
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = item { actions.onDelete(layer.id) },
        )
    }
}
