package com.artflow.studio.data.export

import com.artflow.studio.core.canvas.CanvasOperations
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportNaming
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.FitMode
import com.artflow.studio.core.pixels.ImportMemoryBudget
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.layer.LayerBlendContract
import kotlin.math.roundToInt

/** Describes existing pixels without copying them, including crops that have not happened yet. */
internal data class StillExportInput(
    val composite: PixelBuffer,
    val layers: List<LayerRaster>,
    val options: ExportOptions,
    val hasAdjustmentLayers: Boolean = false,
    val groups: List<Layer> = emptyList(),
    val masks: Map<Long, PixelBuffer> = emptyMap(),
    val materials: Map<MaterialChannel, PixelBuffer> = emptyMap(),
    val crop: IntBounds? = null,
)

/**
 * Admits additional live storage before preparing any export pixels. Existing snapshots are already
 * reflected in current heap headroom. Retained prepared layers, masks and output buffers are charged
 * together; sequential scratch buffers are charged at their peak, rather than once per layer.
 *
 * The allowance uses the same conservative heap/headroom policy as image imports. A supplied limit
 * can only narrow it. Encoded sizes assume incompressible pixels, never the compressibility of a fixture.
 */
internal object StillExportMemory {
    const val MESSAGE = "This export needs too much memory for this device. Reduce the output scale or export fewer layers."
    const val MAX_MODEL_BYTES = 64L * 1024 * 1024
    private const val BOOKKEEPING = 64L * 1024
    private const val CODEC_WORKSPACE = 1024L * 1024
    private const val NODE_BYTES = 16L * 1024

    fun requireFits(
        input: StillExportInput,
        limitBytes: Long = Long.MAX_VALUE,
        modelBytes: Long = 0,
    ) {
        require(modelBytes in 0..MAX_MODEL_BYTES) { "The 3D model is empty or too large" }
        Estimate(input, limitBytes).check(modelBytes)
    }

    /** PDF keeps completed pages until writeTo; admission must cover every page's embedded image. */
    fun requirePdfFits(
        frames: List<PixelBuffer>,
        options: ExportOptions,
        limitBytes: Long,
    ) {
        val budget = Budget(limitBytes)
        var imageBytes = 0L
        var work = 0L
        for (frame in frames) {
            val source = Size(frame.width, frame.height)
            val image = pdfSize(source, options)
            imageBytes += image.bytes
            // Rendered copy, optional flattened copy, platform bitmap and native page workspace.
            work = maxOf(work, image.bytes * (if (options.flattenOntoBackground) 5 else 4))
            budget.temporary(work + imageBytes * 5 + CODEC_WORKSPACE)
        }
    }

    private class Estimate(
        private val input: StillExportInput,
        limitBytes: Long,
    ) {
        private val budget = Budget(limitBytes)
        private val options = input.options
        private val original = size(input.composite)
        private val target = ExportNaming.resolveSize(original.width, original.height, options).let { Size(it.first, it.second) }
        private var deferredMaskWorkspace = 0L

        fun check(modelBytes: Long) {
            target.checkSafe()
            if (input.crop != null) {
                budget.retain(original.bytes)
                input.layers.forEach { budget.retain(size(it.buffer).bytes) }
                input.masks.values.forEach { budget.retain(size(it).bytes) }
            }
            prepare(original, retain = true)
            when (options.format) {
                ExportFormat.PNG -> budget.temporary(flattenBytes() + pngWorkspace(target))
                ExportFormat.TIFF -> budget.temporary(flattenBytes() + target.bytes + BOOKKEEPING)
                ExportFormat.JPEG -> {
                    // Matte pixels, Bitmap, native codec planes; encoded bytes, density/ICC copies
                    // and ByteArrayOutputStream growth. JPEG's bound allows 16 bytes per pixel.
                    budget.temporary(flattenBytes() + target.bytes * 28 + CODEC_WORKSPACE)
                }
                ExportFormat.WEBP -> {
                    // Lossless WebP retains hash chains and backward references in addition to pixels.
                    budget.temporary(flattenBytes() + target.bytes * 18 + CODEC_WORKSPACE)
                }
                ExportFormat.PDF -> {
                    val image = pdfSize(target, options)
                    budget.temporary(image.bytes * (if (options.flattenOntoBackground) 10 else 9) + CODEC_WORKSPACE)
                }
                ExportFormat.PSD, ExportFormat.PROCREATE -> layered()
                ExportFormat.LAYER_PNGS -> layerPngs()
                ExportFormat.MODEL_OBJ, ExportFormat.MODEL_GLB -> model(modelBytes)
                else -> Unit // The caller reports unsupported formats without encoding them.
            }
        }

        private fun size(buffer: PixelBuffer): Size {
            val crop = input.crop ?: return Size(buffer.width, buffer.height)
            val left = crop.left.coerceIn(0, buffer.width - 1)
            val top = crop.top.coerceIn(0, buffer.height - 1)
            val right = crop.right.coerceIn(left, buffer.width - 1)
            val bottom = crop.bottom.coerceIn(top, buffer.height - 1)
            return Size(right - left + 1, bottom - top + 1)
        }

        /** Returns newly retained pixels; profiles and fit intermediates are admitted together. */
        private fun prepare(
            source: Size,
            retain: Boolean,
            convertProfile: Boolean = true,
        ): Long {
            val converted = if (convertProfile && options.colorProfile != options.outputProfile) source.bytes else 0L
            if (source == target) {
                budget.temporary(converted)
                if (retain) budget.retain(converted)
                return converted
            }
            val placement = ExportNaming.applyFit(source.width, source.height, target.width, target.height, options.fitMode)
            val drawn = Size(placement.drawWidth, placement.drawHeight).also { it.checkSafe() }
            val fitted = if (options.fitMode == FitMode.STRETCH) 0L else target.bytes
            budget.temporary(converted + drawn.bytes + fitted)
            if (retain) budget.retain(target.bytes)
            return converted + drawn.bytes + fitted
        }

        private fun flattenBytes(): Long = if (options.flattenOntoBackground) target.bytes else 0L

        private fun layerPngs() {
            // The ZIP retains previous entries while the next layer is prepared and encoded.
            val zip = encodedBound(pngBound(target) * (input.layers.size.toLong() + 1))
            var preparation = 0L
            input.layers.forEach { preparation = maxOf(preparation, prepare(size(it.buffer), retain = false)) }
            budget.temporary(zip * 3 + maxOf(preparation, flattenBytes()) + pngWorkspace(target))
        }

        private fun model(modelBytes: Long) {
            var mapWork = 0L
            input.materials.forEach { (channel, map) ->
                require(channel.isScalar) { "The color texture is exported separately" }
                val source = Size(map.width, map.height)
                // resolved() owns a grayscale source-sized copy, then scaling can own another plane.
                mapWork = maxOf(mapWork, source.bytes + if (source == target) 0L else target.bytes)
            }
            val zip = encodedBound(pngBound(target) * (input.materials.size.toLong() + 1) + modelBytes)
            // Model input growth, UTF-16 decoding, the rewritten OBJ and its UTF-8 copy coexist.
            budget.temporary(modelBytes * 20 + zip * 3 + mapWork + flattenBytes() + pngWorkspace(target))
        }

        private fun layered() {
            val psd = options.format == ExportFormat.PSD
            budget.retain((input.groups.size.toLong() + input.layers.size) * 512)
            val groups = input.groups.associateBy { it.id }
            var open = emptyList<Layer>()
            var pixelLayers = 0L
            var maskCount = 0L
            var records = 0L
            var metadata = BOOKKEEPING
            var resizedMasks = false
            for (entry in input.layers) {
                val chain = ancestors(entry.layer, groups)
                if (psd && !options.includeHiddenLayers && chain.any { !it.isVisible }) continue
                val shared = open.zip(chain).takeWhile { (a, b) -> a.id == b.id }.size
                chain.drop(shared).forEach { group ->
                    val nodeBytes = NODE_BYTES + group.name.length.toLong() * 16
                    budget.retain(nodeBytes)
                    metadata += nodeBytes
                    records += if (psd) 2 else 1
                    if (psd && input.masks[group.id] != null) {
                        resizedMasks = mask(group, deferred = true) || resizedMasks
                        maskCount++
                    }
                }
                open = chain
                prepare(size(entry.buffer), retain = true)
                if (input.masks[entry.layer.id] != null) {
                    resizedMasks = mask(entry.layer) || resizedMasks
                    maskCount++
                }
                pixelLayers++
                records++
                val nodeBytes = NODE_BYTES + entry.name.length.toLong() * 16
                budget.retain(nodeBytes)
                metadata += nodeBytes
            }
            // PSD headers (and their masks) are emitted when folders close, after their
            // prepared children remain in the record list. Coverage is already retained above;
            // check the largest deferred resize against the final retained output total too.
            budget.temporary(deferredMaskWorkspace)
            val rendered =
                input.hasAdjustmentLayers ||
                    resizedMasks ||
                    if (psd) {
                        input.layers.any { it.layer.blendContract == LayerBlendContract.PROCREATE_DARKEN_19 }
                    } else {
                        input.groups.any { it.blendMode != BlendMode.PASS_THROUGH || it.hasActiveMask() }
                    }
            if (rendered || psd && options.flattenOntoBackground) {
                pixelLayers++
                records++
                if (psd) budget.retain(flattenBytes())
            }
            if (psd) {
                // Empty PSD stacks acquire an Artwork layer inside the codec.
                if (records == 0L) pixelLayers++
                require(records <= 1024) { "Too many PSD layers" }
                val plane =
                    if (options.psdUseRle) {
                        (target.width + (target.width + 127L) / 128 + 2) * target.height + 2
                    } else {
                        target.pixels + 2
                    }
                val encoded = plane * ((pixelLayers + 1) * 4 + maskCount) + metadata
                // Channel payloads, channelData, layerSection, final output and their growth/copies.
                budget.temporary(flattenBytes() + encoded * 12 + target.bytes * 3 + CODEC_WORKSPACE)
            } else {
                val tiles = ((target.width + 255L) / 256) * ((target.height + 255L) / 256)
                val planes = (pixelLayers + 1) * 4 + maskCount
                // Edge tiles remain 256 square even for a one-pixel canvas. ZIP indexes also stay live.
                val encoded = encodedBound(tiles * 256 * 256 * planes + metadata + tiles * planes * 1024)
                budget.temporary(encoded * 3 + metadata + CODEC_WORKSPACE * 4)
            }
        }

        private fun mask(
            layer: Layer,
            deferred: Boolean = false,
        ): Boolean {
            val source = size(input.masks.getValue(layer.id))
            val preparation = prepare(source, retain = false, convertProfile = false)
            // The resized ARGB mask coexists with the byte coverage retained by the writer.
            val resized = if (source == target) 0L else target.bytes
            budget.temporary(resized + target.pixels)
            if (deferred) deferredMaskWorkspace = maxOf(deferredMaskWorkspace, preparation, resized)
            budget.retain(target.pixels)
            val placement = ExportNaming.applyFit(source.width, source.height, target.width, target.height, options.fitMode)
            return layer.maskEnabled && (placement.drawWidth != source.width || placement.drawHeight != source.height)
        }
    }

    private fun ancestors(
        layer: Layer,
        groups: Map<Long, Layer>,
    ): List<Layer> {
        val chain = ArrayDeque<Layer>()
        var parent = layer.parentGroupId?.let(groups::get)
        while (parent != null) {
            val found = parent
            if (chain.any { it.id == found.id }) break
            chain.addFirst(found)
            parent = found.parentGroupId?.let(groups::get)
        }
        return chain.toList()
    }

    private fun pdfSize(
        source: Size,
        options: ExportOptions,
    ): Size {
        val scale = options.pdfOversample.coerceIn(0.25f, 3f)
        return Size(maxOf(1, (source.width * scale).roundToInt()), maxOf(1, (source.height * scale).roundToInt()))
            .also { it.checkSafe() }
    }

    private fun encodedBound(rawBytes: Long): Long = rawBytes + rawBytes / 8 + BOOKKEEPING

    private fun pngBound(size: Size): Long = encodedBound(size.bytes + size.height)

    private fun pngWorkspace(size: Size): Long = pngBound(size) * 3 + size.width * 4L + CODEC_WORKSPACE

    private data class Size(
        val width: Int,
        val height: Int,
    ) {
        val pixels: Long get() = width.toLong() * height
        val bytes: Long get() = pixels * 4

        fun checkSafe() {
            require(CanvasOperations.isSizeSafe(width, height)) { "The export image is too large; reduce its scale" }
        }
    }

    private class Budget(
        limitBytes: Long,
    ) {
        private val available = minOf(limitBytes, ImportMemoryBudget.currentBytes())
        private var retained = BOOKKEEPING

        init {
            temporary(0)
        }

        fun temporary(bytes: Long) {
            require(bytes >= 0 && bytes <= available - retained) { MESSAGE }
        }

        fun retain(bytes: Long) {
            temporary(bytes)
            retained += bytes
        }
    }
}
