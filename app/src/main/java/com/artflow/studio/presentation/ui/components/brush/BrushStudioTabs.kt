package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.artflow.studio.presentation.ui.theme.LocalArtFlowFlags

/** Size the text itself, not just its parent tab; wrap-content text can round below glyph width. */
@Composable
fun BrushStudioTabs(
    selectedTab: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge
    val touchSize = if (LocalArtFlowFlags.current.largeTouchTargets) 56.dp else 48.dp
    ScrollableTabRow(selectedTabIndex = selectedTab, modifier = modifier, edgePadding = 0.dp) {
        listOf("Library", "Settings", "Drawing pad").forEachIndexed { index, title ->
            val measured = measurer.measure(title, style = style, maxLines = 1, softWrap = false)
            // Two physical pixels keep fractional glyph advances inside the actual Text layout.
            // Measuring with the current style/density also follows font-scale and theme changes.
            val labelWidth = with(density) { (measured.size.width + 2).toDp() }
            Tab(selected = selectedTab == index, onClick = { onSelect(index) }, modifier = Modifier.heightIn(min = touchSize)) {
                Text(
                    title,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp).width(labelWidth),
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
