package com.artflow.studio.presentation.ui.components.brush

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.core.render.BrushTexture
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.local.GrainStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.ColorDynamics
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.GrainBlend
import com.artflow.studio.domain.model.brush.MAX_BRUSH_SIZE
import com.artflow.studio.domain.model.brush.MIN_BRUSH_OPACITY
import com.artflow.studio.domain.model.brush.MIN_BRUSH_SIZE
import com.artflow.studio.domain.model.brush.PressureResponse
import com.artflow.studio.domain.model.brush.RenderingMode
import com.artflow.studio.domain.model.brush.StudioBrushes
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.presentation.ui.theme.LocalArtFlowFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** All settings remain available; the studio can also show one named attribute at a time. */
@Composable
fun AdvancedBrushSettingsPanel(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
    modifier: Modifier = Modifier,
    attribute: BrushAttribute = BrushAttribute.ALL,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (attribute == BrushAttribute.ALL) {
            BrushPreviewWidget(
                brushParams,
                Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
        BrushAttribute.visible(attribute).forEach { item ->
            key(item) { BrushAttributeSettings(item, brushParams, onBrushParamsChanged) }
        }
    }
}

@Composable
private fun BrushAttributeSettings(
    attribute: BrushAttribute,
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    when (attribute) {
        BrushAttribute.PROPERTIES -> BrushProperties(brushParams, onBrushParamsChanged)
        BrushAttribute.STROKE -> BrushStrokeSettings(brushParams, onBrushParamsChanged)
        BrushAttribute.TAPER -> BrushTaperSettings(brushParams, onBrushParamsChanged)
        BrushAttribute.PRESSURE -> BrushPressureSettings(brushParams, onBrushParamsChanged)
        BrushAttribute.GRAIN -> BrushGrainSettings(brushParams, onBrushParamsChanged)
        BrushAttribute.SCATTER -> BrushScatterSettings(brushParams, onBrushParamsChanged)
        BrushAttribute.COLOUR -> BrushJitterSettings(brushParams, onBrushParamsChanged)
        BrushAttribute.ROTATION -> BrushRotationSettings(brushParams, onBrushParamsChanged)
        BrushAttribute.WET -> BrushWetSettings(brushParams, onBrushParamsChanged)
        BrushAttribute.DYNAMICS -> BrushSpeedSettings(brushParams, onBrushParamsChanged)
        BrushAttribute.DUAL -> BrushDualSettings(brushParams, onBrushParamsChanged)
        BrushAttribute.ALL -> Unit
    }
}

@Composable
private fun BrushStrokeSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    BrushSettingsSection(title = stringResource(R.string.brush_adv_stroke_dynamics)) {
        // Spacing Control
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_spacing),
            value = brushParams.spacing,
            onValueChange = { onBrushParamsChanged(brushParams.copy(spacing = it)) },
            valueRange = 0.01f..1f,
            valueDisplay = "%.0f%%".format(brushParams.spacing * 100),
        )

        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_fall_off),
            value = brushParams.falloff,
            onValueChange = { onBrushParamsChanged(brushParams.copy(falloff = it)) },
            valueRange = 0f..1f,
            valueDisplay =
                if (brushParams.falloff <= 0f) {
                    stringResource(R.string.brush_adv_none)
                } else {
                    "%.0f%%".format(brushParams.falloff * 100)
                },
        )

        // Smoothing Control
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_smoothing),
            value = brushParams.smoothing,
            onValueChange = { onBrushParamsChanged(brushParams.copy(smoothing = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.smoothing * 100),
        )

        // Flow Control
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_flow),
            value = brushParams.flow,
            onValueChange = { onBrushParamsChanged(brushParams.copy(flow = it)) },
            valueRange = 0.01f..1f,
            valueDisplay = "%.0f%%".format(brushParams.flow * 100),
        )
    }
}

@Composable
private fun BrushTaperSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    BrushSettingsSection(title = stringResource(R.string.brush_adv_tapering)) {
        // Start Taper
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_start_taper),
            value = brushParams.taperStart,
            onValueChange = { onBrushParamsChanged(brushParams.copy(taperStart = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.taperStart * 100),
        )

        // End Taper
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_end_taper),
            value = brushParams.taperEnd,
            onValueChange = { onBrushParamsChanged(brushParams.copy(taperEnd = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.taperEnd * 100),
        )

        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_taper_opacity),
            value = brushParams.taperOpacity,
            onValueChange = { onBrushParamsChanged(brushParams.copy(taperOpacity = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.taperOpacity * 100),
        )
        ShapeSwitch("Separate taper for finger strokes", brushParams.touchTaperStart != null || brushParams.touchTaperEnd != null) { on ->
            onBrushParamsChanged(
                if (on) {
                    brushParams.copy(touchTaperStart = brushParams.taperStart, touchTaperEnd = brushParams.taperEnd)
                } else {
                    brushParams.copy(touchTaperStart = null, touchTaperEnd = null)
                },
            )
        }
        brushParams.touchTaperStart?.let { start ->
            BrushParameterSlider(
                label = "Touch start taper",
                value = start,
                onValueChange = { onBrushParamsChanged(brushParams.copy(touchTaperStart = it.coerceIn(0f, 1f))) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(start * 100),
            )
        }
        brushParams.touchTaperEnd?.let { end ->
            BrushParameterSlider(
                label = "Touch end taper",
                value = end,
                onValueChange = { onBrushParamsChanged(brushParams.copy(touchTaperEnd = it.coerceIn(0f, 1f))) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(end * 100),
            )
        }
        BrushParameterSlider(
            label = "Tip sharpness",
            value = brushParams.tipSharpness,
            onValueChange = { onBrushParamsChanged(brushParams.copy(tipSharpness = it.coerceIn(0f, 1f))) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.tipSharpness * 100),
        )
    }
}

@Composable
private fun BrushPressureSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    BrushSettingsSection(title = stringResource(R.string.brush_adv_pressure_dynamics)) {
        // Pressure to Size
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_pressure_size),
            value = brushParams.pressureToSize,
            onValueChange = { onBrushParamsChanged(brushParams.copy(pressureToSize = it)) },
            valueRange = -1f..1f,
            valueDisplay = "%.0f%%".format(brushParams.pressureToSize * 100),
        )

        // Pressure to Opacity
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_pressure_opacity),
            value = brushParams.pressureToOpacity,
            onValueChange = { onBrushParamsChanged(brushParams.copy(pressureToOpacity = it)) },
            valueRange = -1f..1f,
            valueDisplay = "%.0f%%".format(brushParams.pressureToOpacity * 100),
        )
        BrushParameterSlider(
            label = "Pressure → Flow",
            value = brushParams.pressureToFlow,
            onValueChange = { onBrushParamsChanged(brushParams.copy(pressureToFlow = it.coerceIn(-1f, 1f))) },
            valueRange = -1f..1f,
            valueDisplay = "%.0f%%".format(brushParams.pressureToFlow * 100),
        )

        if (brushParams.importedPressure != null || brushParams.importedAuxiliaryCurves.isNotEmpty()) {
            Text(
                "Imported pressure curves can change how individual controls respond.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(
                onClick = { onBrushParamsChanged(brushParams.copy(importedPressure = null, importedAuxiliaryCurves = emptyMap())) },
            ) {
                Text("Reset imported pressure curves")
            }
        }

        // Pressure to Bleed
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_pressure_bleed),
            value = brushParams.bleed,
            onValueChange = { onBrushParamsChanged(brushParams.copy(bleed = it.coerceIn(0f, 1f))) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.bleed * 100),
        )

        // Pressure Curve Selection
        PressureCurveSelector(
            brushParams = brushParams,
            onBrushParamsChanged = onBrushParamsChanged,
        )
    }
}

@Composable
private fun BrushGrainSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    val latestParams by rememberUpdatedState(brushParams)
    val latestChange by rememberUpdatedState(onBrushParamsChanged)
    var imported by remember { mutableStateOf(CustomGrains.ids()) }
    var importFailed by remember { mutableStateOf(false) }
    val importImage =
        rememberImageImport { id ->
            importFailed = id == null
            if (id != null) {
                imported = CustomGrains.ids()
                latestChange(latestParams.copy(textureId = id, blendTexture = true))
            }
        }
    BrushSettingsSection(title = stringResource(R.string.brush_adv_brush_grain)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("brush-grain-options"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = !brushParams.blendTexture || brushParams.textureId == null,
                onClick = { onBrushParamsChanged(brushParams.copy(textureId = null, blendTexture = false)) },
                label = { Text(stringResource(R.string.brush_adv_none)) },
            )
            BrushTexture.Kind.entries.forEach { texture ->
                FilterChip(
                    selected = brushParams.blendTexture && brushParams.textureId == texture.id,
                    onClick = { onBrushParamsChanged(brushParams.copy(textureId = texture.id, blendTexture = true)) },
                    label = { Text(texture.label) },
                )
            }
            imported.forEachIndexed { index, id ->
                FilterChip(
                    selected = brushParams.blendTexture && brushParams.textureId == id,
                    onClick = { onBrushParamsChanged(brushParams.copy(textureId = id, blendTexture = true)) },
                    label = { Text(stringResource(R.string.brush_adv_image_n, index + 1)) },
                )
            }
            AssistChip(onClick = importImage, label = { Text(stringResource(R.string.brush_adv_import_photo)) })
        }
        if (importFailed) {
            Text(
                stringResource(R.string.brush_adv_that_image_could_not_be_used_as_a_grain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (brushParams.blendTexture && CustomGrains.isCustom(brushParams.textureId) && CustomGrains.get(brushParams.textureId) == null) {
            Text(
                stringResource(R.string.brush_adv_this_brush_s_imported_grain_is_not_on_this_device),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (brushParams.blendTexture && brushParams.textureId != null) {
            BrushParameterSlider(
                label = "Grain scale",
                value = brushParams.textureScale.coerceIn(0.05f, 8f),
                onValueChange = { onBrushParamsChanged(brushParams.copy(textureScale = it)) },
                valueRange = 0.05f..8f,
                valueDisplay = "%.2f×".format(brushParams.textureScale),
            )
            BrushParameterSlider(
                label = stringResource(R.string.brush_adv_grain_rotation),
                value = brushParams.textureRotation.coerceIn(0f, 360f),
                onValueChange = { onBrushParamsChanged(brushParams.copy(textureRotation = it)) },
                valueRange = 0f..360f,
                valueDisplay = "%.0f°".format(brushParams.textureRotation),
            )
            BrushParameterSlider(
                label = stringResource(R.string.brush_adv_grain_depth),
                value = brushParams.grainDepth.coerceIn(0f, 1f),
                onValueChange = { onBrushParamsChanged(brushParams.copy(grainDepth = it)) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.grainDepth * 100),
            )
            BrushParameterSlider(
                label = "Grain brightness",
                value = brushParams.grainBrightness.coerceIn(-1f, 1f),
                onValueChange = { onBrushParamsChanged(brushParams.copy(grainBrightness = it)) },
                valueRange = -1f..1f,
                valueDisplay = "%+.0f%%".format(brushParams.grainBrightness * 100),
            )
            BrushParameterSlider(
                label = "Grain contrast",
                value = brushParams.grainContrast.coerceIn(-1f, 1f),
                onValueChange = { onBrushParamsChanged(brushParams.copy(grainContrast = it)) },
                valueRange = -1f..1f,
                valueDisplay = "%+.0f%%".format(brushParams.grainContrast * 100),
            )
            Text("Grain blend", style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GrainBlend.entries.forEach { blend ->
                    FilterChip(
                        selected = brushParams.grainBlend == blend,
                        onClick = { onBrushParamsChanged(brushParams.copy(grainBlend = blend)) },
                        label = { Text(blend.displayName, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            ShapeSwitch("Moving grain (starts with each stroke)", brushParams.grainMoving) {
                onBrushParamsChanged(brushParams.copy(grainMoving = it))
            }
        }
    }
}

@Composable
private fun BrushScatterSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    BrushSettingsSection(title = stringResource(R.string.brush_adv_scatter_count)) {
        // Scatter
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_scatter),
            value = brushParams.scatter,
            onValueChange = { onBrushParamsChanged(brushParams.copy(scatter = it)) },
            valueRange = 0f..2f,
            valueDisplay = "%.0f%%".format(brushParams.scatter * 100),
        )

        // Count
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_count),
            value = brushParams.count.toFloat(),
            onValueChange = { onBrushParamsChanged(brushParams.copy(count = it.toInt())) },
            valueRange = 1f..16f,
            valueDisplay = "%d".format(brushParams.count),
            isInteger = true,
        )
        if (brushParams.count > 1) {
            BrushParameterSlider(
                label = "Count Jitter",
                value = brushParams.countJitter,
                onValueChange = { onBrushParamsChanged(brushParams.copy(countJitter = it.coerceIn(0f, 1f))) },
                valueRange = 0f..1f,
                valueDisplay = "%.0f%%".format(brushParams.countJitter * 100),
            )
        }
    }
}

@Composable
private fun BrushJitterSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    BrushSettingsSection(title = stringResource(R.string.brush_adv_jitter_randomization)) {
        // Size Jitter
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_size_jitter),
            value = brushParams.sizeJitter,
            onValueChange = { onBrushParamsChanged(brushParams.copy(sizeJitter = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.sizeJitter * 100),
        )

        // Opacity Jitter
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_opacity_jitter),
            value = brushParams.opacityJitter,
            onValueChange = { onBrushParamsChanged(brushParams.copy(opacityJitter = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.opacityJitter * 100),
        )

        ShapeSwitch("Colour jitter once per stroke", brushParams.colorJitterPerStroke) {
            onBrushParamsChanged(brushParams.copy(colorJitterPerStroke = it))
        }

        // Hue Jitter
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_hue_jitter),
            value = brushParams.hueJitter,
            onValueChange = { onBrushParamsChanged(brushParams.copy(hueJitter = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.hueJitter * 100),
        )

        // Saturation Jitter
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_saturation_jitter),
            value = brushParams.saturationJitter,
            onValueChange = { onBrushParamsChanged(brushParams.copy(saturationJitter = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.saturationJitter * 100),
        )

        // Brightness Jitter
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_brightness_jitter),
            value = brushParams.brightnessJitter,
            onValueChange = { onBrushParamsChanged(brushParams.copy(brightnessJitter = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.brightnessJitter * 100),
        )

        // Procreate's secondary colour dynamics (the secondary swatch in the colour panel).
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_secondary_colour_pressure),
            value = brushParams.secondaryPressure,
            onValueChange = { onBrushParamsChanged(brushParams.copy(secondaryPressure = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.secondaryPressure * 100),
        )
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_secondary_colour_jitter),
            value = brushParams.secondaryJitter,
            onValueChange = { onBrushParamsChanged(brushParams.copy(secondaryJitter = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.secondaryJitter * 100),
        )
        ColorDynamicsSliders(brushParams.colorDynamics) { onBrushParamsChanged(brushParams.copy(colorDynamics = it)) }
    }
}

/** Procreate's Colour Pressure and Colour Tilt: hue, saturation and brightness that follow the pen. */
@Composable
private fun ColorDynamicsSliders(
    dynamics: ColorDynamics,
    onChange: (ColorDynamics) -> Unit,
) {
    val sliders =
        listOf(
            Triple("Pressure → hue", dynamics.pressureHue) { v: Float -> dynamics.copy(pressureHue = v) },
            Triple("Pressure → saturation", dynamics.pressureSaturation) { v: Float -> dynamics.copy(pressureSaturation = v) },
            Triple("Pressure → brightness", dynamics.pressureBrightness) { v: Float -> dynamics.copy(pressureBrightness = v) },
            Triple("Tilt → hue", dynamics.tiltHue) { v: Float -> dynamics.copy(tiltHue = v) },
            Triple("Tilt → saturation", dynamics.tiltSaturation) { v: Float -> dynamics.copy(tiltSaturation = v) },
            Triple("Tilt → brightness", dynamics.tiltBrightness) { v: Float -> dynamics.copy(tiltBrightness = v) },
        )
    sliders.forEach { (label, value, update) ->
        BrushParameterSlider(
            label = label,
            value = value,
            onValueChange = { onChange(update(it.coerceIn(0f, 1f))) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(value * 100),
        )
    }
}

@Composable
private fun BrushRotationSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    val latestParams by rememberUpdatedState(brushParams)
    val latestChange by rememberUpdatedState(onBrushParamsChanged)
    var imported by remember { mutableStateOf(CustomGrains.ids()) }
    var importFailed by remember { mutableStateOf(false) }
    val importImage =
        rememberImageImport { id ->
            importFailed = id == null
            if (id != null) {
                imported = CustomGrains.ids()
                latestChange(latestParams.copy(shapeId = id))
            }
        }
    BrushSettingsSection(title = stringResource(R.string.brush_adv_shape)) {
        Text(
            stringResource(R.string.brush_adv_lower_roundness_flattens_the_tip_into_a_chisel_rot) +
                stringResource(R.string.brush_adv_an_imported_photo_becomes_the_tip_white_paints_bla),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("brush-shape-options"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = brushParams.shapeId == null,
                onClick = { onBrushParamsChanged(brushParams.copy(shapeId = null)) },
                label = { Text(stringResource(R.string.brush_adv_round_tip)) },
            )
            imported.forEachIndexed { index, id ->
                FilterChip(
                    selected = brushParams.shapeId == id,
                    onClick = { onBrushParamsChanged(brushParams.copy(shapeId = id)) },
                    label = { Text(stringResource(R.string.brush_adv_image_n, index + 1)) },
                )
            }
            AssistChip(onClick = importImage, label = { Text(stringResource(R.string.brush_adv_import_shape)) })
        }
        if (importFailed) {
            Text(
                stringResource(R.string.brush_adv_that_image_could_not_be_used_as_a_brush_shape),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_roundness),
            value = brushParams.roundness,
            onValueChange = { onBrushParamsChanged(brushParams.copy(roundness = it.coerceIn(0.05f, 1f))) },
            valueRange = 0.05f..1f,
            valueDisplay = "%.0f%%".format(brushParams.roundness * 100),
        )
        // Brush Rotation
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_rotation),
            value = brushParams.rotation,
            onValueChange = { onBrushParamsChanged(brushParams.copy(rotation = it)) },
            valueRange = 0f..360f,
            valueDisplay = "%.0f°".format(brushParams.rotation),
            isInteger = true,
        )
        ShapeSwitch("Randomized", brushParams.tipRandomized) { onBrushParamsChanged(brushParams.copy(tipRandomized = it)) }
        ShapeSwitch("Flip X", brushParams.tipFlipX) { onBrushParamsChanged(brushParams.copy(tipFlipX = it)) }
        ShapeSwitch("Flip Y", brushParams.tipFlipY) { onBrushParamsChanged(brushParams.copy(tipFlipY = it)) }
        ShapeSwitch("Random flip X per dab", brushParams.tipFlipXJitter) {
            onBrushParamsChanged(brushParams.copy(tipFlipXJitter = it))
        }
        ShapeSwitch("Random flip Y per dab", brushParams.tipFlipYJitter) {
            onBrushParamsChanged(brushParams.copy(tipFlipYJitter = it))
        }
    }
}

@Composable
private fun ShapeSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val touchSize = if (LocalArtFlowFlags.current.largeTouchTargets) 56.dp else 48.dp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier =
                Modifier
                    .sizeIn(minWidth = touchSize, minHeight = touchSize)
                    .semantics { contentDescription = label },
        )
    }
}

@Composable
private fun BrushWetSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    BrushSettingsSection(title = stringResource(R.string.brush_adv_wet_paint_and_rendering)) {
        // Wet Mix
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_wet_mix),
            value = brushParams.wetMix,
            onValueChange = { onBrushParamsChanged(brushParams.copy(wetMix = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.wetMix * 100),
        )
        BrushParameterSlider(
            label = "Pigment dilution",
            value = brushParams.dilution,
            onValueChange = { onBrushParamsChanged(brushParams.copy(dilution = it.coerceIn(0f, 1f))) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.dilution * 100),
        )
        BrushParameterSlider(
            label = "Blur",
            value = brushParams.wetBlur,
            onValueChange = { onBrushParamsChanged(brushParams.copy(wetBlur = it.coerceIn(0f, 1f))) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.wetBlur * 100),
        )
        BrushParameterSlider(
            label = "Colour carry",
            value = brushParams.pull,
            onValueChange = { onBrushParamsChanged(brushParams.copy(pull = it.coerceIn(0f, 1f))) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.pull * 100),
        )
        BrushWetPickupSettings(brushParams, onBrushParamsChanged)
        BrushParameterSlider(
            label = "Wet edges",
            value = brushParams.wetEdges,
            onValueChange = { onBrushParamsChanged(brushParams.copy(wetEdges = it.coerceIn(0f, 1f))) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.wetEdges * 100),
        )
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_burnt_edges),
            value = brushParams.burntEdges,
            onValueChange = { onBrushParamsChanged(brushParams.copy(burntEdges = it.coerceIn(0f, 1f))) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.burntEdges * 100),
        )
        Text("Rendering", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RenderingMode.entries.forEach { mode ->
                FilterChip(
                    selected = brushParams.rendering == mode,
                    onClick = { onBrushParamsChanged(brushParams.copy(renderingMode = mode, buildUp = mode.blending)) },
                    label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text("Blend mode", style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("brush-blend-modes"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BlendMode.getLayerBlendModes().forEach { mode ->
                FilterChip(
                    selected = brushParams.blendMode == mode,
                    onClick = { onBrushParamsChanged(brushParams.copy(blendMode = mode)) },
                    label = { Text(mode.displayName) },
                )
            }
        }
    }
}

@Composable
private fun BrushWetPickupSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    BrushParameterSlider(
        label = "Water dilution",
        value = brushParams.wetDilution,
        onValueChange = { onBrushParamsChanged(brushParams.copy(wetDilution = it.coerceIn(0f, 1f))) },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(brushParams.wetDilution * 100),
    )
    BrushParameterSlider(
        label = "Charge",
        value = brushParams.wetCharge,
        onValueChange = { onBrushParamsChanged(brushParams.copy(wetCharge = it.coerceIn(0f, 1f))) },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(brushParams.wetCharge * 100),
    )
    BrushParameterSlider(
        label = "Attack",
        value = brushParams.wetAttack,
        onValueChange = { onBrushParamsChanged(brushParams.copy(wetAttack = it.coerceIn(0f, 1f))) },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(brushParams.wetAttack * 100),
    )
    BrushParameterSlider(
        label = "Canvas pull",
        value = brushParams.wetPull,
        onValueChange = { onBrushParamsChanged(brushParams.copy(wetPull = it.coerceIn(0f, 1f))) },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(brushParams.wetPull * 100),
    )
    BrushParameterSlider(
        label = "Blur jitter",
        value = brushParams.wetBlurJitter,
        onValueChange = { onBrushParamsChanged(brushParams.copy(wetBlurJitter = it.coerceIn(0f, 1f))) },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(brushParams.wetBlurJitter * 100),
    )
}

@Composable
private fun BrushSpeedSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    val touchSize = if (LocalArtFlowFlags.current.largeTouchTargets) 56.dp else 48.dp
    BrushSettingsSection(title = stringResource(R.string.brush_adv_speed_and_colour_dynamics)) {
        Text(
            "Positive size/opacity values reduce fast marks; negative values reduce slow marks. Test the response in the drawing pad.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_speed_size),
            value = brushParams.velocityToSize,
            onValueChange = { onBrushParamsChanged(brushParams.copy(velocityToSize = it)) },
            valueRange = -1f..1f,
            valueDisplay = "%.0f%%".format(brushParams.velocityToSize * 100),
        )
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_speed_opacity),
            value = brushParams.velocityToOpacity,
            onValueChange = { onBrushParamsChanged(brushParams.copy(velocityToOpacity = it)) },
            valueRange = -1f..1f,
            valueDisplay = "%.0f%%".format(brushParams.velocityToOpacity * 100),
        )
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_speed_hue),
            value = brushParams.velocityToHue,
            onValueChange = { onBrushParamsChanged(brushParams.copy(velocityToHue = it)) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.velocityToHue * 100),
        )
        // Switch semantics take plain strings, so the labels are read here, in composable scope.
        val brightnessLabel = stringResource(R.string.brush_adv_pressure_changes_brightness)
        val tiltLabel = stringResource(R.string.brush_adv_tilt_turns_the_tip)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(brightnessLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = brushParams.colorPressure,
                onCheckedChange = { onBrushParamsChanged(brushParams.copy(colorPressure = it)) },
                modifier =
                    Modifier
                        .sizeIn(minWidth = touchSize, minHeight = touchSize)
                        .semantics { contentDescription = brightnessLabel },
            )
        }
        BrushParameterSlider(
            label = "Tilt → size",
            value = brushParams.tiltInfluence,
            onValueChange = { onBrushParamsChanged(brushParams.copy(tiltInfluence = it.coerceIn(0f, 1f))) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(brushParams.tiltInfluence * 100),
        )
        // Opacity has its own tilt amount once set; until then it follows the size amount.
        val opacityTilt = brushParams.tiltOpacityInfluence ?: brushParams.tiltInfluence
        BrushParameterSlider(
            label = if (brushParams.tiltOpacityInfluence == null) "Tilt → opacity (follows size)" else "Tilt → opacity",
            value = opacityTilt,
            onValueChange = { onBrushParamsChanged(brushParams.copy(tiltOpacityInfluence = it.coerceIn(0f, 1f))) },
            valueRange = 0f..1f,
            valueDisplay = "%.0f%%".format(opacityTilt * 100),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tiltLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = brushParams.tiltToRotation,
                onCheckedChange = { onBrushParamsChanged(brushParams.copy(tiltToRotation = it)) },
                modifier =
                    Modifier
                        .sizeIn(minWidth = touchSize, minHeight = touchSize)
                        .semantics { contentDescription = tiltLabel },
            )
        }
        Text(
            stringResource(R.string.brush_adv_pressure_and_tilt_depend_on_the_stylus_a_tilted_pe),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Dual brush: a second brush drawn along the same path, combined with this one. */
@Composable
private fun BrushDualSettings(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    BrushSettingsSection(title = stringResource(R.string.brush_adv_dual_brush)) {
        val dual = brushParams.dual
        val combineLabel = stringResource(R.string.brush_adv_combine_with_a_second_brush)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(combineLabel, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = dual != null,
                onCheckedChange = { on ->
                    val second = StudioBrushes.presets.firstOrNull { it.parameters.textureId != null } ?: StudioBrushes.presets.first()
                    onBrushParamsChanged(brushParams.copy(dual = if (on) DualBrush(second.parameters.copy(dual = null)) else null))
                },
                modifier = Modifier.semantics { contentDescription = combineLabel },
            )
        }
        if (dual != null) DualBrushOptions(dual) { onBrushParamsChanged(brushParams.copy(dual = it)) }
    }
}

@Composable
private fun DualBrushOptions(
    dual: DualBrush,
    onChange: (DualBrush) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.brush_adv_second_brush), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StudioBrushes.presets.forEach { preset ->
                val parameters = preset.parameters.copy(size = dual.params.size, dual = null)
                FilterChip(
                    selected = dual.params == parameters,
                    onClick = { onChange(dual.copy(params = parameters)) },
                    label = { Text(preset.name) },
                )
            }
        }
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_second_brush_size),
            value = dual.params.size,
            onValueChange = { onChange(dual.copy(params = dual.params.copy(size = it))) },
            valueRange = 1f..200f,
            valueDisplay = "%.0f px".format(dual.params.size),
        )
        Text("Combine", style = MaterialTheme.typography.labelLarge)
        dual.sourceBlendMode?.let { source ->
            Text(
                "Imported combine mode $source is approximated by ${dual.mode.displayName}. Choose a mode to replace it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DualBrush.Mode.entries.forEach { mode ->
                FilterChip(
                    selected = dual.mode == mode,
                    onClick = { onChange(dual.copy(mode = mode, sourceBlendMode = null)) },
                    label = { Text(mode.displayName) },
                )
            }
        }
        Text(
            stringResource(R.string.brush_adv_multiply_paints_only_where_both_brushes_reach_subt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Brush preview widget that renders sample strokes
 */
@Composable
private fun BrushPreviewWidget(
    brushParams: BrushParams,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        BrushSample(brushParams, Modifier.fillMaxSize().padding(8.dp))
    }
}

/**
 * Collapsible settings section
 */
@Composable
private fun BrushSettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription =
                            if (expanded) {
                                stringResource(R.string.brush_adv_collapse_section, title)
                            } else {
                                stringResource(R.string.brush_adv_expand_section, title)
                            },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(if (expanded) 0f else -90f),
                    )
                }
            }

            // Section Content
            if (expanded) {
                content()
            }
        }
    }
}

/**
 * Pressure curve selector with visual preview
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PressureCurveSelector(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.brush_adv_pressure_curve),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BrushParams.PressureCurve.entries.forEach { curve ->
                FilterChip(
                    selected = brushParams.pressureCurve == curve,
                    onClick = { onBrushParamsChanged(brushParams.copy(pressureCurve = curve)) },
                    label = {
                        Text(
                            text =
                                curve.name
                                    .replace("_", " ")
                                    .lowercase()
                                    .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    modifier = Modifier.width(90.dp),
                )
            }
        }

        if (brushParams.pressureCurve == BrushParams.PressureCurve.CUSTOM) {
            CustomPressureControls(brushParams, onBrushParamsChanged)
        }

        // Visual curve preview uses exactly the engine's pressure function.
        PressureCurvePreview(
            brushParams = brushParams,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    ),
        )
    }
}

/**
 * Visual preview of pressure curve
 */
@Composable
private fun PressureCurvePreview(
    brushParams: BrushParams,
    modifier: Modifier = Modifier,
) {
    // Resolved outside the draw lambda: MaterialTheme is @Composable and cannot be
    // read from inside DrawScope.
    val curveColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier,
    ) {
        val width = size.width
        val height = size.height
        val padding = 20f

        // Draw axes
        drawLine(
            color = Color.Gray,
            start = Offset(padding, padding),
            end = Offset(padding, height - padding),
            strokeWidth = 1f,
        )
        drawLine(
            color = Color.Gray,
            start = Offset(padding, height - padding),
            end = Offset(width - padding, height - padding),
            strokeWidth = 1f,
        )

        // Draw pressure curve
        val points = mutableListOf<Offset>()
        val steps = 50

        for (i in 0..steps) {
            val input = i.toFloat() / steps // Input pressure (0-1)
            val output = brushParams.pressureResponse(input)

            val x = padding + input * (width - 2 * padding)
            val y = height - padding - output * (height - 2 * padding)
            points.add(Offset(x, y))
        }

        // Draw curve path
        if (points.size > 1) {
            drawPoints(
                points = points,
                pointMode = PointMode.Polygon,
                color = curveColor,
                strokeWidth = 3f,
            )
        }
    }
}

@Composable
private fun CustomPressureControls(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    val response = brushParams.customPressure
    BrushParameterSlider(
        label = stringResource(R.string.advancedbrushsettingspanel_output_at_25_pressure),
        value = response.low,
        onValueChange = { value ->
            val curve = PressureResponse(value, maxOf(value, response.middle), maxOf(value, response.high))
            onBrushParamsChanged(brushParams.copy(customPressure = curve))
        },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(response.low * 100),
    )
    BrushParameterSlider(
        label = stringResource(R.string.advancedbrushsettingspanel_output_at_50_pressure),
        value = response.middle,
        onValueChange = { value ->
            val curve = PressureResponse(minOf(response.low, value), value, maxOf(value, response.high))
            onBrushParamsChanged(brushParams.copy(customPressure = curve))
        },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(response.middle * 100),
    )
    BrushParameterSlider(
        label = stringResource(R.string.advancedbrushsettingspanel_output_at_75_pressure),
        value = response.high,
        onValueChange = { value ->
            val curve = PressureResponse(minOf(response.low, value), minOf(response.middle, value), value)
            onBrushParamsChanged(brushParams.copy(customPressure = curve))
        },
        valueRange = 0f..1f,
        valueDisplay = "%.0f%%".format(response.high * 100),
    )
}

@Composable
private fun BrushProperties(
    brushParams: BrushParams,
    onBrushParamsChanged: (BrushParams) -> Unit,
) {
    BrushSettingsSection(title = stringResource(R.string.brush_adv_brush_properties)) {
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_brush_size),
            value = brushParams.size,
            onValueChange = { onBrushParamsChanged(brushParams.copy(size = it)) },
            valueRange = 1f..512f,
            valueDisplay = "%.1f px".format(brushParams.size),
        )
        BrushParameterSlider(
            label = stringResource(R.string.brush_adv_brush_opacity),
            value = brushParams.opacity,
            onValueChange = { onBrushParamsChanged(brushParams.copy(opacity = it)) },
            valueRange = 0.01f..1f,
            valueDisplay = "%.0f%%".format(brushParams.opacity * 100),
        )
        // Procreate's size and opacity limits: the sidebar sliders span exactly this range.
        val sizes = brushParams.sizeLimits
        val opacities = brushParams.opacityLimits
        BrushParameterSlider(
            label = "Minimum size",
            value = sizes.start,
            onValueChange = { onBrushParamsChanged(brushParams.copy(minSize = it.coerceAtMost(sizes.endInclusive - 1f))) },
            valueRange = MIN_BRUSH_SIZE..MAX_BRUSH_SIZE,
            valueDisplay = "%.0f px".format(sizes.start),
        )
        BrushParameterSlider(
            label = "Maximum size",
            value = sizes.endInclusive,
            onValueChange = { onBrushParamsChanged(brushParams.copy(maxSize = it.coerceAtLeast(sizes.start + 1f))) },
            valueRange = MIN_BRUSH_SIZE..MAX_BRUSH_SIZE,
            valueDisplay = "%.0f px".format(sizes.endInclusive),
        )
        BrushParameterSlider(
            label = "Minimum opacity",
            value = opacities.start,
            onValueChange = { onBrushParamsChanged(brushParams.copy(minOpacity = it.coerceAtMost(opacities.endInclusive - 0.01f))) },
            valueRange = MIN_BRUSH_OPACITY..1f,
            valueDisplay = "%.0f%%".format(opacities.start * 100),
        )
        BrushParameterSlider(
            label = "Maximum opacity",
            value = opacities.endInclusive,
            onValueChange = { onBrushParamsChanged(brushParams.copy(maxOpacity = it.coerceAtLeast(opacities.start + 0.01f))) },
            valueRange = MIN_BRUSH_OPACITY..1f,
            valueDisplay = "%.0f%%".format(opacities.endInclusive * 100),
        )
    }
}

private const val GRAIN_DECODE_SIZE = 1024

/** Opens the image picker and turns the chosen photo into a stored tile; reports its identity or null. */
@Composable
private fun rememberImageImport(onResult: (String?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onResult)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val id =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            BitmapPixelBridge.decodeUri(context.contentResolver, uri, maxDimension = GRAIN_DECODE_SIZE)?.let { image ->
                                GrainStorage.save(GrainStorage.directory(context.filesDir), CustomGrains.tileFrom(image))
                            }
                        }.getOrNull()
                    }
                latest(id)
            }
        }
    return {
        try {
            picker.launch("image/*")
        } catch (missing: ActivityNotFoundException) {
            latest(null)
        }
    }
}
