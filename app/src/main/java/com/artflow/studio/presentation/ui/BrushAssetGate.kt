package com.artflow.studio.presentation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.lifecycle.AssetStartupState

/** The editor and import/navigation effects do not exist until startup is admitted. */
@Composable
internal fun BrushAssetGate(
    state: AssetStartupState,
    onRetry: () -> Unit,
    onContinueWithAvailable: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (state.allowsEditor) {
        content()
        return
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (state == AssetStartupState.LOADING) {
            CircularProgressIndicator()
            Text("Loading saved brushes…")
        } else {
            Text(
                "Some saved brush assets couldn't load",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                "Retry, or open with the available brushes. Brushes with missing textures or shapes may paint differently.",
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRetry) { Text("Retry") }
            TextButton(onClick = onContinueWithAvailable) { Text("Open with available brushes") }
        }
    }
}
