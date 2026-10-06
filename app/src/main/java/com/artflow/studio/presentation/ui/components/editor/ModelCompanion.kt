package com.artflow.studio.presentation.ui.components.editor

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.artflow.studio.core.three.Mesh
import com.artflow.studio.core.three.ModelLighting
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
    var lightingOpen by rememberSaveable { mutableStateOf(false) }
    var showMesh by rememberSaveable { mutableStateOf(false) }
    var lighting by remember { mutableStateOf(ModelLighting()) }
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
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        FilterChip(selected = !painting, onClick = { painting = false }, label = { Text("Turn") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = painting, onClick = { painting = true }, label = { Text("Paint") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = lightingOpen, onClick = { lightingOpen = !lightingOpen }, label = { Text("Light") })
                        Spacer(Modifier.width(4.dp))
                        FilterChip(selected = showMesh, onClick = { showMesh = !showMesh }, label = { Text("Mesh") })
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close 3D view") }
                }
                Box(Modifier.weight(1f)) {
                    AndroidView(
                        factory = { context -> ModelView(context) },
                        update = { view ->
                            if (view.mesh !== mesh) view.mesh = mesh
                            view.painting = painting
                            view.painter = painter
                            view.lighting = lighting
                            view.showMesh = showMesh
                            if (artwork != null && sent[0] !== artwork) {
                                sent[0] = artwork
                                view.setTexture(artwork)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (lightingOpen) {
                        LightingPanel(lighting, { lighting = it }, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
}

/** The lighting studio: presets, the key light's angle, height, brightness, fill and exposure, and the surface material. */
@Composable
private fun LightingPanel(
    lighting: ModelLighting,
    onChange: (ModelLighting) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA),
        modifier = modifier.fillMaxWidth().heightIn(max = 220.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                ModelLighting.presets.forEach { preset ->
                    FilterChip(
                        selected = preset.isApplied(lighting),
                        onClick = { onChange(preset.appliedTo(lighting)) },
                        label = { Text(preset.name) },
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
            }
            LightSlider("Angle", lighting.azimuth, -180f..180f) { onChange(lighting.copy(azimuth = it)) }
            LightSlider("Height", lighting.elevation, -ModelLighting.MAX_ELEVATION..ModelLighting.MAX_ELEVATION) {
                onChange(lighting.copy(elevation = it))
            }
            LightSlider("Brightness", lighting.intensity, 0f..ModelLighting.MAX_INTENSITY) { onChange(lighting.copy(intensity = it)) }
            LightSlider("Fill", lighting.ambient, 0f..1f) { onChange(lighting.copy(ambient = it)) }
            LightSlider("Metallic", lighting.metallic, 0f..1f) { onChange(lighting.copy(metallic = it)) }
            LightSlider("Roughness", lighting.roughness, 0f..1f) { onChange(lighting.copy(roughness = it)) }
            LightSlider("Warmth", lighting.warmth, -1f..1f) { onChange(lighting.copy(warmth = it)) }
            LightSlider("Exposure", lighting.exposure, MIN_EXPOSURE..MAX_EXPOSURE) { onChange(lighting.copy(exposure = it)) }
            // Procreate's Add light: a second light, off until it is given some strength.
            LightSlider("Light 2", lighting.secondIntensity, 0f..ModelLighting.MAX_INTENSITY) {
                onChange(lighting.copy(secondIntensity = it))
            }
            if (lighting.secondIntensity > 0f) {
                LightSlider("Angle 2", lighting.secondAzimuth, -180f..180f) { onChange(lighting.copy(secondAzimuth = it)) }
                LightSlider("Height 2", lighting.secondElevation, -ModelLighting.MAX_ELEVATION..ModelLighting.MAX_ELEVATION) {
                    onChange(lighting.copy(secondElevation = it))
                }
            }
        }
    }
}

@Composable
private fun LightSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
        Slider(
            value = value.coerceIn(range),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.weight(1f).height(32.dp).semantics { contentDescription = "Light $label" },
        )
    }
}

private const val PANEL_ALPHA = 0.92f
private const val MIN_EXPOSURE = 0.4f
private const val MAX_EXPOSURE = 2f
