package com.artflow.studio.presentation.ui.components.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.artflow.studio.core.three.Mesh
import com.artflow.studio.core.three.MeshPicker
import com.artflow.studio.data.renderer.opengl.ModelRenderer
import kotlin.math.abs
import kotlin.math.hypot

/** Receives strokes painted on the model, in texture space: u and v run 0..1 with v = 0 at the bottom. */
interface ModelPainter {
    fun begin(
        u: Float,
        v: Float,
        pressure: Float,
    )

    fun move(
        u: Float,
        v: Float,
        pressure: Float,
    )

    fun end()
}

/**
 * The 3D model view. In paint mode one finger paints on the model; otherwise one finger turns it.
 * Two fingers always turn it and pinch to move nearer or farther, as in Procreate's 3D painting.
 */
@SuppressLint("ViewConstructor")
class ModelView(
    context: Context,
) : GLSurfaceView(context) {
    private val renderer = ModelRenderer()
    var mesh: Mesh? = null
        set(value) {
            field = value
            value?.let(renderer::setMesh)
            requestRender()
        }
    var painting = false
    var painter: ModelPainter? = null
    private var stroking = false
    private var lastU = 0f
    private var lastV = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, DEPTH_BITS, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Paints the model with a copy of [artwork]. */
    fun setTexture(artwork: Bitmap) {
        renderer.setTexture(artwork.copy(Bitmap.Config.ARGB_8888, false))
        requestRender()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            endStroke()
            twoFingers(event)
        } else if (painting) {
            paint(event)
        } else {
            turn(event)
        }
        return true
    }

    private fun twoFingers(event: MotionEvent) {
        val x = (event.getX(0) + event.getX(1)) / 2f
        val y = (event.getY(0) + event.getY(1)) / 2f
        val span = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            lastX = x
            lastY = y
            lastSpan = span
            return
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && lastSpan > 0f) {
            renderer.camera = renderer.camera.turned(-(x - lastX) * TURN_PER_PIXEL, (y - lastY) * TURN_PER_PIXEL).zoomed(span / lastSpan)
            requestRender()
        }
        lastX = x
        lastY = y
        lastSpan = span
    }

    private fun turn(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                renderer.camera = renderer.camera.turned(-(event.x - lastX) * TURN_PER_PIXEL, (event.y - lastY) * TURN_PER_PIXEL)
                lastX = event.x
                lastY = event.y
                requestRender()
            }
        }
    }

    private fun paint(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val hit = pick(event.x, event.y)
                val pressure = event.pressure.coerceIn(MIN_PRESSURE, 1f)
                when {
                    hit == null -> {
                        endStroke()
                    }

                    // Crossing a seam jumps across the texture; lift there rather than draw across it.
                    stroking && abs(hit.first - lastU) + abs(hit.second - lastV) > SEAM_JUMP -> {
                        endStroke()
                        startStroke(hit, pressure)
                    }

                    stroking -> {
                        painter?.move(hit.first, hit.second, pressure)
                        track(hit)
                    }

                    else -> {
                        startStroke(hit, pressure)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endStroke()
            }
        }
    }

    private fun startStroke(
        hit: Pair<Float, Float>,
        pressure: Float,
    ) {
        painter?.begin(hit.first, hit.second, pressure)
        stroking = true
        track(hit)
    }

    private fun track(hit: Pair<Float, Float>) {
        lastU = hit.first
        lastV = hit.second
    }

    private fun endStroke() {
        if (stroking) painter?.end()
        stroking = false
    }

    private fun pick(
        x: Float,
        y: Float,
    ): Pair<Float, Float>? {
        val model = mesh ?: return null
        val (origin, direction) = renderer.camera.ray(x, y, width.toFloat(), height.toFloat())
        return MeshPicker.pick(model, origin, direction)
    }

    private companion object {
        const val DEPTH_BITS = 16
        const val TURN_PER_PIXEL = 0.01f
        const val MIN_PRESSURE = 0.05f
        const val SEAM_JUMP = 0.15f
    }
}
