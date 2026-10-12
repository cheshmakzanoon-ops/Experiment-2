package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush

@Composable
fun SavedBrushToolbar(
    parameters: BrushParams,
    controls: BrushLibraryControls,
) {
    var naming by remember { mutableStateOf(false) }
    var fileError by remember { mutableStateOf<String?>(null) }
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
            ) { Text(stringResource(R.string.savedbrushcontrols_save_a_copy)) }
            BrushFileButtons(parameters, controls, available) { fileError = it }
        }
        (state.error ?: fileError)?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = controls.retry, enabled = !state.busy) { Text(stringResource(R.string.savedbrushcontrols_retry_saved_library)) }
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
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.savedbrushcontrols_actions_for_brush, brush.name))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.savedbrushcontrols_rename_saved_brush)) }, onClick = {
                expanded = false
                renaming = true
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.savedbrushcontrols_delete_saved_brush)) }, onClick = {
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
            title = { Text(stringResource(R.string.savedbrushcontrols_delete_saved_brush_2)) },
            text = {
                Column {
                    Text(stringResource(R.string.savedbrushcontrols_remove_from_saved_library, brush.name))
                    controls.state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { controls.delete(brush.id) }, enabled = !controls.state.busy) { Text(stringResource(R.string.savedbrushcontrols_delete_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }, enabled = !controls.state.busy) { Text(stringResource(R.string.cancel)) }
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
                    stringResource(R.string.savedbrushcontrols_saved_copies_persist_independently_of),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(BrushLibraryCodec.MAX_NAME) },
                    label = { Text(stringResource(R.string.savedbrushcontrols_brush_name)) },
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
        dismissButton = { TextButton(onClick = onDismiss, enabled = !controls.state.busy) { Text(stringResource(R.string.cancel)) } },
    )
}
