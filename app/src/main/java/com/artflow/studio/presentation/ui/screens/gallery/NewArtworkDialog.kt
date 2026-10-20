@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.screens.gallery

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.canvas.CanvasPresets
import com.artflow.studio.core.canvas.CanvasUnit
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel

/** The Custom tab's fields: lengths are text in [unit], and the pixels follow from them and the DPI. */
@Stable
private class CustomSize(
    start: CanvasOperations.Preset,
) {
    var unit by mutableStateOf(CanvasUnit.PIXELS)
    var width by mutableStateOf(start.width.toString())
    var height by mutableStateOf(start.height.toString())
    var dpi by mutableStateOf(start.dpi.toString())

    val dpiValue: Int get() = dpi.toIntOrNull() ?: 0
    val widthPx: Int get() = unit.toPixels(CanvasUnit.parse(width) ?: 0.0, dpiValue)
    val heightPx: Int get() = unit.toPixels(CanvasUnit.parse(height) ?: 0.0, dpiValue)

    /** Starts the fields from [preset], in pixels. */
    fun load(preset: CanvasOperations.Preset) {
        unit = CanvasUnit.PIXELS
        width = preset.width.toString()
        height = preset.height.toString()
        dpi = preset.dpi.toString()
    }

    /** Shows the same pixel size in [next], where the following edits are typed. */
    fun switchTo(next: CanvasUnit) {
        val pixelsWide = widthPx
        val pixelsHigh = heightPx
        unit = next
        width = next.format(next.fromPixels(pixelsWide, dpiValue))
        height = next.format(next.fromPixels(pixelsHigh, dpiValue))
    }
}

/** The New artwork dialog wired to the gallery: it creates the artwork, inside [stack] when one is open, and hands its id to [onOpen]. */
@Composable
internal fun NewArtworkHost(
    viewModel: MainViewModel,
    settings: AppSettings,
    stack: String?,
    onDismiss: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    NewArtworkDialog(
        defaultPresetName = settings.defaultPresetName,
        saved = settings.canvasPresets,
        onDismiss = onDismiss,
        onSavePreset = viewModel::saveCanvasPreset,
        onDeletePreset = viewModel::deleteCanvasPreset,
        onCreate = { name, preset, width, height, dpi, wideColor ->
            onDismiss()
            viewModel.createProject(name, preset, width, height, dpi) { id ->
                if (wideColor) PendingImports.putProfile(id, ColorProfile.DISPLAY_P3)
                // New artworks made inside a stack belong to it, as in Procreate.
                if (stack != null) viewModel.moveToStack(id, stack)
                onOpen(id)
            }
        },
    )
}

/**
 * New artwork: a name, then a built-in, device-screen or saved size, or a custom one typed in pixels,
 * inches, centimetres or millimetres. A custom size can be saved to the list.
 */
@Composable
internal fun NewArtworkDialog(
    defaultPresetName: String,
    saved: List<CanvasOperations.Preset>,
    onDismiss: () -> Unit,
    onSavePreset: (CanvasOperations.Preset) -> Unit,
    onDeletePreset: (String) -> Unit,
    onCreate: (String, CanvasOperations.Preset?, Int, Int, Int, Boolean) -> Unit,
) {
    val display = LocalContext.current.resources.displayMetrics
    val screen = CanvasOperations.screenPreset(display.widthPixels, display.heightPixels, display.densityDpi)
    val choices = saved + listOfNotNull(screen) + CanvasOperations.PRESETS
    val start = choices.firstOrNull { it.name == defaultPresetName } ?: CanvasOperations.PRESETS.first()
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(start) }
    var custom by remember { mutableStateOf(false) }
    var wideColor by remember { mutableStateOf(false) }
    val size = remember { CustomSize(start) }

    val widthPx = if (custom) size.widthPx else selected.width
    val heightPx = if (custom) size.heightPx else selected.height
    val dpi = if (custom) size.dpiValue else selected.dpi
    val dpiOk = dpi in CanvasOperations.MIN_DPI..CanvasOperations.MAX_DPI
    val sizeOk = CanvasOperations.isSizeSafe(widthPx, heightPx)

    fun saveSize() {
        CanvasPresets.add(saved, CanvasOperations.Preset(name, widthPx, heightPx, dpi)).firstOrNull()?.let { entry ->
            onSavePreset(entry)
            selected = entry
            size.load(entry)
            custom = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.galleryscreen_new_artwork)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.galleryscreen_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = !custom,
                        onClick = { custom = false },
                        label = { Text(stringResource(R.string.galleryscreen_preset)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = custom,
                        onClick = { custom = true },
                        label = { Text(stringResource(R.string.galleryscreen_custom)) },
                    )
                }
                if (custom) {
                    CustomFields(size)
                    TextButton(onClick = ::saveSize, enabled = sizeOk && dpiOk) { Text(stringResource(R.string.newcanvas_save_size)) }
                    Text(
                        stringResource(R.string.newcanvas_saved_size_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PresetGrid(choices, selected, screen) {
                        selected = it
                        size.load(it)
                    }
                    if (selected in saved) {
                        TextButton(
                            onClick = {
                                onDeletePreset(selected.name)
                                selected = CanvasOperations.PRESETS.first()
                            },
                        ) { Text(stringResource(R.string.newcanvas_delete_size, selected.name)) }
                    }
                }
                SizeReadout(widthPx, heightPx, dpi)
                SizeWarning(widthPx, heightPx, dpiOk)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.galleryscreen_display_p3_colour_profile), modifier = Modifier.weight(1f))
                    Switch(checked = wideColor, onCheckedChange = { wideColor = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = sizeOk && dpiOk,
                onClick = {
                    if (custom) {
                        onCreate(name, null, widthPx, heightPx, dpi, wideColor)
                    } else {
                        onCreate(name, selected, selected.width, selected.height, selected.dpi, wideColor)
                    }
                },
            ) { Text(stringResource(R.string.brushstudiodialog_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PresetGrid(
    choices: List<CanvasOperations.Preset>,
    selected: CanvasOperations.Preset,
    screen: CanvasOperations.Preset?,
    onPick: (CanvasOperations.Preset) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.heightIn(max = 220.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(choices) { preset ->
            FilterChip(
                selected = selected == preset,
                onClick = { onPick(preset) },
                label = {
                    Text(
                        if (preset == screen) stringResource(R.string.newcanvas_screen_size) else preset.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun CustomFields(size: CustomSize) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        CanvasUnit.entries.forEach { unit ->
            val description = stringResource(unit.nameRes)
            FilterChip(
                selected = size.unit == unit,
                onClick = { size.switchTo(unit) },
                label = { Text(unit.suffix) },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LengthField(size.width, size.unit, R.string.newcanvas_width_in, Modifier.weight(1f)) { size.width = it }
        LengthField(size.height, size.unit, R.string.newcanvas_height_in, Modifier.weight(1f)) { size.height = it }
    }
    OutlinedTextField(
        value = size.dpi,
        onValueChange = { size.dpi = CanvasUnit.PIXELS.sanitize(it) },
        label = { Text(stringResource(R.string.galleryscreen_dpi)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@get:StringRes
private val CanvasUnit.nameRes: Int
    get() =
        when (this) {
            CanvasUnit.PIXELS -> R.string.newcanvas_unit_pixels
            CanvasUnit.INCHES -> R.string.newcanvas_unit_inches
            CanvasUnit.CENTIMETERS -> R.string.newcanvas_unit_centimeters
            CanvasUnit.MILLIMETERS -> R.string.newcanvas_unit_millimeters
        }

@Composable
private fun LengthField(
    value: String,
    unit: CanvasUnit,
    @StringRes label: Int,
    modifier: Modifier,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(unit.sanitize(it)) },
        label = { Text(stringResource(label, unit.suffix)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (unit == CanvasUnit.PIXELS) KeyboardType.Number else KeyboardType.Decimal),
        modifier = modifier,
    )
}

/** The size in pixels and, once the DPI is known, in inches and centimetres. */
@Composable
private fun SizeReadout(
    widthPx: Int,
    heightPx: Int,
    dpi: Int,
) {
    val style = MaterialTheme.typography.bodySmall
    val colour = MaterialTheme.colorScheme.onSurfaceVariant
    Text(stringResource(R.string.newcanvas_size_pixels, widthPx, heightPx, dpi), style = style, color = colour)
    if (dpi > 0) {
        val inches = CanvasUnit.INCHES
        val centimetres = CanvasUnit.CENTIMETERS
        Text(
            stringResource(
                R.string.newcanvas_size_physical,
                inches.fromPixels(widthPx, dpi),
                inches.fromPixels(heightPx, dpi),
                centimetres.fromPixels(widthPx, dpi),
                centimetres.fromPixels(heightPx, dpi),
            ),
            style = style,
            color = colour,
        )
    }
}

@Composable
private fun SizeWarning(
    widthPx: Int,
    heightPx: Int,
    dpiOk: Boolean,
) {
    val message =
        CanvasOperations.sizeWarning(widthPx, heightPx)
            ?: if (dpiOk) null else stringResource(R.string.newcanvas_dpi_range, CanvasOperations.MIN_DPI, CanvasOperations.MAX_DPI)
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
