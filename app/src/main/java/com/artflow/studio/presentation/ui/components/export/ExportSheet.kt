@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.artflow.studio.presentation.ui.components.export

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.artflow.studio.R
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.export.*
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.presentation.ui.viewmodel.ExportUiState

/** Controls and presets share the exact options sent to the encoder. */
@Composable
fun ExportSheet(
    availableFormats: List<ExportFormat>,
    frameCount: Int,
    canvasWidth: Int,
    canvasHeight: Int,
    canvasDpi: Int,
    previewBytes: ByteArray?,
    exportState: ExportUiState,
    onExport: (ExportOptions) -> Unit,
    actions: ExportActions,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onExportTimelapse = actions.exportTimelapse
    val onClearTimelapse = actions.clearTimelapse
    var options by remember {
        mutableStateOf(ExportOptions(format = availableFormats.firstOrNull() ?: ExportFormat.PNG, dpi = canvasDpi))
    }
    val (width, height) = ExportNaming.resolveSize(canvasWidth, canvasHeight, options)
    val safe = CanvasOperations.isSizeSafe(width, height)
    val canExportAllFrames = options.format == ExportFormat.PNG || options.format == ExportFormat.PDF || options.format.requiresAnimation
    val validArea = options.area != ExportArea.ALL_FRAMES || (canExportAllFrames && frameCount > 1)
    LaunchedEffect(options.format, frameCount) {
        if (!validArea) options = options.copy(area = ExportArea.FULL_CANVAS)
    }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleMedium)
        ExportPreview(previewBytes)
        Text(stringResource(R.string.export_format), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            availableFormats.forEach { format ->
                FilterChip(
                    selected = options.format == format,
                    onClick = { options = options.copy(format = format) },
                    label = { Text(format.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text(stringResource(R.string.export_area), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportArea.entries.forEach { area ->
                FilterChip(
                    selected = options.area == area,
                    enabled = area != ExportArea.ALL_FRAMES || (canExportAllFrames && frameCount > 1),
                    onClick = { options = options.copy(area = area) },
                    label = { Text(area.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        LabeledSlider(
            stringResource(R.string.export_scale),
            options.scale,
            0.1f..4f,
            { options = options.copy(scale = it, targetWidth = null) },
            "${(options.scale * 100).toInt()}%",
        )
        Text(
            if (safe) {
                stringResource(R.string.export_output_size, width, height)
            } else {
                CanvasOperations.sizeWarning(width, height) ?: stringResource(R.string.export_size_too_large)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (safe) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        FormatControls(options, onChange = { options = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(options.includeHiddenLayers, onCheckedChange = { options = options.copy(includeHiddenLayers = it) })
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.export_include_hidden), style = MaterialTheme.typography.bodySmall)
        }
        if (options.format != ExportFormat.JPEG && options.format != ExportFormat.MP4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(options.flattenOntoBackground, onCheckedChange = { options = options.copy(flattenOntoBackground = it) })
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_flatten_white), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(stringResource(R.string.export_opaque_background), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = options.fileName.orEmpty(),
            onValueChange = { options = options.copy(fileName = it) },
            label = { Text(stringResource(R.string.export_file_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.export_presets), style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ExportPresets.ALL.filter { it.options.format in availableFormats }.forEach { preset ->
                AssistChip(
                    onClick = { options = preset.options.copy(fileName = options.fileName) },
                    label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        val running = exportState is ExportUiState.Running
        Button(
            onClick = { onExport(options.copy(fileName = options.fileName?.ifBlank { null })) },
            enabled = !running && safe && validArea && (!options.format.requiresAnimation || frameCount > 1),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (running) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_exporting))
            } else {
                val label =
                    if (options.format == ExportFormat.PNG && options.area == ExportArea.ALL_FRAMES) {
                        stringResource(R.string.export_png_frames_zip)
                    } else {
                        stringResource(R.string.export_format_action, options.format.displayName)
                    }
                Text(label)
            }
        }
        if (onExportTimelapse != null) {
            Text(stringResource(R.string.export_timelapse_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.export_timelapse_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportTimelapse, enabled = !running) { Text(stringResource(R.string.export_timelapse_action)) }
                if (onClearTimelapse != null) {
                    TextButton(onClick = onClearTimelapse, enabled = !running) { Text(stringResource(R.string.export_timelapse_clear)) }
                }
            }
        }
        ExportResultPanel(exportState, actions, onDismissResult)
    }
}

@Composable
private fun ExportPreview(bytes: ByteArray?) {
    val bitmap = remember(bytes) { bytes?.let { BitmapPixelBridge.fromEncodedBytes(it) }?.let { BitmapPixelBridge.toBitmap(it) } }
    DisposableEffect(bitmap) { onDispose { bitmap?.recycle() } }
    bitmap?.let {
        Image(
            it.asImageBitmap(),
            stringResource(R.string.export_preview_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
        )
    }
}

@Composable
private fun FormatControls(
    options: ExportOptions,
    onChange: (ExportOptions) -> Unit,
) {
    if (options.format == ExportFormat.JPEG || options.format == ExportFormat.WEBP) {
        LabeledSlider(
            stringResource(R.string.export_quality),
            options.quality.toFloat(),
            1f..100f,
            { onChange(options.copy(quality = it.toInt())) },
        )
    }
    if (options.format in DPI_FORMATS) {
        LabeledSlider(stringResource(R.string.export_dpi), options.dpi.toFloat(), 36f..600f, { onChange(options.copy(dpi = it.toInt())) })
    }
    if (options.format == ExportFormat.PDF) {
        Text(stringResource(R.string.export_pdf_page), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PdfPageSize.entries.forEach { page ->
                FilterChip(
                    selected = options.pdfPageSize == page,
                    onClick = { onChange(options.copy(pdfPageSize = page)) },
                    label = { Text(page.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
    if (options.format == ExportFormat.PSD) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(options.psdUseRle, onCheckedChange = { onChange(options.copy(psdUseRle = it)) })
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.export_rle), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (options.format == ExportFormat.GIF || options.format == ExportFormat.APNG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(options.gifLoop, onCheckedChange = { onChange(options.copy(gifLoop = it)) })
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.export_loop), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (options.format == ExportFormat.MP4) {
        LabeledSlider(
            stringResource(R.string.export_bitrate),
            options.videoBitrate / 1_000_000f,
            1f..30f,
            { onChange(options.copy(videoBitrate = (it * 1_000_000).toInt())) },
        )
    }
}

@Composable
private fun ExportResultPanel(
    state: ExportUiState,
    actions: ExportActions,
    onDismiss: () -> Unit,
) {
    when (state) {
        is ExportUiState.Done -> {
            val result = state.result
            result.warning?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(result.fileName, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        "${result.width}×${result.height} · ${result.sizeLabel}" +
                            if (result.frameCount > 1) " · ${result.frameCount} frames" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { actions.share(result) }) {
                            Icon(Icons.Default.Share, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.export_share))
                        }
                        if (result.format.supportsGallery) {
                            OutlinedButton(onClick = { actions.gallery(result) }) { Text(stringResource(R.string.export_gallery)) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { actions.saveFile(result) }) { Text(stringResource(R.string.export_save_file)) }
                        TextButton(onClick = { actions.open(result) }) { Text(stringResource(R.string.export_open)) }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.export_another)) }
                }
            }
        }
        is ExportUiState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error)
        else -> Unit
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    valueLabel: String = value.toInt().toString(),
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(valueLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value.coerceIn(range.start, range.endInclusive), onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

/** Preview bytes for the export sheet: a scaled PNG of the flattened canvas. */
fun previewBytesFor(buffer: com.artflow.studio.core.pixels.PixelBuffer?): ByteArray? {
    if (buffer == null) return null
    val (width, height) = CanvasOperations.fitInside(buffer.width, buffer.height, 512, 512)
    val scaled = if (width == buffer.width && height == buffer.height) buffer else buffer.scaled(width, height)
    return BitmapPixelBridge.toPngBytes(scaled)
}

/** Human-readable frame timing summary, used by the animation export section. */
fun frameSummary(frames: List<AnimationFrame>): String =
    if (frames.isEmpty()) "No frames" else "${frames.size} frames · ${frames.sumOf { it.durationMs }} ms"

/** Formats that record a print resolution. */
private val DPI_FORMATS =
    setOf(ExportFormat.PNG, ExportFormat.JPEG, ExportFormat.PSD, ExportFormat.PDF, ExportFormat.TIFF, ExportFormat.LAYER_PNGS)
