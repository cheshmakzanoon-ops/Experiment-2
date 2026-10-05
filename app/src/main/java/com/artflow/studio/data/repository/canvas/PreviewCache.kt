package com.artflow.studio.data.repository.canvas

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.StrokeReach
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps the last preview composite. While the document is unchanged and only strokes in progress
 * move, the next frame recomposites just the area those strokes touched since the last frame
 * (and re-uploads only that area), instead of the whole canvas.
 */
internal class PreviewCache {
    /**
     * What a preview shows apart from strokes in progress: [document] must match exactly, and each
     * layer's pixel identities in [layers] must match unless the layer is listed in the [Damage].
     */
    data class Key(
        val document: List<Any?>,
        val layers: Map<Long, List<Any?>>,
    )

    /** Document edits since the previous frame that stayed inside [region], on [layers] only. */
    data class Damage(
        val region: IntBounds,
        val layers: Set<Long>,
    ) {
        fun plus(
            area: IntBounds,
            layer: Long,
        ): Damage = Damage(union(region, area), layers + layer)

        companion object {
            val NONE = Damage(IntBounds(0, 0, -1, -1), emptySet())
        }
    }

    private class Extent(
        val stroke: Stroke,
        val bounds: IntBounds,
    )

    private var key: Key? = null
    private var composite: PixelBuffer? = null
    private var extents: Map<String, Extent> = emptyMap()

    fun invalidate() {
        key = null
        composite = null
        extents = emptyMap()
    }

    /**
     * Produces the next frame. [key] identifies everything except the strokes in progress (null
     * forces a full composite); [damage] lists document edits since the last frame (null: anything
     * may have changed); [strokes] are the strokes in progress as drawn, in canvas coordinates.
     * [render] composites the whole canvas for null, or just the given area.
     */
    suspend fun frame(
        key: Key?,
        damage: Damage?,
        strokes: List<Stroke>,
        width: Int,
        height: Int,
        render: suspend (IntBounds?) -> PixelBuffer,
    ): CanvasRepository.PreviewFrame {
        val current = extentsOf(strokes)
        val cached = composite?.takeIf { it.width == width && it.height == height && reusable(key, damage) }
        val dirty = cached?.let { damage?.region?.let { area -> clip(union(dirtyRegion(current, width, height), area), width, height) } }
        this.key = key
        extents = current
        if (cached == null || dirty == null || dirty.width.toLong() * dirty.height * 2 > width.toLong() * height) {
            return CanvasRepository.PreviewFrame(render(null).also { composite = it }, null)
        }
        if (!dirty.isEmpty) paste(cached, render(dirty), dirty)
        return CanvasRepository.PreviewFrame(cached, dirty)
    }

    private fun extentsOf(strokes: List<Stroke>): Map<String, Extent> {
        val seen = HashMap<Long, Int>()
        return strokes.associate { stroke ->
            val copy = seen.merge(stroke.id, 1, Int::plus) ?: 1
            "${stroke.id}:$copy" to Extent(stroke, boundsOf(stroke, 0))
        }
    }

    /** Area to recomposite: new stroke tails, or whole strokes that changed shape or vanished. */
    private fun dirtyRegion(
        current: Map<String, Extent>,
        width: Int,
        height: Int,
    ): IntBounds {
        var dirty: IntBounds? = null
        current.forEach { (id, now) ->
            val before = extents[id]
            val area =
                when {
                    before == null -> now.bounds
                    now.stroke.points == before.stroke.points -> EMPTY
                    appends(now.stroke, before.stroke) -> boundsOf(now.stroke, before.stroke.points.size - 1)
                    else -> union(before.bounds, now.bounds)
                }
            dirty = union(dirty, area)
        }
        extents.forEach { (id, before) -> if (id !in current) dirty = union(dirty, before.bounds) }
        return clip(dirty ?: EMPTY, width, height)
    }

    private fun reusable(
        key: Key?,
        damage: Damage?,
    ): Boolean {
        val before = this.key
        if (key == null || before == null || damage == null) return false
        if (key.document != before.document || key.layers.keys != before.layers.keys) return false
        return key.layers.all { (id, identity) -> identity == before.layers[id] || id in damage.layers }
    }

    private fun clip(
        region: IntBounds,
        width: Int,
        height: Int,
    ): IntBounds {
        if (region.isEmpty) return EMPTY
        val clipped = IntBounds(max(0, region.left), max(0, region.top), min(width - 1, region.right), min(height - 1, region.bottom))
        return if (clipped.isEmpty) EMPTY else clipped
    }

    /**
     * True when [now] only added points after [before]'s and its earlier pixels cannot change.
     * Tapers depend on the stroke's total length, so a tapered stroke redraws completely.
     */
    private fun appends(
        now: Stroke,
        before: Stroke,
    ): Boolean {
        val params = now.brushParams
        val count = before.points.size
        return params.taperStart <= 0f &&
            params.taperEnd <= 0f &&
            count > 0 &&
            now.points.size >= count &&
            now.points[count - 1] == before.points[count - 1]
    }

    private fun paste(
        target: PixelBuffer,
        part: PixelBuffer,
        region: IntBounds,
    ) {
        require(part.width == region.width && part.height == region.height) { "Region size mismatch" }
        for (y in 0 until region.height) {
            System.arraycopy(part.pixels, y * part.width, target.pixels, (region.top + y) * target.width + region.left, part.width)
        }
    }

    companion object {
        private val EMPTY = IntBounds(0, 0, -1, -1)

        /** Bounds of the stroke's points from [from] on, grown by the farthest a dab can reach. */
        fun boundsOf(
            stroke: Stroke,
            from: Int,
        ): IntBounds {
            val points = stroke.points.drop(max(0, from))
            if (points.isEmpty()) return EMPTY
            val reach = StrokeReach.of(stroke.brushParams) + StrokeReach.PADDING
            return IntBounds(
                floor(points.minOf { it.x } - reach).toInt(),
                floor(points.minOf { it.y } - reach).toInt(),
                ceil(points.maxOf { it.x } + reach).toInt(),
                ceil(points.maxOf { it.y } + reach).toInt(),
            )
        }

        fun translate(
            stroke: Stroke,
            dx: Float,
            dy: Float,
        ): Stroke = stroke.copy(points = stroke.points.map { it.copy(x = it.x + dx, y = it.y + dy) })

        fun union(
            a: IntBounds?,
            b: IntBounds,
        ): IntBounds =
            when {
                b.isEmpty -> a ?: b
                a == null || a.isEmpty -> b
                else -> IntBounds(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
            }
    }
}
