package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.presentation.ui.theme.LocalArtFlowFlags

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
) {
    var menuVisible by remember { mutableStateOf(false) }
    val touchSize = if (LocalArtFlowFlags.current.largeTouchTargets) 56.dp else 48.dp
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clickable { if (isActive) menuVisible = true else actions.onSelect(layer.id) },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isActive) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
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
                TextButton(onClick = onBlendMode, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(layer.blendMode.shortCode(), style = MaterialTheme.typography.titleSmall)
                }
                Checkbox(
                    checked = layer.isVisible,
                    onCheckedChange = { actions.onVisibility(layer.id, it) },
                    modifier = Modifier.size(touchSize).semantics { contentDescription = "Visibility" },
                )
                Box {
                    IconButton(onClick = { menuVisible = true }, modifier = Modifier.size(touchSize)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Layer menu", modifier = Modifier.size(18.dp))
                    }
                    LayerMenu(layer, menuVisible, actions, options, onBlendMode, onRename) { menuVisible = false }
                }
            }
            if (isActive) {
                Slider(value = layer.opacity, onValueChange = { actions.onOpacity(layer.id, it) }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

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
            if (layer.isReference) add("reference")
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
                text = { Text(if (layer.isReference) "Reference ✓" else "Reference") },
                onClick = item { options.onReference(layer.id, !layer.isReference) },
            )
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
