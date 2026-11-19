package com.artflow.studio.domain.repository.canvas

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.Layer

/** Immutable destination metadata captured before an importer starts decoding. */
data class LayerImportDestination(
    val width: Int,
    val height: Int,
    val colorProfile: ColorProfile,
    val availableRasterPlanes: Int,
    val availableLayerSlots: Int,
    val canReplaceBlank: Boolean,
) {
    fun canImport(
        rasterPlanes: Int,
        layers: Int,
    ): Boolean = rasterPlanes in 0..availableRasterPlanes && layers in 0..availableLayerSlots

    fun requireCapacity(
        rasterPlanes: Int,
        layers: Int,
    ) {
        check(canImport(rasterPlanes, layers)) { CAPACITY_MESSAGE }
    }

    companion object {
        const val CAPACITY_MESSAGE =
            "This import exceeds this device's artwork memory or layer limit. Reduce layers, masks, frames or canvas size."
    }
}

/**
 * A layer fully prepared for the destination canvas. IDs and parent IDs are local to the plan.
 * Pixels and masks are owned by this plan and must never be mutated after publication.
 */
data class PreparedImportLayer(
    val layer: Layer,
    val pixels: PixelBuffer? = null,
    val mask: PixelBuffer? = null,
)

/** Complete bottom-to-top stack addition; preparation must not mutate the live repository. */
data class PreparedLayerImport(
    val layers: List<PreparedImportLayer>,
    val colorProfile: ColorProfile? = null,
    /** Already converted into the plan's resulting colour profile. */
    val backgroundColor: Int? = null,
    val replaceBlank: Boolean = false,
) {
    val rasterPlanes: Int get() = layers.sumOf { (if (it.pixels != null) 1 else 0) + (if (it.mask != null) 1 else 0) }

    fun validate(destination: LayerImportDestination) {
        check(layers.isNotEmpty() && layers.any { !it.layer.isGroup }) { "The import has no readable pixel layers" }
        destination.requireCapacity(rasterPlanes, layers.size)
        check(!replaceBlank || destination.canReplaceBlank) { "The destination is no longer a new blank artwork" }
        check(colorProfile == null || colorProfile == destination.colorProfile || replaceBlank) {
            "Changing an imported document's profile requires a new blank artwork"
        }
        val byId = layers.associateBy { it.layer.id }
        require(byId.size == layers.size && byId.keys.all { it > 0 }) { "Invalid imported layer identities" }
        val positions = layers.mapIndexed { index, item -> item.layer.id to index }.toMap()
        layers.forEachIndexed { index, item ->
            val layer = item.layer
            require(layer.opacity.isFinite() && layer.opacity in 0f..1f) { "Invalid imported layer opacity" }
            require(layer.strokes.isEmpty() && layer.textContent == null) { "Imports must prepare raster content" }
            require((item.pixels == null) == layer.isGroup) { "Imported pixel layers require prepared pixels" }
            listOfNotNull(item.pixels, item.mask).forEach { pixels ->
                require(pixels.width == destination.width && pixels.height == destination.height) { "Import dimensions changed" }
            }
            layer.parentGroupId?.let { parent ->
                require(byId[parent]?.layer?.isGroup == true && positions.getValue(parent) > index) { "Invalid imported hierarchy" }
            }
        }
    }
}
