package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.data.local.BrushShareFiles
import com.artflow.studio.data.local.BrushShareFormat
import com.artflow.studio.domain.model.brush.SavedBrush

/** ArtFlow remains selected by default; the artist chooses a Procreate file explicitly. */
@Composable
internal fun BrushShareDialog(
    isSet: Boolean,
    brushes: List<SavedBrush>,
    onDismiss: () -> Unit,
    onShare: (BrushShareFormat) -> Unit,
) {
    var format by remember(isSet) { mutableStateOf(BrushShareFormat.ARTFLOW) }
    val procreate = if (isSet) BrushShareFormat.PROCREATE_SET else BrushShareFormat.PROCREATE_BRUSH
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isSet) R.string.brush_share_set_title else R.string.brush_share_title)) },
        text = {
            Column(
                Modifier.selectableGroup().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(BrushShareFormat.ARTFLOW, procreate).forEach { choice ->
                    val label =
                        when (choice) {
                            BrushShareFormat.ARTFLOW -> R.string.brush_share_artflow
                            BrushShareFormat.PROCREATE_BRUSH -> R.string.brush_share_procreate_brush
                            BrushShareFormat.PROCREATE_SET -> R.string.brush_share_procreate_set
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(format == choice, role = Role.RadioButton) { format = choice }
                                .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = format == choice, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label))
                    }
                }
                Text(
                    stringResource(
                        if (format == BrushShareFormat.ARTFLOW) {
                            R.string.brush_share_artflow_description
                        } else {
                            R.string.brush_share_procreate_description
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (format != BrushShareFormat.ARTFLOW) {
                    val conversions = remember(brushes, format) { BrushShareFiles.countConversions(brushes, format) }
                    if (conversions.isNotEmpty()) {
                        Text(stringResource(R.string.brush_share_count_changes), style = MaterialTheme.typography.titleSmall)
                        conversions.forEach { change ->
                            Text(
                                stringResource(
                                    if (change.secondary) {
                                        R.string.brush_share_secondary_count_change
                                    } else {
                                        R.string.brush_share_count_change
                                    },
                                    change.brushName,
                                    change.original,
                                    change.exported,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(stringResource(R.string.brush_share_count_keep_artflow), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onShare(format) }) { Text(stringResource(R.string.brush_share_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.brush_share_cancel)) } },
    )
}
