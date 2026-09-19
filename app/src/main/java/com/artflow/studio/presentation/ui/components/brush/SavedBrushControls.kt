package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush

@Composable
fun SavedBrushToolbar(
    parameters: BrushParams,
    controls: BrushLibraryControls,
) {
    var naming by remember { mutableStateOf(false) }
    val state = controls.state
    val available = !state.loading && !state.busy && state.error == null
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.loading) "Loading saved brushes…" else "${state.brushes.size} saved brushes",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { naming = true },
                enabled = available && state.brushes.size < BrushLibraryCodec.MAX_BRUSHES,
            ) { Text("Save a copy") }
        }
        state.error?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = controls.retry, enabled = !state.busy) { Text("Retry saved library") }
        }
    }
    if (naming) {
        val snapshot = remember { parameters }
        SavedBrushNameDialog("Save brush copy", "", controls, { controls.saveCopy(it, snapshot) }, { naming = false })
    }
}

@Composable
fun SavedBrushMenu(
    brush: SavedBrush,
    controls: BrushLibraryControls,
) {
    var expanded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !controls.state.busy) {
            Icon(Icons.Default.MoreVert, contentDescription = "Saved brush actions ${brush.name}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Rename saved brush") }, onClick = {
                expanded = false
                renaming = true
            })
            DropdownMenuItem(text = { Text("Delete saved brush") }, onClick = {
                expanded = false
                deleting = true
            })
        }
    }
    if (renaming) {
        SavedBrushNameDialog("Rename saved brush", brush.name, controls, { controls.rename(brush.id, it) }, { renaming = false })
    }
    if (deleting) {
        val revision = remember { controls.state.revision }
        LaunchedEffect(controls.state.revision) { if (controls.state.revision != revision) deleting = false }
        AlertDialog(
            onDismissRequest = { if (!controls.state.busy) deleting = false },
            title = { Text("Delete saved brush?") },
            text = {
                Column {
                    Text("Remove “${brush.name}” from the saved library? Existing artwork and the current brush draft will not change.")
                    controls.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { controls.delete(brush.id) }, enabled = !controls.state.busy) { Text("Delete copy") }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }, enabled = !controls.state.busy) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SavedBrushNameDialog(
    title: String,
    initialName: String,
    controls: BrushLibraryControls,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    val revision = remember { controls.state.revision }
    val normalized = name.trim()
    val valid = BrushLibraryCodec.validName(normalized)
    LaunchedEffect(controls.state.revision) { if (controls.state.revision != revision) onDismiss() }
    AlertDialog(
        onDismissRequest = { if (!controls.state.busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Saved copies persist independently of Use brush. They do not change existing artwork.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(BrushLibraryCodec.MAX_NAME) },
                    label = { Text("Brush name") },
                    singleLine = true,
                    enabled = !controls.state.busy,
                    isError = name.isNotEmpty() && !valid,
                )
                controls.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(normalized) }, enabled = valid && !controls.state.busy) {
                val caption =
                    when {
                        controls.state.busy -> "Saving…"
                        initialName.isEmpty() -> "Save copy"
                        else -> "Rename"
                    }
                Text(caption)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !controls.state.busy) { Text("Cancel") } },
    )
}
