package com.artflow.studio.presentation.ui.components.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.MotionEvent
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.core.three.Mesh
import com.artflow.studio.core.three.MeshPicker
import com.artflow.studio.core.three.ModelLighting
import com.artflow.studio.core.three.SurfaceHit
import com.artflow.studio.core.three.SurfaceSink
import com.artflow.studio.core.three.SurfaceStroker
import com.artflow.studio.data.renderer.opengl.CanvasPreviewAvailability
import com.artflow.studio.data.renderer.opengl.ModelRenderer
import timber.log.Timber
import kotlin.math.hypot

/** Receives strokes painted on the model, in texture space: u and v run 0..1 with v = 0 at the bottom. */
interface ModelPainter : SurfaceSink

/**
 * The 3D model view. In paint mode one finger paints on the model; otherwise one finger turns it.
 * Two fingers always turn it and pinch to move nearer or farther, as in Procreate's 3D painting.
 */
@SuppressLint("ViewConstructor")
class ModelView internal constructor(
    context: Context,
    private val renderer: ModelRenderer,
) : GLSurfaceView(context) {
    constructor(context: Context) : this(context, ModelRenderer())

    var mesh: Mesh? = null
        private set
    var onPreviewMessage: ((String?) -> Unit)? = null

    @Volatile
    private var attachment = 0L
    private var paused = false
    private var gesturePreviewInterruption = 0L
    val isPreviewAvailable: Boolean
        get() = isAttachedToWindow && !paused && renderer.previewAvailability == CanvasPreviewAvailability.AVAILABLE
    var lighting: ModelLighting
        get() = renderer.lighting
        set(value) {
            if (value == renderer.lighting) return
            renderer.lighting = value
            requestRender()
        }
    var showMesh: Boolean
        get() = renderer.showMesh
        set(value) {
            if (value == renderer.showMesh) return
            renderer.showMesh = value
            requestRender()
        }
    var painting = false
        set(value) {
            if (field != value) cancelPainting()
            field = value
        }
    var gesturesEnabled = true
        set(value) {
            if (!value) cancelPainting()
            field = value
        }
    var painter: ModelPainter? = null
        set(value) {
            if (field !== value) cancelPainting()
            field = value
        }
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

                    override fun finish() {
                        painter?.finish()
                    }

                    override fun cancel() {
                        painter?.cancel()
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
        renderer.onStatusMessage = { message ->
            val owner = attachment
            post {
                if (owner == attachment && isAttachedToWindow && !isPreviewAvailable) onPreviewMessage?.invoke(message)
            }
        }
        renderer.onPreviewAvailabilityChanged = { availability ->
            val owner = attachment
            val interruption = renderer.previewStatus.interruptions
            post {
                if (owner == attachment && isAttachedToWindow) {
                    if (availability != CanvasPreviewAvailability.AVAILABLE && gesturePreviewInterruption < interruption) cancelPainting()
                    if (availability == CanvasPreviewAvailability.AVAILABLE && isPreviewAvailable) onPreviewMessage?.invoke(null)
                }
            }
        }
    }

    /** Copies one coherent mesh/color/material generation; the caller keeps its published bitmaps. */
    fun setFrame(
        mesh: Mesh,
        artwork: Bitmap?,
        maps: Map<MaterialChannel, Bitmap>,
    ): Boolean {
        if (this.mesh !== mesh) {
            cancelPainting()
            renderer.awaitPrimaryPreview(invalidateContent = true)
        }
        this.mesh = mesh
        val copies = mutableListOf<Bitmap>()
        var transferred = false
        try {
            if (artwork != null) {
                listOf(artwork, maps[MaterialChannel.METALLIC], maps[MaterialChannel.ROUGHNESS]).forEach { source ->
                    copies += copyChannel(source)
                }
            }
            renderer.setFrame(mesh, copies.toList())
            transferred = true
            requestRender()
            return true
        } catch (failure: IllegalStateException) {
            Timber.w(failure, "Could not prepare the model preview")
        } catch (failure: IllegalArgumentException) {
            Timber.w(failure, "Model preview pixels were unavailable")
        } catch (failure: OutOfMemoryError) {
            Timber.w(failure, "Model preview copies did not fit")
        } finally {
            if (!transferred) copies.forEach(Bitmap::recycle)
        }
        renderer.invalidateFrame()
        return false
    }

    private fun copyChannel(source: Bitmap?): Bitmap =
        if (source == null) {
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        } else {
            checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false)) { "Could not copy the model preview" }
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gesturesEnabled) return false
        val preview = renderer.previewStatus
        if (!isAttachedToWindow || paused || preview.availability != CanvasPreviewAvailability.AVAILABLE) {
            cancelPainting()
            return false
        }
        val interruption = preview.interruptions
        if (gesturePreviewInterruption < interruption) {
            cancelPainting()
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return true
        }
        if (renderer.previewStatus != preview) {
            cancelPainting()
            return false
        }
        if (
            event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0)
        ) {
            cancelPainting()
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            gestureOnly = false
            gesturePreviewInterruption = interruption
        }
        if (event.pointerCount >= 2) {
            if (!gestureOnly) cancelPainting()
            gestureOnly = true
            twoFingers(event)
        } else if (painting) {
            if (!gestureOnly) paint(event)
        } else {
            turn(event)
        }
        return true
    }

    private fun cancelPainting() {
        stroker.cancel()
        gestureOnly = true
        lastSpan = 0f
    }

    override fun onDetachedFromWindow() {
        attachment++
        cancelPainting()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachment++
        renderer.awaitPrimaryPreview()
        requestRender()
    }

    override fun onPause() {
        paused = true
        cancelPainting()
        super.onPause()
        renderer.awaitPrimaryPreview()
    }

    override fun onResume() {
        paused = false
        super.onResume()
        requestRender()
    }

    /** AndroidView's final release, after pausing the GL thread; ordinary reattachment retains pixels. */
    fun release() {
        onPreviewMessage = null
        onPause()
        renderer.dispose()
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
            MotionEvent.ACTION_MOVE -> moveSamples(event)
            MotionEvent.ACTION_UP -> {
                moveSamples(event)
                stroker.up()
            }
        }
    }

    private fun moveSamples(event: MotionEvent) {
        for (index in 0 until event.historySize) {
            stroker.move(
                event.getHistoricalX(index),
                event.getHistoricalY(index),
                event.getHistoricalPressure(index).coerceIn(MIN_PRESSURE, 1f),
            )
        }
        stroker.move(event.x, event.y, event.pressure.coerceIn(MIN_PRESSURE, 1f))
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
