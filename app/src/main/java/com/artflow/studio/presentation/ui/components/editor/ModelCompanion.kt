package com.artflow.studio.presentation.ui.components.editor

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.artflow.studio.core.three.Mesh
import com.artflow.studio.presentation.ui.components.canvas.ModelPainter
import com.artflow.studio.presentation.ui.components.canvas.ModelView

/**
 * The 3D window: the model wearing the artwork. Turn mode spins it with one finger; Paint mode
 * paints on it with the current brush. Two fingers always turn and zoom.
 */
@Composable
fun ModelCompanion(
    mesh: Mesh,
    artwork: Bitmap?,
    painter: ModelPainter,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var painting by rememberSaveable { mutableStateOf(false) }
    // The texture is sent to the view only when the artwork actually changes.
    val sent = remember { arrayOfNulls<Bitmap>(1) }
    BoxWithConstraints(modifier.fillMaxSize().padding(8.dp)) {
        val side = minOf(maxWidth, maxHeight, 360.dp)
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.TopEnd).size(width = side, height = side + 48.dp),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("3D", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = !painting, onClick = { painting = false }, label = { Text("Turn") })
                    Spacer(Modifier.width(4.dp))
                    FilterChip(selected = painting, onClick = { painting = true }, label = { Text("Paint") })
                    Box(Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close 3D view") }
                }
                AndroidView(
                    factory = { context -> ModelView(context) },
                    update = { view ->
                        if (view.mesh !== mesh) view.mesh = mesh
                        view.painting = painting
                        view.painter = painter
                        if (artwork != null && sent[0] !== artwork) {
                            sent[0] = artwork
                            view.setTexture(artwork)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
