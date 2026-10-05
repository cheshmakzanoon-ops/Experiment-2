package com.artflow.studio.data.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Brings outside pictures, Photoshop and Procreate documents into the open canvas as new layers. */
object LayerImports {
    /** Adds [image] as a new layer, centred and scaled down to fit the canvas if needed; returns its id. */
    suspend fun insertImage(
        repository: CanvasRepository,
        image: PixelBuffer,
    ): Long {
        val size = repository.getCanvasSize()
        val fit = minOf(1f, size.width.toFloat() / image.width, size.height.toFloat() / image.height)
        val scaled =
            if (fit < 1f) {
                withContext(Dispatchers.Default) {
                    image.scaled((image.width * fit).toInt().coerceAtLeast(1), (image.height * fit).toInt().coerceAtLeast(1))
                }
            } else {
                image
            }
        // Decoded pictures are sRGB; a Display P3 canvas stores the same colours in its own space.
        val placed = withContext(Dispatchers.Default) { ColorProfiles.convert(scaled, ColorProfile.SRGB, repository.getColorProfile()) }
        val layer = repository.addLayer(name = "Photo")
        val drawn =
            repository.applyRasterEdit(layer.id, "Insert photo") { target ->
                target.drawInto(placed, (size.width - placed.width) / 2, (size.height - placed.height) / 2)
            }
        check(drawn) { "The photo could not be placed on the new layer" }
        return layer.id
    }

    /**
     * Adds every PSD layer (or the flattened composite) as new layers, with Photoshop folders
     * rebuilt as layer groups (nested as they were) and clipping kept; returns how many pixel
     * layers were added.
     */
    suspend fun importPsd(
        repository: CanvasRepository,
        bytes: ByteArray,
    ): Int {
        val document =
            withContext(Dispatchers.Default) { PsdCodec.read(bytes) }
                ?: error("This file is not a supported PSD document")
        val size = repository.getCanvasSize()
        val profile = repository.getColorProfile()
        val dx = (size.width - document.width) / 2
        val dy = (size.height - document.height) / 2
        val sources =
            document.layers
                .takeIf { layers -> layers.any { it.section == PsdCodec.Section.NONE } }
                ?: listOfNotNull(document.composite?.let { PsdCodec.PsdLayer("Background", it) })
        check(sources.isNotEmpty()) { "The PSD document has no readable layers" }
        // Records are bottom first: a divider opens a folder, its header (above the contents) closes it.
        val open = ArrayDeque<MutableList<Node>>()
        val root = mutableListOf<Node>()
        var added = 0
        sources.forEach { source ->
            when {
                source.section == PsdCodec.Section.GROUP_END -> {
                    open.addLast(mutableListOf())
                }

                source.section.isGroupHeader -> {
                    val members = open.removeLastOrNull() ?: return@forEach
                    (open.lastOrNull() ?: root) += Node.Folder(source, members)
                }

                else -> {
                    val layer = repository.addLayer(name = source.name.ifBlank { "PSD layer" })
                    repository.applyRasterEdit(layer.id, "Import PSD layer") { target ->
                        target.drawInto(ColorProfiles.convert(source.pixels, ColorProfile.SRGB, profile), dx + source.left, dy + source.top)
                    }
                    applyLook(repository, layer.id, source)
                    if (source.isClippingMask) repository.setLayerClippingMask(layer.id, true)
                    (open.lastOrNull() ?: root) += Node.Pixels(layer.id)
                    added++
                }
            }
        }
        // Folders left open by a damaged file keep their layers ungrouped.
        open.forEach { root += it }
        root.forEach { node -> if (node is Node.Folder) group(repository, node) }
        return added
    }

    /** What a Procreate import brought in. */
    class ProcreateResult(
        val layers: Int,
        /** True when the layers would not fit in memory and the flattened image came in instead. */
        val flattened: Boolean,
    )

    /**
     * Rebuilds a Procreate document's layers in the open canvas (made at the document's size):
     * nested groups, opacity, blend modes, visibility, clipping, alpha lock, masks and the
     * background colour. Layers are decoded one at a time; when they would not all fit in memory,
     * the flattened image comes in as a single layer instead.
     */
    suspend fun importProcreate(
        repository: CanvasRepository,
        document: ProcreateReader.Document,
    ): ProcreateResult {
        val profile = repository.getColorProfile()
        // The new canvas's own empty layer goes once the document's layers are in.
        val starting = repository.getAllLayers().filter { !it.isGroup }.map { it.id }
        document.background?.let { repository.setCanvasBackgroundColor(it) }
        val layers = document.layers()
        val bytes = document.width.toLong() * document.height * BYTES_PER_PIXEL
        val flattened = (layers.size + WORKING_COPIES) * bytes > Runtime.getRuntime().maxMemory() / 2
        var added = 0
        if (flattened) {
            val composite = withContext(Dispatchers.Default) { document.composite() }
            if (composite != null) {
                addPixels(repository, "Flattened", composite, profile)
                added = 1
            }
        } else {
            val built = document.nodes.asReversed().mapNotNull { node -> build(repository, document, node, profile) }
            added = layers.size
            built.forEach { if (it is Built.Folder) group(repository, it) }
        }
        if (added >
            0
        ) {
            starting
                .filter { id ->
                    repository.layerPixels(id)?.pixels?.all { it == 0 } != false
                }.forEach { repository.removeLayer(it) }
        }
        return ProcreateResult(added, flattened)
    }

    /** A Procreate node as added: a pixel layer, or a group still to be made from its members. */
    private sealed interface Built {
        class Pixels(
            val id: Long,
        ) : Built

        class Folder(
            val group: ProcreateReader.Group,
            val members: List<Built>,
        ) : Built
    }

    /** Adds [node]'s layers bottom first (as each new layer goes on top). */
    private suspend fun build(
        repository: CanvasRepository,
        document: ProcreateReader.Document,
        node: ProcreateReader.Node,
        profile: ColorProfile,
    ): Built? =
        when (node) {
            is ProcreateReader.Group -> {
                Built.Folder(node, node.children.asReversed().mapNotNull { build(repository, document, it, profile) })
            }

            is ProcreateReader.Layer -> {
                val pixels = withContext(Dispatchers.Default) { document.pixels(node) }
                val id = addPixels(repository, node.name.ifBlank { "Layer" }, pixels, profile)
                if (node.opacity < 1f) repository.setLayerOpacity(id, node.opacity)
                if (node.blendMode != BlendMode.NORMAL) repository.setLayerBlendMode(id, node.blendMode)
                if (!node.visible) repository.setLayerVisibility(id, false)
                if (node.clipped) repository.setLayerClippingMask(id, true)
                if (node.alphaLocked) repository.setLayerAlphaLock(id, true)
                withContext(Dispatchers.Default) { document.mask(node) }?.let { repository.addLayerMask(id, it) }
                Built.Pixels(id)
            }
        }

    private suspend fun addPixels(
        repository: CanvasRepository,
        name: String,
        pixels: PixelBuffer,
        profile: ColorProfile,
    ): Long {
        val layer = repository.addLayer(name = name)
        val placed = withContext(Dispatchers.Default) { ColorProfiles.convert(pixels, ColorProfile.SRGB, profile) }
        check(repository.applyRasterEdit(layer.id, "Import Procreate layer") { target -> target.drawInto(placed, 0, 0) }) {
            "The layer \"$name\" could not be imported"
        }
        return layer.id
    }

    /** Groups a folder's layers, inner folders first; returns the group id, or null when it holds no layers. */
    private suspend fun group(
        repository: CanvasRepository,
        folder: Built.Folder,
    ): Long? {
        val ids =
            folder.members.mapNotNull { member ->
                when (member) {
                    is Built.Pixels -> member.id
                    is Built.Folder -> group(repository, member)
                }
            }
        if (ids.isEmpty()) return null
        val id = repository.groupLayers(ids) ?: return null
        repository.setLayerName(id, folder.group.name.ifBlank { "Group" })
        if (folder.group.opacity < 1f) repository.setLayerOpacity(id, folder.group.opacity)
        if (!folder.group.visible) repository.setLayerVisibility(id, false)
        return id
    }

    private const val BYTES_PER_PIXEL = 4L

    /** Buffers held besides the layers themselves: decoding, conversion and the composite. */
    private const val WORKING_COPIES = 4

    private sealed interface Node {
        data class Pixels(
            val id: Long,
        ) : Node

        data class Folder(
            val header: PsdCodec.PsdLayer,
            val members: List<Node>,
        ) : Node
    }

    /** Groups a folder's layers, inner folders first; returns the group id, or null when it holds no layers. */
    private suspend fun group(
        repository: CanvasRepository,
        folder: Node.Folder,
    ): Long? {
        val ids =
            folder.members.mapNotNull { member ->
                when (member) {
                    is Node.Pixels -> member.id
                    is Node.Folder -> group(repository, member)
                }
            }
        if (ids.isEmpty()) return null
        val id = repository.groupLayers(ids) ?: return null
        repository.setLayerName(id, folder.header.name.ifBlank { "Group" })
        applyLook(repository, id, folder.header)
        return id
    }

    private suspend fun applyLook(
        repository: CanvasRepository,
        id: Long,
        source: PsdCodec.PsdLayer,
    ) {
        if (source.opacity < 255) repository.setLayerOpacity(id, source.opacity / 255f)
        if (source.blendMode != BlendMode.NORMAL) repository.setLayerBlendMode(id, source.blendMode)
        if (!source.isVisible) repository.setLayerVisibility(id, false)
    }
}
