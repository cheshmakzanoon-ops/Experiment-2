package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.presentation.ui.viewmodel.BrushLibraryViewModel

/** Explicit state/actions keep the studio UI testable without storage or a Hilt activity. */
data class BrushLibraryControls(
    val state: BrushLibraryViewModel.State,
    val saveCopy: (String, BrushParams) -> Unit,
    val rename: (String, String) -> Unit,
    val delete: (String) -> Unit,
    val retry: () -> Unit,
)

/** Production entry point: saved presets share the navigation entry's lifecycle, not a dialog's. */
@Composable
fun BrushStudioDialog(
    initial: BrushParams,
    onApply: (BrushParams) -> Unit,
    onDismiss: () -> Unit,
    viewModel: BrushLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    BrushStudioContent(
        initial = initial,
        onApply = onApply,
        onDismiss = onDismiss,
        library = BrushLibraryControls(state, viewModel::saveCopy, viewModel::rename, viewModel::delete, viewModel::retry),
    )
}
