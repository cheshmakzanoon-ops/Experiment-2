package com.artflow.studio.data.renderer.opengl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.BitmapPixelBridge
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * GPU canvas renderer.
 *
 * A textured quad displays the composited artwork, including in-flight strokes inside their
 * actual layer. The CPU compositor is shared by previews, commits, thumbnails and exports.
 * There is no separate approximate brush overlay: erasing, masks, pressure, grain and opacity
 * must look the same before and after the artist lifts the stylus.
 *
 * Onion-skin frames are drawn with the same textured-quad program in a tinted, alpha-blended pass.
 */
class OpenGLCanvasRenderer
    @Inject
    constructor() : GLSurfaceView.Renderer {
        // --- Canvas / viewport state ---------------------------------------------------------------

        private var viewWidth = 0
        private var viewHeight = 0
        private var canvasWidth = 1
        private var canvasHeight = 1

        private var scale = 1f
        private var offsetX = 0f
        private var offsetY = 0f
        private var rotationDegrees = 0f

        @Volatile
        private var backgroundColor = floatArrayOf(1f, 1f, 1f, 1f)

        @Volatile
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

        // --- Textures ------------------------------------------------------------------------------

        private var compositeTexture = 0
        private var checkerTexture = 0

        /** Onion-skin frame textures, rebuilt whenever the ghost frames change. */
        private val onionTextures = mutableListOf<Int>()
        private val onionAlphas = mutableListOf<Float>()

        // --- Data staged from other threads ---------------------------------------------------------

        // Ownership is transferred atomically. A producer may recycle only a superseded, unclaimed
        // bitmap; it must never recycle one that the GL thread is uploading.
        private val pendingComposite = AtomicReference<Bitmap?>(null)
        private val pendingOnionSkins = AtomicReference<List<Pair<Bitmap, Float>>?>(null)

        // GL-thread-owned images survive EGL context loss and can be reuploaded into the new context.
        private var retainedComposite: Bitmap? = null
        private var retainedOnions: List<Pair<Bitmap, Float>> = emptyList()
        private var maxTextureSize = 2048
        private var isInitialized = false

        private val quadVertexBuffer: FloatBuffer =
            ByteBuffer
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

        override fun onSurfaceCreated(
            unused: GL10?,
            config: EGLConfig?,
        ) {
            // Texture/program names from a previous EGL context are invalid, even if their numeric
            // values happen to be reused. Keep CPU images, but allocate all GL objects anew.
            compositeTexture = 0
            checkerTexture = 0
            onionTextures.clear()
            onionAlphas.clear()
            val textureLimit = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, textureLimit, 0)
            maxTextureSize = textureLimit[0].coerceAtLeast(2048)
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

            checkerTexture = createCheckerboardTexture()
            isInitialized = true
        }

        override fun onSurfaceChanged(
            unused: GL10?,
            width: Int,
            height: Int,
        ) {
            viewWidth = width
            viewHeight = height
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(unused: GL10?) {
            if (!isInitialized) return

            GLES20.glClearColor(0.08f, 0.08f, 0.09f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // GLSurfaceView has already scheduled this frame. A cleared framebuffer must always be
            // repainted, including pan/zoom, expose and redundant requestRender calls.

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
            }
            val paper = backgroundColor
            if (paper[3] > 0f) {
                GLES20.glUniform1f(quadUseTextureHandle, 0f)
                GLES20.glUniform4f(quadTintHandle, paper[0] * paper[3], paper[1] * paper[3], paper[2] * paper[3], paper[3])
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

            // 3. Upload changed artwork, but draw the retained texture on EVERY frame.
            val incoming = pendingComposite.getAndSet(null)
            if (incoming != null) {
                retainedComposite?.recycle()
                retainedComposite = incoming
            }
            retainedComposite?.let { bitmap ->
                if (compositeTexture == 0) {
                    compositeTexture = uploadBitmap(bitmap)
                } else if (incoming != null) {
                    updateBitmap(compositeTexture, bitmap)
                }
                GLES20.glUniform4f(quadTintHandle, 1f, 1f, 1f, 1f)
                drawQuad(compositeTexture, textureRepeat = false)
            }
        }

        private fun refreshOnionTextures() {
            val incoming = pendingOnionSkins.getAndSet(null)
            if (incoming != null) {
                retainedOnions.forEach { it.first.recycle() }
                retainedOnions = incoming
            }
            if (incoming == null && onionTextures.size == retainedOnions.size) return
            onionTextures.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
            onionTextures.clear()
            onionAlphas.clear()
            retainedOnions.forEach { (bitmap, alpha) ->
                onionTextures += uploadBitmap(bitmap)
                onionAlphas += alpha
            }
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
            pendingComposite.getAndSet(BitmapPixelBridge.toBitmap(buffer))?.recycle()
        }

        fun setOnionSkins(frames: List<Pair<PixelBuffer, Float>>) {
            val bitmaps = mutableListOf<Pair<Bitmap, Float>>()
            var published = false
            try {
                frames.forEach { (buffer, alpha) -> bitmaps += BitmapPixelBridge.toBitmap(buffer) to alpha.coerceIn(0f, 1f) }
                val obsolete = pendingOnionSkins.getAndSet(bitmaps)
                published = true // The GL thread owns these bitmaps from this point.
                obsolete?.forEach { it.first.recycle() }
            } finally {
                if (!published) bitmaps.forEach { it.first.recycle() }
            }
        }

        fun clearOnionSkins() {
            pendingOnionSkins.getAndSet(emptyList())?.forEach { it.first.recycle() }
        }

        fun setCanvasSize(
            width: Int,
            height: Int,
            dpi: Int,
        ) {
            canvasWidth = max(1, width)
            canvasHeight = max(1, height)
        }

        fun setTransformation(
            scale: Float,
            offsetX: Float,
            offsetY: Float,
            rotation: Float,
        ) {
            this.scale = scale
            this.offsetX = offsetX
            this.offsetY = offsetY
            this.rotationDegrees = rotation
        }

        fun setBackgroundArgb(argb: Int) {
            backgroundColor =
                floatArrayOf(
                    android.graphics.Color.red(argb) / 255f,
                    android.graphics.Color.green(argb) / 255f,
                    android.graphics.Color.blue(argb) / 255f,
                    android.graphics.Color.alpha(argb) / 255f,
                )
        }

        fun setCheckerboardVisible(visible: Boolean) {
            showCheckerboard = visible
        }

        /** Called only AFTER GLSurfaceView has stopped its GL thread and destroyed the surface. */
        fun dispose() {
            // EGL already owns destruction of context objects. Calling glDelete* here on the main
            // thread would act on no context (or the wrong context).
            compositeTexture = 0
            checkerTexture = 0
            quadProgram = 0
            onionTextures.clear()
            onionAlphas.clear()
            pendingComposite.getAndSet(null)?.recycle()
            pendingOnionSkins.getAndSet(null)?.forEach { it.first.recycle() }
            retainedComposite?.recycle()
            retainedComposite = null
            retainedOnions.forEach { it.first.recycle() }
            retainedOnions = emptyList()
            isInitialized = false
        }

        // -----------------------------------------------------------------------------------------
        // Drawing
        // -----------------------------------------------------------------------------------------

        /** Draws the unit quad expanded to the canvas rectangle, optionally tiled. */
        private fun drawQuad(
            textureId: Int,
            textureRepeat: Boolean,
        ) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glUniform1i(quadTextureHandle, 0)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                if (textureRepeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                if (textureRepeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE,
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
            uploadPixels(bitmap)
            return texture
        }

        /** Re-uploads into an existing texture, avoiding a texture object per frame. */
        private fun updateBitmap(
            textureId: Int,
            bitmap: Bitmap,
        ) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            uploadPixels(bitmap)
        }

        private fun uploadPixels(bitmap: Bitmap) {
            val longest = maxOf(bitmap.width, bitmap.height)
            val upload =
                if (longest <= maxTextureSize) {
                    bitmap
                } else {
                    val ratio = maxTextureSize.toFloat() / longest
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * ratio).toInt().coerceAtLeast(1),
                        (bitmap.height * ratio).toInt().coerceAtLeast(1),
                        true,
                    )
                }
            try {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, upload, 0)
            } finally {
                if (upload !== bitmap) upload.recycle()
            }
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

        private fun createProgram(
            vertexSource: String,
            fragmentSource: String,
        ): Int {
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

        private fun compileShader(
            type: Int,
            source: String,
        ): Int {
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

        companion object {
            private val QUAD_VERTICES =
                floatArrayOf(
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                    1f,
                    0f,
                    0f,
                    1f,
                    0f,
                    1f,
                    1f,
                    1f,
                    1f,
                    1f,
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
        }
    }
