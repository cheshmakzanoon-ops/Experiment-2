package com.artflow.studio.data.renderer.opengl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPU canvas renderer.
 *
 * Two shader programs:
 * 1. **Textured quad** — draws the composited artwork. The composite is produced on the CPU by
 *    `Compositor`, which is the same code path used for saving and exporting, so what is on screen
 *    is exactly what gets written to disk. It is uploaded as a single texture, which makes
 *    correctness independent of the GPU's blend-mode support.
 * 2. **Capsule stroke** — draws the in-progress stroke. Each segment becomes a quad whose fragment
 *    shader computes the distance to the segment, giving round caps and analytic anti-aliasing with
 *    variable width, matching the CPU stroke rasteriser's geometry.
 *
 * Onion-skin frames are drawn with the same textured-quad program in a tinted, alpha-blended pass.
 */
@Singleton
class OpenGLCanvasRenderer @Inject constructor() : GLSurfaceView.Renderer {

    // --- Canvas / viewport state ---------------------------------------------------------------

    private var viewWidth = 0
    private var viewHeight = 0
    private var canvasWidth = 1
    private var canvasHeight = 1

    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var rotationDegrees = 0f

    private var backgroundColor = floatArrayOf(1f, 1f, 1f, 1f)
    private var showCheckerboard = true

    // --- Programs ------------------------------------------------------------------------------

    private var quadProgram = 0
    private var quadPositionHandle = 0
    private var quadTexCoordHandle = 0
    private var quadMatrixHandle = 0
    private var quadTextureHandle = 0
    private var quadTintHandle = 0
    private var quadAlphaHandle = 0
    private var quadCanvasSizeHandle = 0
    private var quadUseTextureHandle = 0

    private var strokeProgram = 0
    private var strokePositionHandle = 0
    private var strokeSegAHandle = 0
    private var strokeSegBHandle = 0
    private var strokeRadiiHandle = 0
    private var strokeColorHandle = 0
    private var strokeMatrixHandle = 0
    private var strokeViewportScaleHandle = 0

    // --- Textures ------------------------------------------------------------------------------

    private var compositeTexture = 0
    private var checkerTexture = 0

    /** Onion-skin frame textures, rebuilt whenever the ghost frames change. */
    private val onionTextures = mutableListOf<Int>()
    private val onionAlphas = mutableListOf<Float>()

    // --- Data staged from other threads ---------------------------------------------------------

    @Volatile
    private var pendingComposite: Bitmap? = null

    @Volatile
    private var pendingOnionSkins: List<Pair<Bitmap, Float>> = emptyList()

    @Volatile
    private var pendingStrokeVertices: FloatArray? = null

    @Volatile
    private var pendingStrokeVertexCount = 0

    private var onionDirty = true
    private var needsRedraw = true
    private var isInitialized = false

    private val quadVertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_VERTICES)
            position(0)
        }

    // -----------------------------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // -----------------------------------------------------------------------------------------

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA) // premultiplied

        quadProgram = createProgram(QUAD_VERTEX_SHADER, QUAD_FRAGMENT_SHADER)
        quadPositionHandle = GLES20.glGetAttribLocation(quadProgram, "aPosition")
        quadTexCoordHandle = GLES20.glGetAttribLocation(quadProgram, "aTexCoord")
        quadMatrixHandle = GLES20.glGetUniformLocation(quadProgram, "uMatrix")
        quadTextureHandle = GLES20.glGetUniformLocation(quadProgram, "uTexture")
        quadTintHandle = GLES20.glGetUniformLocation(quadProgram, "uTint")
        quadAlphaHandle = GLES20.glGetUniformLocation(quadProgram, "uAlpha")
        quadCanvasSizeHandle = GLES20.glGetUniformLocation(quadProgram, "uCanvasSize")
        quadUseTextureHandle = GLES20.glGetUniformLocation(quadProgram, "uUseTexture")

        strokeProgram = createProgram(STROKE_VERTEX_SHADER, STROKE_FRAGMENT_SHADER)
        strokePositionHandle = GLES20.glGetAttribLocation(strokeProgram, "aPosition")
        strokeSegAHandle = GLES20.glGetAttribLocation(strokeProgram, "aSegA")
        strokeSegBHandle = GLES20.glGetAttribLocation(strokeProgram, "aSegB")
        strokeRadiiHandle = GLES20.glGetAttribLocation(strokeProgram, "aRadii")
        strokeColorHandle = GLES20.glGetAttribLocation(strokeProgram, "aColor")
        strokeMatrixHandle = GLES20.glGetUniformLocation(strokeProgram, "uMatrix")
        strokeViewportScaleHandle = GLES20.glGetUniformLocation(strokeProgram, "uViewportScale")

        checkerTexture = createCheckerboardTexture()
        onionDirty = true
        isInitialized = true
        needsRedraw = true
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
        needsRedraw = true
    }

    override fun onDrawFrame(unused: GL10?) {
        if (!isInitialized) return

        GLES20.glClearColor(0.08f, 0.08f, 0.09f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (!needsRedraw) return
        needsRedraw = false

        val matrix = projectionMatrix()

        // 1. Canvas background (checkerboard or a solid colour).
        GLES20.glUseProgram(quadProgram)
        GLES20.glUniformMatrix4fv(quadMatrixHandle, 1, false, matrix, 0)
        GLES20.glUniform2f(quadCanvasSizeHandle, canvasWidth.toFloat(), canvasHeight.toFloat())
        GLES20.glUniform1f(quadAlphaHandle, 1f)
        GLES20.glUniform4f(quadTintHandle, 1f, 1f, 1f, 1f)

        if (showCheckerboard) {
            GLES20.glUniform1f(quadUseTextureHandle, 1f)
            drawQuad(checkerTexture, textureRepeat = true)
        } else {
            GLES20.glUniform1f(quadUseTextureHandle, 0f)
            GLES20.glUniform4f(
                quadTintHandle,
                backgroundColor[0], backgroundColor[1], backgroundColor[2], backgroundColor[3]
            )
            drawQuad(checkerTexture, textureRepeat = false)
        }

        // 2. Onion-skin frames behind the current one.
        refreshOnionTextures()
        GLES20.glUniform1f(quadUseTextureHandle, 1f)
        onionTextures.forEachIndexed { index, texture ->
            GLES20.glUniform4f(quadTintHandle, 1f, 1f, 1f, 1f)
            GLES20.glUniform1f(quadAlphaHandle, onionAlphas.getOrElse(index) { 0.3f })
            drawQuad(texture, textureRepeat = false)
        }
        GLES20.glUniform1f(quadAlphaHandle, 1f)

        // 3. The composited artwork.
        pendingComposite?.let { bitmap ->
            val texture = if (compositeTexture == 0) {
                uploadBitmap(bitmap).also { compositeTexture = it }
            } else {
                updateBitmap(compositeTexture, bitmap)
                compositeTexture
            }
            GLES20.glUniform4f(quadTintHandle, 1f, 1f, 1f, 1f)
            drawQuad(texture, textureRepeat = false)
            // The upload is done; free the staging bitmap straight away.
            bitmap.recycle()
            pendingComposite = null
        }

        // 4. The in-progress stroke, on top of the committed artwork.
        val vertices = pendingStrokeVertices
        if (vertices != null && pendingStrokeVertexCount > 0) {
            drawStrokeSegments(vertices, pendingStrokeVertexCount, matrix)
        }
    }

    /** Rebuilds the onion-skin textures when the ghost frames change. */
    private fun refreshOnionTextures() {
        if (!onionDirty) return
        onionDirty = false
        onionTextures.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        onionTextures.clear()
        onionAlphas.clear()
        pendingOnionSkins.forEach { (bitmap, alpha) ->
            onionTextures += uploadBitmap(bitmap)
            onionAlphas += alpha
            bitmap.recycle()
        }
        pendingOnionSkins = emptyList()
    }

    // -----------------------------------------------------------------------------------------
    // Public API (called from the main thread)
    // -----------------------------------------------------------------------------------------

    /**
     * Uploads the composited artwork as the canvas texture.
     *
     * The bitmap is owned by the renderer from here on: it is uploaded and recycled on the GL
     * thread. A bitmap staged but never drawn (because a newer composite arrived first) is released
     * immediately so a fast-painting session cannot leak.
     */
    fun setComposite(buffer: PixelBuffer) {
        pendingComposite?.recycle()
        pendingComposite = BitmapPixelBridge.toBitmap(buffer)
        needsRedraw = true
    }

    /** Draws onion-skin ghosts behind the artwork, each with its own opacity. */
    fun setOnionSkins(frames: List<Pair<PixelBuffer, Float>>) {
        pendingOnionSkins.forEach { it.first.recycle() }
        pendingOnionSkins = frames.map { (buffer, alpha) -> BitmapPixelBridge.toBitmap(buffer) to alpha }
        onionDirty = true
        needsRedraw = true
    }

    fun clearOnionSkins() {
        if (pendingOnionSkins.isEmpty() && onionTextures.isEmpty()) return
        pendingOnionSkins.forEach { it.first.recycle() }
        pendingOnionSkins = emptyList()
        onionDirty = true
        needsRedraw = true
    }

    /**
     * Stages the in-progress stroke for rendering.
     *
     * The stroke is expanded into per-segment quads here (CPU) so the GL thread only has to upload
     * a vertex buffer. Geometry matches `StrokeRasterizer`: round-capped segments with pressure
     * interpolated across each segment.
     */
    fun setInProgressStroke(stroke: Stroke?, symmetryCopies: List<Stroke> = emptyList()) {
        val all = listOfNotNull(stroke) + symmetryCopies
        if (all.isEmpty()) {
            pendingStrokeVertices = null
            pendingStrokeVertexCount = 0
            needsRedraw = true
            return
        }
        val builder = StrokeVertexBuilder()
        all.forEach { builder.addStroke(it) }
        pendingStrokeVertices = builder.toFloatArray()
        pendingStrokeVertexCount = builder.vertexCount
        needsRedraw = true
    }

    fun setCanvasSize(width: Int, height: Int, dpi: Int) {
        canvasWidth = max(1, width)
        canvasHeight = max(1, height)
        needsRedraw = true
    }

    fun setTransformation(scale: Float, offsetX: Float, offsetY: Float, rotation: Float) {
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
        this.rotationDegrees = rotation
        needsRedraw = true
    }

    fun setBackgroundArgb(argb: Int) {
        backgroundColor = floatArrayOf(
            android.graphics.Color.red(argb) / 255f,
            android.graphics.Color.green(argb) / 255f,
            android.graphics.Color.blue(argb) / 255f,
            android.graphics.Color.alpha(argb) / 255f
        )
        needsRedraw = true
    }

    fun setCheckerboardVisible(visible: Boolean) {
        showCheckerboard = visible
        needsRedraw = true
    }

    fun requestRedraw() {
        needsRedraw = true
    }

    fun dispose() {
        if (quadProgram != 0) GLES20.glDeleteProgram(quadProgram)
        if (strokeProgram != 0) GLES20.glDeleteProgram(strokeProgram)
        val textures = mutableListOf(compositeTexture, checkerTexture)
        textures += onionTextures
        textures.filter { it != 0 }.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
        compositeTexture = 0
        onionTextures.clear()
        onionAlphas.clear()
        pendingComposite?.recycle()
        pendingComposite = null
        pendingOnionSkins.forEach { it.first.recycle() }
        pendingOnionSkins = emptyList()
        pendingStrokeVertices = null
        isInitialized = false
    }

    // -----------------------------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------------------------

    /** Draws the unit quad expanded to the canvas rectangle, optionally tiled. */
    private fun drawQuad(textureId: Int, textureRepeat: Boolean) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(quadTextureHandle, 0)
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            if (textureRepeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            if (textureRepeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE
        )

        quadVertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(quadPositionHandle)
        GLES20.glVertexAttribPointer(quadPositionHandle, 2, GLES20.GL_FLOAT, false, 16, quadVertexBuffer)
        quadVertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(quadTexCoordHandle)
        GLES20.glVertexAttribPointer(quadTexCoordHandle, 2, GLES20.GL_FLOAT, false, 16, quadVertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(quadPositionHandle)
        GLES20.glDisableVertexAttribArray(quadTexCoordHandle)
    }

    private fun drawStrokeSegments(vertices: FloatArray, vertexCount: Int, matrix: FloatArray) {
        GLES20.glUseProgram(strokeProgram)
        GLES20.glUniformMatrix4fv(strokeMatrixHandle, 1, false, matrix, 0)
        // Convert pixel sizes into normalised device units so anti-aliasing is one pixel wide.
        GLES20.glUniform2f(
            strokeViewportScaleHandle,
            2f / viewWidth.coerceAtLeast(1),
            2f / viewHeight.coerceAtLeast(1)
        )

        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(vertices)
                position(0)
            }

        val stride = StrokeVertexBuilder.FLOATS_PER_VERTEX * 4
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(strokePositionHandle)
        GLES20.glVertexAttribPointer(strokePositionHandle, 2, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(2)
        GLES20.glEnableVertexAttribArray(strokeSegAHandle)
        GLES20.glVertexAttribPointer(strokeSegAHandle, 2, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(4)
        GLES20.glEnableVertexAttribArray(strokeSegBHandle)
        GLES20.glVertexAttribPointer(strokeSegBHandle, 2, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(6)
        GLES20.glEnableVertexAttribArray(strokeRadiiHandle)
        GLES20.glVertexAttribPointer(strokeRadiiHandle, 2, GLES20.GL_FLOAT, false, stride, buffer)
        buffer.position(8)
        GLES20.glEnableVertexAttribArray(strokeColorHandle)
        GLES20.glVertexAttribPointer(strokeColorHandle, 4, GLES20.GL_FLOAT, false, stride, buffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)

        GLES20.glDisableVertexAttribArray(strokePositionHandle)
        GLES20.glDisableVertexAttribArray(strokeSegAHandle)
        GLES20.glDisableVertexAttribArray(strokeSegBHandle)
        GLES20.glDisableVertexAttribArray(strokeRadiiHandle)
        GLES20.glDisableVertexAttribArray(strokeColorHandle)
    }

    // -----------------------------------------------------------------------------------------
    // Matrices, textures and shaders
    // -----------------------------------------------------------------------------------------

    /**
     * Maps canvas pixel coordinates to clip space, honouring zoom, pan and rotation.
     * Rotation happens around the canvas centre so zooming and rotating feel natural.
     */
    internal fun projectionMatrix(): FloatArray {
        val halfWidth = viewWidth / 2f
        val halfHeight = viewHeight / 2f
        val centreX = canvasWidth / 2f
        val centreY = canvasHeight / 2f

        val radians = Math.toRadians(rotationDegrees.toDouble())
        val cosR = cos(radians).toFloat()
        val sinR = sin(radians).toFloat()
        val safeScale = if (abs(scale) < 1e-4f) 1e-4f else scale

        // Canvas -> view: scale about the canvas centre, rotate, then offset; view -> clip divides
        // by the half-extent and flips the y axis (canvas y grows downwards, clip y upwards).
        //
        //   clip.x = ( cos*s*(px-cx) - sin*s*(py-cy) + offsetX) / halfWidth
        //   clip.y = -( sin*s*(px-cx) + cos*s*(py-cy) + offsetY) / halfHeight
        val m = FloatArray(16)
        m[0] = cosR * safeScale / halfWidth
        m[4] = -sinR * safeScale / halfWidth
        m[12] = (offsetX - (cosR * centreX - sinR * centreY) * safeScale) / halfWidth
        m[1] = -sinR * safeScale / halfHeight
        m[5] = -cosR * safeScale / halfHeight
        m[13] = ((sinR * centreX + cosR * centreY) * safeScale - offsetY) / halfHeight
        m[10] = 1f
        m[15] = 1f
        return m
    }

    private fun uploadBitmap(bitmap: Bitmap): Int {
        val handles = IntArray(1)
        GLES20.glGenTextures(1, handles, 0)
        val texture = handles[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        return texture
    }

    /** Re-uploads into an existing texture, avoiding a texture object per frame. */
    private fun updateBitmap(textureId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /** 2x2 checkerboard for transparency, tiled across the canvas quad. */
    private fun createCheckerboardTexture(): Int {
        val size = 32
        val pixels = IntArray(size * size)
        val light = 0xFFFFFFFF.toInt()
        val dark = 0xFFE6E6E6.toInt()
        for (y in 0 until size) {
            for (x in 0 until size) {
                val isLight = ((x / 16) + (y / 16)) % 2 == 0
                pixels[y * size + x] = if (isLight) light else dark
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val texture = uploadBitmap(bitmap)
        bitmap.recycle()
        return texture
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw IllegalStateException("Shader link failed: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }

    /**
     * Expands strokes into per-segment triangle quads.
     *
     * Kept as its own class (and unit-testable except for the GL calls) so the geometry can be
     * asserted in tests: each segment contributes six vertices covering the capsule, and the
     * fragment shader turns that quad into a round-capped stroke.
     */
    class StrokeVertexBuilder {
        private val vertices = ArrayList<Float>(1024)
        var vertexCount: Int = 0
            private set

        fun addStroke(stroke: Stroke) {
            val points = stroke.points
            if (points.isEmpty()) return
            val params = stroke.brushParams

            if (points.size == 1) {
                // A tap is a single round dab; model it as a zero-length segment.
                addSegment(points[0], points[0], params)
                return
            }

            for (i in 1 until points.size) {
                addSegment(points[i - 1], points[i], params)
            }
        }

        private fun addSegment(a: StrokePoint, b: StrokePoint, params: com.artflow.studio.domain.model.brush.BrushParams) {
            val radiusA = max(0.5f, params.calculateEffectiveSize(a.pressure) / 2f)
            val radiusB = max(0.5f, params.calculateEffectiveSize(b.pressure) / 2f)
            val alpha = params.calculateEffectiveOpacity((a.pressure + b.pressure) / 2f)
            val color = params.applyColorJitter(a.color, a.pressure)

            val r = ((color shr 16) and 0xFF) / 255f
            val g = ((color shr 8) and 0xFF) / 255f
            val bl = (color and 0xFF) / 255f

            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = sqrt(dx * dx + dy * dy)
            val normalX: Float
            val normalY: Float
            if (length < 1e-4f) {
                normalX = 0f
                normalY = 1f
            } else {
                normalX = -dy / length
                normalY = dx / length
            }

            val maxRadius = max(radiusA, radiusB)
            // Pad the quad by a pixel so the anti-aliased edge is not clipped.
            val pad = maxRadius + 1f
            val startX = a.x
            val startY = a.y
            val endX = b.x
            val endY = b.y

            // Four corners of the capsule's bounding quad.
            val cornerAX = startX - normalX * pad
            val cornerAY = startY - normalY * pad
            val cornerBX = endX - normalX * pad
            val cornerBY = endY - normalY * pad
            val cornerCX = endX + normalX * pad
            val cornerCY = endY + normalY * pad
            val cornerDX = startX + normalX * pad
            val cornerDY = startY + normalY * pad

            emit(cornerAX, cornerAY, startX, startY, endX, endY, radiusA, radiusB, r, g, bl, alpha)
            emit(cornerBX, cornerBY, startX, startY, endX, endY, radiusA, radiusB, r, g, bl, alpha)
            emit(cornerCX, cornerCY, startX, startY, endX, endY, radiusA, radiusB, r, g, bl, alpha)

            emit(cornerAX, cornerAY, startX, startY, endX, endY, radiusA, radiusB, r, g, bl, alpha)
            emit(cornerCX, cornerCY, startX, startY, endX, endY, radiusA, radiusB, r, g, bl, alpha)
            emit(cornerDX, cornerDY, startX, startY, endX, endY, radiusA, radiusB, r, g, bl, alpha)
        }

        private fun emit(
            x: Float,
            y: Float,
            segAX: Float,
            segAY: Float,
            segBX: Float,
            segBY: Float,
            radiusA: Float,
            radiusB: Float,
            r: Float,
            g: Float,
            b: Float,
            alpha: Float
        ) {
            vertices.add(x)
            vertices.add(y)
            vertices.add(segAX)
            vertices.add(segAY)
            vertices.add(segBX)
            vertices.add(segBY)
            vertices.add(radiusA)
            vertices.add(radiusB)
            vertices.add(r)
            vertices.add(g)
            vertices.add(b)
            vertices.add(alpha)
            vertexCount++
        }

        fun toFloatArray(): FloatArray = FloatArray(vertices.size) { vertices[it] }

        companion object {
            const val FLOATS_PER_VERTEX = 12
        }
    }

    companion object {
        private val QUAD_VERTICES = floatArrayOf(
            0f, 0f, 0f, 0f,
            1f, 0f, 1f, 0f,
            0f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f
        )

        private const val QUAD_VERTEX_SHADER = """
            uniform mat4 uMatrix;
            uniform vec2 uCanvasSize;
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                // The quad topology is a unit square; scale it to canvas pixels here so the MVP
                // matrix only ever deals with canvas coordinates.
                gl_Position = uMatrix * vec4(aPosition * uCanvasSize, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """

        private const val QUAD_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uTint;
            uniform float uAlpha;
            uniform float uUseTexture;
            varying vec2 vTexCoord;
            void main() {
                vec4 sampled = texture2D(uTexture, vTexCoord);
                // uUseTexture = 1 samples the texture, 0 produces a solid tinted quad.
                vec4 texel = mix(vec4(1.0, 1.0, 1.0, 1.0), sampled, uUseTexture);
                gl_FragColor = texel * uTint * uAlpha;
            }
        """

        private const val STROKE_VERTEX_SHADER = """
            uniform mat4 uMatrix;
            uniform vec2 uViewportScale;
            attribute vec2 aPosition;
            attribute vec2 aSegA;
            attribute vec2 aSegB;
            attribute vec2 aRadii;
            attribute vec4 aColor;
            varying vec2 vPosition;
            varying vec2 vSegA;
            varying vec2 vSegB;
            varying vec2 vRadii;
            varying vec4 vColor;
            void main() {
                gl_Position = uMatrix * vec4(aPosition, 0.0, 1.0);
                vPosition = aPosition;
                vSegA = aSegA;
                vSegB = aSegB;
                vRadii = aRadii;
                vColor = aColor;
            }
        """

        /**
         * Capsule distance field: the fragment's alpha is derived from its distance to the
         * segment, with the radius interpolated along the segment. This gives round caps and a
         * one-pixel anti-aliased edge at any zoom level.
         */
        private const val STROKE_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vPosition;
            varying vec2 vSegA;
            varying vec2 vSegB;
            varying vec2 vRadii;
            varying vec4 vColor;
            void main() {
                vec2 ab = vSegB - vSegA;
                float abLenSq = dot(ab, ab);
                float t = abLenSq > 0.0001 ? clamp(dot(vPosition - vSegA, ab) / abLenSq, 0.0, 1.0) : 0.0;
                vec2 closest = vSegA + ab * t;
                float radius = mix(vRadii.x, vRadii.y, t);
                float distanceToSegment = length(vPosition - closest);
                float aa = max(0.5, radius * 0.08);
                float coverage = 1.0 - smoothstep(radius - aa, radius, distanceToSegment);
                if (coverage <= 0.0) discard;
                gl_FragColor = vec4(vColor.rgb * vColor.a * coverage, vColor.a * coverage);
            }
        """
    }
}
