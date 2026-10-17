package com.artflow.studio.presentation.ui.screens.canvas

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
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp,
        modifier = modifier.padding(top = 12.dp),
    ) {
        Text(
            stringResource(R.string.canvasscreen_zoom_and_angle, current.zoomPercent, current.angleDegrees),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private const val NAVIGATION_HUD_MS = 1_500L
