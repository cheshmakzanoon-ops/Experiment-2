package com.artflow.studio.presentation.ui.screens.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.presentation.ui.components.canvas.NavigationReadout
import kotlinx.coroutines.delay

/**
 * The zoom percentage and angle shown while the artist pinches, pans or scrolls. The readout belongs to this
 * small composable, so each change redraws only the HUD, not the editor, and it hides a moment after the last change.
 */
@Composable
internal fun NavigationHud(
    readout: MutableState<NavigationReadout?>,
    modifier: Modifier = Modifier,
) {
    val current = readout.value
    LaunchedEffect(current) {
        if (current != null) {
            delay(NAVIGATION_HUD_MS)
            readout.value = null
        }
    }
    if (current == null) return
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 6.dp,
        modifier = modifier.padding(top = 12.dp),
    ) {
        Text(
            stringResource(R.string.canvasscreen_zoom_and_angle, current.zoomPercent, current.angleDegrees),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

private const val NAVIGATION_HUD_MS = 1_500L
