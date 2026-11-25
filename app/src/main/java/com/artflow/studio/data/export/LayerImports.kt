package com.artflow.studio.data.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
import com.artflow.studio.core.export.ProcreateReader
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.export.PsdFolders
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.LayerImportDestination
import com.artflow.studio.domain.repository.canvas.PreparedImportLayer
import com.artflow.studio.domain.repository.canvas.PreparedLayerImport
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Outside pictures are prepared completely before one project-bound, undoable publication. */
object LayerImports {
    suspend fun insertImage(
        repository: CanvasRepository,
        image: PixelBuffer,
        isPrivate: Boolean = false,
    ): Long = insertImage(repository, isPrivate) { image }

    /** Captures the destination before loading pixels; neither overload takes ownership of the source. */
    suspend fun insertImage(
        repository: CanvasRepository,
        isPrivate: Boolean = false,
        decode: suspend () -> PixelBuffer,
    ): Long =
        repository
            .importLayers { destination ->
                destination.requireCapacity(1, 1)
                val image = decode()
                coroutineContext.ensureActive()
                val plane = destination.width.toLong() * destination.height * BYTES_PER_PIXEL
                check(image.pixels.size.toLong() * BYTES_PER_PIXEL + plane * WORKING_COPIES <= importBudget()) { MEMORY_MESSAGE }
                val fit = minOf(1f, destination.width.toFloat() / image.width, destination.height.toFloat() / image.height)
                val scaled =
                    if (fit < 1f) {
                        val width = (image.width * fit).toInt().coerceAtLeast(1)
                        val height = (image.height * fit).toInt().coerceAtLeast(1)
                        image.scaled(width, height)
                    } else {
                        image
                    }
                val pixels =
                    place(
                        destination,
                        scaled,
                        ColorProfile.SRGB,
                        (destination.width - scaled.width) / 2,
                        (destination.height - scaled.height) / 2,
                    )
                val layer = Layer(1, if (isPrivate) "Private photo" else "Photo", 0, isPrivate = isPrivate)
                PreparedLayerImport(listOf(PreparedImportLayer(layer, pixels)))
            }.single()
            .id

    /** PSD decoding and all destination raster/mask construction happen before publication. */
    suspend fun importPsd(
        repository: CanvasRepository,
        bytes: ByteArray,
    ): Int = importPsd(repository) { bytes }

    /** File-provider reads belong to the same destination-bound preparation as PSD decoding. */
    suspend fun importPsd(
        repository: CanvasRepository,
        read: suspend () -> ByteArray,
    ): Int = importPsdWithReport(repository, read).layers

    class PsdResult(
        val layers: Int,
        val sourceInfo: PsdCodec.ReadInfo,
    )

    /** Color/auxiliary limitations are reported only after the atomic import actually publishes. */
    suspend fun importPsdWithReport(
        repository: CanvasRepository,
        read: suspend () -> ByteArray,
    ): PsdResult {
        var sourceInfo: PsdCodec.ReadInfo? = null
        val imported =
            repository
                .importLayers { destination ->
                    val bytes = read()
                    coroutineContext.ensureActive()
                    val info = PsdCodec.inspect(bytes) ?: error("This file is not a supported PSD document")
                    sourceInfo = info
                    check(info.requiredMemoryBytes <= importBudget()) { MEMORY_MESSAGE }
                    val document = PsdCodec.read(bytes, importBudget()) ?: error("This file is not a supported PSD document")
                    val sources =
                        document.layers.takeIf { layers -> layers.any { it.section == PsdCodec.Section.NONE } }
                            ?: listOfNotNull(document.composite?.let { PsdCodec.PsdLayer("Background", it) })
                    check(sources.isNotEmpty()) { "The PSD document has no readable layers" }
                    val planes =
                        sources.count { it.section == PsdCodec.Section.NONE } +
                            sources.count { it.section != PsdCodec.Section.GROUP_END && it.mask != null }
                    destination.requireCapacity(planes, sources.count { it.section != PsdCodec.Section.GROUP_END })
                    val stagedBytes = destination.width.toLong() * destination.height * BYTES_PER_PIXEL * (planes + WORKING_COPIES)
                    check(info.requiredMemoryBytes <= importBudget() - stagedBytes) { MEMORY_MESSAGE }
                    val builder = ImportBuilder(destination)
                    val dx = (destination.width - document.width) / 2
                    val dy = (destination.height - document.height) / 2
                    psdNodes(sources).forEach { builder.psd(it, null, dx, dy) }
                    PreparedLayerImport(builder.layers)
                }.count { !it.isGroup }
        return PsdResult(imported, checkNotNull(sourceInfo))
    }

    class ProcreateResult(
        val layers: Int,
        val flattened: Boolean,
        val hasUnsupportedColorProfile: Boolean,
    )

    /** A new artwork can adopt its source profile; insertion converts into the existing profile. */
    suspend fun importProcreate(
        repository: CanvasRepository,
        document: ProcreateReader.Document,
        preserveDocumentProfile: Boolean = false,
        rasterBudgetBytes: Long = Runtime.getRuntime().maxMemory() / 2,
    ): ProcreateResult {
        require(rasterBudgetBytes >= 0) { "The import memory budget cannot be negative" }
        var flattened = false
        val imported =
            repository.importLayers { destination ->
                val sources = document.layers()
                val planes = sources.size + sources.count { it.maskUuid != null }
                val bytes =
                    maxOf(document.width.toLong() * document.height, destination.width.toLong() * destination.height) * BYTES_PER_PIXEL
                val budget = minOf(rasterBudgetBytes, importBudget())
                flattened = sources.isEmpty() ||
                    (planes + WORKING_COPIES) * bytes > budget ||
                    !destination.canImport(planes, countNodes(document.nodes))
                if (flattened) destination.requireCapacity(1, 1)
                val composite =
                    if (flattened) {
                        check(bytes * WORKING_COPIES <= importBudget()) { MEMORY_MESSAGE }
                        document.composite()
                            ?: error("This Procreate document cannot be imported as layers and has no flattened image to import")
                    } else {
                        null
                    }
                check(!preserveDocumentProfile || destination.canReplaceBlank) {
                    "Preserving an imported document's profile requires a new blank artwork"
                }
                val profile = if (preserveDocumentProfile) document.colorProfile else destination.colorProfile
                val builder = ImportBuilder(destination.copy(colorProfile = profile))
                if (composite != null) {
                    builder.pixels(Layer(1, "Flattened", 0), composite, document.colorProfile)
                } else {
                    document.nodes.asReversed().forEach { builder.procreate(document, it, null) }
                }
                val background = if (preserveDocumentProfile) document.background ?: 0 else document.background
                PreparedLayerImport(
                    builder.layers,
                    colorProfile = profile,
                    backgroundColor = background?.let { ColorProfiles.convert(it, document.colorProfile, profile) },
                    replaceBlank = destination.canReplaceBlank,
                )
            }
        return ProcreateResult(imported.count { !it.isGroup }, flattened, document.hasUnsupportedColorProfile)
    }

    private fun countNodes(nodes: List<ProcreateReader.Node>): Int =
        nodes.sumOf { 1 + if (it is ProcreateReader.Group) countNodes(it.children) else 0 }

    private sealed interface PsdNode {
        class Pixels(
            val source: PsdCodec.PsdLayer,
        ) : PsdNode

        class Folder(
            val source: PsdCodec.PsdLayer,
            val children: List<PsdNode>,
        ) : PsdNode
    }

    private fun psdNodes(sources: List<PsdCodec.PsdLayer>): List<PsdNode> {
        val open = ArrayDeque<MutableList<PsdNode>>()
        val root = mutableListOf<PsdNode>()
        sources.forEach { source ->
            when {
                source.section == PsdCodec.Section.GROUP_END -> open.addLast(mutableListOf())
                source.section.isGroupHeader -> {
                    val children = open.removeLastOrNull() ?: return@forEach
                    (open.lastOrNull() ?: root) += PsdNode.Folder(source, children)
                }
                else -> (open.lastOrNull() ?: root) += PsdNode.Pixels(source)
            }
        }
        open.forEach { root += it }
        return root
    }

    private class ImportBuilder(
        val destination: LayerImportDestination,
    ) {
        val layers = mutableListOf<PreparedImportLayer>()
        private var nextId = 1L

        suspend fun psd(
            node: PsdNode,
            parent: Long?,
            offsetX: Int,
            offsetY: Int,
        ) {
            coroutineContext.ensureActive()
            val source =
                when (node) {
                    is PsdNode.Pixels -> node.source
                    is PsdNode.Folder -> node.source
                }
            val id = nextId++
            if (node is PsdNode.Folder) {
                if (node.children.isEmpty()) return
                node.children.forEach { psd(it, id, offsetX, offsetY) }
            }
            val model =
                Layer(
                    id,
                    source.name.ifBlank { if (node is PsdNode.Folder) "Group" else "PSD layer" },
                    layers.size,
                    opacity = source.opacity / 255f,
                    isVisible = source.isVisible,
                    blendMode = PsdFolders.importedBlend(source.blendMode),
                    isGroup = node is PsdNode.Folder,
                    isClippingMask = source.isClippingMask,
                    parentGroupId = parent,
                    maskEnabled = source.mask?.isEnabled ?: true,
                )
            val raster =
                if (node is PsdNode.Pixels) {
                    place(
                        destination,
                        source.pixels,
                        ColorProfile.SRGB,
                        offsetX + source.left,
                        offsetY + source.top,
                    )
                } else {
                    null
                }
            val mask =
                source.mask?.let {
                    SelectionMask(
                        destination.width,
                        destination.height,
                        it.coverageFor(destination.width, destination.height, offsetX, offsetY),
                    ).toMaskBitmap()
                }
            layers += PreparedImportLayer(model, raster, mask)
        }

        suspend fun procreate(
            document: ProcreateReader.Document,
            node: ProcreateReader.Node,
            parent: Long?,
        ) {
            coroutineContext.ensureActive()
            val id = nextId++
            when (node) {
                is ProcreateReader.Group -> {
                    if (node.children.isEmpty()) return
                    node.children.asReversed().forEach { procreate(document, it, id) }
                    val model =
                        Layer(
                            id,
                            node.name.ifBlank { "Group" },
                            layers.size,
                            opacity = node.opacity,
                            isVisible = node.visible,
                            blendMode = node.blendMode,
                            isGroup = true,
                            parentGroupId = parent,
                        )
                    layers += PreparedImportLayer(model)
                }
                is ProcreateReader.Layer -> {
                    val model =
                        Layer(
                            id,
                            node.name.ifBlank { "Layer" },
                            layers.size,
                            opacity = node.opacity,
                            isVisible = node.visible,
                            blendMode = node.blendMode,
                            blendContract = node.blendContract,
                            isAlphaLocked = node.alphaLocked,
                            isClippingMask = node.clipped,
                            parentGroupId = parent,
                            maskEnabled = node.maskEnabled,
                        )
                    val raster = place(destination, document.pixels(node), document.colorProfile, 0, 0)
                    val mask =
                        document.mask(node)?.let {
                            check(
                                it.width == destination.width && it.height == destination.height,
                            ) { "The imported mask dimensions do not match the artwork" }
                            it.toMaskBitmap()
                        }
                    // A present tile that cannot be decoded would become a silent hole in the painting.
                    check(document.damagedTileCount == 0) {
                        "This Procreate file has ${document.damagedTileCount} damaged tile(s) in \"${node.name}\" or an earlier layer, " +
                            "so it was not imported. Re-save it in Procreate and try again."
                    }
                    layers += PreparedImportLayer(model, raster, mask)
                }
            }
        }

        suspend fun pixels(
            layer: Layer,
            source: PixelBuffer,
            sourceProfile: ColorProfile,
        ) {
            coroutineContext.ensureActive()
            layers += PreparedImportLayer(layer, place(destination, source, sourceProfile, 0, 0))
        }
    }

    private fun place(
        destination: LayerImportDestination,
        source: PixelBuffer,
        profile: ColorProfile,
        x: Int,
        y: Int,
    ): PixelBuffer =
        PixelBuffer(destination.width, destination.height).also {
            it.drawInto(ColorProfiles.convert(source, profile, destination.colorProfile), x, y)
        }

    private fun importBudget(): Long = Runtime.getRuntime().maxMemory() / 3

    private const val BYTES_PER_PIXEL = 4L
    private const val WORKING_COPIES = 4
    private const val MEMORY_MESSAGE = "This import needs more memory than this device provides. Reduce its dimensions or layers."
}
