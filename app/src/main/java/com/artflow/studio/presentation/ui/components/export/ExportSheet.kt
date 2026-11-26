@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.artflow.studio.presentation.ui.components.export

import android.content.Context
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    actions: ExportActions,
    modifier: Modifier = Modifier,
    animationLoop: Boolean = true,
) {
    val onExport = actions.export
    val onDismissResult = actions.dismissResult
    val onExportTimelapse = actions.exportTimelapse
    val onClearTimelapse = actions.clearTimelapse
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val dismissKeyboard = {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }
    var options by remember {
        mutableStateOf(ExportOptions(format = availableFormats.firstOrNull() ?: ExportFormat.PNG, dpi = canvasDpi, gifLoop = animationLoop))
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
        if (options.format.requiresAnimation) {
            Text(stringResource(R.string.animation_export_sequence), style = MaterialTheme.typography.bodySmall)
        }
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
        val includeHiddenLabel = stringResource(R.string.export_include_hidden)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                options.includeHiddenLayers,
                onCheckedChange = { options = options.copy(includeHiddenLayers = it) },
                modifier = Modifier.semantics { contentDescription = includeHiddenLabel },
            )
            Spacer(Modifier.width(8.dp))
            Text(includeHiddenLabel, style = MaterialTheme.typography.bodySmall)
        }
        if (options.format != ExportFormat.JPEG && options.format != ExportFormat.MP4) {
            val flattenWhiteLabel = stringResource(R.string.export_flatten_white)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    options.flattenOntoBackground,
                    onCheckedChange = { options = options.copy(flattenOntoBackground = it) },
                    modifier = Modifier.semantics { contentDescription = flattenWhiteLabel },
                )
                Spacer(Modifier.width(8.dp))
                Text(flattenWhiteLabel, style = MaterialTheme.typography.bodySmall)
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
            onClick = {
                // End filename editing before export changes the sheet's contents and scroll bounds.
                dismissKeyboard()
                onExport(options.copy(fileName = options.fileName?.ifBlank { null }))
            },
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
                OutlinedButton(
                    onClick = {
                        dismissKeyboard()
                        onExportTimelapse()
                    },
                    enabled = !running,
                ) { Text(stringResource(R.string.export_timelapse_action)) }
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
    val bitmap by produceState<android.graphics.Bitmap?>(null, bytes) {
        value = null
        var decoded: android.graphics.Bitmap? = null
        var published = false
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                decoded = bytes?.let { BitmapPixelBridge.fromEncodedBytes(it) }?.let { BitmapPixelBridge.toBitmap(it) }
            }
            value = decoded
            published = true
            // ImageBitmap and the platform display list can retain a published bitmap after
            // this producer is replaced or disposed. Let those references govern its lifetime;
            // only a decode that never reached the UI can be recycled here.
        } finally {
            if (!published) decoded?.recycle()
        }
    }
    // Reserve both children from sheet entry. A late decode, a different image aspect ratio,
    // or a cleared preview must not move controls or a result the user has already scrolled to.
    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(
                current.asImageBitmap(),
                stringResource(R.string.export_preview_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Null also covers an unavailable source or a failed decode; it is not evidence
            // that more work is pending, so do not leave an indefinite progress indicator.
            Text(
                stringResource(R.string.export_preview_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(stringResource(R.string.export_preview_description), style = MaterialTheme.typography.labelSmall)
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
        Text(stringResource(R.string.export_psd_masks), style = MaterialTheme.typography.bodySmall)
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
fun previewBytesFor(buffer: com.artflow.studio.core.pixels.PixelBuffer?): ByteArray? = buffer?.let { ArtworkPreview.encode(it) }

/** Human-readable frame timing summary, used by the animation export section. */
fun frameSummary(frames: List<AnimationFrame>): String =
    if (frames.isEmpty()) "No frames" else "${frames.size} frames · ${frames.sumOf { it.durationMs }} ms"

/** Formats that record a print resolution. */
private val DPI_FORMATS =
    setOf(ExportFormat.PNG, ExportFormat.JPEG, ExportFormat.PSD, ExportFormat.PDF, ExportFormat.TIFF, ExportFormat.LAYER_PNGS)
