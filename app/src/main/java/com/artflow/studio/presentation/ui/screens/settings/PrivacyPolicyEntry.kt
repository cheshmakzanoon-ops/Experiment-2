package com.artflow.studio.presentation.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.artflow.studio.R

/** Available offline; the published policy must describe the same backup and export behavior. */
@Composable
fun PrivacyPolicyEntry() {
    var visible by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { visible = true }) { Text("Privacy policy") }
    if (visible) {
        AlertDialog(
            onDismissRequest = { visible = false },
            title = { Text("ArtFlow Privacy Policy") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(stringResource(R.string.privacy_policy)) }
                }
            },
            confirmButton = { TextButton(onClick = { visible = false }) { Text("Close") } },
        )
    }
}
