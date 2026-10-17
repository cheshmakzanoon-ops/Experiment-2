package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.export.ExportArea
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportRegion
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.LayerRaster
import com.artflow.studio.domain.repository.canvas.CanvasRepository

/** An image, document or animation export rendered from the canvas. */
internal suspend fun renderedExport(
    canvasRepository: CanvasRepository,
    exporter: ArtworkExporter,
    projectId: Long,
    name: String,
    resolved: ExportOptions,
): Result<ExportResult> {
    val allFrames =
        resolved.area == ExportArea.ALL_FRAMES ||
            (resolved.format.requiresAnimation && resolved.area != ExportArea.CURRENT_FRAME)
    require(!allFrames || resolved.format.requiresAnimation || resolved.format == ExportFormat.PDF) {
        "For all frames, choose PNG frames, PDF, GIF or MP4"
    }
    val snapshot =
        canvasRepository.exportSnapshot(
            allFrames,
            resolved.includeHiddenLayers,
            resolved.format.needsLayers,
        )
    val region = ExportRegion.resolve(snapshot.frames, snapshot.selection, resolved.area)
    val frames = snapshot.frames.map { ExportRegion.apply(it, snapshot.selection, resolved.area, region) }
    val delays =
        resolved.animationFpsOverride?.let { fps -> List(frames.size) { 1000 / fps.coerceIn(1, 60) } }
            ?: snapshot.delaysMs
    return if (allFrames || resolved.format.requiresAnimation) {
        exporter.exportAnimation(projectId, name, frames, delays, resolved)
    } else {
        val layers =
            snapshot.layers.map { (layer, buffer) ->
                LayerRaster(layer.name, ExportRegion.apply(buffer, snapshot.selection, resolved.area, region), layer)
            }
        exporter.exportStill(projectId, name, frames.first(), layers, resolved, snapshot.hasAdjustmentLayers, snapshot.groups)
    }
}
