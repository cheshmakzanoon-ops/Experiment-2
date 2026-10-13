package com.artflow.studio.presentation.ui.components.brush

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import com.artflow.studio.R
import com.artflow.studio.core.render.BrushPreview
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.brush.BrushParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Render on a worker; cancellation prevents an old parameter set from replacing a newer sample. */
@Composable
fun BrushSample(
    parameters: BrushParams,
    modifier: Modifier = Modifier,
) {
    val sample by produceState<ImageBitmap?>(null, parameters) {
        value = null
        value = withContext(Dispatchers.Default) { BitmapPixelBridge.toBitmap(BrushPreview.render(parameters)).asImageBitmap() }
    }
    Box(modifier) {
        sample?.let {
            Image(
                it,
                contentDescription = stringResource(R.string.brushsample_actual_brush_stroke_preview),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
