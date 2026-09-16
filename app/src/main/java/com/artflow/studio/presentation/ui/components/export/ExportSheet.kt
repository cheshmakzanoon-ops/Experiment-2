@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.artflow.studio.presentation.ui.components.export

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportPreset
import com.artflow.studio.core.export.ExportPresets
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.export.FitMode
import com.artflow.studio.core.export.PdfPageSize
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.presentation.ui.viewmodel.ExportUiState

/**
 * Export sheet (Phases 38-42).
 *
 * Offers every format the pipeline implements, with live size estimates, a preview of the flattened
 * artwork, and share / save-to-gallery actions once the file exists.
 */
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
    onShare: (ExportResult) -> Unit,
    onSaveToGallery: (ExportResult) -> Unit,
    onView: (ExportResult) -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    var format by remember { mutableStateOf(availableFormats.firstOrNull() ?: ExportFormat.PNG) }
    var area by remember { mutableStateOf(ExportArea.FULL_CANVAS) }
    var scale by remember { mutableStateOf(1f) }
    var quality by remember { mutableStateOf(92) }
    var includeHidden by remember { mutableStateOf(false) }
    var flatten by remember { mutableStateOf(format == ExportFormat.JPEG) }
    var dpi by remember { mutableStateOf(canvasDpi) }
    var fitMode by remember { mutableStateOf(FitMode.STRETCH) }
    var pdfPage by remember { mutableStateOf(PdfPageSize.FIT_CANVAS) }
    var fileName by remember { mutableStateOf("") }
    var videoBitrate by remember { mutableStateOf(8_000_000) }
    var psdRle by remember { mutableStateOf(true) }
    var loopGif by remember { mutableStateOf(true) }

    val targetWidth = (canvasWidth * scale).toInt().coerceAtLeast(1)
    val targetHeight = (canvasHeight * scale).toInt().coerceAtLeast(1)
    val safe = CanvasOperations.isSizeSafe(targetWidth, targetHeight)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Export", style = MaterialTheme.typography.titleMedium)

        previewBytes?.let { bytes ->
            val bitmap = remember(bytes) { BitmapPixelBridge.fromEncodedBytes(bytes) }
            bitmap?.let { buffer ->
                val image = remember(buffer) { BitmapPixelBridge.toBitmap(buffer).asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = "Export preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                )
            }
        }

        Text("Format", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            availableFormats.forEach { entry ->
                FilterChip(
                    selected = format == entry,
                    onClick = {
                        format = entry
                        if (entry == ExportFormat.JPEG) flatten = true
                    },
                    label = { Text(entry.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        if (format.requiresAnimation && frameCount <= 1) {
            Text(
                "This format needs more than one frame. Add frames in the animation panel first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Text("Area", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportArea.entries.forEach { entry ->
                val enabled = !(entry == ExportArea.ALL_FRAMES && frameCount <= 1)
                FilterChip(
                    selected = area == entry,
                    enabled = enabled,
                    onClick = { area = entry },
                    label = { Text(entry.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        LabeledSlider(
            label = "Scale",
            value = scale,
            range = 0.1f..4f,
            onChange = { scale = it },
            valueLabel = "${(scale * 100).toInt()}%"
        )
        Text(
            if (safe) {
                "Output: $targetWidth × $targetHeight px"
            } else {
                CanvasOperations.sizeWarning(targetWidth, targetHeight) ?: "This size is too large"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (safe) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
        )

        if (format == ExportFormat.JPEG || format == ExportFormat.WEBP) {
            LabeledSlider(
                label = "Quality",
                value = quality.toFloat(),
                range = 1f..100f,
                onChange = { quality = it.toInt() },
                valueLabel = quality.toString()
            )
        }

        if (format == ExportFormat.PDF) {
            Text("PDF page", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PdfPageSize.entries.take(4).forEach { page ->
                    FilterChip(
                        selected = pdfPage == page,
                        onClick = { pdfPage = page },
                        label = { Text(page.displayName, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            LabeledSlider(
                label = "Dpi",
                value = dpi.toFloat(),
                range = 36f..600f,
                onChange = { dpi = it.toInt() },
                valueLabel = dpi.toString()
            )
        }

        if (format == ExportFormat.PSD) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = psdRle, onCheckedChange = { psdRle = it })
                Spacer(Modifier.width(8.dp))
                Text("RLE compress layers", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (format.requiresAnimation) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = loopGif, onCheckedChange = { loopGif = it })
                Spacer(Modifier.width(8.dp))
                Text("Loop the animation", style = MaterialTheme.typography.bodySmall)
            }
            if (format == ExportFormat.MP4) {
                LabeledSlider(
                    label = "Bitrate (Mbps)",
                    value = videoBitrate / 1_000_000f,
                    range = 1f..30f,
                    onChange = { videoBitrate = (it * 1_000_000).toInt() },
                    valueLabel = "${(videoBitrate / 1_000_000f).toInt()}"
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = includeHidden, onCheckedChange = { includeHidden = it })
            Spacer(Modifier.width(8.dp))
            Text("Include hidden layers", style = MaterialTheme.typography.bodySmall)
        }
        if (format != ExportFormat.PNG && format != ExportFormat.PSD) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = flatten, onCheckedChange = { flatten = it })
                Spacer(Modifier.width(8.dp))
                Text("Flatten onto the background colour", style = MaterialTheme.typography.bodySmall)
            }
        }

        Text("Size", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FitMode.entries.forEach { mode ->
                FilterChip(
                    selected = fitMode == mode,
                    onClick = { fitMode = mode },
                    label = { Text(mode.displayName, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        OutlinedTextField(
            value = fileName,
            onValueChange = { fileName = it },
            label = { Text("File name (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth()
        )

        Text("Presets", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExportPresets.ALL.take(4).forEach { preset: ExportPreset ->
                AssistChip(
                    onClick = {
                        format = preset.options.format
                        scale = preset.options.scale
                        quality = preset.options.quality
                        flat(preset)?.let { flatten = it }
                    },
                    label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Button(
            onClick = {
                onExport(
                    ExportOptions(
                        format = format,
                        area = area,
                        scale = scale,
                        quality = quality,
                        includeHiddenLayers = includeHidden,
                        flattenOntoBackground = flatten,
                        backgroundColor = 0xFFFFFFFF.toInt(),
                        dpi = dpi,
                        fitMode = fitMode,
                        fileName = fileName.ifBlank { null },
                        gifLoop = loopGif,
                        videoBitrate = videoBitrate,
                        pdfPageSize = pdfPage,
                        psdUseRle = psdRle
                    )
                )
            },
            enabled = exportState !is ExportUiState.Running && safe && (!format.requiresAnimation || frameCount > 1),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (exportState is ExportUiState.Running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Exporting…")
            } else {
                Text("Export ${format.displayName}")
            }
        }

        when (exportState) {
            is ExportUiState.Done -> {
                val result = exportState.result
                Card {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(result.fileName, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "${result.width}×${result.height} · ${result.sizeLabel}" +
                                if (result.frameCount > 1) " · ${result.frameCount} frames" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onShare(result) }) {
                                Icon(Icons.Default.Share, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Share")
                            }
                            OutlinedButton(onClick = { onSaveToGallery(result) }) {
                                Icon(Icons.Default.Star, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Gallery")
                            }
                            TextButton(onClick = { onView(result) }) { Text("Open") }
                        }
                        TextButton(onClick = onDismissResult) { Text("Export another") }
                    }
                }
            }
            is ExportUiState.Failed -> Text(
                text = exportState.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
            else -> Unit
        }
    }
}

/** Presets only carry the fields they care about; returns null when the preset says nothing. */
private fun flat(preset: ExportPreset): Boolean? = when (preset.options.format) {
    ExportFormat.PNG, ExportFormat.PSD -> null
    else -> preset.options.flattenOntoBackground
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    valueLabel: String = value.toInt().toString()
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth()
        )
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
