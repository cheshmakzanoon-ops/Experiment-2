package com.artflow.studio.presentation.ui.screens.gallery

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.artflow.studio.R
import com.artflow.studio.domain.model.Project
import com.artflow.studio.presentation.ui.viewmodel.MainUiState
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel

/** What a gallery stack's menu can ask for; renaming and merging need a dialog. */
internal enum class StackCommand { RENAME, MERGE }

internal data class StackAction(
    val name: String,
    val command: StackCommand,
)

/** The gallery's dialogs and preview, and which of them is open. */
@Stable
internal class GalleryDialogs {
    var rename by mutableStateOf<Project?>(null)
    var delete by mutableStateOf<Project?>(null)
    var stack by mutableStateOf<Project?>(null)
    var preview by mutableStateOf<Pair<List<Project>, Int>?>(null)
    var batch by mutableStateOf<GalleryBatch?>(null)
    var stackAction by mutableStateOf<StackAction?>(null)
}

/** Shows whichever of the gallery's dialogs is open; [onBatchDone] runs after a batch action on the selection has been carried out. */
@Composable
internal fun GalleryDialogHost(
    dialogs: GalleryDialogs,
    uiState: MainUiState,
    chosen: Set<Long>?,
    viewModel: MainViewModel,
    onBatchDone: () -> Unit,
) {
    dialogs.preview?.let { (projects, index) ->
        GalleryPreview(projects, index, viewModel::previewImage) { dialogs.preview = null }
    }
    dialogs.batch?.let { action ->
        BatchDialog(action, selectedProjects(uiState, chosen.orEmpty()), existingStacks(uiState), viewModel) { done ->
            dialogs.batch = null
            if (done) onBatchDone()
        }
    }
    dialogs.stack?.let { project ->
        StackDialog("Stack ${project.name}", project.stack, existingStacks(uiState), onDismiss = { dialogs.stack = null }) { stack ->
            viewModel.moveToStack(project.id, stack)
            dialogs.stack = null
        }
    }
    dialogs.rename?.let { project ->
        RenameDialog(project, onDismiss = { dialogs.rename = null }) { name ->
            viewModel.rename(project, name)
            dialogs.rename = null
        }
    }
    dialogs.delete?.let { project ->
        DeleteDialog(project, onDismiss = { dialogs.delete = null }) {
            viewModel.delete(project)
            dialogs.delete = null
        }
    }
    dialogs.stackAction?.let { action -> StackActionDialog(action, existingStacks(uiState), viewModel) { dialogs.stackAction = null } }
}

@Composable
private fun StackActionDialog(
    action: StackAction,
    stacks: List<String>,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    when (action.command) {
        StackCommand.RENAME ->
            TextDialog(stringResource(R.string.gallery_stack_rename), action.name, onDismiss = onClose) { name ->
                viewModel.renameStack(action.name, name)
                onClose()
            }
        StackCommand.MERGE ->
            StackDialog(
                stringResource(R.string.gallery_stack_merge_title, action.name),
                null,
                stacks.filter { it != action.name },
                onDismiss = onClose,
            ) { target ->
                target?.let { viewModel.renameStack(action.name, it) }
                onClose()
            }
    }
}

/** One line of text to confirm or cancel. */
@Composable
private fun TextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RenameDialog(
    project: Project,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var text by remember(project.id) { mutableStateOf(project.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.galleryscreen_rename_artwork)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(text) }) { Text(stringResource(R.string.layer_rename)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DeleteDialog(
    project: Project,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.galleryscreen_delete_project, project.name)) },
        text = { Text(stringResource(R.string.galleryscreen_the_artwork_and_its_layers_are_removed)) },
        confirmButton = {
            TextButton(
                onClick = onDelete,
            ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun selectedProjects(
    state: MainUiState,
    ids: Set<Long>,
): List<Project> = (state as? MainUiState.Success)?.projects.orEmpty().filter { it.id in ids }

internal fun existingStacks(state: MainUiState): List<String> =
    (state as? MainUiState.Success)
        ?.projects
        ?.mapNotNull { it.stack }
        ?.distinct()
        ?.sorted()
        .orEmpty()
