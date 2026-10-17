@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.screens.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.domain.model.Project
import com.artflow.studio.presentation.ui.viewmodel.MainUiState
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel

/** What the gallery's Select mode can do with the chosen artworks. */
internal data class SelectionActions(
    val onStack: () -> Unit,
    val onDuplicate: () -> Unit,
    val onDelete: () -> Unit,
    val onDone: () -> Unit,
    val onSelectAll: () -> Unit,
    val onShare: () -> Unit,
    val onFavorite: () -> Unit,
    /** True when every chosen artwork is a favourite, so the command removes the star instead of adding it. */
    val allFavourite: Boolean,
)

/** Procreate's gallery Select mode: the top bar while artworks are being chosen. */
@Composable
internal fun SelectionTopBar(
    count: Int,
    actions: SelectionActions,
) {
    val title =
        if (count == 0) {
            stringResource(R.string.galleryselection_select_artworks)
        } else {
            stringResource(R.string.galleryselection_count_selected, count)
        }
    TopAppBar(
        title = {
            Text(title)
        },
        navigationIcon = {
            IconButton(onClick = actions.onDone) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.galleryselection_done_selecting))
            }
        },
        actions = {
            TextButton(onClick = actions.onStack, enabled = count > 0) { Text(stringResource(R.string.galleryselection_stack)) }
            TextButton(onClick = actions.onDuplicate, enabled = count > 0) { Text(stringResource(R.string.duplicate)) }
            TextButton(onClick = actions.onDelete, enabled = count > 0) { Text(stringResource(R.string.delete)) }
            SelectionMenu(count, actions)
        },
    )
}

/** Select all, Share and Favourite, which the bar has no room to spell out. */
@Composable
private fun SelectionMenu(
    count: Int,
    actions: SelectionActions,
) {
    var open by remember { mutableStateOf(false) }

    fun choose(command: () -> Unit): () -> Unit =
        {
            open = false
            command()
        }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.gallery_more_actions))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.gallery_select_all)) }, onClick = choose(actions.onSelectAll))
            DropdownMenuItem(
                text = { Text(stringResource(R.string.gallery_share_selected)) },
                onClick = choose(actions.onShare),
                enabled = count > 0,
            )
            val favouriteLabel = if (actions.allFavourite) R.string.gallery_unfavourite_selected else R.string.gallery_favourite_selected
            DropdownMenuItem(
                text = { Text(stringResource(favouriteLabel)) },
                onClick = choose(actions.onFavorite),
                enabled = count > 0,
            )
        }
    }
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
        title = { Text(stringResource(R.string.galleryselection_delete_artworks, count)) },
        text = { Text(stringResource(R.string.galleryselection_the_artworks_and_their_layers_are)) },
        confirmButton = {
            TextButton(
                onClick = onDelete,
            ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The artworks the grid shows as cards that can be chosen: the open stack's, the search hits, or those outside any stack. */
internal fun shownProjects(
    state: MainUiState,
    openStack: String?,
    query: String,
): List<Project> {
    val all = (state as? MainUiState.Success)?.projects.orEmpty()
    return GalleryLayout.of(all, openStack, searching = query.isNotEmpty()).projects
}
