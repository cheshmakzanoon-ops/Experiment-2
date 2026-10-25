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
            preserveLayerMasks = resolved.format == ExportFormat.PSD || resolved.format == ExportFormat.PROCREATE,
            animationPlayback = allFrames && resolved.format.requiresAnimation,
            includeMaterials = resolved.format == ExportFormat.MODEL_OBJ,
        )
    val order = snapshot.frameOrder
    require(order.isNotEmpty()) { "No visible animated frames in the playback range" }
    val sourceFrames = order.distinct().associateWith { snapshot.frames[it] }
    val region = ExportRegion.resolve(sourceFrames.values.toList(), snapshot.selection, resolved.area)
    val cropped = sourceFrames.mapValues { (_, pixels) -> ExportRegion.apply(pixels, snapshot.selection, resolved.area, region) }
    val frames = order.map { cropped.getValue(it) }
    val delays =
        resolved.animationFpsOverride?.let { fps -> List(frames.size) { 1000 / fps.coerceIn(1, 60) } }
            ?: order.map { snapshot.delaysMs[it] }
    val result =
        if (allFrames || resolved.format.requiresAnimation) {
            exporter.exportAnimation(projectId, name, frames, delays, resolved)
        } else {
            val layers =
                snapshot.layers.map { (layer, buffer) ->
                    LayerRaster(layer.name, ExportRegion.apply(buffer, snapshot.selection, resolved.area, region), layer)
                }
            // Selection coverage already clips the layer's alpha; applying it to the independent mask
            // too would square feathered selection edges. Only crop the mask to the shared rectangle.
            val layerMasks = snapshot.layerMasks.mapValues { (_, mask) -> region?.let { mask.crop(it) } ?: mask }
            exporter.exportStill(
                projectId,
                name,
                frames.first(),
                layers,
                resolved,
                snapshot.hasAdjustmentLayers,
                snapshot.groups,
                layerMasks,
                snapshot.materialMaps,
            )
        }
    return if (snapshot.hasMaterialLayers && resolved.format != ExportFormat.MODEL_OBJ) {
        result.map { exported ->
            exported.copy(
                warning =
                    listOfNotNull(
                        exported.warning,
                        "This format contains the color artwork. Use ArtFlow artwork to preserve editable material layers, " +
                            "or 3D model to export maps.",
                    ).joinToString(" "),
            )
        }
    } else {
        result
    }
}
