package com.artflow.studio.core.canvas

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The single image-to-view mapping used by reference drawing, gestures and colour sampling.
 * Image edges are half-open: letterboxing and the right/bottom edge never sample a pixel.
 * Pan is bounded so an enlarged image cannot be lost outside its viewport.
 */
data class ReferenceViewport(
    val imageWidth: Int,
    val imageHeight: Int,
    val viewWidth: Float,
    val viewHeight: Float,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    init {
        require(imageWidth > 0 && imageHeight > 0)
        require(viewWidth in 1f..1_000_000f && viewHeight in 1f..1_000_000f)
        require(zoom in 1f..MAX_ZOOM && panX.isFinite() && panY.isFinite())
    }

    val scale: Float get() = min(viewWidth / imageWidth, viewHeight / imageHeight) * zoom
    val displayWidth: Float get() = imageWidth * scale
    val displayHeight: Float get() = imageHeight * scale
    private val maxPanX: Float get() = max(0f, (displayWidth - viewWidth) / 2f)
    private val maxPanY: Float get() = max(0f, (displayHeight - viewHeight) / 2f)
    private val boundedPanX: Float get() = panX.coerceIn(-maxPanX, maxPanX)
    private val boundedPanY: Float get() = panY.coerceIn(-maxPanY, maxPanY)
    val left: Float get() = (viewWidth - displayWidth) / 2f + boundedPanX
    val top: Float get() = (viewHeight - displayHeight) / 2f + boundedPanY

    fun pixelAt(
        x: Float,
        y: Float,
    ): Pair<Int, Int>? {
        if (!x.isFinite() || !y.isFinite()) return null
        if (x !in 0f..<viewWidth || y !in 0f..<viewHeight) return null
        val imageX = (x - left) / scale
        val imageY = (y - top) / scale
        if (imageX !in 0f..<imageWidth.toFloat() || imageY !in 0f..<imageHeight.toFloat()) return null
        return floor(imageX).toInt() to floor(imageY).toInt()
    }

    fun panBy(
        dx: Float,
        dy: Float,
    ): ReferenceViewport {
        if (!dx.isFinite() || !dy.isFinite()) return this
        return copy(
            panX = if (maxPanX == 0f) 0f else (boundedPanX + dx).coerceIn(-maxPanX, maxPanX),
            panY = if (maxPanY == 0f) 0f else (boundedPanY + dy).coerceIn(-maxPanY, maxPanY),
        )
    }

    /** Keep the image point beneath the gesture centroid stationary, unless an edge constrains it. */
    fun zoomBy(
        factor: Float,
        focusX: Float = viewWidth / 2f,
        focusY: Float = viewHeight / 2f,
    ): ReferenceViewport {
        if (!factor.isFinite() || factor <= 0f) return this
        if (!focusX.isFinite() || !focusY.isFinite()) return this
        val nextZoom = (zoom * factor).coerceIn(1f, MAX_ZOOM)
        val ratio = nextZoom / zoom
        val x = focusX.coerceIn(0f, viewWidth) - viewWidth / 2f
        val y = focusY.coerceIn(0f, viewHeight) - viewHeight / 2f
        return copy(
            zoom = nextZoom,
            panX = x - (x - boundedPanX) * ratio,
            panY = y - (y - boundedPanY) * ratio,
        ).panBy(0f, 0f)
    }

    fun fit(): ReferenceViewport = copy(zoom = 1f, panX = 0f, panY = 0f)

    companion object {
        const val MAX_ZOOM = 8f
    }
}
