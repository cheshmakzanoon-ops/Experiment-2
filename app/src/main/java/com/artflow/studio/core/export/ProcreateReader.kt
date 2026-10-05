package com.artflow.studio.core.export

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.layer.BlendMode
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reads Procreate documents (.procreate): a zip holding a keyed archive ("Document.archive") that
 * describes the canvas and its layer tree, and each layer's pixels as compressed tiles in a folder
 * named by the layer's UUID ("col~row.chunk" in LZO, ".lz4" in Apple's LZ4 container).
 *
 * The tile grid's orientation and channel order are not described anywhere in the file, and
 * readers disagree, so the document calibrates itself: the flattened image ("composite") is laid
 * out in each of the eight rotations and mirrorings, in RGBA and BGRA, and the one that matches the
 * file's own thumbnail (QuickLook/Thumbnail.png) is used for every layer.
 */
object ProcreateReader {
    const val MAX_SIDE = 16_384

    /** The files of the document's zip, by their full path inside it. */
    interface Files {
        val names: Collection<String>

        fun read(name: String): ByteArray?
    }

    sealed interface Node {
        val name: String
        val opacity: Float
        val visible: Boolean
    }

    class Layer(
        override val name: String,
        override val opacity: Float,
        override val visible: Boolean,
        val blendMode: BlendMode,
        val clipped: Boolean,
        val alphaLocked: Boolean,
        internal val uuid: String?,
        internal val maskUuid: String?,
    ) : Node

    class Group(
        override val name: String,
        override val opacity: Float,
        override val visible: Boolean,
        /** Top first, as Procreate lists them. */
        val children: List<Node>,
    ) : Node

    class Document internal constructor(
        val width: Int,
        val height: Int,
        val dpi: Int,
        val name: String?,
        /** The background colour (opaque ARGB), or null when the background is hidden. */
        val background: Int?,
        /** Top first, as Procreate lists them. */
        val nodes: List<Node>,
        private val tiles: Tiles,
        private val compositeUuid: String?,
    ) {
        /** A layer's pixels (straight ARGB), upright; empty when it has no tiles. */
        fun pixels(layer: Layer): PixelBuffer = tiles.layer(layer.uuid)

        /** The layer's mask as coverage (white reveals; areas with no stored pixels reveal too), or null when it has none. */
        fun mask(layer: Layer): SelectionMask? {
            val uuid = layer.maskUuid ?: return null
            val pixels = tiles.layer(uuid)
            val coverage =
                ByteArray(pixels.pixels.size) { index ->
                    val pixel = pixels.pixels[index]
                    val alpha = pixel ushr 24
                    // The mask's grey over white, so unpainted pixels stay revealed.
                    (((pixel shr 16) and 0xFF) * alpha / 255 + 255 - alpha).toByte()
                }
            return SelectionMask(pixels.width, pixels.height, coverage)
        }

        /** The flattened image as Procreate saved it, or null when the file has none. */
        fun composite(): PixelBuffer? = compositeUuid?.takeIf { tiles.has(it) }?.let { tiles.layer(it) }

        /** Every pixel layer, top first, groups opened. */
        fun layers(): List<Layer> = flatten(nodes)

        private fun flatten(nodes: List<Node>): List<Layer> =
            nodes.flatMap { node ->
                when (node) {
                    is Layer -> listOf(node)
                    is Group -> flatten(node.children)
                }
            }
    }

    fun isProcreate(files: Files): Boolean = ARCHIVE in files.names

    /** Opens the document; [decodeImage] decodes the PNG thumbnail used to calibrate the tiles. */
    fun open(
        files: Files,
        decodeImage: (ByteArray) -> PixelBuffer?,
    ): Document {
        val archive = KeyedArchive(BinaryPlist.read(requireNotNull(files.read(ARCHIVE)) { "This is not a Procreate document" }))
        val document = requireNotNull(archive.root) { "The Procreate document has no description" }
        val (width, height) = size(archive.string(document["size"]))
        val tileSize = number(document["tileSize"])?.toInt() ?: DEFAULT_TILE
        require(tileSize in MIN_TILE..MAX_TILE) { "The Procreate document's tiles are an unexpected size" }
        val composite = archive.string(archive.map(document["composite"])?.get("UUID"))
        val tiles = Tiles(files, width, height, tileSize)
        val nodes = archive.array(document["layers"]).mapNotNull { node(archive, it, 0) }
        val background =
            archive
                .data(document["backgroundColor"])
                ?.takeIf { document["backgroundHidden"] != true }
                ?.let(::colour)
        val thumbnail = files.read(THUMBNAIL)?.let(decodeImage)
        val sample = composite?.takeIf { tiles.has(it) } ?: tiles.largest()
        if (thumbnail != null && sample != null) tiles.calibrate(sample, thumbnail, background ?: WHITE)
        val dpi = number(document["SilicaDocumentArchiveDPIKey"])?.roundToInt()?.takeIf { it in 1..MAX_DPI } ?: DEFAULT_DPI
        return Document(
            tiles.width,
            tiles.height,
            dpi,
            archive.string(document["name"]),
            background,
            nodes,
            tiles,
            composite,
        )
    }

    private fun node(
        archive: KeyedArchive,
        value: Any?,
        depth: Int,
    ): Node? {
        val item = archive.map(value) ?: return null
        val name = archive.string(item["name"]).orEmpty()
        val opacity = (number(item["opacity"]) ?: 1.0).toFloat().coerceIn(0f, 1f)
        val visible = item["hidden"] != true
        if (archive.className(item) == "SilicaGroup" || "children" in item) {
            require(depth < MAX_NESTING) { "The Procreate document's groups are nested too deeply" }
            val children = archive.array(item["children"]).mapNotNull { node(archive, it, depth + 1) }
            return Group(name, opacity, visible, children)
        }
        val mode = number(item["extendedBlend"])?.toInt()?.let(BLEND_MODES::get) ?: number(item["blend"])?.toInt()?.let(BLEND_MODES::get)
        return Layer(
            name = name,
            opacity = opacity,
            visible = visible,
            blendMode = mode ?: BlendMode.NORMAL,
            clipped = item["clipped"] == true,
            alphaLocked = item["preserve"] == true,
            uuid = archive.string(item["UUID"]),
            maskUuid = archive.string(archive.map(item["mask"])?.get("UUID")),
        )
    }

    /** "{width, height}" as Procreate writes the canvas size. */
    private fun size(text: String?): Pair<Int, Int> {
        val numbers = Regex("-?\\d+(\\.\\d+)?").findAll(text.orEmpty()).map { it.value.toDouble().roundToInt() }.toList()
        require(numbers.size == 2) { "The Procreate document has no canvas size" }
        val (width, height) = numbers
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE) { "This Procreate canvas is too large to open" }
        return width to height
    }

    private fun number(value: Any?): Double? = (value as? Number)?.toDouble()

    /** Four little-endian floats, red to alpha, as the background colour is stored. */
    private fun colour(bytes: ByteArray): Int? {
        if (bytes.size < COLOUR_BYTES) return null
        val channels =
            (0 until 3).map { index ->
                val bits =
                    (0 until 4).fold(0) { word, k -> word or ((bytes[index * 4 + k].toInt() and 0xFF) shl (8 * k)) }
                (
                    java.lang.Float
                        .intBitsToFloat(bits)
                        .coerceIn(0f, 1f) * 255f
                ).roundToInt()
            }
        return (0xFF shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    /**
     * The layers' tiles: where each one is, how to decode it, and the layout (rotation, mirroring
     * and channel order) the thumbnail calibrated.
     */
    internal class Tiles(
        private val files: Files,
        private val storedWidth: Int,
        private val storedHeight: Int,
        private val size: Int,
    ) {
        /** Index into [ORIENTATIONS]: quarter turns clockwise, then whether the image is mirrored. */
        private var orientation = 0
        private var bgra = false
        private val byLayer: Map<String, List<Tile>> = index(files.names)

        val width get() = if (turns() % 2 == 0) storedWidth else storedHeight
        val height get() = if (turns() % 2 == 0) storedHeight else storedWidth

        private class Tile(
            val column: Int,
            val row: Int,
            val path: String,
        )

        fun has(uuid: String): Boolean = byLayer[uuid]?.isNotEmpty() == true

        fun largest(): String? = byLayer.maxByOrNull { it.value.size }?.key

        /** The layer's pixels, straight ARGB, in the calibrated layout. */
        fun layer(uuid: String?): PixelBuffer {
            val stored = uuid?.let(::stored)
            val out = PixelBuffer(width, height)
            stored ?: return out
            val swap = bgra
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val raw = stored[storedIndex(x, y)]
                    out.pixels[y * width + x] = straight(if (swap) swapRedBlue(raw) else raw)
                }
            }
            return out
        }

        /** Picks the layout whose image best matches [thumbnail], composited over [background]. */
        fun calibrate(
            uuid: String,
            thumbnail: PixelBuffer,
            background: Int,
        ) {
            val stored = stored(uuid) ?: return
            val grid = Grid.of(stored, storedWidth, storedHeight, background, premultiplied = true)
            val target = Grid.of(thumbnail.pixels, thumbnail.width, thumbnail.height, background, premultiplied = false)
            val aspect = thumbnail.width.toFloat() / thumbnail.height
            var best = Double.MAX_VALUE
            for (candidate in ORIENTATIONS.indices) {
                val turned = ORIENTATIONS[candidate].first % 2 == 1
                val ratio = if (turned) storedHeight.toFloat() / storedWidth else storedWidth.toFloat() / storedHeight
                if (abs(ratio - aspect) / aspect > ASPECT_TOLERANCE) continue
                for (swap in listOf(false, true)) {
                    val error = grid.distance(target, candidate, swap)
                    if (error < best) {
                        best = error
                        orientation = candidate
                        bgra = swap
                    }
                }
            }
        }

        private fun turns() = ORIENTATIONS[orientation].first

        /** Where output pixel ([x], [y]) is in the stored grid. */
        private fun storedIndex(
            x: Int,
            y: Int,
        ): Int {
            val (sx, sy) = locate(x, y, width, height, orientation)
            return sy * storedWidth + if (ORIENTATIONS[orientation].second) storedWidth - 1 - sx else sx
        }

        /** The layer's tiles as stored: premultiplied, channels packed in file order behind alpha. */
        private fun stored(uuid: String): IntArray? {
            val tiles = byLayer[uuid] ?: return null
            val pixels = IntArray(storedWidth * storedHeight)
            tiles.forEach { tile -> files.read(tile.path)?.let { place(pixels, tile, it) } }
            return pixels
        }

        private fun place(
            pixels: IntArray,
            tile: Tile,
            data: ByteArray,
        ) {
            val left = tile.column * size
            val top = tile.row * size
            if (left >= storedWidth || top >= storedHeight) return
            val visibleWidth = minOf(size, storedWidth - left)
            val visibleHeight = minOf(size, storedHeight - top)
            val bytes = decode(tile.path, data, size * size * 4) ?: return
            // Edge tiles are stored either whole or cut to the canvas.
            val stride =
                when (bytes.size) {
                    size * size * 4 -> size
                    visibleWidth * visibleHeight * 4 -> visibleWidth
                    else -> return
                }
            for (y in 0 until visibleHeight) {
                var at = y * stride * 4
                val row = (top + y) * storedWidth + left
                for (x in 0 until visibleWidth) {
                    pixels[row + x] =
                        ((bytes[at + 3].toInt() and 0xFF) shl 24) or ((bytes[at].toInt() and 0xFF) shl 16) or
                        ((bytes[at + 1].toInt() and 0xFF) shl 8) or (bytes[at + 2].toInt() and 0xFF)
                    at += 4
                }
            }
        }

        /** A tile's bytes, or null when they cannot be decoded (the tile is then left empty). */
        private fun decode(
            path: String,
            data: ByteArray,
            size: Int,
        ): ByteArray? =
            try {
                when {
                    Lz4.isApple(data) -> Lz4.decompressApple(data, size)
                    path.endsWith(".lz4") -> ByteArray(size).let { out -> out.copyOf(Lz4.decompress(data, 0, data.size, out, 0)) }
                    path.endsWith(".chunk") -> Lzo1x.decompress(data, size)
                    isZlib(data) -> inflate(data, size)
                    else -> data
                }
            } catch (damaged: IllegalArgumentException) {
                null
            }

        private fun isZlib(data: ByteArray): Boolean =
            data.size > 2 && data[0] == ZLIB && ((data[0].toInt() and 0xFF) * 256 + (data[1].toInt() and 0xFF)) % 31 == 0

        private fun inflate(
            data: ByteArray,
            size: Int,
        ): ByteArray {
            val inflater = Inflater()
            try {
                inflater.setInput(data)
                val out = ByteArray(size)
                val written = inflater.inflate(out)
                return if (written == size) out else out.copyOf(written)
            } catch (damaged: java.util.zip.DataFormatException) {
                throw IllegalArgumentException("The compressed data is damaged", damaged)
            } finally {
                inflater.end()
            }
        }

        private fun index(names: Collection<String>): Map<String, List<Tile>> {
            val result = HashMap<String, MutableList<Tile>>()
            names.forEach { name ->
                val match = TILE_NAME.matchEntire(name) ?: return@forEach
                val (folder, column, row) = match.destructured
                val tile = Tile(column.toIntOrNull() ?: return@forEach, row.toIntOrNull() ?: return@forEach, name)
                result.getOrPut(folder) { mutableListOf() } += tile
            }
            return result
        }
    }

    /**
     * A small grid of average colours over an image, composited over a background, for comparing
     * a stored layout with the thumbnail regardless of their sizes.
     */
    private class Grid(
        val values: DoubleArray,
    ) {
        /** How far this (stored) grid, laid out as [orientation] and optionally channel-swapped, is from [target]. */
        fun distance(
            target: Grid,
            orientation: Int,
            swap: Boolean,
        ): Double {
            var total = 0.0
            val mirrored = ORIENTATIONS[orientation].second
            for (y in 0 until CELLS) {
                for (x in 0 until CELLS) {
                    val (sx, sy) = locate(x, y, CELLS, CELLS, orientation)
                    val source = (sy * CELLS + if (mirrored) CELLS - 1 - sx else sx) * 3
                    total += cell(source, target, (y * CELLS + x) * 3, swap)
                }
            }
            return total
        }

        private fun cell(
            source: Int,
            target: Grid,
            goal: Int,
            swap: Boolean,
        ): Double =
            (0 until 3).sumOf { channel ->
                abs(values[source + if (swap) 2 - channel else channel] - target.values[goal + channel])
            }

        companion object {
            fun of(
                pixels: IntArray,
                width: Int,
                height: Int,
                background: Int,
                premultiplied: Boolean,
            ): Grid {
                val sums = DoubleArray(CELLS * CELLS * 3)
                val counts = IntArray(CELLS * CELLS)
                val step = maxOf(1, maxOf(width, height) / (CELLS * SAMPLES_PER_CELL))
                for (y in 0 until height step step) {
                    val cy = y * CELLS / height
                    for (x in 0 until width step step) {
                        val cell = cy * CELLS + x * CELLS / width
                        val over = over(pixels[y * width + x], background, premultiplied)
                        for (channel in 0 until 3) sums[cell * 3 + channel] += over[channel]
                        counts[cell]++
                    }
                }
                for (cell in counts.indices) {
                    for (channel in 0 until 3) sums[cell * 3 + channel] /= maxOf(1, counts[cell]).toDouble()
                }
                return Grid(sums)
            }

            private fun over(
                pixel: Int,
                background: Int,
                premultiplied: Boolean,
            ): DoubleArray {
                val alpha = (pixel ushr 24) / 255.0
                return DoubleArray(3) { channel ->
                    val shift = 16 - channel * 8
                    val value = ((pixel shr shift) and 0xFF).toDouble()
                    val behind = ((background shr shift) and 0xFF).toDouble()
                    (if (premultiplied) value else value * alpha) + behind * (1 - alpha)
                }
            }
        }
    }

    /**
     * Where pixel ([x], [y]) of an image laid out as [orientation] (size [width] by [height]) comes
     * from in the stored image, before mirroring: each quarter turn is undone in turn.
     */
    private fun locate(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        orientation: Int,
    ): Pair<Int, Int> {
        var px = x
        var py = y
        var currentWidth = width
        var currentHeight = height
        repeat(ORIENTATIONS[orientation].first) {
            // A clockwise quarter turn put stored (sx, sy) at (h - 1 - sy, sx).
            val sx = py
            val sy = currentWidth - 1 - px
            px = sx
            py = sy
            val swap = currentWidth
            currentWidth = currentHeight
            currentHeight = swap
        }
        return px to py
    }

    private fun swapRedBlue(pixel: Int): Int = (pixel and GREEN_ALPHA) or ((pixel shr 16) and 0xFF) or ((pixel and 0xFF) shl 16)

    private fun straight(pixel: Int): Int {
        val alpha = pixel ushr 24
        if (alpha == 0) return 0
        if (alpha == 255) return pixel

        fun channel(shift: Int) = minOf(255, (((pixel shr shift) and 0xFF) * 255 + alpha / 2) / alpha)
        return (alpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** Quarter turns clockwise and mirroring for each of the eight layouts. */
    private val ORIENTATIONS = (0 until 4).flatMap { turns -> listOf(turns to false, turns to true) }

    /** Procreate's blend mode numbers. */
    private val BLEND_MODES =
        mapOf(
            0 to BlendMode.NORMAL,
            1 to BlendMode.MULTIPLY,
            2 to BlendMode.SCREEN,
            3 to BlendMode.ADD,
            4 to BlendMode.LIGHTEN,
            5 to BlendMode.EXCLUSION,
            6 to BlendMode.DIFFERENCE,
            7 to BlendMode.SUBTRACT,
            8 to BlendMode.LINEAR_BURN,
            9 to BlendMode.COLOR_DODGE,
            10 to BlendMode.COLOR_BURN,
            11 to BlendMode.OVERLAY,
            12 to BlendMode.HARD_LIGHT,
            13 to BlendMode.COLOR,
            14 to BlendMode.LUMINOSITY,
            15 to BlendMode.HUE,
            16 to BlendMode.SATURATION,
            17 to BlendMode.SOFT_LIGHT,
            19 to BlendMode.DARKEN,
            20 to BlendMode.HARD_MIX,
            21 to BlendMode.VIVID_LIGHT,
            22 to BlendMode.LINEAR_LIGHT,
            23 to BlendMode.PIN_LIGHT,
            24 to BlendMode.LIGHTER_COLOR,
            25 to BlendMode.DARKER_COLOR,
            26 to BlendMode.DIVIDE,
        )

    private const val ARCHIVE = "Document.archive"
    private const val THUMBNAIL = "QuickLook/Thumbnail.png"
    private val TILE_NAME = Regex("([^/]+)/(\\d+)~(\\d+)(\\.[A-Za-z0-9]+)?")
    private const val DEFAULT_TILE = 256
    private const val MIN_TILE = 16
    private const val MAX_TILE = 4096
    private const val DEFAULT_DPI = 132
    private const val MAX_DPI = 10_000
    private const val MAX_NESTING = 32
    private const val COLOUR_BYTES = 16
    private const val WHITE = -1
    private const val CELLS = 16
    private const val SAMPLES_PER_CELL = 8
    private const val ASPECT_TOLERANCE = 0.1f
    private const val GREEN_ALPHA = 0xFF00FF00.toInt()
    private const val ZLIB: Byte = 0x78
}
