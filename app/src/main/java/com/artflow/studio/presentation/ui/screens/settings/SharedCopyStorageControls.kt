package com.artflow.studio.presentation.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.artflow.studio.presentation.ui.viewmodel.SharedCopyStorageState

/** Removing a previously handed-off URI always requires this explicit explanation and confirmation. */
@Composable
internal fun SharedCopyStorageControls(
    state: SharedCopyStorageState,
    storageLabel: String,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val summary = state.summary
    Column {
        Text("Artwork and exports: $storageLabel", style = MaterialTheme.typography.bodySmall)
        if (summary != null) {
            val copies = if (summary.count == 1) "1 shared copy" else "${summary.count} shared copies"
            Text("$copies · ${Formatter.formatFileSize(context, summary.bytes)} ready to remove")
            if (summary.activeCount > 0) {
                val requests = if (summary.activeCount == 1) "1 sharing request is" else "${summary.activeCount} sharing requests are"
                Text("$requests still active and will be kept.", style = MaterialTheme.typography.bodySmall)
            }
            if (summary.unreadableCount > 0) {
                Text(
                    "Some shared-copy folders could not be inspected. Their files will be kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(
            "Copies made for sharing can be removed separately from your saved artwork and exports.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { confirm = true }, enabled = !state.busy && (summary?.count ?: 0) > 0) {
            Text(if (state.removing) "Removing shared copies…" else "Remove shared copies")
        }
        TextButton(onClick = onRefresh, enabled = !state.busy) {
            Text(if (state.refreshing) "Checking…" else "Refresh")
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove shared copies?") },
            text = {
                Text(
                    "Old shared links will stop opening. Copies saved by another app are unaffected. " +
                        "Your artwork, saved images, ordinary exports and timelapse recordings will stay. " +
                        "Active sharing requests are kept; abandoned share preparations can be removed.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        onRemove()
                    },
                    enabled = !state.busy,
                ) { Text("Remove copies") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
