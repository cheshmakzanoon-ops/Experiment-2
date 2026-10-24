package com.artflow.studio.core.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.IccProfile
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.LayerBlendContract
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import com.artflow.studio.domain.model.layer.Layer as StackLayer

/**
 * Writes Procreate documents (.procreate): the zip that [ProcreateReader] opens, with the keyed archive
 * ("Document.archive") describing the canvas and its layer tree, and each layer's pixels as 256-pixel tiles
 * in Apple's LZ4 container, stored premultiplied in RGBA order in the upright layout.
 *
 * The file has no thumbnail. [ProcreateReader] calibrates its tile layout against a thumbnail when one is
 * present, and a symmetrical artwork can calibrate to a mirrored layout, so the writer leaves the layout alone.
 *
 * Tree/profile field placement follows the pinned native document encoder and genuine archives. Pixel
 * containers are checked through [ProcreateReader] and independent plist/LZ4 parsing. Exported files have
 * not been opened in Procreate itself; this does not establish complete native document compatibility.
 */
object ProcreateWriter {
    /** One entry of the document's tree: a [Layer] or a [Group]. */
    sealed interface Node

    /**
     * A pixel layer: [pixels] (straight ARGB, the document's size) and its properties. [mask] is the layer's
     * coverage (255 reveals); null when it has none.
     */
    class Layer(
        val name: String,
        val pixels: PixelBuffer,
        val opacity: Float = 1f,
        val visible: Boolean = true,
        val blendMode: BlendMode = BlendMode.NORMAL,
        val clipped: Boolean = false,
        val alphaLocked: Boolean = false,
        val mask: SelectionMask? = null,
        val blendContract: LayerBlendContract = LayerBlendContract.STANDARD,
    ) : Node

    /** A group of layers; [children] are top first, as Procreate lists them. */
    class Group(
        val name: String,
        val opacity: Float = 1f,
        val visible: Boolean = true,
        val children: List<Node>,
    ) : Node

    /**
     * Writes a document of [width] by [height] pixels. [nodes] are the top-level layers and groups, top first;
     * [composite] is the flattened artwork without the background (transparent where nothing is painted), and
     * [background] is the background colour as ARGB, or null when the background is hidden. All colour
     * channels are already encoded in [colorProfile]; this writer tags them without converting them.
     */
    fun write(
        width: Int,
        height: Int,
        nodes: List<Node>,
        composite: PixelBuffer,
        background: Int? = null,
        dpi: Int = DEFAULT_DPI,
        name: String = "",
        colorProfile: ColorProfile = ColorProfile.SRGB,
    ): ByteArray {
        require(width in 1..ProcreateReader.MAX_SIDE && height in 1..ProcreateReader.MAX_SIDE) {
            "This canvas is too large for a Procreate document"
        }
        require(dpi in 1..MAX_DPI) { "Invalid resolution" }
        require(composite.width == width && composite.height == height) { "The composite must match the document size" }
        val builder = Builder(width, height)
        val tree = builder.nodes(nodes, depth = 0)
        val archive = builder.archive
        val compositeUuid = builder.uuid()
        builder.pending += Pending(compositeUuid, composite, null)
        val compositeFields =
            builder.layerFields(
                compositeUuid,
                "Composite",
                opacity = 1f,
                visible = true,
                mode = BlendMode.NORMAL,
                clipped = false,
                alphaLocked = false,
            )
        val compositeLayer = archive.instance(LAYER, compositeFields)
        val colour = backgroundBytes(background)
        val profile =
            IccProfile.forProfile(colorProfile)?.let { icc ->
                archive.instance(
                    "SiColorProfile",
                    mapOf(
                        "SiColorProfileArchiveICCNameKey" to archive.add(colorProfile.label),
                        "SiColorProfileArchiveICCDataKey" to archive.add(icc),
                    ),
                )
            } ?: BinaryPlist.Uid(0)
        val document =
            mapOf(
                "\$class" to archive.classOf(DOCUMENT),
                "size" to archive.add("{$width, $height}"),
                "tileSize" to TILE.toLong(),
                "layers" to archive.array(builder.pixelLayers),
                "unwrappedLayers" to archive.array(tree),
                "composite" to compositeLayer,
                "name" to archive.add(name),
                "backgroundColor" to archive.add(colour),
                "backgroundHidden" to (background == null),
                "colorProfile" to profile,
                "SilicaDocumentArchiveDPIKey" to dpi.toDouble(),
                "orientation" to 1L,
            )
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ARCHIVE))
            zip.write(archive.encode(document))
            zip.closeEntry()
            builder.pending.forEach { writeTiles(zip, it, width, height) }
        }
        return out.toByteArray()
    }

    /**
     * Nests a layer stack into the document's tree, top first at every level. [items] are the stack's pixel
     * layers, bottom first as ArtFlow lists them; [layerOf] gives each item's layer and [groups] are all the
     * stack's groups. Each group becomes [group] (built from its layer and its nodes, top first) around the items
     * inside it, and each item becomes [leaf]. A group is split in two when its items are not contiguous in the
     * stack, as PSD folders are.
     */
    fun <T> nest(
        items: List<T>,
        layerOf: (T) -> StackLayer,
        groups: List<StackLayer>,
        group: (StackLayer, List<Node>) -> Group,
        leaf: (T) -> Node,
    ): List<Node> {
        val byId = groups.associateBy { it.id }
        val frames = arrayListOf(Frame(null))
        for (item in items) {
            val chain = ancestors(layerOf(item), byId)
            var shared = 0
            while (shared < chain.size && shared + 1 < frames.size && frames[shared + 1].layer?.id == chain[shared].id) shared++
            closeFrames(frames, shared + 1, group)
            chain.drop(shared).forEach { frames += Frame(it) }
            frames.last().children += leaf(item)
        }
        closeFrames(frames, 1, group)
        val root = frames.first()
        return root.children.asReversed().toList()
    }

    /** A group being built: its layer (null for the document itself) and its nodes, bottom first. */
    private class Frame(
        val layer: StackLayer?,
    ) {
        val children = ArrayList<Node>()
    }

    private fun closeFrames(
        frames: MutableList<Frame>,
        size: Int,
        group: (StackLayer, List<Node>) -> Group,
    ) {
        while (frames.size > size) {
            val frame = frames.removeAt(frames.lastIndex)
            val layer = checkNotNull(frame.layer) { "The document itself cannot be closed" }
            frames.last().children += group(layer, frame.children.asReversed().toList())
        }
    }

    /** The groups containing [layer], outermost first. */
    private fun ancestors(
        layer: StackLayer,
        groups: Map<Long, StackLayer>,
    ): List<StackLayer> {
        val chain = ArrayDeque<StackLayer>()
        var parent = layer.parentGroupId?.let { groups[it] }
        while (parent != null) {
            val found = parent
            if (chain.any { it.id == found.id }) break
            chain.addFirst(found)
            parent = found.parentGroupId?.let { groups[it] }
        }
        return chain.toList()
    }

    /** Tiles still to write: a layer's pixels, or a mask's coverage. */
    private class Pending(
        val uuid: String,
        val pixels: PixelBuffer?,
        val mask: SelectionMask?,
    )

    /** Builds the keyed archive and collects the tiles, which are written after it. */
    private class Builder(
        private val width: Int,
        private val height: Int,
    ) {
        val archive = KeyedArchiveBuilder()
        val pending = ArrayList<Pending>()

        // Native documents index each pixel-layer object once, separately from the nested tree.
        // Masks and the saved composite are not entries of this index.
        val pixelLayers = ArrayList<BinaryPlist.Uid>()

        fun uuid(): String = UUID.randomUUID().toString().uppercase()

        fun nodes(
            nodes: List<Node>,
            depth: Int,
        ): List<BinaryPlist.Uid> = nodes.map { node(it, depth) }

        private fun node(
            node: Node,
            depth: Int,
        ): BinaryPlist.Uid =
            when (node) {
                is Layer -> layer(node)
                is Group -> group(node, depth)
            }

        private fun layer(layer: Layer): BinaryPlist.Uid {
            require(layer.pixels.width == width && layer.pixels.height == height) {
                "The layer \"${layer.name}\" is not the size of the document"
            }
            val uuid = uuid()
            pending += Pending(uuid, layer.pixels, null)
            val fields =
                layerFields(
                    uuid,
                    layer.name,
                    layer.opacity,
                    layer.visible,
                    layer.blendMode,
                    layer.clipped,
                    layer.alphaLocked,
                    layer.blendContract,
                ).toMutableMap()
            layer.mask?.let { mask ->
                require(mask.width == width && mask.height == height) { "The mask of \"${layer.name}\" is not the size of the document" }
                val maskUuid = uuid()
                pending += Pending(maskUuid, null, mask)
                fields["mask"] = archive.instance(LAYER, layerFields(maskUuid, "Mask", 1f, true, BlendMode.NORMAL, false, false))
            }
            return archive.instance(LAYER, fields).also { pixelLayers += it }
        }

        private fun group(
            group: Group,
            depth: Int,
        ): BinaryPlist.Uid {
            require(depth < ProcreateReader.MAX_NESTING) { "The groups nest more deeply than a Procreate document allows" }
            val children = archive.array(nodes(group.children, depth + 1))
            return archive.instance(
                GROUP,
                mapOf(
                    "name" to archive.add(group.name),
                    "isHidden" to !group.visible,
                    "hidden" to !group.visible,
                    "opacity" to group.opacity.coerceIn(0f, 1f).toDouble(),
                    "children" to children,
                ),
            )
        }

        /** The keyed fields every layer and mask carries: name, UUID, opacity, visibility, blend and flags. */
        fun layerFields(
            uuid: String,
            name: String,
            opacity: Float,
            visible: Boolean,
            mode: BlendMode,
            clipped: Boolean,
            alphaLocked: Boolean,
            blendContract: LayerBlendContract = LayerBlendContract.STANDARD,
        ): Map<String, Any?> {
            val blend = ProcreateLayerBlendModes.encode(mode, blendContract)
            return mapOf(
                "UUID" to archive.add(uuid),
                "name" to archive.add(name),
                "opacity" to opacity.coerceIn(0f, 1f).toDouble(),
                "hidden" to !visible,
                "blend" to blend.getValue("blend"),
                "extendedBlend" to blend.getValue("extendedBlend"),
                "extendedBlend2" to blend.getValue("extendedBlend2"),
                "clipped" to clipped,
                "preserve" to alphaLocked,
            )
        }
    }

    private fun backgroundBytes(background: Int?): ByteArray {
        val rgb = background ?: WHITE
        val buffer = ByteBuffer.allocate(COLOUR_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putFloat(((rgb shr 16) and 0xFF) / 255f)
        buffer.putFloat(((rgb shr 8) and 0xFF) / 255f)
        buffer.putFloat((rgb and 0xFF) / 255f)
        buffer.putFloat(1f)
        return buffer.array()
    }

    private fun writeTiles(
        zip: ZipOutputStream,
        item: Pending,
        width: Int,
        height: Int,
    ) {
        for (row in 0 until ceilDiv(height, TILE)) {
            for (column in 0 until ceilDiv(width, TILE)) {
                val raw = item.pixels?.let { pixelTile(it, column, row) } ?: item.mask?.let { maskTile(it, column, row) }
                if (raw == null) continue
                zip.putNextEntry(ZipEntry("${item.uuid}/$column~$row.lz4"))
                zip.write(appleContainer(raw))
                zip.closeEntry()
            }
        }
    }

    /** A 256 by 256 tile of premultiplied RGBA, or null when every pixel in it is transparent (it is then not stored). */
    private fun pixelTile(
        pixels: PixelBuffer,
        column: Int,
        row: Int,
    ): ByteArray? {
        val left = column * TILE
        val top = row * TILE
        val right = min(pixels.width, left + TILE)
        val bottom = min(pixels.height, top + TILE)
        var tile: ByteArray? = null
        for (y in top until bottom) {
            for (x in left until right) {
                val argb = pixels.pixels[y * pixels.width + x]
                val alpha = argb ushr 24
                if (alpha == 0) continue
                val bytes = tile ?: ByteArray(TILE * TILE * 4).also { tile = it }
                val at = ((y - top) * TILE + (x - left)) * 4
                bytes[at] = premultiplied(argb shr 16, alpha)
                bytes[at + 1] = premultiplied(argb shr 8, alpha)
                bytes[at + 2] = premultiplied(argb, alpha)
                bytes[at + 3] = alpha.toByte()
            }
        }
        return tile
    }

    /**
     * A mask tile as opaque grey (the coverage in every channel), or null when the tile reveals everything, as
     * a missing tile does when read back.
     */
    private fun maskTile(
        mask: SelectionMask,
        column: Int,
        row: Int,
    ): ByteArray? {
        val left = column * TILE
        val top = row * TILE
        val right = min(mask.width, left + TILE)
        val bottom = min(mask.height, top + TILE)
        var tile: ByteArray? = null
        for (y in top until bottom) {
            for (x in left until right) {
                val coverage = mask.coverage[y * mask.width + x].toInt() and 0xFF
                // Full coverage stays transparent here, and a transparent mask pixel reads back as full coverage.
                if (coverage == FULL) continue
                val bytes = tile ?: ByteArray(TILE * TILE * 4).also { tile = it }
                val at = ((y - top) * TILE + (x - left)) * 4
                bytes[at] = coverage.toByte()
                bytes[at + 1] = coverage.toByte()
                bytes[at + 2] = coverage.toByte()
                bytes[at + 3] = FULL.toByte()
            }
        }
        return tile
    }

    private fun premultiplied(
        channel: Int,
        alpha: Int,
    ): Byte = (((channel and 0xFF) * alpha + HALF) / FULL).toByte()

    /**
     * Apple's LZ4 container: one block of LZ4, or a stored block when LZ4 does not make it smaller, then the end
     * marker. The reader accepts both block kinds.
     */
    private fun appleContainer(raw: ByteArray): ByteArray {
        val packed = Lz4.compress(raw)
        val out = ByteArrayOutputStream(min(packed.size, raw.size) + HEADER_BYTES * 2)
        if (packed.size < raw.size) {
            out.write(BLOCK_LZ4)
            littleEndian(out, raw.size)
            littleEndian(out, packed.size)
            out.write(packed)
        } else {
            out.write(BLOCK_STORED)
            littleEndian(out, raw.size)
            out.write(raw)
        }
        out.write(END_MARKER)
        return out.toByteArray()
    }

    private fun littleEndian(
        out: ByteArrayOutputStream,
        value: Int,
    ) {
        for (shift in 0 until 32 step 8) out.write((value ushr shift) and 0xFF)
    }

    private fun ceilDiv(
        value: Int,
        divisor: Int,
    ): Int = (value + divisor - 1) / divisor

    /** The tile side Procreate documents use. */
    const val TILE = 256

    private const val ARCHIVE = "Document.archive"
    private const val DOCUMENT = "SilicaDocument"
    private const val LAYER = "SilicaLayer"
    private const val GROUP = "SilicaGroup"
    private const val DEFAULT_DPI = 132
    private const val MAX_DPI = 10_000
    private const val WHITE = 0xFFFFFF
    private const val COLOUR_BYTES = 16
    private const val FULL = 255
    private const val HALF = 127
    private const val HEADER_BYTES = 12
    private val BLOCK_LZ4 = "bv41".toByteArray(Charsets.ISO_8859_1)
    private val BLOCK_STORED = "bvx-".toByteArray(Charsets.ISO_8859_1)
    private val END_MARKER = "bv4\$".toByteArray(Charsets.ISO_8859_1)
}
