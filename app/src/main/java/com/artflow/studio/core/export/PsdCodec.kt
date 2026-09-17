package com.artflow.studio.core.export

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
 * blend mode, layer names, RLE (PackBits) compression and a correctly flattened composite.
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

    private const val COMPRESSION_RAW = 0
    private const val COMPRESSION_RLE = 1
    private const val COMPRESSION_ZIP = 2
    private const val COMPRESSION_ZIP_PREDICTION = 3

    private const val CHANNEL_RED = 0
    private const val CHANNEL_GREEN = 1
    private const val CHANNEL_BLUE = 2
    private const val CHANNEL_ALPHA = -1

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
                    .toLong()
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
        val top = layer.top
        val left = layer.left
        val bottom = top + pixels.height
        val right = left + pixels.width

        out.writeInt(top)
        out.writeInt(left)
        out.writeInt(bottom)
        out.writeInt(right)

        val channelIds = intArrayOf(CHANNEL_RED, CHANNEL_GREEN, CHANNEL_BLUE, CHANNEL_ALPHA)
        out.writeShort(channelIds.size)

        // Channel data lengths are needed before the data itself, so compute them first.
        val channelPayloads =
            channelIds.map { channelId ->
                val plane = extractChannel(pixels, channelId)
                if (useRle) encodeRle(plane, pixels.width, pixels.height) else rawBytes(plane)
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

        // Extra data: mask (none), blending ranges (none), name (pascal, padded to 4).
        val extra = ByteArrayOutputStream()
        extra.writeInt(0) // layer mask data length
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
        val extraBytes = extra.toByteArray()
        out.writeInt(extraBytes.size)
        out.write(extraBytes)
    }

    private fun layerChannelData(
        layer: PsdLayer,
        useRle: Boolean,
    ): ByteArray {
        val pixels = layer.pixels
        val out = ByteArrayOutputStream()
        intArrayOf(CHANNEL_RED, CHANNEL_GREEN, CHANNEL_BLUE, CHANNEL_ALPHA).forEach { channelId ->
            val plane = extractChannel(pixels, channelId)
            if (useRle) {
                out.writeShort(COMPRESSION_RLE)
                out.write(encodeRle(plane, pixels.width, pixels.height))
            } else {
                out.writeShort(COMPRESSION_RAW)
                out.write(rawBytes(plane))
            }
        }
        return out.toByteArray()
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
                    extractChannel(image, CHANNEL_RED),
                    extractChannel(image, CHANNEL_GREEN),
                    extractChannel(image, CHANNEL_BLUE),
                    extractChannel(image, CHANNEL_ALPHA),
                )
            // The RLE byte-count table lists every row of every channel up front.
            val encoded = planes.map { encodeRlePayload(it, image.width, image.height) }
            encoded.forEach { payload -> payload.counts.forEach { out.writeShort(it) } }
            encoded.forEach { payload -> out.write(payload.data) }
        } else {
            out.writeShort(COMPRESSION_RAW)
            listOf(CHANNEL_RED, CHANNEL_GREEN, CHANNEL_BLUE, CHANNEL_ALPHA).forEach { channelId ->
                out.write(rawBytes(extractChannel(image, channelId)))
            }
        }
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
        if (width <= 0 || height <= 0 || width > 30_000 || height > 30_000) return null
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
        if (layerAndMaskLength > 0) {
            val sectionEnd = reader.position + layerAndMaskLength
            val layerInfoLength = reader.readInt()
            if (layerInfoLength > 0) {
                val layerInfoEnd = reader.position + layerInfoLength
                layers += readLayerRecords(reader, width, height, channelCount, depth, colorMode)
                reader.position = layerInfoEnd
            }
            reader.position = sectionEnd
        }

        // Image data (composite).
        val composite =
            if (reader.remaining() >= 2) {
                readImageData(reader, width, height, channelCount, depth, colorMode)
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
        )

        val pending = mutableListOf<Pending>()
        for (i in 0 until layerCount) {
            if (reader.remaining() < 32) break
            val top = reader.readInt()
            val left = reader.readInt()
            val bottom = reader.readInt()
            val right = reader.readInt()
            val layerChannelCount = reader.readShort()
            val channels =
                (0 until layerChannelCount).map {
                    val id = reader.readShort().toShort().toInt()
                    val length = reader.readInt()
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
            if (extraLength > 0 && reader.remaining() >= 8) {
                val maskLength = reader.readInt()
                if (maskLength > 0) reader.skip(maskLength)
                val blendingRangesLength = reader.readInt()
                if (blendingRangesLength > 0) reader.skip(blendingRangesLength)
                if (reader.position < extraEnd) {
                    val nameLength = reader.readByte()
                    if (nameLength > 0) name = reader.readAscii(nameLength)
                    reader.skip((4 - (nameLength + 1) % 4) % 4)
                }
                // A "luni" block carries the UTF-16 name and is preferred when present.
                var scanPosition = reader.position
                while (scanPosition + 12 < extraEnd) {
                    reader.position = scanPosition
                    val signature = reader.readAscii(4)
                    if (signature != "8BIM") break
                    val key = reader.readAscii(4)
                    val length = reader.readInt()
                    if (length <= 0 || reader.remaining() < length) break
                    if (key == "luni") {
                        val unicodeName = reader.readUnicodeString(length)
                        if (unicodeName.isNotBlank()) name = unicodeName
                        break
                    }
                    scanPosition = reader.position + length + (length % 2)
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
                    blendMode = blendModeFromKey(if (blendSignature == "8BIM") blendKey else "norm"),
                    channels = channels,
                    isClippingMask = isClippingMask,
                )
        }

        // Channel image data follows the records, in the same order.
        val layers = mutableListOf<PsdLayer>()
        pending.forEach { record ->
            val layerWidth = (record.right - record.left).coerceAtLeast(0)
            val layerHeight = (record.bottom - record.top).coerceAtLeast(0)
            if (layerWidth == 0 || layerHeight == 0) {
                record.channels.forEach { (_, length) -> reader.skip(max(0, length)) }
                return@forEach
            }

            val planes = HashMap<Int, ByteArray>()
            record.channels.forEach { (channelId, length) ->
                val start = reader.position
                planes[channelId] = readChannel(reader, layerWidth, layerHeight, depth, length)
                // Trust the declared length over where the decoder ended up.
                reader.position = start + length
            }

            val buffer = PixelBuffer(max(1, layerWidth), max(1, layerHeight))
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
                    val g = channelValue(green, index, colorMode)
                    val b = channelValue(blue, index, colorMode)
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
                )
        }
        return layers
    }

    private fun readImageData(
        reader: PsdReader,
        width: Int,
        height: Int,
        channelCount: Int,
        depth: Int,
        colorMode: Int,
    ): PixelBuffer? {
        val compression = reader.readShort()
        val planes = HashMap<Int, ByteArray>()
        val channelIds =
            (0 until channelCount).map { index ->
                when (colorMode) {
                    COLOR_MODE_GRAYSCALE -> if (index == 0) CHANNEL_RED else CHANNEL_ALPHA
                    COLOR_MODE_CMYK -> index
                    else ->
                        when (index) {
                            0 -> CHANNEL_RED
                            1 -> CHANNEL_GREEN
                            2 -> CHANNEL_BLUE
                            else -> CHANNEL_ALPHA
                        }
                }
            }

        when (compression) {
            COMPRESSION_RAW -> {
                channelIds.forEach { id ->
                    val size = width * height * (if (depth == 16) 2 else 1)
                    planes[id] = reader.readBytes(min(size, reader.remaining()))
                }
            }
            COMPRESSION_RLE -> {
                // The byte-count table lists every row of every channel, then the row data follows.
                repeat(channelCount * height) { reader.readShort() }
                channelIds.forEach { id ->
                    val out = ByteArray(width * height)
                    for (row in 0 until height) {
                        val rowBytes = decodeRleRow(reader, width)
                        System.arraycopy(rowBytes, 0, out, row * width, min(width, rowBytes.size))
                    }
                    planes[id] = out
                }
            }
            COMPRESSION_ZIP, COMPRESSION_ZIP_PREDICTION -> {
                channelIds.forEach { id ->
                    // ZIP channels carry their own length prefix.
                    val size = reader.readInt()
                    if (size <= 0 || reader.remaining() < size) return@forEach
                    val compressed = reader.readBytes(size)
                    planes[id] = inflate(compressed)
                }
            }
            else -> return null
        }

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
            val g = channelValue(green, index, colorMode)
            val b = channelValue(blue, index, colorMode)
            val a = alpha?.getOrNull(index)?.toInt()?.and(0xFF) ?: 255
            buffer.pixels[index] = Channels.argb(a, r, g, b)
        }
        return buffer
    }

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
        if (declaredLength <= 0 || reader.remaining() < 2) return ByteArray(width * height)
        val compression = reader.readShort()
        return when (compression) {
            COMPRESSION_RAW -> {
                val raw = reader.readBytes(min(declaredLength - 2, reader.remaining()))
                downsample(raw, width, height, depth)
            }
            COMPRESSION_RLE -> {
                repeat(height) { reader.readShort() } // row byte counts
                val out = ByteArray(width * height)
                for (row in 0 until height) {
                    val rowBytes = decodeRleRow(reader, width)
                    System.arraycopy(rowBytes, 0, out, row * width, min(width, rowBytes.size))
                }
                downsample(out, width, height, 1)
            }
            COMPRESSION_ZIP, COMPRESSION_ZIP_PREDICTION -> {
                val size = reader.readInt()
                if (size <= 0 || reader.remaining() < size) {
                    ByteArray(width * height)
                } else {
                    downsample(inflate(reader.readBytes(size)), width, height, depth)
                }
            }
            else -> ByteArray(width * height)
        }
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
                control == 128 -> Unit // no-op
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

    private fun decodeRleRow(
        reader: PsdReader,
        width: Int,
    ): ByteArray = unpackBits(reader, width)

    private fun inflate(bytes: ByteArray): ByteArray =
        try {
            val inflater = java.util.zip.Inflater()
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
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
            else -> BlendMode.NORMAL
        }

    // -----------------------------------------------------------------------------------------
    // Small binary reader/writer helpers
    // -----------------------------------------------------------------------------------------

    /** Bounds-checked big-endian reader; every accessor returns 0 past the end of the data. */
    class PsdReader(
        private val bytes: ByteArray,
    ) {
        var position: Int = 0

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
