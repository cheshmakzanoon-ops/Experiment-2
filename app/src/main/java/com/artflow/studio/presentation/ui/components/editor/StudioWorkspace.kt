@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.tool.ToolGroup
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.presentation.ui.theme.LocalArtFlowFlags

/** Workflow routes, distinct from tools that operate directly on the canvas. */
enum class StudioAction(val label: String) {
    BRUSH_STUDIO("Brush Studio"),
    TOOL_OPTIONS("Tool options"),
    SELECTION("Selection options"),
    TRANSFORM("Layer transform"),
    GUIDES("Drawing guides"),
    ANIMATION("Animation"),
    CANVAS("Canvas setup"),
    TEXT("Text settings"),
    EXPORT("Export artwork"),
}

private fun StudioAction.icon(): ImageVector =
    when (this) {
        StudioAction.BRUSH_STUDIO -> Icons.Default.Brush
        StudioAction.TOOL_OPTIONS -> Icons.Default.Tune
        StudioAction.SELECTION -> Icons.Default.SelectAll
        StudioAction.TRANSFORM -> Icons.Default.OpenWith
        StudioAction.GUIDES -> Icons.Default.GridOn
        StudioAction.ANIMATION -> Icons.Default.Movie
        StudioAction.CANVAS -> Icons.Default.AspectRatio
        StudioAction.TEXT -> Icons.Default.TextFields
        StudioAction.EXPORT -> Icons.Default.IosShare
    }

/** The frequent painting actions stay visible; the complete tool set is a single tap away. */
@Composable
fun StudioDock(
    activeTool: ToolType,
    color: Int,
    layerCount: Int,
    onTool: (ToolType) -> Unit,
    onColor: () -> Unit,
    onLayers: () -> Unit,
    onPalette: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = if (LocalArtFlowFlags.current.largeTouchTargets) 60.dp else 52.dp
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(ToolType.BRUSH, ToolType.SMUDGE, ToolType.ERASER).forEach { tool ->
            DockButton(tool.displayName, tool == activeTool, { onTool(tool) }, Modifier.width(target)) {
                Icon(tool.icon(), contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        DockButton("Colour", false, onColor, Modifier.width(target)) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), CircleShape),
            )
        }
        DockButton("Layers", false, onLayers, Modifier.width(target), "Layers, $layerCount layers") {
            Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        DockButton("Tools", activeTool !in listOf(ToolType.BRUSH, ToolType.SMUDGE, ToolType.ERASER), onPalette, Modifier.width(target)) {
            Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun DockButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String = label,
    glyph: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                selected = isSelected
                contentDescription = description
            }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        glyph()
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Scrollable, wrapped controls keep every tool and workflow reachable on narrow/large-text screens. */
@Composable
fun StudioToolPalette(
    activeTool: ToolType,
    onTool: (ToolType) -> Unit,
    onAction: (StudioAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Active · ${activeTool.displayName}", style = MaterialTheme.typography.labelLarge)
        Text("WORKSPACE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StudioAction.entries.forEach { action ->
                OutlinedButton(onClick = { onAction(action) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(action.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(action.label)
                }
            }
        }
        HorizontalDivider()
        ToolGroup.entries.forEach { group ->
            Text(group.displayName.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ToolType.entries.filter { it.group == group }.forEach { tool ->
                    FilterChip(
                        selected = tool == activeTool,
                        onClick = { onTool(tool) },
                        label = { Text(tool.displayName) },
                        leadingIcon = { Icon(tool.icon(), contentDescription = null, modifier = Modifier.size(18.dp)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

/** Always visible in focus mode. It restores editing chrome rather than navigating away. */
@Composable
fun RestoreStudioButton(
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onRestore,
        modifier = modifier.heightIn(min = if (LocalArtFlowFlags.current.largeTouchTargets) 60.dp else 48.dp),
    ) {
        Icon(Icons.Default.FullscreenExit, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Show controls")
    }
}
