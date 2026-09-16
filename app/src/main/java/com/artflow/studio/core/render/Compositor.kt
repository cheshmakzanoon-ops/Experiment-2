package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.PixelBufferPool
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.brush.RectF
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Composites a layer stack into a single image.
 *
 * This is the single source of truth for "what the artwork looks like": the live canvas uploads the
 * result, the gallery thumbnail and the flattened preview are the same call, and every exporter
 * (PNG/JPEG/WebP/PDF/PSD/GIF/MP4) uses it too. That is what makes the app WYSIWYG.
 *
 * Handles, in stack order (bottom first):
 * - per-layer pixel content (raster base + vector strokes)
 * - filter layers (Phase 29)
 * - layer masks, alpha lock and clipping masks (Phases 15-16)
 * - all 16 blend modes with correct alpha compositing (Phase 12)
 * - adjustment layers, applied to everything below them (Phase 25)
 *
 * Working buffers are recycled through a [PixelBufferPool] so a composite pass does not allocate a
 * fresh canvas-sized buffer per layer (the allocation churn that produced GC pauses and OOM
 * crashes on large canvases).
 */
class Compositor(
    private val strokeRasterizer: StrokeRasterizer = StrokeRasterizer(),
) {
    /**
     * One layer's inputs. [raster] and [strokes] are both optional; a layer can be purely vector,
     * purely pixel, or a combination.
     */
    data class LayerInput(
        val layer: Layer,
        val raster: PixelBuffer? = null,
        val strokes: List<Stroke> = emptyList(),
        /** Grayscale mask image; its value multiplies the layer's alpha. */
        val mask: PixelBuffer? = null,
    )

    /** Options shared by every compositing entry point. */
    data class Options(
        val includeHiddenLayers: Boolean = false,
        /** Composite reference layers too (used by "export all layers"). */
        val includeReferenceLayers: Boolean = false,
        /** Restrict the result to a selection (export selection, fill selection). */
        val selection: SelectionMask? = null,
        /** Skip adjustment layers (used to preview raw layer content). */
        val applyAdjustments: Boolean = true,
    )

    private val bufferPool = PixelBufferPool()

    /**
     * Composites [inputs] (bottom layer first) into a new buffer.
     *
     * The returned buffer is owned by the caller; it is only returned to [bufferPool] when
     * compositing throws mid-pass (a [finally] guard), so a failure cannot leak it. On success the
     * caller keeps ownership — callers store the result as a layer raster or hand it to an encoder.
     *
     * @param backgroundColor composited first; a fully transparent colour leaves transparency.
     */
    fun composite(
        inputs: List<LayerInput>,
        width: Int,
        height: Int,
        backgroundColor: Int = 0,
        options: Options = Options(),
    ): PixelBuffer {
        val result = bufferPool.obtain(max(1, width), max(1, height))
        var completed = false
        try {
            compositeInto(result, inputs, backgroundColor, options)
            completed = true
            return result
        } finally {
            // The result never reached the caller, so give it back instead of leaking it.
            if (!completed) bufferPool.release(result)
        }
    }

    /** Compositing body of [composite], factored out so the pool guard stays a plain [finally]. */
    private fun compositeInto(
        result: PixelBuffer,
        inputs: List<LayerInput>,
        backgroundColor: Int,
        options: Options,
    ) {
        if ((backgroundColor ushr 24) != 0) result.fill(backgroundColor)

        // Tracks the layer a clipping mask clips to (the nearest non-clipping layer below).
        var clipBase: PixelBuffer? = null

        inputs.sortedBy { it.layer.index }.forEach { input ->
            val layer = input.layer
            if (layer.isReference && !options.includeReferenceLayers) return@forEach
            if (!layer.isVisible && !options.includeHiddenLayers) return@forEach
            if (layer.opacity <= 0.001f && !options.includeHiddenLayers) return@forEach

            if (layer.adjustmentType != null) {
                if (!options.applyAdjustments) return@forEach
                applyAdjustment(result, input, options)
                return@forEach
            }

            val content = renderLayerContent(input, result.width, result.height, bufferPool)
            if (content == null) return@forEach

            if (layer.isClippingMask && clipBase != null) {
                applyClipping(content, clipBase!!)
            }

            BlendModes.composite(result, content, layer.blendMode, layer.opacity)
            if (!layer.isClippingMask) {
                clipBase = content
            } else {
                // Clipping content is blended into the result and nothing references it
                // afterwards, so it goes straight back to the pool (discarded when full).
                bufferPool.release(content)
            }
        }

        options.selection?.let { selection -> applySelection(result, selection) }
    }

    /**
     * Restricts [result] to the selection: alpha is zeroed where nothing is selected and scaled
     * on feathered edges. Row-major when the mask matches the buffer (the normal case); falls
     * back to masked indexing for a smaller mask.
     */
    private fun applySelection(
        result: PixelBuffer,
        selection: SelectionMask,
    ) {
        if (selection.width != result.width || selection.height != result.height) {
            for (i in result.pixels.indices) {
                val coverage = selection.alphaAt(i)
                if (coverage >= 1f) continue
                val pixel = result.pixels[i]
                result.pixels[i] =
                    if (coverage <= 0f) {
                        Channels.withAlpha(pixel, 0)
                    } else {
                        Channels.scaleAlpha(pixel, coverage)
                    }
            }
            return
        }
        for (y in 0 until result.height) {
            val row = y * result.width
            for (x in 0 until result.width) {
                val index = row + x
                val coverage = selection.coverageAt(x, y) / 255f
                if (coverage >= 1f) continue
                val pixel = result.pixels[index]
                result.pixels[index] =
                    if (coverage <= 0f) {
                        Channels.withAlpha(pixel, 0)
                    } else {
                        Channels.scaleAlpha(pixel, coverage)
                    }
            }
        }
    }

    /**
     * Renders one layer's own content: raster base, then strokes on top, then its filter, then its
     * mask. Returns null when the layer has no content at all.
     *
     * When [bufferPool] is supplied the aligned canvas is recycled through it; the caller then
     * owns the returned buffer and must release it once nothing references it. Without a pool a
     * fresh buffer is allocated (one-off callers such as thumbnails).
     */
    fun renderLayerContent(
        input: LayerInput,
        width: Int,
        height: Int,
        bufferPool: PixelBufferPool? = null,
    ): PixelBuffer? {
        val layer = input.layer
        val hasRaster = input.raster != null
        if (!hasRaster && input.strokes.isEmpty()) return null

        val content: PixelBuffer =
            if (hasRaster) {
                val base = input.raster!!
                // The base raster is blitted (never painted through), so the aligned canvas can be
                // a pooled scratch buffer; without a pool fall back to copy/exact allocation.
                val aligned: PixelBuffer =
                    if (bufferPool != null) {
                        val canvas = bufferPool.obtain(width, height)
                        canvas.drawInto(base, 0, 0)
                        canvas
                    } else if (base.width == width && base.height == height) {
                        base.copy()
                    } else {
                        PixelBuffer(width, height).also { it.drawInto(base, 0, 0) }
                    }
                if (input.strokes.isNotEmpty()) {
                    // Strokes paint on top of the raster base exactly as they appear on screen.
                    input.strokes.forEach { stroke ->
                        strokeRasterizer.draw(aligned, stroke, alphaLock = layer.isAlphaLocked)
                    }
                }
                aligned
            } else {
                strokeRasterizer.rasterize(
                    strokes = input.strokes,
                    width = width,
                    height = height,
                    alphaLock = layer.isAlphaLocked,
                )
            }

        layer.filterType?.let { filter ->
            applyFilter(content, filter, layer.filterAmount)
        }

        if (layer.hasActiveMask()) {
            applyMask(content, input.mask, layer)
        }
        return content
    }

    /** Applies a filter layer's effect to [buffer] in place, mixing by [amount]. */
    fun applyFilter(
        buffer: PixelBuffer,
        filter: FilterType,
        amount: Float,
    ) {
        val intensity = amount.coerceIn(0f, 1f)
        if (intensity <= 0f) return
        val filtered =
            when (filter) {
                FilterType.GAUSSIAN_BLUR -> ImageFilters.gaussianBlur(buffer, 1f + intensity * 12f)
                FilterType.MOTION_BLUR -> ImageFilters.motionBlur(buffer, 1f + intensity * 40f, 0f)
                FilterType.SHARPEN -> ImageFilters.sharpen(buffer, intensity * 3f)
                FilterType.NOISE -> ImageFilters.addNoise(buffer, intensity * 0.4f)
                FilterType.CHROMATIC_ABERRATION ->
                    ImageFilters.chromaticAberration(
                        buffer,
                        intensity * 0.02f,
                        buffer.width / 2f,
                        buffer.height / 2f,
                    )
                FilterType.VIGNETTE -> ImageFilters.vignette(buffer, intensity)
                FilterType.FIND_EDGES -> ImageFilters.findEdges(buffer)
                FilterType.EMBOSS -> ImageFilters.emboss(buffer, intensity * 1.5f)
                FilterType.TILT_SHIFT ->
                    ImageFilters.tiltShift(
                        buffer,
                        intensity * 12f,
                        buffer.height / 2f,
                        buffer.height * 0.35f,
                    )
            }
        // Blend the filtered result back at full strength: the amount already shaped the filter.
        System.arraycopy(filtered.pixels, 0, buffer.pixels, 0, buffer.pixels.size)
    }

    /**
     * Multiplies a layer's alpha by its mask. The mask is a grayscale image where black hides and
     * white reveals; [Layer.maskInverted] flips that, and [Layer.maskDensity] scales the effect.
     */
    fun applyMask(
        content: PixelBuffer,
        mask: PixelBuffer?,
        layer: Layer,
    ) {
        if (mask == null) return
        val density = layer.maskDensity.coerceIn(0f, 1f)
        if (density <= 0f) return

        val source =
            if (layer.maskFeather > 0f) {
                ImageFilters.gaussianBlur(mask, layer.maskFeather)
            } else {
                mask
            }

        for (y in 0 until content.height) {
            for (x in 0 until content.width) {
                val index = y * content.width + x
                val maskPixel =
                    if (x < source.width && y < source.height) {
                        source.pixels[y * source.width + x]
                    } else {
                        // Outside the mask image the mask is treated as opaque white.
                        0xFFFFFFFF.toInt()
                    }
                val raw = Channels.luminance(maskPixel)
                val value = if (layer.maskInverted) 1f - raw else raw
                val factor = (1f - density) + density * value
                val pixel = content.pixels[index]
                val alpha = Channels.alpha(pixel) * factor
                content.pixels[index] = Channels.withAlpha(pixel, alpha.roundToInt().coerceIn(0, 255))
            }
        }
    }

    /** Clips [content] to the alpha of the layer below (clipping mask, Phase 15). */
    fun applyClipping(
        content: PixelBuffer,
        clipBase: PixelBuffer,
    ) {
        if (content.width != clipBase.width || content.height != clipBase.height) return
        for (i in content.pixels.indices) {
            val baseAlpha = (clipBase.pixels[i] ushr 24) and 0xFF
            if (baseAlpha == 255) continue
            if (baseAlpha == 0) {
                content.pixels[i] = 0
                continue
            }
            val pixel = content.pixels[i]
            val alpha = Channels.alpha(pixel) * baseAlpha / 255f
            content.pixels[i] = Channels.withAlpha(pixel, alpha.roundToInt().coerceIn(0, 255))
        }
    }

    /** Applies an adjustment input to the accumulated [result] below it. */
    private fun applyAdjustment(
        result: PixelBuffer,
        input: LayerInput,
        options: Options,
    ) {
        val layer = input.layer
        val type = layer.adjustmentType ?: return
        val adjusted =
            AdjustmentProcessor.apply(
                source = result,
                type = type,
                parameters = layer.adjustmentParameters,
                intensity = 1f,
                mask = options.selection?.coverage,
            )
        val intensity = layer.opacity.coerceIn(0f, 1f)
        for (i in result.pixels.indices) {
            result.pixels[i] = ImageFilters.lerpArgb(result.pixels[i], adjusted.pixels[i], intensity)
        }
    }

    /**
     * Builds a layer's thumbnail (used by the layer list). Small, fast, and honours opacity and
     * blend mode by compositing the layer on its own.
     *
     * A layer with no raster (purely vector) is sized from its strokes' bounding box plus padding
     * for the brush footprint, and the strokes are translated into that crop's coordinate space.
     * Empty vector layers produce no thumbnail at all; [renderLayerContent] is never given a
     * zero-or-one pixel canvas, which used to either throw or clip the artwork to a single pixel.
     */
    fun layerThumbnail(
        input: LayerInput,
        size: Int = 96,
    ): PixelBuffer? {
        val raster = input.raster
        val target: LayerInput
        val contentWidth: Int
        val contentHeight: Int
        if (raster != null) {
            target = input
            contentWidth = raster.width
            contentHeight = raster.height
        } else {
            val bounds = input.strokes.bounds() ?: return null
            val left = floor(bounds.left).toInt()
            val top = floor(bounds.top).toInt()
            contentWidth = ceil(bounds.right).toInt() - left + 1 + THUMBNAIL_PADDING * 2
            contentHeight = ceil(bounds.bottom).toInt() - top + 1 + THUMBNAIL_PADDING * 2
            if (contentWidth <= 0 || contentHeight <= 0) return null
            // Strokes keep absolute canvas coordinates, so shift them into the crop's space.
            target =
                input.copy(
                    strokes =
                        input.strokes.map { stroke ->
                            stroke.copy(
                                points =
                                    stroke.points.map { point ->
                                        point.copy(
                                            x = point.x - left + THUMBNAIL_PADDING,
                                            y = point.y - top + THUMBNAIL_PADDING,
                                        )
                                    },
                            )
                        },
                )
        }

        val content = renderLayerContent(target, contentWidth, contentHeight) ?: return null

        val scale =
            min(
                size.toFloat() / content.width.coerceAtLeast(1),
                size.toFloat() / content.height.coerceAtLeast(1),
            ).coerceAtMost(1f)
        return if (scale >= 1f) {
            content
        } else {
            content.scaled(
                (content.width * scale).roundToInt().coerceAtLeast(1),
                (content.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }

    /** Releases pooled buffers (called when the canvas is disposed). */
    fun release() {
        bufferPool.clear()
        strokeRasterizer.release()
    }

    private companion object {
        /** Padding added around a vector layer's stroke bounds when sizing its thumbnail. */
        const val THUMBNAIL_PADDING = 16
    }
}

/**
 * Bounding box of every point in [strokes], or null when the list is empty. Sizing thumbnails
 * without materialising an [IntBounds] keeps the compositor's public surface unchanged.
 */
private fun List<Stroke>.bounds(): RectF? {
    if (isEmpty()) return null
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    forEach { stroke ->
        stroke.points.forEach { point ->
            if (point.x < minX) minX = point.x
            if (point.y < minY) minY = point.y
            if (point.x > maxX) maxX = point.x
            if (point.y > maxY) maxY = point.y
        }
    }
    if (maxX < minX || maxY < minY) return null
    return RectF(minX, minY, maxX, maxY)
}
