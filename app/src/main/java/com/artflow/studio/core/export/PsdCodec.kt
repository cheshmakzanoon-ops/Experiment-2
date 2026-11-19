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
 * and the composite image data when layers are missing. CMYK conversion is an unprofiled
 * approximation; embedded ICC profiles and spot-color appearance are not reproduced.
 */
object PsdCodec {
    const val SIGNATURE = "8BPS"
    const val SIGNATURE_PSB = "8BPB"
    private const val VERSION = 1
    const val VERSION_PSB = 2
    private const val COLOR_MODE_RGB = 3
    private const val COLOR_MODE_GRAYSCALE = 1
    private const val COLOR_MODE_CMYK = 4
    private const val DEPTH_8 = 8
    private const val DEPTH_16 = 16

    /** The most channels a PSD may have (the format's limit), which bounds every per-channel allocation. */
    private const val MAX_CHANNELS = 56
    private val PSB_WIDE_BLOCKS =
        setOf("LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD")

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

    /** True when [bytes] begin with the standard PSD or large-document PSB signature. */
    fun isPsdOrPsb(bytes: ByteArray): Boolean {
        if (bytes.size < 26) return false
        val sig = String(bytes, 0, 4, Charsets.US_ASCII)
        return sig == SIGNATURE || sig == SIGNATURE_PSB
    }

    /** Header information and a conservative peak allocation estimate, without decoding any pixels. */
    data class ReadInfo(
        val width: Int,
        val height: Int,
        val dpi: Int,
        /** Includes the supplied bytes, retained rasters/masks and the largest decoding workspace. */
        val requiredMemoryBytes: Long,
        /** Only explicit PSD transparency metadata identifies an extra channel as image alpha. */
        val mergedTransparencyChannel: Int? = null,
        val isCmyk: Boolean = false,
        val hasEmbeddedIccProfile: Boolean = false,
        /** Saved selections and spot channels are decoded safely but are not imported as artwork. */
        val ignoredAuxiliaryChannels: Int = 0,
        /** Alternate layer records are currently represented by the merged image, not editable layers. */
        val usesFlattenedAlternateLayers: Boolean = false,
    ) {
        val hasMergedTransparency: Boolean get() = mergedTransparencyChannel != null
    }

    /** Leaves room for the open artwork, undo, rendering and the rest of the application. */
    fun defaultReadBudgetBytes(): Long = Runtime.getRuntime().maxMemory() / 3

    /**
     * Reads only bounded metadata. A compressed file's byte count cannot bound its decoded size.
     * All layer and mask dimensions are checked before the first raster/channel allocation.
     */
    fun inspect(bytes: ByteArray): ReadInfo? =
        try {
            inspectMetadata(bytes)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun inspectMetadata(bytes: ByteArray): ReadInfo? {
        if (!isPsdOrPsb(bytes) || bytes.size < 38) return null
        val reader = PsdReader(bytes)
        reader.position = 4
        val version = reader.readShort()
        if (version != VERSION && version != VERSION_PSB) return null
        val isPsb = version == VERSION_PSB
        reader.skip(6)
        val channels = reader.readShort()
        val height = reader.readInt()
        val width = reader.readInt()
        val depth = reader.readShort()
        val colorMode = reader.readShort()
        if (!isSupportedReadHeader(width, height, channels, depth, colorMode)) return null
        val colorLength = reader.readInt()
        if (colorLength !in 0..reader.remaining()) return null
        reader.skip(colorLength)
        val resourcesLength = reader.readInt()
        if (resourcesLength !in 0..reader.remaining()) return null
        val resourcesEnd = reader.position + resourcesLength
        val resources = PsdImageResources.read(reader, resourcesEnd, channels, colorChannelCount(colorMode))
        if (reader.remaining() < if (isPsb) 8 else 4) return null
        val sectionLength = if (isPsb) reader.readLong() else reader.readInt().toLong()
        if (sectionLength !in 0L..reader.remaining().toLong()) return null
        val sectionEnd = reader.position + sectionLength.toInt()
        val metadata = inspectLayerMetadata(reader, sectionEnd, depth, isPsb)
        val (pending, hasMergedTransparency) = metadata
        val colorChannels = colorChannelCount(colorMode)
        require(!hasMergedTransparency || channels > colorChannels) { "Missing PSD merged transparency channel" }
        // Explicit layer/global markers have precedence. The zero-identifier fallback applies
        // only to layerless composites, never to a positive-count stack's saved selections.
        val transparency = if (hasMergedTransparency) colorChannels else resources.transparencyChannel.takeIf { !metadata.hasLayers }
        reader.position = sectionEnd
        var retained = bytes.size.toLong()
        var workspace = 0L
        pending.forEach { record ->
            val (stored, transient) = layerReadMemory(record, depth)
            retained += stored
            workspace = maxOf(workspace, transient)
        }
        if (reader.remaining() >= 2) {
            val pixels = width.toLong() * height
            retained += pixels * 4
            // ZIP holds a whole merged stream plus per-channel copies; 16-bit reduction
            // temporarily retains both sample depths. The bound also covers raw/RLE paths.
            val raw = pixels * channels * (depth / 8)
            val rowTable = height.toLong() * channels * 4
            workspace = maxOf(workspace, raw * 4 + reader.remaining() + rowTable + width.toLong() * 2 + 8192)
        }
        return ReadInfo(
            width,
            height,
            resources.dpi,
            retained + workspace,
            transparency,
            colorMode == COLOR_MODE_CMYK,
            resources.hasEmbeddedIccProfile,
            channels - colorChannels - if (transparency == null) 0 else 1,
            metadata.hasLayers && pending.isEmpty(),
        )
    }

    private data class LayerReadMetadata(
        val layers: List<PendingLayer>,
        val hasMergedTransparency: Boolean,
        val hasLayers: Boolean = layers.isNotEmpty(),
    )

    private data class MergedImageMetadata(
        val hasTransparency: Boolean = false,
        val hasLayers: Boolean = false,
    )

    /** Inspects layer metadata and all declared channel extents without allocating pixel planes. */
    private fun inspectLayerMetadata(
        reader: PsdReader,
        sectionEnd: Int,
        depth: Int,
        isPsb: Boolean,
    ): LayerReadMetadata {
        if (reader.position == sectionEnd) return LayerReadMetadata(emptyList(), false)
        require(sectionEnd - reader.position >= if (isPsb) 8 else 4) { "Truncated PSD layer info length" }
        val layerLength = if (isPsb) reader.readLong() else reader.readInt().toLong()
        require(layerLength in 0L..(sectionEnd - reader.position).toLong()) { "Invalid PSD layer info length" }
        val pending = mutableListOf<PendingLayer>()
        var hasMergedTransparency = false
        if (layerLength > 0L) {
            require(layerLength >= 2L) { "Truncated PSD layer count" }
            val layerEnd = reader.position + layerLength.toInt()
            val signedCount = reader.readShort().toShort().toInt()
            hasMergedTransparency = signedCount < 0
            val count = kotlin.math.abs(signedCount)
            require(count <= 1024) { "Too many PSD layers" }
            repeat(count) { index ->
                require(reader.position < layerEnd) { "Truncated PSD layer record" }
                pending += requireNotNull(readSingleLayerPending(reader, index, isPsb)) { "Truncated PSD layer record" }
                require(reader.position <= layerEnd) { "PSD layer record exceeds layer info" }
            }
            val channelBytes = pending.sumOf { record -> record.channels.sumOf { it.second.toLong() } }
            require(channelBytes <= layerEnd - reader.position) { "PSD channel payload exceeds layer info" }
            reader.position = layerEnd
        }
        val merged = readMergedTransparencyMarker(reader, sectionEnd, depth, isPsb)
        return LayerReadMetadata(pending, merged.hasTransparency || hasMergedTransparency, pending.isNotEmpty() || merged.hasLayers)
    }

    private fun isSupportedReadHeader(
        width: Int,
        height: Int,
        channels: Int,
        depth: Int,
        colorMode: Int,
    ): Boolean =
        CanvasOperations.isSizeSafe(width, height) &&
            channels in colorChannelCount(colorMode)..MAX_CHANNELS &&
            (depth == DEPTH_8 || depth == DEPTH_16) &&
            colorMode in setOf(COLOR_MODE_RGB, COLOR_MODE_GRAYSCALE, COLOR_MODE_CMYK)

    private fun colorChannelCount(colorMode: Int): Int =
        when (colorMode) {
            COLOR_MODE_GRAYSCALE -> 1
            COLOR_MODE_RGB -> 3
            COLOR_MODE_CMYK -> 4
            else -> MAX_CHANNELS + 1
        }

    /** Mtrn/Mt16 identify merged transparency even when the primary layer count is zero. */
    private fun readMergedTransparencyMarker(
        reader: PsdReader,
        sectionEnd: Int,
        depth: Int,
        isPsb: Boolean,
    ): MergedImageMetadata {
        // Older writers omit the empty global-mask field entirely.
        if (reader.position == sectionEnd) return MergedImageMetadata()
        require(sectionEnd - reader.position >= 4) { "Truncated PSD global mask length" }
        val firstField = reader.readAscii(4)
        reader.position -= 4
        if (firstField != "8BIM" && firstField != "8B64") {
            val maskLength = reader.readInt()
            require(maskLength in 0..(sectionEnd - reader.position)) { "Invalid PSD global mask length" }
            reader.skip(maskLength)
        }
        var hasTransparency = false
        var hasLayers = false
        while (reader.position < sectionEnd) {
            // Adobe specifies even padding; Photoshop global blocks also use four-byte padding.
            var padding = 0
            while (reader.position < sectionEnd && padding < 3) {
                if (reader.readByte() != 0) {
                    reader.position--
                    break
                }
                padding++
            }
            if (reader.position == sectionEnd) break
            require(sectionEnd - reader.position >= 12) { "Truncated PSD global tagged block" }
            val signature = reader.readAscii(4)
            require(signature == "8BIM" || signature == "8B64") { "Invalid PSD global tagged block signature" }
            val key = reader.readAscii(4)
            val wideLength = signature == "8B64" || (isPsb && key in PSB_WIDE_BLOCKS)
            require(sectionEnd - reader.position >= if (wideLength) 8 else 4) { "Truncated PSD global tagged block length" }
            val length = if (wideLength) reader.readLong() else reader.readInt().toLong()
            require(length in 0L..(sectionEnd - reader.position).toLong()) { "Invalid PSD global tagged block length" }
            val blockEnd = reader.position + length.toInt()
            if (key == if (depth == DEPTH_16) "Mt16" else "Mtrn") {
                require(length == 0L) { "Invalid PSD merged transparency marker" }
                hasTransparency = true
            } else if (key == if (depth == DEPTH_16) "Lr16" else "Layr") {
                // Deep documents may move their layer info out of the primary section.
                require(length >= 2L) { "Truncated PSD alternate layer count" }
                val count = reader.readShort().toShort().toInt()
                hasTransparency = (count < 0) || hasTransparency
                hasLayers = (count != 0) || hasLayers
            }
            reader.position = blockEnd
        }
        return MergedImageMetadata(hasTransparency, hasLayers)
    }

    private fun layerReadMemory(
        record: PendingLayer,
        depth: Int,
    ): Pair<Long, Long> {
        val width = record.right.toLong() - record.left
        val height = record.bottom.toLong() - record.top
        require(width >= 0 && height >= 0) { "Invalid PSD layer rectangle" }
        val raster = record.section == Section.NONE && width > 0 && height > 0
        if (raster) {
            require(width <= 8192 && height <= 8192 && width * height <= 40_000_000L) { "A PSD layer is too large" }
        }
        val pixels = if (raster) width * height else 0L
        val (maskWidth, maskHeight) = maskDimensions(record.mask)
        val maskPixels = maskWidth.toLong() * maskHeight
        val channelPixels = record.channels.map { (id, _) -> if (id == CHANNEL_USER_MASK) maskPixels else pixels }
        val largestPlane = channelPixels.maxOrNull() ?: 0L
        // Retained metadata is bounded per record; names are capped by readUnicodeString.
        val retained = pixels * 4 + maskPixels + record.name.length.toLong() * 4 + record.channels.size * 32L + 256
        val rawPlane = largestPlane * (depth / 8)
        val encodedCopy = record.channels.maxOfOrNull { it.second.toLong() } ?: 0L
        val rowScratch = maxOf(height.coerceAtMost(8192), maskHeight.toLong()) * 4 + maxOf(width.coerceAtMost(8192), maskWidth.toLong()) * 2
        // Five raw planes conservatively cover inflater growth/copy, prediction and
        // downsampling while already decoded channels remain in the map.
        val workspace = channelPixels.sum() + rawPlane * 5 + encodedCopy + rowScratch + 8192
        return retained to workspace
    }

    /**
     * Parses a PSD/PSB within [memoryBudgetBytes]. Returns null for invalid or over-budget input.
     * The estimate is checked before any layer, mask, channel or composite pixel allocation.
     */
    fun read(
        bytes: ByteArray,
        memoryBudgetBytes: Long = defaultReadBudgetBytes(),
    ): PsdDocument? {
        require(memoryBudgetBytes >= 0L) { "The PSD read budget cannot be negative" }
        if (bytes.size.toLong() > memoryBudgetBytes) return null
        val info = inspect(bytes) ?: return null
        if (info.requiredMemoryBytes > memoryBudgetBytes) return null

        // Metadata inspection has already validated the complete header and signature.
        val reader = PsdReader(bytes)
        reader.position = 4
        val version = reader.readShort()
        if (version != VERSION && version != VERSION_PSB) return null
        val isPsb = version == VERSION_PSB
        reader.skip(6)
        val channelCount = reader.readShort()
        val height = reader.readInt()
        val width = reader.readInt()
        val depth = reader.readShort()
        val colorMode = reader.readShort()
        if (!isSupportedReadHeader(width, height, channelCount, depth, colorMode)) return null

        // Colour mode data.
        val colorModeLength = reader.readInt()
        if (colorModeLength < 0 || colorModeLength > reader.remaining()) return null
        reader.skip(colorModeLength)

        // The metadata pass has already validated every resource boundary and extracted its facts.
        val resourcesLength = reader.readInt()
        if (resourcesLength < 0 || resourcesLength > reader.remaining()) return null
        reader.skip(resourcesLength)

        // Layer and mask information.
        val layerAndMaskLength = if (isPsb) reader.readLong() else reader.readInt().toLong()
        if (layerAndMaskLength < 0 || layerAndMaskLength > reader.remaining()) return null
        val layers = mutableListOf<PsdLayer>()
        if (layerAndMaskLength > 0) {
            val sectionEnd = (reader.position + layerAndMaskLength).toInt().coerceAtMost(reader.position + reader.remaining())
            val layerInfoLength = if (isPsb) reader.readLong() else reader.readInt().toLong()
            if (layerInfoLength < 0 || layerInfoLength > reader.remaining()) return null
            if (layerInfoLength > 0) {
                val layerInfoEnd = (reader.position + layerInfoLength).toInt().coerceAtMost(sectionEnd)
                // A damaged layer record is a damaged file: the document reads as not a PSD, as documented.
                layers +=
                    try {
                        readLayerRecords(reader, depth, colorMode, isPsb)
                    } catch (damaged: IllegalArgumentException) {
                        return null
                    }
                reader.position = layerInfoEnd
            }
            reader.position = sectionEnd
        }

        // Image data (composite).
        if (reader.remaining() == 1) return null
        val composite =
            if (reader.remaining() >= 2) {
                try {
                    readImageData(reader, width, height, channelCount, depth, colorMode, info.mergedTransparencyChannel, isPsb)
                        ?: return null
                } catch (_: IllegalArgumentException) {
                    return null
                }
            } else {
                null
            }

        return PsdDocument(
            width = width,
            height = height,
            layers = layers,
            composite = composite,
            dpi = info.dpi,
        )
    }

    private data class PendingLayer(
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

    private data class ExtraLayerData(
        val name: String,
        val section: Section,
        val folderBlend: BlendMode?,
        val maskHeader: MaskHeader?,
    )

    private data class ExtraBlocksResult(
        val name: String? = null,
        val section: Section? = null,
        val folderBlend: BlendMode? = null,
    )

    private fun readLayerRecords(
        reader: PsdReader,
        depth: Int,
        colorMode: Int,
        isPsb: Boolean = false,
    ): List<PsdLayer> {
        val layerCount = kotlin.math.abs(reader.readShort().toShort().toInt())
        require(layerCount <= 1024) { "Too many PSD layers" }
        if (layerCount == 0) return emptyList()

        val pending = mutableListOf<PendingLayer>()
        for (i in 0 until layerCount) {
            val layer = readSingleLayerPending(reader, i, isPsb) ?: break
            pending += layer
        }

        val layers = mutableListOf<PsdLayer>()
        pending.forEach { record ->
            val layerWidthLong = (record.right.toLong() - record.left).coerceAtLeast(0L)
            val layerHeightLong = (record.bottom.toLong() - record.top).coerceAtLeast(0L)
            val (maskWidth, maskHeight) = maskDimensions(record.mask)
            if (record.section != Section.NONE) {
                layers += decodeFolderLayer(record, reader, depth, isPsb, maskWidth, maskHeight)
                return@forEach
            }
            if (layerWidthLong == 0L || layerHeightLong == 0L) {
                record.channels.forEach { (_, length) -> reader.skip(max(0, length)) }
                return@forEach
            }
            require(layerWidthLong <= Int.MAX_VALUE.toLong() && layerHeightLong <= Int.MAX_VALUE.toLong()) {
                "A PSD layer is too large"
            }
            layers +=
                decodeRasterLayer(
                    record,
                    reader,
                    layerWidthLong.toInt(),
                    layerHeightLong.toInt(),
                    depth,
                    colorMode,
                    isPsb,
                    maskWidth,
                    maskHeight,
                )
        }
        return layers
    }

    private fun readSingleLayerPending(
        reader: PsdReader,
        layerIndex: Int,
        isPsb: Boolean,
    ): PendingLayer? {
        if (reader.remaining() < 32) return null
        val top = reader.readInt()
        val left = reader.readInt()
        val bottom = reader.readInt()
        val right = reader.readInt()
        val layerChannelCount = reader.readShort()
        require(layerChannelCount <= MAX_CHANNELS) { "A PSD layer has too many channels" }
        val channels =
            (0 until layerChannelCount).map {
                val id = reader.readShort().toShort().toInt()
                val length = if (isPsb) reader.readLong() else reader.readInt().toLong()
                require(length in 0L..Int.MAX_VALUE.toLong()) { "Invalid PSD channel length" }
                id to length.toInt()
            }
        val blendSignature = reader.readAscii(4)
        val blendKey = reader.readAscii(4)
        val opacity = reader.readByte()
        val isClippingMask = reader.readByte() == 1
        val flags = reader.readByte()
        reader.skip(1)
        val extraLength = reader.readInt()
        require(extraLength in 0..reader.remaining()) { "Truncated PSD extra layer data" }
        val extraEnd = reader.position + extraLength

        val extra = parseExtraLayerData(reader, extraLength, extraEnd, layerIndex)
        reader.position = extraEnd

        return PendingLayer(
            name = extra.name,
            top = top,
            left = left,
            bottom = bottom,
            right = right,
            opacity = opacity,
            isVisible = (flags and 0x02) == 0,
            blendMode = extra.folderBlend ?: blendModeFromKey(if (blendSignature == "8BIM") blendKey else "norm"),
            channels = channels,
            isClippingMask = isClippingMask,
            section = extra.section,
            mask = extra.maskHeader,
        )
    }

    private fun parseExtraLayerData(
        reader: PsdReader,
        extraLength: Int,
        extraEnd: Int,
        layerIndex: Int,
    ): ExtraLayerData {
        var name = "Layer ${layerIndex + 1}"
        var section = Section.NONE
        var folderBlend: BlendMode? = null
        var maskHeader: MaskHeader? = null
        if (extraLength <= 0 || reader.remaining() < 8) {
            return ExtraLayerData(name, section, folderBlend, maskHeader)
        }
        val maskLength = reader.readInt()
        require(maskLength >= 0) { "Invalid PSD mask length" }
        val maskStart = reader.position
        if (maskLength >= MASK_HEADER_LENGTH && reader.remaining() >= MASK_HEADER_LENGTH) {
            val maskTop = reader.readInt()
            val maskLeft = reader.readInt()
            val maskBottom = reader.readInt()
            val maskRight = reader.readInt()
            maskHeader = MaskHeader(maskTop, maskLeft, maskBottom, maskRight, reader.readByte(), reader.readByte())
        }
        reader.position = (maskStart.toLong() + maskLength).coerceAtMost(extraEnd.toLong()).toInt()
        if (reader.position + 4 <= extraEnd) {
            val blendingRangesLength = reader.readInt()
            require(blendingRangesLength >= 0) { "Invalid PSD blending ranges length" }
            reader.position = (reader.position.toLong() + blendingRangesLength).coerceAtMost(extraEnd.toLong()).toInt()
        }
        if (reader.position < extraEnd) {
            val nameLength = reader.readByte()
            if (nameLength > 0) name = reader.readAscii(nameLength)
            reader.skip((4 - (nameLength + 1) % 4) % 4)
        }
        val blocks = parseExtraBlocks(reader, extraEnd)
        return ExtraLayerData(
            name = blocks.name ?: name,
            section = blocks.section ?: section,
            folderBlend = blocks.folderBlend ?: folderBlend,
            maskHeader = maskHeader,
        )
    }

    private fun parseExtraBlocks(
        reader: PsdReader,
        extraEnd: Int,
    ): ExtraBlocksResult {
        var name: String? = null
        var section: Section? = null
        var folderBlend: BlendMode? = null
        var scanPosition = reader.position
        while (scanPosition.toLong() + 12L <= extraEnd.toLong()) {
            reader.position = scanPosition
            val signature = reader.readAscii(4)
            if (signature != "8BIM" && signature != "8B64") break
            val key = reader.readAscii(4)
            val length = reader.readInt()
            val blockStart = reader.position
            if (length < 0 || length.toLong() > (extraEnd - blockStart).toLong()) break
            if (key == "luni") {
                val unicodeName = reader.readUnicodeString(length)
                if (unicodeName.isNotBlank()) name = unicodeName
            } else if ((key == "lsct" || key == "lsdk") && length >= 4) {
                val (sec, blend) = parseFolderSection(reader, length)
                section = sec
                if (blend != null) folderBlend = blend
            }
            val nextScan = blockStart.toLong() + length.toLong() + (length % 2).toLong()
            if (nextScan > extraEnd.toLong()) break
            scanPosition = nextScan.toInt()
        }
        return ExtraBlocksResult(name, section, folderBlend)
    }

    private fun parseFolderSection(
        reader: PsdReader,
        length: Int,
    ): Pair<Section, BlendMode?> {
        val section = Section.of(reader.readInt())
        val folderBlend =
            if (length >= LSCT_LENGTH && reader.readAscii(4) == "8BIM") {
                val blend = reader.readAscii(4)
                if (blend == PASS_THROUGH_KEY) BlendMode.PASS_THROUGH else blendModeFromKey(blend)
            } else {
                null
            }
        return section to folderBlend
    }

    private fun decodeFolderLayer(
        record: PendingLayer,
        reader: PsdReader,
        depth: Int,
        isPsb: Boolean,
        maskWidth: Int,
        maskHeight: Int,
    ): PsdLayer {
        var maskPlane: ByteArray? = null
        record.channels.forEach { (channelId, length) ->
            require(length <= reader.remaining()) { "Truncated PSD folder channel" }
            val start = reader.position
            if (channelId == CHANNEL_USER_MASK && maskWidth > 0 && maskHeight > 0) {
                maskPlane = readChannel(reader, maskWidth, maskHeight, depth, length, isPsb)
            }
            reader.position = start + length
        }
        return if (record.section == Section.GROUP_END) {
            PsdLayer.groupEnd()
        } else {
            PsdLayer
                .groupHeader(record.name, record.opacity, record.isVisible, record.blendMode)
                .copy(
                    section = record.section,
                    mask = record.mask?.let { maskOf(it, record.left, record.top, maskPlane) },
                )
        }
    }

    private fun decodeRasterLayer(
        record: PendingLayer,
        reader: PsdReader,
        layerWidth: Int,
        layerHeight: Int,
        depth: Int,
        colorMode: Int,
        isPsb: Boolean,
        maskWidth: Int,
        maskHeight: Int,
    ): PsdLayer {
        require(CanvasOperations.isSizeSafe(layerWidth, layerHeight)) { "A PSD layer is too large" }
        val planes = HashMap<Int, ByteArray>()
        record.channels.forEach { (channelId, length) ->
            require(length <= reader.remaining()) { "Truncated PSD layer channel" }
            val start = reader.position
            if (channelId != CHANNEL_USER_MASK) {
                planes[channelId] = readChannel(reader, layerWidth, layerHeight, depth, length, isPsb)
            } else if (maskWidth > 0 && maskHeight > 0) {
                planes[channelId] = readChannel(reader, maskWidth, maskHeight, depth, length, isPsb)
            }
            reader.position = start + length
        }
        val buffer = decodeLayerPixels(planes, layerWidth, layerHeight, colorMode)
        return PsdLayer(
            name = record.name,
            pixels = buffer,
            opacity = record.opacity,
            isVisible = record.isVisible,
            blendMode = record.blendMode,
            left = record.left,
            top = record.top,
            isClippingMask = record.isClippingMask,
            mask = record.mask?.let { maskOf(it, record.left, record.top, planes[CHANNEL_USER_MASK]) },
        )
    }

    private fun decodeLayerPixels(
        planes: Map<Int, ByteArray>,
        layerWidth: Int,
        layerHeight: Int,
        colorMode: Int,
    ): PixelBuffer {
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
                val g = if (colorMode == COLOR_MODE_GRAYSCALE) r else channelValue(green, index, colorMode)
                val b = if (colorMode == COLOR_MODE_GRAYSCALE) r else channelValue(blue, index, colorMode)
                val a = alpha?.getOrNull(index)?.toInt()?.and(0xFF) ?: 255
                buffer.pixels[index] = Channels.argb(a, r, g, b)
            }
        }
        return buffer
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
        transparencyChannel: Int?,
        isPsb: Boolean = false,
    ): PixelBuffer? {
        val compression = reader.readShort()
        val planes = readMergedPlanes(reader, compression, channelCount, width, height, depth, isPsb) ?: return null

        val buffer = PixelBuffer(width, height)
        val red = planes[CHANNEL_RED]
        val green = planes[CHANNEL_GREEN]
        val blue = planes[CHANNEL_BLUE]
        // The metadata pass resolved explicit transparency to one exact channel. Arbitrary saved
        // selections/spot channels must never replace it, including those before an identified ID.
        val alpha = transparencyChannel?.let { planes[it] }
        if (colorMode == COLOR_MODE_CMYK) {
            val c = planes[0]
            val m = planes[1]
            val y = planes[2]
            val k = planes[3]
            for (index in buffer.pixels.indices) {
                val kk = k?.getOrNull(index)?.toInt()?.and(0xFF) ?: 0
                val a = alpha?.getOrNull(index)?.toInt()?.and(0xFF) ?: 255
                buffer.pixels[index] =
                    Channels.argb(
                        a,
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
                if (alpha != null) {
                    Channels.argb(a, removeWhiteMatte(r, a), removeWhiteMatte(g, a), removeWhiteMatte(b, a))
                } else {
                    Channels.argb(a, r, g, b)
                }
        }
        return buffer
    }

    /** Inverse of the merged RGB/grayscale white matte, with 8-bit quantization at low alpha. */
    private fun removeWhiteMatte(
        value: Int,
        alpha: Int,
    ): Int = if (alpha in 1..254) ((255 * (value + alpha - 255) + alpha / 2) / alpha).coerceIn(0, 255) else value

    /** Unprofiled CMYK approximation: PSD stores 255 for no ink and 0 for full ink. */
    private fun cmykToRgb(
        plane: ByteArray?,
        index: Int,
        black: Int,
    ): Int {
        val component = plane?.getOrNull(index)?.toInt()?.and(0xFF) ?: 0
        return (component * black + 127) / 255
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
        isPsb: Boolean = false,
    ): ByteArray {
        require(declaredLength >= 2 && declaredLength <= reader.remaining()) { "Truncated PSD channel" }
        // A channel's decoder must never borrow bytes from the next channel, even when a
        // malformed row table or PackBits run would otherwise produce the expected pixel count.
        val channelEnd = reader.position + declaredLength
        val bytesPerSample = if (depth == DEPTH_16) 2 else 1
        val rowBytesLong = width.toLong() * bytesPerSample
        val planeBytesLong = rowBytesLong * height
        require(planeBytesLong in 0L..Int.MAX_VALUE.toLong()) { "A PSD channel is too large" }
        val rowBytes = rowBytesLong.toInt()
        val planeBytes = planeBytesLong.toInt()
        val compression = reader.readShort()
        val plane =
            when (compression) {
                COMPRESSION_RAW -> {
                    require(channelEnd - reader.position >= planeBytes) { "Truncated raw PSD channel" }
                    val raw = reader.readBytes(planeBytes)
                    downsample(raw, width, height, depth)
                }

                COMPRESSION_RLE -> {
                    val countBytes = if (isPsb) 4L else 2L
                    require((channelEnd - reader.position).toLong() >= height.toLong() * countBytes) { "Truncated PSD row-length table" }
                    val rowLengths = IntArray(height) { if (isPsb) reader.readInt() else reader.readShort() }
                    require(rowLengths.all { it >= 0 } && rowLengths.sumOf { it.toLong() } <= (channelEnd - reader.position).toLong()) {
                        "PSD rows exceed their channel"
                    }
                    // A 16-bit row holds two bytes per pixel, so it is decoded at its full width before downsampling.
                    val out = ByteArray(planeBytes)
                    for (row in 0 until height) {
                        val decoded = decodeRleRow(reader, rowBytes, reader.position + rowLengths[row])
                        System.arraycopy(decoded, 0, out, row * rowBytes, rowBytes)
                    }
                    downsample(out, width, height, depth)
                }

                COMPRESSION_ZIP, COMPRESSION_ZIP_PREDICTION -> {
                    // A layer channel is one zlib stream whose length is the record's declared length, with no size prefix.
                    val size = channelEnd - reader.position
                    require(size > 0) { "Empty ZIP PSD channel" }
                    val decoded = inflate(reader.readBytes(size), planeBytes)
                    require(decoded.size == planeBytes) { "Incomplete ZIP PSD channel" }
                    if (compression == COMPRESSION_ZIP_PREDICTION) undoPrediction(decoded, width, height, bytesPerSample)
                    downsample(decoded, width, height, depth)
                }

                else -> throw IllegalArgumentException("Unsupported PSD channel compression")
            }
        reader.position = channelEnd
        return plane
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
        require(width >= 0) { "Invalid PSD scanline width" }
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
        isPsb: Boolean = false,
    ): HashMap<Int, ByteArray>? {
        val planes = HashMap<Int, ByteArray>()
        val channelIds = (0 until channelCount).toList()

        val bytesPerSample = if (depth == 16) 2 else 1
        val planeBytesLong = width.toLong() * height * bytesPerSample
        if (planeBytesLong !in 0L..Int.MAX_VALUE.toLong()) return null
        val planeBytes = planeBytesLong.toInt()
        when (compression) {
            COMPRESSION_RAW -> {
                // Merged planes are the last file section and contain no padding.
                if (planeBytesLong * channelCount != reader.remaining().toLong()) return null
                channelIds.forEach { id ->
                    planes[id] = reader.readBytes(planeBytes)
                }
            }

            COMPRESSION_RLE -> {
                planes.putAll(readMergedRlePlanes(reader, channelIds, width * bytesPerSample, height, isPsb))
            }

            COMPRESSION_ZIP, COMPRESSION_ZIP_PREDICTION -> {
                // The merged image is one zlib stream: each channel's whole plane follows the one before it.
                val totalBytesLong = planeBytesLong * channelIds.size
                if (totalBytesLong !in 0L..Int.MAX_VALUE.toLong()) return null
                val totalBytes = totalBytesLong.toInt()
                val stream = inflate(reader.readBytes(reader.remaining()), totalBytes)
                if (stream.size < totalBytes) return null
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

    /** Keeps the high byte of each 16-bit sample. */
    private fun highBytes(samples: ByteArray): ByteArray = ByteArray(samples.size / 2) { samples[it * 2] }

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
            position = (position.toLong() + count.toLong()).coerceIn(0L, bytes.size.toLong()).toInt()
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

        fun readLong(): Long {
            val high = readInt().toLong() and 0xFFFFFFFFL
            val low = readInt().toLong() and 0xFFFFFFFFL
            return (high shl 32) or low
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
            // Layer names must not allocate a second copy of an attacker-sized metadata block.
            // The surrounding block reader advances to the declared end after this bounded read.
            val raw = readBytes(min(length, 8196))
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

/** Each merged scanline is bounded by its declared byte count, including PackBits no-ops. */
private fun readMergedRlePlanes(
    reader: PsdCodec.PsdReader,
    channelIds: List<Int>,
    rowBytes: Int,
    height: Int,
    isPsb: Boolean,
): HashMap<Int, ByteArray> {
    val rowCount = channelIds.size * height
    val countBytes = if (isPsb) 4L else 2L
    require(reader.remaining().toLong() >= rowCount.toLong() * countBytes) { "Truncated merged PSD row-length table" }
    val rowLengths = IntArray(rowCount) { if (isPsb) reader.readInt() else reader.readShort() }
    require(rowLengths.all { it >= 0 } && rowLengths.sumOf { it.toLong() } == reader.remaining().toLong()) {
        "Merged PSD row lengths do not match the payload"
    }
    val planes = HashMap<Int, ByteArray>()
    channelIds.forEachIndexed { channel, id ->
        val out = ByteArray(rowBytes * height)
        for (row in 0 until height) {
            val rowEnd = reader.position + rowLengths[channel * height + row]
            val decoded = decodeRleRow(reader, rowBytes, rowEnd)
            System.arraycopy(decoded, 0, out, row * rowBytes, rowBytes)
        }
        planes[id] = out
    }
    return planes
}

/** A row may neither borrow the next row's bytes nor pad or truncate decoded samples. */
private fun decodeRleRow(
    reader: PsdCodec.PsdReader,
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

/** Inflates exactly [limit] bytes, rejecting truncated or oversized streams without expanding beyond the budget. */
private fun inflate(
    bytes: ByteArray,
    limit: Int,
): ByteArray {
    val inflater = java.util.zip.Inflater()
    return try {
        inflater.setInput(bytes)
        val initialSize = minOf(limit.toLong(), bytes.size.toLong() * 4, 1024L * 1024).toInt()
        val out = ByteArrayOutputStream(initialSize)
        val buffer = ByteArray(8192)
        while (!inflater.finished() && out.size() < limit) {
            val read = inflater.inflate(buffer, 0, min(buffer.size, limit - out.size()))
            if (read == 0) break
            out.write(buffer, 0, read)
        }
        if (out.size() != limit) return ByteArray(0)
        // Consume the trailer if the decoder reached its output limit first. One extra
        // output byte proves the stream exceeds the declared plane dimensions.
        if (!inflater.finished() && (inflater.inflate(buffer, 0, 1) != 0 || !inflater.finished())) return ByteArray(0)
        out.toByteArray()
    } catch (_: java.util.zip.DataFormatException) {
        // Callers reject an incomplete stream; damaged pixels must not silently become zeroes.
        ByteArray(0)
    } finally {
        inflater.end()
    }
}
