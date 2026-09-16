package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.AdjustmentProcessor
import com.artflow.studio.core.pixels.BlendModes
import com.artflow.studio.core.pixels.Channels
import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
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
 */
class Compositor(
    private val strokeRasterizer: StrokeRasterizer = StrokeRasterizer()
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
        val mask: PixelBuffer? = null
    )

    /** Options shared by every compositing entry point. */
    data class Options(
        val includeHiddenLayers: Boolean = false,
        /** Composite reference layers too (used by "export all layers"). */
        val includeReferenceLayers: Boolean = false,
        /** Restrict the result to a selection (export selection, fill selection). */
        val selection: SelectionMask? = null,
        /** Skip adjustment layers (used to preview raw layer content). */
        val applyAdjustments: Boolean = true
    )

    /**
     * Composites [inputs] (bottom layer first) into a new buffer.
     *
     * @param backgroundColor composited first; a fully transparent colour leaves transparency.
     */
    fun composite(
        inputs: List<LayerInput>,
        width: Int,
        height: Int,
        backgroundColor: Int = 0,
        options: Options = Options()
    ): PixelBuffer {
        val safeWidth = max(1, width)
        val safeHeight = max(1, height)
        val result = PixelBuffer(safeWidth, safeHeight)
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

            val content = renderLayerContent(input, safeWidth, safeHeight)
            if (content == null) return@forEach

            if (layer.isClippingMask && clipBase != null) {
                applyClipping(content, clipBase!!)
            }

            BlendModes.composite(result, content, layer.blendMode, layer.opacity)
            if (!layer.isClippingMask) clipBase = content
        }

        options.selection?.let { selection ->
            for (i in result.pixels.indices) {
                val coverage = selection.alphaAt(i)
                if (coverage >= 1f) continue
                val pixel = result.pixels[i]
                result.pixels[i] = if (coverage <= 0f) {
                    Channels.withAlpha(pixel, 0)
                } else {
                    Channels.scaleAlpha(pixel, coverage)
                }
            }
        }
        return result
    }

    /**
     * Renders one layer's own content: raster base, then strokes on top, then its filter, then its
     * mask. Returns null when the layer has no content at all.
     */
    fun renderLayerContent(input: LayerInput, width: Int, height: Int): PixelBuffer? {
        val layer = input.layer
        val hasRaster = input.raster != null
        if (!hasRaster && input.strokes.isEmpty()) return null

        val content: PixelBuffer = if (hasRaster) {
            val base = input.raster!!
            val aligned = if (base.width == width && base.height == height) {
                base.copy()
            } else {
                val canvas = PixelBuffer(width, height)
                canvas.drawInto(base, 0, 0)
                canvas
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
                alphaLock = layer.isAlphaLocked
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
    fun applyFilter(buffer: PixelBuffer, filter: FilterType, amount: Float) {
        val intensity = amount.coerceIn(0f, 1f)
        if (intensity <= 0f) return
        val filtered = when (filter) {
            FilterType.GAUSSIAN_BLUR -> ImageFilters.gaussianBlur(buffer, 1f + intensity * 12f)
            FilterType.MOTION_BLUR -> ImageFilters.motionBlur(buffer, 1f + intensity * 40f, 0f)
            FilterType.SHARPEN -> ImageFilters.sharpen(buffer, intensity * 3f)
            FilterType.NOISE -> ImageFilters.addNoise(buffer, intensity * 0.4f)
            FilterType.CHROMATIC_ABERRATION -> ImageFilters.chromaticAberration(
                buffer,
                intensity * 0.02f,
                buffer.width / 2f,
                buffer.height / 2f
            )
            FilterType.VIGNETTE -> ImageFilters.vignette(buffer, intensity)
            FilterType.FIND_EDGES -> ImageFilters.findEdges(buffer)
            FilterType.EMBOSS -> ImageFilters.emboss(buffer, intensity * 1.5f)
            FilterType.TILT_SHIFT -> ImageFilters.tiltShift(
                buffer,
                intensity * 12f,
                buffer.height / 2f,
                buffer.height * 0.35f
            )
        }
        // Blend the filtered result back at full strength: the amount already shaped the filter.
        System.arraycopy(filtered.pixels, 0, buffer.pixels, 0, buffer.pixels.size)
    }

    /**
     * Multiplies a layer's alpha by its mask. The mask is a grayscale image where black hides and
     * white reveals; [Layer.maskInverted] flips that, and [Layer.maskDensity] scales the effect.
     */
    fun applyMask(content: PixelBuffer, mask: PixelBuffer?, layer: Layer) {
        if (mask == null) return
        val density = layer.maskDensity.coerceIn(0f, 1f)
        if (density <= 0f) return

        val source = if (layer.maskFeather > 0f) {
            ImageFilters.gaussianBlur(mask, layer.maskFeather)
        } else {
            mask
        }

        for (y in 0 until content.height) {
            for (x in 0 until content.width) {
                val index = y * content.width + x
                val maskPixel = if (x < source.width && y < source.height) {
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
    fun applyClipping(content: PixelBuffer, clipBase: PixelBuffer) {
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
    private fun applyAdjustment(result: PixelBuffer, input: LayerInput, options: Options) {
        val layer = input.layer
        val type = layer.adjustmentType ?: return
        val adjusted = AdjustmentProcessor.apply(
            source = result,
            type = type,
            parameters = layer.adjustmentParameters,
            intensity = 1f,
            mask = options.selection?.coverage
        )
        val intensity = layer.opacity.coerceIn(0f, 1f)
        for (i in result.pixels.indices) {
            result.pixels[i] = ImageFilters.lerpArgb(result.pixels[i], adjusted.pixels[i], intensity)
        }
    }

    /**
     * Builds a layer's thumbnail (used by the layer list). Small, fast, and honours opacity and
     * blend mode by compositing the layer on its own.
     */
    fun layerThumbnail(input: LayerInput, size: Int = 96): PixelBuffer? {
        val content = renderLayerContent(input, input.raster?.width ?: 0, input.raster?.height ?: 0)
            ?: run {
                val width = input.raster?.width ?: 1
                val height = input.raster?.height ?: 1
                if (width <= 0 || height <= 0) return null
                renderLayerContent(input, width, height)
            }
            ?: return null

        val scale = min(
            size.toFloat() / content.width.coerceAtLeast(1),
            size.toFloat() / content.height.coerceAtLeast(1)
        ).coerceAtMost(1f)
        return if (scale >= 1f) content else content.scaled(
            (content.width * scale).roundToInt().coerceAtLeast(1),
            (content.height * scale).roundToInt().coerceAtLeast(1)
        )
    }

    /** Releases the reusable scratch buffer. */
    fun release() {
        strokeRasterizer.release()
    }
}
