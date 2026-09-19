package com.artflow.studio.presentation.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*

/** Reset is explicit and only follows a successful backup to the user's document-provider choice. */
@Composable
internal fun PaletteRecoveryNotice(
    running: Boolean,
    onChooseBackup: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Column {
        Text("Saved palettes need recovery", color = MaterialTheme.colorScheme.error)
        Text(
            "Your artwork and other settings are available. The unreadable palette data is preserved, " +
                "but custom palettes cannot be edited until it has been backed up and reset.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { confirm = true }, enabled = !running) {
            Text(if (running) "Saving palette backup…" else "Back up and reset palettes")
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Preserve unreadable palettes?") },
            text = {
                Text(
                    "Choose a file for the exact original palette text. Only after that file has been saved " +
                        "will the unreadable custom-palette list be reset. Artwork and other preferences are not changed. " +
                        "Cancelling or a failed backup leaves the stored data untouched. This does not repair the backup's contents.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onChooseBackup()
                }) { Text("Choose backup file") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
