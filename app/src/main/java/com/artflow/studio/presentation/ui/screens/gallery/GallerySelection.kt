@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.screens.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.artflow.studio.domain.model.Project
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel

/** What the gallery's Select mode can do with the chosen artworks. */
internal data class SelectionActions(
    val onStack: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onDone: () -> Unit,
)

/** Procreate's gallery Select mode: the top bar while artworks are being chosen. */
@Composable
internal fun SelectionTopBar(
    count: Int,
    actions: SelectionActions,
) {
    TopAppBar(
        title = { Text(if (count == 0) "Select artworks" else "$count selected") },
        navigationIcon = {
            IconButton(onClick = actions.onDone) { Icon(Icons.Default.Close, contentDescription = "Done selecting") }
        },
        actions = {
            TextButton(onClick = actions.onStack, enabled = count > 0) { Text("Stack") }
            TextButton(onClick = actions.onDuplicate, enabled = count > 0) { Text("Duplicate") }
            TextButton(onClick = actions.onDelete, enabled = count > 0) { Text("Delete") }
        },
    )
}

/** The tick in a card's corner while selecting. */
@Composable
internal fun SelectionMark(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Icon(
        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = if (selected) "Selected" else "Not selected",
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(6.dp).background(MaterialTheme.colorScheme.surface, CircleShape),
    )
}

/** Batch actions that need a confirmation dialog. */
internal enum class GalleryBatch { STACK, DELETE }

/** Confirms a batch action; [onClose] receives whether it ran. */
@Composable
internal fun BatchDialog(
    action: GalleryBatch,
    chosen: List<Project>,
    stacks: List<String>,
    viewModel: MainViewModel,
    onClose: (Boolean) -> Unit,
) {
    if (action == GalleryBatch.DELETE) {
        DeleteManyDialog(chosen.size, onDismiss = { onClose(false) }) {
            chosen.forEach(viewModel::delete)
            onClose(true)
        }
    } else {
        StackDialog("Stack ${chosen.size} artworks", null, stacks, onDismiss = { onClose(false) }) { stack ->
            chosen.forEach { viewModel.moveToStack(it.id, stack) }
            onClose(true)
        }
    }
}

/** Confirms deleting several artworks at once. */
@Composable
private fun DeleteManyDialog(
    count: Int,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $count artworks?") },
        text = { Text("The artworks and their layers are removed from this device. This cannot be undone.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
