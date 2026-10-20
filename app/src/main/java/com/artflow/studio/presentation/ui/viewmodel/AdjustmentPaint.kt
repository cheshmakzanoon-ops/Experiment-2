package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.pixels.ImageFilters
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pencil-mode helpers: a painted coverage mask and the blend of an effect through it. */
object AdjustmentPaint {
    /** Adds a soft round dab to [mask], keeping the strongest coverage where dabs overlap. */
    fun dab(
        mask: SelectionMask,
        x: Float,
        y: Float,
        radius: Float,
    ) {
        val x0 = max(0, floor(x - radius).toInt())
        val x1 = min(mask.width - 1, ceil(x + radius).toInt())
        val y0 = max(0, floor(y - radius).toInt())
        val y1 = min(mask.height - 1, ceil(y + radius).toInt())
        for (py in y0..y1) {
            for (px in x0..x1) {
                val dx = px + 0.5f - x
                val dy = py + 0.5f - y
                val falloff = 1f - sqrt(dx * dx + dy * dy) / radius
                if (falloff <= 0f) continue
                val index = py * mask.width + px
                val value = (min(1f, falloff * 2f) * 255f).toInt()
                if (value > (mask.coverage[index].toInt() and 0xFF)) mask.coverage[index] = value.toByte()
            }
        }
    }

    /**
     * [effect] where [selection] and [painted] (if given) allow it, [source] elsewhere. A selection that
     * is present but empty selects nothing; only a missing selection limits nothing.
     */
    fun mix(
        source: PixelBuffer,
        effect: PixelBuffer,
        selection: SelectionMask?,
        painted: SelectionMask?,
    ): PixelBuffer {
        val limit = selection?.takeIf { it.width == source.width && it.height == source.height }
        if (limit == null && painted == null) return effect
        if (limit != null && !limit.isActive()) return source.copy()
        val out = PixelBuffer(source.width, source.height)
        for (i in out.pixels.indices) {
            val amount = (limit?.alphaAt(i) ?: 1f) * (painted?.alphaAt(i) ?: 1f)
            out.pixels[i] = if (amount <= 0f) source.pixels[i] else ImageFilters.lerpArgb(source.pixels[i], effect.pixels[i], amount)
        }
        return out
    }
}
