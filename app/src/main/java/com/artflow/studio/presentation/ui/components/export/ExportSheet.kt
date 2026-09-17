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
import androidx.compose.ui.unit.dp
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
        Text("Export", style = MaterialTheme.typography.titleMedium)
        ExportPreview(previewBytes)
        Text("Format", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            availableFormats.forEach { format ->
                FilterChip(
                    selected = options.format == format,
                    onClick = { options = options.copy(format = format) },
                    label = { Text(format.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
        Text("Area", style = MaterialTheme.typography.labelMedium)
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
            "Scale",
            options.scale,
            0.1f..4f,
            { options = options.copy(scale = it, targetWidth = null) },
            "${(options.scale * 100).toInt()}%",
        )
        Text(
            if (safe) {
                "Output before any selection/content crop: $width × $height px"
            } else {
                CanvasOperations.sizeWarning(width, height) ?: "This size is too large"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (safe) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        )
        FormatControls(options, onChange = { options = it })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(options.includeHiddenLayers, onCheckedChange = { options = options.copy(includeHiddenLayers = it) })
            Spacer(Modifier.width(8.dp))
            Text("Include hidden layers", style = MaterialTheme.typography.bodySmall)
        }
        if (options.format != ExportFormat.JPEG && options.format != ExportFormat.MP4) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(options.flattenOntoBackground, onCheckedChange = { options = options.copy(flattenOntoBackground = it) })
                Spacer(Modifier.width(8.dp))
                Text("Flatten onto white", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text("This format uses an opaque white background.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = options.fileName.orEmpty(),
            onValueChange = { options = options.copy(fileName = it) },
            label = { Text("File name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Presets", style = MaterialTheme.typography.labelMedium)
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
                Text("Exporting…")
            } else {
                val label =
                    if (options.format == ExportFormat.PNG && options.area == ExportArea.ALL_FRAMES) {
                        "Export PNG frames (zip)"
                    } else {
                        "Export ${options.format.displayName}"
                    }
                Text(label)
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
            "Export preview",
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
        LabeledSlider("Quality", options.quality.toFloat(), 1f..100f, { onChange(options.copy(quality = it.toInt())) })
    }
    if (options.format in listOf(ExportFormat.PNG, ExportFormat.JPEG, ExportFormat.PSD, ExportFormat.PDF)) {
        LabeledSlider("DPI", options.dpi.toFloat(), 36f..600f, { onChange(options.copy(dpi = it.toInt())) })
    }
    if (options.format == ExportFormat.PDF) {
        Text("PDF page", style = MaterialTheme.typography.labelMedium)
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
            Text("RLE compress layers", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (options.format == ExportFormat.GIF) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(options.gifLoop, onCheckedChange = { onChange(options.copy(gifLoop = it)) })
            Spacer(Modifier.width(8.dp))
            Text("Loop the animation", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (options.format == ExportFormat.MP4) {
        LabeledSlider(
            "Bitrate (Mbps)",
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
                            Text("Share")
                        }
                        if (result.format.supportsGallery) {
                            OutlinedButton(onClick = { actions.gallery(result) }) { Text("Gallery") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { actions.saveFile(result) }) { Text("Save file") }
                        TextButton(onClick = { actions.open(result) }) { Text("Open") }
                    }
                    TextButton(onClick = onDismiss) { Text("Export another") }
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
