package com.artflow.studio.presentation.ui.components.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import com.artflow.studio.core.three.Mesh
import com.artflow.studio.core.three.MeshPicker
import com.artflow.studio.core.three.ModelLighting
import com.artflow.studio.core.three.SurfaceHit
import com.artflow.studio.core.three.SurfaceSink
import com.artflow.studio.core.three.SurfaceStroker
import com.artflow.studio.data.renderer.opengl.ModelRenderer
import kotlin.math.hypot

/** Receives strokes painted on the model, in texture space: u and v run 0..1 with v = 0 at the bottom. */
interface ModelPainter : SurfaceSink

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
    var lighting: ModelLighting
        get() = renderer.lighting
        set(value) {
            if (value == renderer.lighting) return
            renderer.lighting = value
            requestRender()
        }
    var painting = false
    var painter: ModelPainter? = null
    private val stroker =
        SurfaceStroker(
            pick = ::pick,
            sink =
                object : SurfaceSink {
                    override fun begin(
                        u: Float,
                        v: Float,
                        pressure: Float,
                    ) {
                        painter?.begin(u, v, pressure)
                    }

                    override fun move(
                        u: Float,
                        v: Float,
                        pressure: Float,
                    ) {
                        painter?.move(u, v, pressure)
                    }

                    override fun end() {
                        painter?.end()
                    }
                },
        )

    // After a two-finger turn, the finger left on the glass does not start painting.
    private var gestureOnly = false
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
        if (event.actionMasked == MotionEvent.ACTION_DOWN) gestureOnly = false
        if (event.pointerCount >= 2) {
            stroker.up()
            gestureOnly = true
            twoFingers(event)
        } else if (painting) {
            if (!gestureOnly) paint(event)
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
        val pressure = event.pressure.coerceIn(MIN_PRESSURE, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> stroker.down(event.x, event.y, pressure)
            MotionEvent.ACTION_MOVE -> stroker.move(event.x, event.y, pressure)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stroker.up()
        }
    }

    private fun pick(
        x: Float,
        y: Float,
    ): SurfaceHit? {
        val model = mesh ?: return null
        val (origin, direction) = renderer.camera.ray(x, y, width.toFloat(), height.toFloat())
        return MeshPicker.hit(model, origin, direction)
    }

    private companion object {
        const val DEPTH_BITS = 16
        const val TURN_PER_PIXEL = 0.01f
        const val MIN_PRESSURE = 0.05f
    }
}
