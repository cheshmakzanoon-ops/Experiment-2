package com.artflow.studio.presentation.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.artflow.studio.R

/** Reset is explicit and only follows a successful backup to the user's document-provider choice. */
@Composable
internal fun PaletteRecoveryNotice(
    running: Boolean,
    onChooseBackup: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(R.string.paletterecoverynotice_saved_palettes_need_recovery), color = MaterialTheme.colorScheme.error)
        Text(
            stringResource(R.string.paletterecoverynotice_your_artwork_is_available),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { confirm = true }, enabled = !running) {
            Text(if (running) "Saving palette backup…" else "Back up and reset palettes")
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.paletterecoverynotice_preserve_unreadable_palettes)) },
            text = {
                Text(
                    stringResource(R.string.paletterecoverynotice_choose_a_file_for_the_original),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onChooseBackup()
                }) { Text(stringResource(R.string.paletterecoverynotice_choose_backup_file)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
