package com.artflow.studio.core.export

import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Photoshop document support (Phase 39).
 *
 * A from-scratch PSD reader/writer for 8-bit RGB layered documents — the subset every other
 * application actually produces and consumes. Written in pure Kotlin (no `android.graphics`) so
 * the round-trip is verified by unit tests instead of by opening files in Photoshop.
 *
 * Supported on write: one layer per art layer with RGBA channels, per-layer opacity, visibility,
 * blend mode, layer names, independent layer/folder masks, RLE (PackBits) compression and a
 * correctly flattened composite. Disabled masks retain their editable coverage.
 * Supported on read: raw, RLE and ZIP-compressed channel data, 8- and 16-bit depth (16-bit is
 * downsampled to 8), grayscale/RGB/CMYK colour modes, layer groups (flattened into their layers),
 * and the composite image data when layers are missing.
 */
object PsdCodec {
    const val SIGNATURE = "8BPS"
    private const val VERSION = 1
    private const val COLOR_MODE_RGB = 3
    private const val COLOR_MODE_GRAYSCALE = 1
    private const val COLOR_MODE_CMYK = 4
    private const val DEPTH_8 = 8
    private const val DEPTH_16 = 16

    /** The most channels a PSD may have (the format's limit), which bounds every per-channel allocation. */
    private const val MAX_CHANNELS = 56

    private const val COMPRESSION_RAW = 0
    private const val COMPRESSION_RLE = 1
    private const val COMPRESSION_ZIP = 2
    private const val COMPRESSION_ZIP_PREDICTION = 3

    private const val CHANNEL_RED = 0
    private const val CHANNEL_GREEN = 1
    private const val CHANNEL_BLUE = 2
    private const val CHANNEL_ALPHA = -1
    private const val CHANNEL_USER_MASK = -2
    private const val LSCT_LENGTH = 12
    private const val MASK_HEADER_LENGTH = 18
    private const val MASK_RELATIVE = 0x01
    private const val MASK_DISABLED = 0x02
    private const val MASK_INVERTED = 0x04
    private const val PASS_THROUGH_KEY = "pass"

    /** A layer to be written to (or read from) a PSD file. */
    data class PsdLayer(
        val name: String,
        val pixels: PixelBuffer,
        val opacity: Int = 255,
        val isVisible: Boolean = true,
        val blendMode: BlendMode = BlendMode.NORMAL,
        /** Position of the layer inside the document canvas. */
        val left: Int = 0,
        val top: Int = 0,
        val isClippingMask: Boolean = false,
        /** Folder structure; folder records carry no pixels. */
        val section: Section = Section.NONE,
        /** The independent mask in document coordinates; the pixels are not pre-masked. */
        val mask: PsdMask? = null,
    ) {
        companion object {
            private val NO_PIXELS = PixelBuffer(1, 1)

            /** The hidden divider below a folder's contents (records are stored bottom first). */
            fun groupEnd() = PsdLayer(GROUP_END_NAME, NO_PIXELS, section = Section.GROUP_END)

            /** A folder's own record, above its contents: its name, opacity, visibility and blend. */
            fun groupHeader(
                name: String,
                opacity: Int = 255,
                isVisible: Boolean = true,
                blendMode: BlendMode = BlendMode.PASS_THROUGH,
            ) = PsdLayer(name, NO_PIXELS, opacity, isVisible, blendMode, section = Section.GROUP_OPEN)
        }
    }

    /**
     * Photoshop layer folders: the records between a [GROUP_END] divider and the next folder
     * header above it are inside that folder; folders nest.
     */
    enum class Section(
        val code: Int,
    ) {
        NONE(0),
        GROUP_OPEN(1),
        GROUP_CLOSED(2),
        GROUP_END(3),
        ;

        val isGroupHeader: Boolean get() = this == GROUP_OPEN || this == GROUP_CLOSED

        companion object {
            fun of(code: Int): Section = entries.firstOrNull { it.code == code } ?: NONE
        }
    }

    const val GROUP_END_NAME = "</Layer group>"

    /**
     * A layer mask in document coordinates. [coverage] holds one 0..255 value
     * per pixel of its rectangle (255 reveals); pixels outside the rectangle take [outside].
     */
    data class PsdMask(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val coverage: ByteArray,
        val outside: Int,
        val isEnabled: Boolean = true,
    ) {
        init {
            require(width >= 0 && height >= 0 && width <= 8192 && height <= 8192) { "Invalid PSD mask dimensions" }
            require(width.toLong() * height <= 40_000_000L && coverage.size.toLong() == width.toLong() * height) {
                "PSD mask coverage must match its bounded rectangle"
            }
            require(outside in 0..255) { "Invalid PSD mask outside coverage" }
            require(left.toLong() + width <= Int.MAX_VALUE && top.toLong() + height <= Int.MAX_VALUE) {
                "PSD mask rectangle overflows"
            }
        }

        /** The mask laid over a canvas, with the document's top-left corner at ([offsetX], [offsetY]). */
        fun coverageFor(
            canvasWidth: Int,
            canvasHeight: Int,
            offsetX: Int,
            offsetY: Int,
        ): ByteArray {
            require(CanvasOperations.isSizeSafe(canvasWidth, canvasHeight)) { "Invalid mask canvas size" }
            val out = ByteArray(canvasWidth * canvasHeight) { outside.toByte() }
            for (y in 0 until height) {
                val targetY = offsetY.toLong() + top + y
                if (targetY !in 0L until canvasHeight.toLong()) continue
                for (x in 0 until width) {
                    val targetX = offsetX.toLong() + left + x
                    if (targetX !in 0L until canvasWidth.toLong()) continue
                    out[(targetY * canvasWidth + targetX).toInt()] = coverage[y * width + x]
                }
            }
            return out
        }
    }

    /** The fixed part of a layer mask record: its rectangle, default value and flags. */
    private data class MaskHeader(
        val top: Int,
        val left: Int,
        val bottom: Int,
        val right: Int,
        val defaultValue: Int,
        val flags: Int,
    )

    /** A parsed PSD document. */
    data class PsdDocument(
        val width: Int,
        val height: Int,
        val layers: List<PsdLayer>,
        /** Flattened image as stored in the file's image data section. */
        val composite: PixelBuffer?,
        val dpi: Int = 72,
    )

    // -----------------------------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------------------------

    /**
     * Serialises [layers] into a layered PSD.
     *
     * @param composite the flattened artwork. Written into the image data section so applications
     *   that only read the composite (and thumbnails in file browsers) show the correct image.
     */
    fun write(
        width: Int,
        height: Int,
        layers: List<PsdLayer>,
        composite: PixelBuffer,
        dpi: Int = 72,
        useRle: Boolean = true,
    ): ByteArray {
        require(width in 1..30_000 && height in 1..30_000) { "Invalid PSD dimensions" }
        require(layers.size <= 1024) { "Too many PSD layers" }
        require(dpi in 1..32_767) { "Invalid PSD resolution" }
        val storedLayers = layers.ifEmpty { listOf(PsdLayer("Artwork", composite)) }
        val pixels =
            storedLayers.sumOf {
                it.pixels.pixels.size
                    .toLong() + (it.mask?.coverage?.size ?: 0)
            } + width.toLong() * height
        require(pixels * 16L <= Runtime.getRuntime().maxMemory() / 2) { "PSD exceeds the export memory budget" }
        require(composite.width == width && composite.height == height) {
            "Composite must match the document size"
        }

        val out = ByteArrayOutputStream(width * height * 4 + 65536)

        // --- File header -------------------------------------------------------------------
        out.writeAscii(SIGNATURE)
        out.writeShort(VERSION)
        out.writeZeros(6)
        // The fourth merged channel is transparency, identified by a negative layer count.
        out.writeShort(4)
        out.writeInt(height)
        out.writeInt(width)
        out.writeShort(DEPTH_8)
        out.writeShort(COLOR_MODE_RGB)

        // --- Colour mode data --------------------------------------------------------------
        out.writeInt(0)

        // --- Image resources (resolution only) ---------------------------------------------
        val resources = ByteArrayOutputStream()
        resources.writeAscii("8BIM")
        resources.writeShort(1005) // ResolutionInfo
        resources.writeShort(0) // empty pascal name, padded
        resources.writeInt(16)
        resources.writeInt((dpi.toFloat() * 65536f).toInt()) // horizontal, 16.16 fixed
        resources.writeShort(1) // pixels per inch
        resources.writeShort(1)
        resources.writeInt((dpi.toFloat() * 65536f).toInt()) // vertical
        resources.writeShort(1)
        resources.writeShort(1)
        val resourceBytes = resources.toByteArray()
        out.writeInt(resourceBytes.size)
        out.write(resourceBytes)

        // --- Layer and mask information ----------------------------------------------------
        val layerSection = ByteArrayOutputStream()
        val layerInfo = ByteArrayOutputStream()

        layerInfo.writeShort(-storedLayers.size)
        storedLayers.forEach { layer ->
            writeLayerRecord(layerInfo, layer, useRle)
        }
        val channelData = ByteArrayOutputStream()
        storedLayers.forEach { layer ->
            channelData.write(layerChannelData(layer, useRle))
        }

        val layerInfoBytes = layerInfo.toByteArray()
        val channelDataBytes = channelData.toByteArray()
        val infoLength = layerInfoBytes.size + channelDataBytes.size
        layerSection.writeInt(infoLength + infoLength % 2)
        layerSection.write(layerInfoBytes)
        layerSection.write(channelDataBytes)
        if (infoLength % 2 != 0) layerSection.write(0)
        layerSection.writeInt(0) // global layer mask info length

        val layerSectionBytes = layerSection.toByteArray()
        out.writeInt(layerSectionBytes.size)
        out.write(layerSectionBytes)

        // --- Image data (flattened composite) ----------------------------------------------
        writeImageData(out, composite, useRle)

        return out.toByteArray()
    }

    private fun writeLayerRecord(
        out: ByteArrayOutputStream,
        layer: PsdLayer,
        useRle: Boolean,
    ) {
        val pixels = layer.pixels
        val folder = layer.section != Section.NONE
        val top = if (folder) 0 else layer.top
        val left = if (folder) 0 else layer.left
        val bottom = if (folder) 0 else top + pixels.height
        val right = if (folder) 0 else left + pixels.width

        out.writeInt(top)
        out.writeInt(left)
        out.writeInt(bottom)
        out.writeInt(right)

        val channelIds = layerChannelIds(layer)
        out.writeShort(channelIds.size)

        // Channel data lengths are needed before the data itself, so compute them first.
        val channelPayloads =
            channelIds.map { channelId ->
                encodeLayerPlane(layer, channelId, useRle)
            }
        channelIds.forEachIndexed { index, channelId ->
            out.writeShort(channelId)
            out.writeInt(channelPayloads[index].size + 2) // + compression flag
        }

        out.writeAscii("8BIM")
        out.writeAscii(blendModeKey(layer.blendMode))
        out.write(layer.opacity.coerceIn(0, 255))
        out.write(if (layer.isClippingMask) 1 else 0) // clipping
        out.write(if (layer.isVisible) 0 else 2) // flags: bit 1 = hidden
        out.write(0) // filler

        // The user mask owns a separate rectangle and channel (-2), not the transparency channel.
        val extra = ByteArrayOutputStream()
        writeMaskRecord(extra, layer.mask)
        extra.writeInt(0) // blending ranges length
        val nameBytes = layer.name.toByteArray(Charsets.US_ASCII)
        val truncatedName = nameBytes.copyOf(min(nameBytes.size, 255))
        val nameLength = truncatedName.size + 1
        val paddedNameLength = (nameLength + 3) / 4 * 4
        extra.write(truncatedName.size)
        extra.write(truncatedName)
        repeat(paddedNameLength - nameLength) { extra.write(0) }
        val unicode = layer.name.take(4096).toByteArray(Charsets.UTF_16BE)
        extra.writeAscii("8BIM")
        extra.writeAscii("luni")
        extra.writeInt(4 + unicode.size)
        extra.writeInt(unicode.size / 2)
        extra.write(unicode)
        if (folder) {
            extra.writeAscii("8BIM")
            extra.writeAscii("lsct")
            extra.writeInt(LSCT_LENGTH)
            extra.writeInt(layer.section.code)
            extra.writeAscii("8BIM")
            extra.writeAscii(if (layer.blendMode == BlendMode.PASS_THROUGH) PASS_THROUGH_KEY else blendModeKey(layer.blendMode))
        }
        val extraBytes = extra.toByteArray()
        out.writeInt(extraBytes.size)
        out.write(extraBytes)
    }

    private fun layerChannelData(
        layer: PsdLayer,
        useRle: Boolean,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        layerChannelIds(layer).forEach { channelId ->
            val emptyFolderPlane = layer.section != Section.NONE && channelId != CHANNEL_USER_MASK
            out.writeShort(if (useRle && !emptyFolderPlane) COMPRESSION_RLE else COMPRESSION_RAW)
            out.write(encodeLayerPlane(layer, channelId, useRle))
        }
        return out.toByteArray()
    }

    private fun layerChannelIds(layer: PsdLayer): IntArray =
        if (layer.mask != null) {
            intArrayOf(CHANNEL_RED, CHANNEL_GREEN, CHANNEL_BLUE, CHANNEL_ALPHA, CHANNEL_USER_MASK)
        } else {
            intArrayOf(CHANNEL_RED, CHANNEL_GREEN, CHANNEL_BLUE, CHANNEL_ALPHA)
        }

    private fun encodeLayerPlane(
        layer: PsdLayer,
        channelId: Int,
        useRle: Boolean,
    ): ByteArray {
        if (channelId == CHANNEL_USER_MASK) {
            val mask = requireNotNull(layer.mask)
            return if (useRle) encodeRle(mask.coverage, mask.width, mask.height) else mask.coverage
        }
        if (layer.section != Section.NONE) return ByteArray(0)
        val pixels = layer.pixels
        val plane = extractChannel(pixels, channelId)
        return if (useRle) encodeRle(plane, pixels.width, pixels.height) else rawBytes(plane)
    }

    /** Adobe's 20-byte user-mask record, with an absolute rectangle and two padding bytes. */
    private fun writeMaskRecord(
        out: ByteArrayOutputStream,
        mask: PsdMask?,
    ) {
        if (mask == null) {
            out.writeInt(0)
            return
        }
        out.writeInt(20)
        out.writeInt(mask.top)
        out.writeInt(mask.left)
        out.writeInt(mask.top + mask.height)
        out.writeInt(mask.left + mask.width)
        out.write(mask.outside)
        out.write(if (mask.isEnabled) 0 else MASK_DISABLED)
        out.writeShort(0)
    }

    private fun writeImageData(
        out: ByteArrayOutputStream,
        image: PixelBuffer,
        useRle: Boolean,
    ) {
        if (useRle) {
            out.writeShort(COMPRESSION_RLE)
            val planes =
                listOf(
                    extractMergedChannel(image, CHANNEL_RED),
                    extractMergedChannel(image, CHANNEL_GREEN),
                    extractMergedChannel(image, CHANNEL_BLUE),
                    extractMergedChannel(image, CHANNEL_ALPHA),
                )
            // The RLE byte-count table lists every row of every channel up front.
            val encoded = planes.map { encodeRlePayload(it, image.width, image.height) }
            encoded.forEach { payload -> payload.counts.forEach { out.writeShort(it) } }
            encoded.forEach { payload -> out.write(payload.data) }
        } else {
            out.writeShort(COMPRESSION_RAW)
            listOf(CHANNEL_RED, CHANNEL_GREEN, CHANNEL_BLUE, CHANNEL_ALPHA).forEach { channelId ->
                out.write(rawBytes(extractMergedChannel(image, channelId)))
            }
        }
    }

    /** PSD's merged RGB preview is stored over white; editable layer channels stay straight. */
    private fun extractMergedChannel(
        image: PixelBuffer,
        channelId: Int,
    ): ByteArray {
        val plane = extractChannel(image, channelId)
        if (channelId == CHANNEL_ALPHA) return plane
        for (index in plane.indices) {
            val alpha = image.pixels[index] ushr 24
            // Hidden RGB has no visible contribution; retaining it also avoids dividing by zero on read.
            if (alpha in 1..254) {
                val value = plane[index].toInt() and 0xFF
                plane[index] = ((value * alpha + 255 * (255 - alpha) + 127) / 255).toByte()
            }
        }
        return plane
    }

    // -----------------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------------

    /** Parses a PSD file. Returns null when the data is not a PSD or is structurally invalid. */
    fun read(bytes: ByteArray): PsdDocument? {
        if (bytes.size < 26) return null
        if (String(bytes, 0, 4, Charsets.US_ASCII) != SIGNATURE) return null

        val reader = PsdReader(bytes)
        reader.position = 4
        val version = reader.readShort()
        if (version != VERSION) return null
        reader.skip(6)
        val channelCount = reader.readShort()
        val height = reader.readInt()
        val width = reader.readInt()
        val depth = reader.readShort()
        val colorMode = reader.readShort()
        if (!CanvasOperations.isSizeSafe(width, height)) return null
        if (channelCount !in 1..MAX_CHANNELS) return null
        if (colorMode !in setOf(COLOR_MODE_RGB, COLOR_MODE_GRAYSCALE, COLOR_MODE_CMYK)) return null

        // Colour mode data.
        val colorModeLength = reader.readInt()
        reader.skip(colorModeLength)

        // Image resources: pull the resolution out and skip the rest.
        val resourcesLength = reader.readInt()
        var dpi = 72
        if (resourcesLength > 0) {
            val resourcesEnd = reader.position + resourcesLength
            dpi = parseResolution(reader) ?: 72
            reader.position = resourcesEnd
        }

        // Layer and mask information.
        val layerAndMaskLength = reader.readInt()
        val layers = mutableListOf<PsdLayer>()
        var hasMergedTransparency = false
        if (layerAndMaskLength > 0) {
            val sectionEnd = reader.position + layerAndMaskLength
            val layerInfoLength = reader.readInt()
            if (layerInfoLength > 0) {
                val layerInfoEnd = reader.position + layerInfoLength
                val recordsStart = reader.position
                hasMergedTransparency = reader.readShort().toShort() < 0
                reader.position = recordsStart
                // A damaged layer record is a damaged file: the document reads as not a PSD, as documented.
                layers +=
                    try {
                        readLayerRecords(reader, width, height, channelCount, depth, colorMode)
                    } catch (damaged: IllegalArgumentException) {
                        return null
                    }
                reader.position = layerInfoEnd
            }
            reader.position = sectionEnd
        }

        // Image data (composite).
        val composite =
            if (reader.remaining() >= 2) {
                readImageData(reader, width, height, channelCount, depth, colorMode, hasMergedTransparency)
            } else {
                null
            }

        return PsdDocument(
            width = width,
            height = height,
            layers = layers,
            composite = composite,
            dpi = dpi,
        )
    }

    private fun parseResolution(reader: PsdReader): Int? {
        // Walk image resource blocks looking for id 1005 (ResolutionInfo).
        var result: Int? = null
        while (reader.remaining() > 12) {
            val signature = reader.readAscii(4)
            if (signature != "8BIM" && signature != "8B64") break
            val id = reader.readShort()
            val nameLength = reader.readByte()
            val namePadding = if ((nameLength + 1) % 2 == 0) 0 else 1
            reader.skip(nameLength + namePadding)
            val dataLength = reader.readInt()
            if (dataLength <= 0 || reader.remaining() < dataLength) break
            if (id == 1005 && dataLength >= 8) {
                val horizontal = reader.readInt()
                val value = horizontal / 65536f
                if (value > 1f) result = value.toInt()
                reader.skip(dataLength - 4)
            } else {
                reader.skip(dataLength)
            }
        }
        return result
    }

    private fun readLayerRecords(
        reader: PsdReader,
        canvasWidth: Int,
        canvasHeight: Int,
        channelCount: Int,
        depth: Int,
        colorMode: Int,
    ): List<PsdLayer> {
        val layerCount = kotlin.math.abs(reader.readShort().toShort().toInt())
        require(layerCount <= 1024) { "Too many PSD layers" }
        if (layerCount == 0) return emptyList()

        data class Pending(
            val name: String,
            val top: Int,
            val left: Int,
            val bottom: Int,
            val right: Int,
            val opacity: Int,
            val isVisible: Boolean,
            val blendMode: BlendMode,
            val channels: List<Pair<Int, Int>>,
            val isClippingMask: Boolean,
            val section: Section,
            val mask: MaskHeader?,
        )

        val pending = mutableListOf<Pending>()
        for (i in 0 until layerCount) {
            if (reader.remaining() < 32) break
            val top = reader.readInt()
            val left = reader.readInt()
            val bottom = reader.readInt()
            val right = reader.readInt()
            val layerChannelCount = reader.readShort()
            require(layerChannelCount <= MAX_CHANNELS) { "A PSD layer has too many channels" }
            val channels =
                (0 until layerChannelCount).map {
                    val id = reader.readShort().toShort().toInt()
                    val length = reader.readInt()
                    require(length >= 0) { "Invalid PSD channel length" }
                    id to length
                }
            val blendSignature = reader.readAscii(4)
            val blendKey = reader.readAscii(4)
            val opacity = reader.readByte()
            val isClippingMask = reader.readByte() == 1
            val flags = reader.readByte()
            reader.skip(1) // filler
            val extraLength = reader.readInt()
            val extraEnd = reader.position + extraLength

            var name = "Layer ${i + 1}"
            var section = Section.NONE
            var folderBlend: BlendMode? = null
            var maskHeader: MaskHeader? = null
            if (extraLength > 0 && reader.remaining() >= 8) {
                val maskLength = reader.readInt()
                val maskStart = reader.position
                if (maskLength >= MASK_HEADER_LENGTH && reader.remaining() >= MASK_HEADER_LENGTH) {
                    val maskTop = reader.readInt()
                    val maskLeft = reader.readInt()
                    val maskBottom = reader.readInt()
                    val maskRight = reader.readInt()
                    maskHeader = MaskHeader(maskTop, maskLeft, maskBottom, maskRight, reader.readByte(), reader.readByte())
                }
                reader.position = (maskStart + maskLength.coerceAtLeast(0)).coerceAtMost(extraEnd)
                val blendingRangesLength = reader.readInt()
                if (blendingRangesLength > 0) reader.skip(blendingRangesLength)
                if (reader.position < extraEnd) {
                    val nameLength = reader.readByte()
                    if (nameLength > 0) name = reader.readAscii(nameLength)
                    reader.skip((4 - (nameLength + 1) % 4) % 4)
                }
                // A "luni" block carries the UTF-16 name and is preferred when present; "lsct"
                // marks folder records. Other blocks are skipped.
                var scanPosition = reader.position
                while (scanPosition + 12 <= extraEnd) {
                    reader.position = scanPosition
                    val signature = reader.readAscii(4)
                    if (signature != "8BIM" && signature != "8B64") break
                    val key = reader.readAscii(4)
                    val length = reader.readInt()
                    if (length < 0 || reader.remaining() < length) break
                    val blockStart = reader.position
                    if (key == "luni") {
                        val unicodeName = reader.readUnicodeString(length)
                        if (unicodeName.isNotBlank()) name = unicodeName
                    } else if ((key == "lsct" || key == "lsdk") && length >= 4) {
                        section = Section.of(reader.readInt())
                        if (length >= LSCT_LENGTH && reader.readAscii(4) == "8BIM") {
                            val blend = reader.readAscii(4)
                            folderBlend = if (blend == PASS_THROUGH_KEY) BlendMode.PASS_THROUGH else blendModeFromKey(blend)
                        }
                    }
                    scanPosition = blockStart + length + (length % 2)
                }
            }
            reader.position = extraEnd

            pending +=
                Pending(
                    name = name,
                    top = top,
                    left = left,
                    bottom = bottom,
                    right = right,
                    opacity = opacity,
                    isVisible = (flags and 0x02) == 0,
                    blendMode = folderBlend ?: blendModeFromKey(if (blendSignature == "8BIM") blendKey else "norm"),
                    channels = channels,
                    isClippingMask = isClippingMask,
                    section = section,
                    mask = maskHeader,
                )
        }

        // Channel image data follows the records, in the same order.
        val layers = mutableListOf<PsdLayer>()
        pending.forEach { record ->
            val layerWidth = (record.right - record.left).coerceAtLeast(0)
            val layerHeight = (record.bottom - record.top).coerceAtLeast(0)
            val mask = record.mask
            val (maskWidth, maskHeight) = maskDimensions(mask)
            if (record.section != Section.NONE) {
                var maskPlane: ByteArray? = null
                record.channels.forEach { (channelId, length) ->
                    require(length <= reader.remaining()) { "Truncated PSD folder channel" }
                    val start = reader.position
                    if (channelId == CHANNEL_USER_MASK && maskWidth > 0 && maskHeight > 0) {
                        maskPlane = readChannel(reader, maskWidth, maskHeight, depth, length)
                    }
                    reader.position = start + length
                }
                layers +=
                    if (record.section == Section.GROUP_END) {
                        PsdLayer.groupEnd()
                    } else {
                        PsdLayer
                            .groupHeader(record.name, record.opacity, record.isVisible, record.blendMode)
                            .copy(
                                section = record.section,
                                mask = mask?.let { maskOf(it, record.left, record.top, maskPlane) },
                            )
                    }
                return@forEach
            }
            if (layerWidth == 0 || layerHeight == 0) {
                record.channels.forEach { (_, length) -> reader.skip(max(0, length)) }
                return@forEach
            }
            require(CanvasOperations.isSizeSafe(layerWidth, layerHeight)) { "A PSD layer is too large" }

            val planes = HashMap<Int, ByteArray>()
            record.channels.forEach { (channelId, length) ->
                require(length <= reader.remaining()) { "Truncated PSD layer channel" }
                val start = reader.position
                if (channelId != CHANNEL_USER_MASK) {
                    planes[channelId] = readChannel(reader, layerWidth, layerHeight, depth, length)
                } else if (maskWidth > 0 && maskHeight > 0) {
                    // The mask has its own rectangle, which need not match the layer's.
                    planes[channelId] = readChannel(reader, maskWidth, maskHeight, depth, length)
                }
                // Trust the declared length over where the decoder ended up.
                reader.position = start + length
            }

            val buffer = PixelBuffer(layerWidth, layerHeight)
            val alpha = planes[CHANNEL_ALPHA]
            if (colorMode == COLOR_MODE_CMYK) {
                for (index in buffer.pixels.indices) {
                    val black = planes[3]?.getOrNull(index)?.toInt()?.and(0xFF) ?: 0
                    val a = alpha?.getOrNull(index)?.toInt()?.and(0xFF) ?: 255
                    buffer.pixels[index] =
                        Channels.argb(
                            a,
                            cmykToRgb(planes[0], index, black),
                            cmykToRgb(planes[1], index, black),
                            cmykToRgb(planes[2], index, black),
                        )
                }
            } else {
                val red = planes[CHANNEL_RED]
                val green = planes[CHANNEL_GREEN]
                val blue = planes[CHANNEL_BLUE]
                for (index in buffer.pixels.indices) {
                    val r = channelValue(red, index, colorMode)
                    // A grayscale document has one gray plane, which fills all three colour channels.
                    val g = if (colorMode == COLOR_MODE_GRAYSCALE) r else channelValue(green, index, colorMode)
                    val b = if (colorMode == COLOR_MODE_GRAYSCALE) r else channelValue(blue, index, colorMode)
                    val a = alpha?.getOrNull(index)?.toInt()?.and(0xFF) ?: 255
                    buffer.pixels[index] = Channels.argb(a, r, g, b)
                }
            }

            layers +=
                PsdLayer(
                    name = record.name,
                    pixels = buffer,
                    opacity = record.opacity,
                    isVisible = record.isVisible,
                    blendMode = record.blendMode,
                    left = record.left,
                    top = record.top,
                    isClippingMask = record.isClippingMask,
                    mask = mask?.let { maskOf(it, record.left, record.top, planes[CHANNEL_USER_MASK]) },
                )
        }
        return layers
    }

    /** Validate mask bounds before allocating or decompressing the independently sized mask plane. */
    private fun maskDimensions(header: MaskHeader?): Pair<Int, Int> {
        if (header == null) return 0 to 0
        val width = header.right.toLong() - header.left
        val height = header.bottom.toLong() - header.top
        require(width in 0L..8192L && height in 0L..8192L && width * height <= 40_000_000L) {
            "A PSD mask has invalid dimensions"
        }
        return width.toInt() to height.toInt()
    }

    /** Places a mask in document coordinates and retains disabled coverage for later editing. */
    private fun maskOf(
        header: MaskHeader,
        layerLeft: Int,
        layerTop: Int,
        plane: ByteArray?,
    ): PsdMask {
        val relative = (header.flags and MASK_RELATIVE) != 0
        val originX = if (relative) layerLeft else 0
        val originY = if (relative) layerTop else 0
        val (width, height) = maskDimensions(header)
        val invert = (header.flags and MASK_INVERTED) != 0

        fun adjust(value: Int): Int = if (invert) 255 - value else value
        // Pixels the file did not store take the mask's default value.
        val coverage =
            ByteArray(width * height) { index ->
                val stored = plane?.getOrNull(index)?.toInt()?.and(0xFF)
                adjust(stored ?: header.defaultValue).toByte()
            }
        val absoluteLeft = originX.toLong() + header.left
        val absoluteTop = originY.toLong() + header.top
        require(absoluteLeft in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "PSD mask left overflows" }
        require(absoluteTop in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "PSD mask top overflows" }
        return PsdMask(
            left = absoluteLeft.toInt(),
            top = absoluteTop.toInt(),
            width = width,
            height = height,
            coverage = coverage,
            outside = adjust(header.defaultValue),
            isEnabled = (header.flags and MASK_DISABLED) == 0,
        )
    }

    private fun readImageData(
        reader: PsdReader,
        width: Int,
        height: Int,
        channelCount: Int,
        depth: Int,
        colorMode: Int,
        hasMergedTransparency: Boolean,
    ): PixelBuffer? {
        val compression = reader.readShort()
        val planes = readMergedPlanes(reader, compression, channelCount, width, height, depth, colorMode) ?: return null

        val buffer = PixelBuffer(width, height)
        val red = planes[CHANNEL_RED]
        val green = planes[CHANNEL_GREEN]
        val blue = planes[CHANNEL_BLUE]
        val alpha = planes[CHANNEL_ALPHA]
        if (colorMode == COLOR_MODE_CMYK) {
            val c = planes[0]
            val m = planes[1]
            val y = planes[2]
            val k = planes[3]
            for (index in buffer.pixels.indices) {
                val kk = k?.getOrNull(index)?.toInt()?.and(0xFF) ?: 0
                buffer.pixels[index] =
                    Channels.argb(
                        255,
                        cmykToRgb(c, index, kk),
                        cmykToRgb(m, index, kk),
                        cmykToRgb(y, index, kk),
                    )
            }
            return buffer
        }
        for (index in buffer.pixels.indices) {
            val r = channelValue(red, index, colorMode)
            // A grayscale document has one gray plane, which fills all three colour channels.
            val g = if (colorMode == COLOR_MODE_GRAYSCALE) r else channelValue(green, index, colorMode)
            val b = if (colorMode == COLOR_MODE_GRAYSCALE) r else channelValue(blue, index, colorMode)
            val a = alpha?.getOrNull(index)?.toInt()?.and(0xFF) ?: 255
            buffer.pixels[index] =
                if (hasMergedTransparency && colorMode == COLOR_MODE_RGB) {
                    Channels.argb(a, removeWhiteMatte(r, a), removeWhiteMatte(g, a), removeWhiteMatte(b, a))
                } else {
                    Channels.argb(a, r, g, b)
                }
        }
        return buffer
    }

    /** Inverse of the merged RGB white matte, with the expected 8-bit quantization at low alpha. */
    private fun removeWhiteMatte(
        value: Int,
        alpha: Int,
    ): Int = if (alpha in 1..254) ((255 * (value + alpha - 255) + alpha / 2) / alpha).coerceIn(0, 255) else value

    /** One CMYK component (0..255 inverted) to an 8-bit RGB channel. */
    private fun cmykToRgb(
        plane: ByteArray?,
        index: Int,
        black: Int,
    ): Int {
        val component = plane?.getOrNull(index)?.toInt()?.and(0xFF) ?: 0
        return (255 - min(255, component + black)).coerceIn(0, 255)
    }

    /**
     * Reads one channel plane, handling raw, RLE and ZIP compressions.
     * 16-bit data is reduced to 8 bits by taking the high byte.
     */
    private fun readChannel(
        reader: PsdReader,
        width: Int,
        height: Int,
        depth: Int,
        declaredLength: Int,
    ): ByteArray {
        require(declaredLength >= 2 && declaredLength <= reader.remaining()) { "Truncated PSD channel" }
        // A channel's decoder must never borrow bytes from the next channel, even when a
        // malformed row table or PackBits run would otherwise produce the expected pixel count.
        val channelEnd = reader.position + declaredLength
        val bytesPerSample = if (depth == DEPTH_16) 2 else 1
        val rowBytes = width * bytesPerSample
        val planeBytes = rowBytes * height
        val compression = reader.readShort()
        val plane =
            when (compression) {
                COMPRESSION_RAW -> {
                    require(channelEnd - reader.position >= planeBytes) { "Truncated raw PSD channel" }
                    val raw = reader.readBytes(planeBytes)
                    downsample(raw, width, height, depth)
                }

                COMPRESSION_RLE -> {
                    require(channelEnd - reader.position >= height * 2) { "Truncated PSD row-length table" }
                    val rowLengths = IntArray(height) { reader.readShort() }
                    require(rowLengths.sumOf { it.toLong() } <= channelEnd - reader.position) { "PSD rows exceed their channel" }
                    // A 16-bit row holds two bytes per pixel, so it is decoded at its full width before downsampling.
                    val out = ByteArray(planeBytes)
                    for (row in 0 until height) {
                        val decoded = decodeChannelRleRow(reader, rowBytes, reader.position + rowLengths[row])
                        System.arraycopy(decoded, 0, out, row * rowBytes, rowBytes)
                    }
                    downsample(out, width, height, depth)
                }

                COMPRESSION_ZIP, COMPRESSION_ZIP_PREDICTION -> {
                    // A layer channel is one zlib stream whose length is the record's declared length, with no size prefix.
                    val size = channelEnd - reader.position
                    if (size <= 0) {
                        ByteArray(width * height)
                    } else {
                        val plane = inflate(reader.readBytes(size), planeBytes).copyOf(planeBytes)
                        if (compression == COMPRESSION_ZIP_PREDICTION) undoPrediction(plane, width, height, bytesPerSample)
                        downsample(plane, width, height, depth)
                    }
                }

                else -> {
                    ByteArray(width * height)
                }
            }
        reader.position = channelEnd
        return plane
    }

    /** A layer row is bounded by its byte count, including any trailing PackBits no-ops. */
    private fun decodeChannelRleRow(
        reader: PsdReader,
        width: Int,
        rowEnd: Int,
    ): ByteArray {
        val out = ByteArray(width)
        var written = 0
        while (reader.position < rowEnd) {
            val control = reader.readByte()
            when {
                control <= 127 -> {
                    val count = control + 1
                    require(count <= rowEnd - reader.position && count <= width - written) { "Invalid PSD literal run" }
                    repeat(count) { out[written++] = reader.readByte().toByte() }
                }

                control != 128 -> {
                    val count = 257 - control
                    require(reader.position < rowEnd && count <= width - written) { "Invalid PSD repeat run" }
                    val value = reader.readByte().toByte()
                    repeat(count) { out[written++] = value }
                }
            }
        }
        require(written == width) { "Incomplete PSD row" }
        return out
    }

    private fun downsample(
        raw: ByteArray,
        width: Int,
        height: Int,
        depth: Int,
    ): ByteArray {
        if (depth != DEPTH_16) return raw
        val out = ByteArray(width * height)
        for (i in out.indices) {
            // Big-endian 16-bit samples: the high byte is the 8-bit value.
            out[i] = raw.getOrNull(i * 2) ?: 0
        }
        return out
    }

    /** Reads one 8-bit channel value; grayscale planes feed all three RGB channels. */
    private fun channelValue(
        plane: ByteArray?,
        index: Int,
        colorMode: Int,
    ): Int {
        if (colorMode == COLOR_MODE_GRAYSCALE) return plane?.getOrNull(index)?.toInt()?.and(0xFF) ?: 0
        return plane?.getOrNull(index)?.toInt()?.and(0xFF) ?: 0
    }

    // -----------------------------------------------------------------------------------------
    // Channel / RLE helpers
    // -----------------------------------------------------------------------------------------

    private fun extractChannel(
        pixels: PixelBuffer,
        channelId: Int,
    ): ByteArray {
        val out = ByteArray(pixels.pixels.size)
        for (i in pixels.pixels.indices) {
            val pixel = pixels.pixels[i]
            out[i] =
                when (channelId) {
                    CHANNEL_RED -> ((pixel shr 16) and 0xFF).toByte()
                    CHANNEL_GREEN -> ((pixel shr 8) and 0xFF).toByte()
                    CHANNEL_BLUE -> (pixel and 0xFF).toByte()
                    CHANNEL_ALPHA -> ((pixel ushr 24) and 0xFF).toByte()
                    else -> 0
                }
        }
        return out
    }

    private fun rawBytes(plane: ByteArray): ByteArray = plane

    private data class RlePayload(
        val counts: List<Int>,
        val data: ByteArray,
    )

    /** PackBits-encodes each scanline separately; the counts table is written by the caller. */
    private fun encodeRlePayload(
        plane: ByteArray,
        width: Int,
        height: Int,
    ): RlePayload {
        val data = ByteArrayOutputStream(plane.size)
        val counts = ArrayList<Int>(height)
        for (row in 0 until height) {
            val encoded = packBits(plane, row * width, width)
            counts += encoded.size
            data.write(encoded)
        }
        return RlePayload(counts, data.toByteArray())
    }

    /** Full channel payload: row counts followed by the PackBits data. */
    private fun encodeRle(
        plane: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val payload = encodeRlePayload(plane, width, height)
        val out = ByteArrayOutputStream(payload.data.size + height * 2)
        payload.counts.forEach { out.writeShort(it) }
        out.write(payload.data)
        return out.toByteArray()
    }

    /**
     * PackBits (Apple RLE) as used by Photoshop: a control byte `n` means
     * `0..127` -> copy the next `n+1` bytes literally, `129..255` -> repeat the next byte `257-n`
     * times, and `128` is a no-op. Runs are capped at 128 bytes.
     */
    fun packBits(
        source: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream(length + length / 64 + 8)
        var position = 0
        while (position < length) {
            // Look for a run of three or more identical bytes.
            var runLength = 1
            while (position + runLength < length &&
                runLength < 128 &&
                source[offset + position + runLength] == source[offset + position]
            ) {
                runLength++
            }

            if (runLength >= 3) {
                out.write(257 - runLength)
                out.write(source[offset + position].toInt())
                position += runLength
                continue
            }

            // Otherwise accumulate literal bytes until a run of three appears.
            var literalLength = 0
            while (position + literalLength < length && literalLength < 128) {
                val remaining = length - position - literalLength
                if (remaining >= 3) {
                    val a = source[offset + position + literalLength]
                    val b = source[offset + position + literalLength + 1]
                    val c = source[offset + position + literalLength + 2]
                    if (a == b && b == c) break
                }
                literalLength++
            }
            if (literalLength == 0) literalLength = 1
            out.write(literalLength - 1)
            out.write(source, offset + position, literalLength)
            position += literalLength
        }
        return out.toByteArray()
    }

    /** Decodes one PackBits scanline into a row of [width] bytes. */
    fun unpackBits(
        reader: PsdReader,
        width: Int,
    ): ByteArray {
        val out = ByteArray(width)
        var written = 0
        while (written < width) {
            if (reader.remaining() <= 0) break
            val control = reader.readByte()
            when {
                control <= 127 -> {
                    val count = control + 1
                    for (i in 0 until count) {
                        if (written >= width) {
                            reader.skip(count - i)
                            break
                        }
                        out[written++] = reader.readByte().toByte()
                    }
                }

                control == 128 -> {
                    Unit
                }

                // no-op
                else -> {
                    val count = 257 - control
                    val value = reader.readByte().toByte()
                    for (i in 0 until count) {
                        if (written >= width) break
                        out[written++] = value
                    }
                }
            }
        }
        return out
    }

    /**
     * The merged image's channel planes, one byte per sample: 16-bit samples keep their high byte.
     * Null when the compression is not one this reader decodes.
     */
    private fun readMergedPlanes(
        reader: PsdReader,
        compression: Int,
        channelCount: Int,
        width: Int,
        height: Int,
        depth: Int,
        colorMode: Int,
    ): HashMap<Int, ByteArray>? {
        val planes = HashMap<Int, ByteArray>()
        val channelIds =
            (0 until channelCount).map { index ->
                when (colorMode) {
                    COLOR_MODE_GRAYSCALE -> {
                        if (index == 0) CHANNEL_RED else CHANNEL_ALPHA
                    }

                    COLOR_MODE_CMYK -> {
                        index
                    }

                    else -> {
                        when (index) {
                            0 -> CHANNEL_RED
                            1 -> CHANNEL_GREEN
                            2 -> CHANNEL_BLUE
                            else -> CHANNEL_ALPHA
                        }
                    }
                }
            }

        val bytesPerSample = if (depth == 16) 2 else 1
        when (compression) {
            COMPRESSION_RAW -> {
                channelIds.forEach { id ->
                    val size = width * height * bytesPerSample
                    planes[id] = reader.readBytes(min(size, reader.remaining()))
                }
            }

            COMPRESSION_RLE -> {
                // The byte-count table lists every row of every channel, then the row data follows.
                repeat(channelCount * height) { reader.readShort() }
                val rowBytes = width * bytesPerSample
                channelIds.forEach { id ->
                    val out = ByteArray(rowBytes * height)
                    for (row in 0 until height) {
                        val decoded = decodeRleRow(reader, rowBytes)
                        System.arraycopy(decoded, 0, out, row * rowBytes, min(rowBytes, decoded.size))
                    }
                    planes[id] = out
                }
            }

            COMPRESSION_ZIP, COMPRESSION_ZIP_PREDICTION -> {
                // The merged image is one zlib stream: each channel's whole plane follows the one before it.
                val planeBytes = width * height * bytesPerSample
                val stream = inflate(reader.readBytes(reader.remaining()), planeBytes * channelIds.size)
                if (stream.size < planeBytes * channelIds.size) return null
                channelIds.forEachIndexed { index, id ->
                    val plane = stream.copyOfRange(index * planeBytes, (index + 1) * planeBytes)
                    if (compression == COMPRESSION_ZIP_PREDICTION) undoPrediction(plane, width, height, bytesPerSample)
                    planes[id] = plane
                }
            }

            else -> {
                return null
            }
        }

        // 16-bit samples: the reader works with one byte per channel, so keep each sample's high byte.
        if (bytesPerSample == 2) {
            for (id in planes.keys.toList()) planes[id] = highBytes(planes.getValue(id))
        }
        return planes
    }

    private fun decodeRleRow(
        reader: PsdReader,
        width: Int,
    ): ByteArray = unpackBits(reader, width)

    /** Keeps the high byte of each 16-bit sample. */
    private fun highBytes(samples: ByteArray): ByteArray = ByteArray(samples.size / 2) { samples[it * 2] }

    /**
     * Undoes ZIP prediction: each sample was stored as its difference from the sample before it in its row,
     * modulo the sample size. A 16-bit sample is a big-endian pair of bytes, so its carry crosses the high byte.
     */
    private fun undoPrediction(
        plane: ByteArray,
        width: Int,
        height: Int,
        bytesPerSample: Int,
    ) {
        val rowBytes = width * bytesPerSample
        for (row in 0 until height) {
            val start = row * rowBytes
            for (x in 1 until width) {
                val at = start + x * bytesPerSample
                val before = at - bytesPerSample
                var carry = 0
                for (b in bytesPerSample - 1 downTo 0) {
                    val sum = (plane[at + b].toInt() and 0xFF) + (plane[before + b].toInt() and 0xFF) + carry
                    plane[at + b] = sum.toByte()
                    carry = sum ushr 8
                }
            }
        }
    }

    /** Inflates a ZIP channel, stopping at [limit] bytes: a plane never needs more than its pixels, so a bomb cannot grow it. */
    private fun inflate(
        bytes: ByteArray,
        limit: Int,
    ): ByteArray =
        try {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(min(limit, bytes.size * 4))
            val buffer = ByteArray(8192)
            while (!inflater.finished() && out.size() < limit) {
                val read = inflater.inflate(buffer, 0, min(buffer.size, limit - out.size()))
                if (read == 0) break
                out.write(buffer, 0, read)
            }
            inflater.end()
            out.toByteArray()
        } catch (_: java.util.zip.DataFormatException) {
            // Malformed PSD channel data: report as an empty stream and let the caller fall back.
            ByteArray(0)
        }

    // -----------------------------------------------------------------------------------------
    // Blend mode mapping
    // -----------------------------------------------------------------------------------------

    /** Maps a domain [BlendMode] onto the four-character PSD key. */
    fun blendModeKey(mode: BlendMode): String =
        when (mode) {
            BlendMode.NORMAL, BlendMode.PASS_THROUGH -> "norm"
            BlendMode.MULTIPLY -> "mul "
            BlendMode.SCREEN -> "scrn"
            BlendMode.OVERLAY -> "over"
            BlendMode.DARKEN -> "dark"
            BlendMode.LIGHTEN -> "lite"
            BlendMode.COLOR_DODGE -> "div "
            BlendMode.COLOR_BURN -> "idiv"
            BlendMode.HARD_LIGHT -> "hLit"
            BlendMode.SOFT_LIGHT -> "sLit"
            BlendMode.DIFFERENCE -> "diff"
            BlendMode.EXCLUSION -> "smud"
            BlendMode.HUE -> "hue "
            BlendMode.SATURATION -> "sat "
            BlendMode.COLOR -> "colr"
            BlendMode.LUMINOSITY -> "lum "
            BlendMode.LINEAR_BURN -> "lbrn"
            BlendMode.DARKER_COLOR -> "dkCl"
            BlendMode.ADD -> "lddg"
            BlendMode.LIGHTER_COLOR -> "lgCl"
            BlendMode.VIVID_LIGHT -> "vLit"
            BlendMode.LINEAR_LIGHT -> "lLit"
            BlendMode.PIN_LIGHT -> "pLit"
            BlendMode.HARD_MIX -> "hMix"
            BlendMode.SUBTRACT -> "fsub"
            BlendMode.DIVIDE -> "fdiv"
        }

    /** Inverse of [blendModeKey]; unknown keys fall back to normal. */
    fun blendModeFromKey(key: String): BlendMode =
        when (key) {
            "mul " -> BlendMode.MULTIPLY
            "scrn" -> BlendMode.SCREEN
            "over" -> BlendMode.OVERLAY
            "dark" -> BlendMode.DARKEN
            "lite" -> BlendMode.LIGHTEN
            "div " -> BlendMode.COLOR_DODGE
            "idiv" -> BlendMode.COLOR_BURN
            "hLit" -> BlendMode.HARD_LIGHT
            "sLit" -> BlendMode.SOFT_LIGHT
            "diff" -> BlendMode.DIFFERENCE
            "smud" -> BlendMode.EXCLUSION
            "hue " -> BlendMode.HUE
            "sat " -> BlendMode.SATURATION
            "colr" -> BlendMode.COLOR
            "lum " -> BlendMode.LUMINOSITY
            "lbrn" -> BlendMode.LINEAR_BURN
            "dkCl" -> BlendMode.DARKER_COLOR
            "lddg" -> BlendMode.ADD
            "lgCl" -> BlendMode.LIGHTER_COLOR
            "vLit" -> BlendMode.VIVID_LIGHT
            "lLit" -> BlendMode.LINEAR_LIGHT
            "pLit" -> BlendMode.PIN_LIGHT
            "hMix" -> BlendMode.HARD_MIX
            "fsub" -> BlendMode.SUBTRACT
            "fdiv" -> BlendMode.DIVIDE
            else -> BlendMode.NORMAL
        }

    // -----------------------------------------------------------------------------------------
    // Small binary reader/writer helpers
    // -----------------------------------------------------------------------------------------

    /** Bounds-checked big-endian reader; every accessor returns 0 past the end of the data. */
    class PsdReader(
        private val bytes: ByteArray,
    ) {
        /** Always within the data: a declared length can point anywhere, and the readers must not index past either end. */
        var position: Int = 0
            set(value) {
                field = value.coerceIn(0, bytes.size)
            }

        fun remaining(): Int = bytes.size - position

        fun skip(count: Int) {
            position = (position + count).coerceIn(0, bytes.size)
        }

        fun readByte(): Int {
            if (position >= bytes.size) return 0
            return bytes[position++].toInt() and 0xFF
        }

        fun readShort(): Int {
            val high = readByte()
            val low = readByte()
            return (high shl 8) or low
        }

        fun readInt(): Int {
            val a = readByte()
            val b = readByte()
            val c = readByte()
            val d = readByte()
            return (a shl 24) or (b shl 16) or (c shl 8) or d
        }

        fun readBytes(count: Int): ByteArray {
            val available = min(count, remaining())
            if (available <= 0) return ByteArray(0)
            val out = bytes.copyOfRange(position, position + available)
            position += available
            return out
        }

        fun readAscii(count: Int): String {
            val raw = readBytes(count)
            return String(raw, Charsets.US_ASCII)
        }

        /** Reads a UTF-16BE pascal-style string of [length] bytes (as written by the "luni" key). */
        fun readUnicodeString(length: Int): String {
            val raw = readBytes(length)
            if (raw.size < 4) return ""
            val charCount =
                ((raw[0].toInt() and 0xFF) shl 24) or
                    ((raw[1].toInt() and 0xFF) shl 16) or
                    ((raw[2].toInt() and 0xFF) shl 8) or
                    (raw[3].toInt() and 0xFF)
            val chars = CharArray(charCount.coerceIn(0, (raw.size - 4) / 2))
            for (i in chars.indices) {
                val high = raw[4 + i * 2].toInt() and 0xFF
                val low = raw[5 + i * 2].toInt() and 0xFF
                chars[i] = ((high shl 8) or low).toChar()
            }
            return String(chars).trimEnd('\u0000')
        }
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeZeros(count: Int) {
        repeat(count) { write(0) }
    }

    private fun ByteArrayOutputStream.writeShort(value: Int) {
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value shr 24) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 8) and 0xFF)
        write(value and 0xFF)
    }
}
