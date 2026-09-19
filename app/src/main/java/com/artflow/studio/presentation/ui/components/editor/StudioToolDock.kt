package com.artflow.studio.presentation.ui.components.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.presentation.ui.theme.LocalArtFlowFlags

/** Keep the three everyday tools at hand; expose the full tool collection only when requested. */
@Composable
fun StudioToolDock(
    activeTool: ToolType,
    expanded: Boolean,
    onToolSelected: (ToolType) -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = listOf(ToolType.BRUSH, ToolType.SMUDGE, ToolType.ERASER)
    val touchSize = if (LocalArtFlowFlags.current.largeTouchTargets) 56.dp else 48.dp
    Column(modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val tools = if (activeTool in primary) primary else primary + activeTool
            tools.forEach { tool ->
                FilledTonalIconToggleButton(
                    checked = activeTool == tool,
                    onCheckedChange = { onToolSelected(tool) },
                    modifier = Modifier.size(touchSize),
                ) {
                    Icon(tool.icon(), contentDescription = tool.displayName)
                }
            }
            TextButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.heightIn(min = touchSize)) {
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                Text(if (expanded) "Hide tools" else "All tools")
            }
        }
        if (expanded) {
            ToolStrip(
                activeTool = activeTool,
                onToolSelected = {
                    onToolSelected(it)
                    onExpandedChange(false)
                },
            )
        }
    }
}
