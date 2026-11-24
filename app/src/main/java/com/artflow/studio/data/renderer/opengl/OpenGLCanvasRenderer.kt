package com.artflow.studio.data.renderer.opengl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.BitmapPixelBridge
import timber.log.Timber
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
 * The injectable constructor uses the device's texture cap; [previewTextureLimit] can impose a
 * smaller preview budget without changing the document, including in GLES capability regressions.
 */
class OpenGLCanvasRenderer
    internal constructor(
        private val previewTextureLimit: () -> Int,
    ) : GLSurfaceView.Renderer {
        internal constructor(previewTextureLimit: Int) : this({ previewTextureLimit })

        @Inject
        constructor() : this(Int.MAX_VALUE)

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

        @Volatile
        private var wideColor = false
        private var proofMode = 0

        /** An on-screen view check (greyscale or colour vision), as a column-major matrix; null is off. */
        @Volatile
        private var viewMatrix: FloatArray? = null

        /** Notan's number of flat values; 0 keeps the view continuous. */
        @Volatile
        private var viewLevels = 0

        /** Predicted pen positions ahead of the live stroke: x, y pairs in canvas pixels. */
        @Volatile
        private var prediction: Prediction? = null

        private class Prediction(
            val points: FloatArray,
            val radius: Float,
            val color: Int,
        )

        private var dabProgram = 0
        private var dabMatrixHandle = 0
        private var dabColorHandle = 0
        private var dabPositionHandle = 0
        private var dabCornerHandle = 0
        private var dabVertexArray = FloatArray(0)
        private var dabVertexBuffer: FloatBuffer? = null
        private val frameMatrix = FloatArray(16)
        private val regionCopyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        private var retainedCanvas: Canvas? = null

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
        private var quadWideColorHandle = 0
        private var quadProofHandle = 0
        private var quadViewHandle = 0
        private var quadViewMatrixHandle = 0
        private var quadViewLevelsHandle = 0

        // --- Textures ------------------------------------------------------------------------------

        private var compositeTexture = 0
        private var compositeTextureSize: CanvasTextureSize? = null
        private var checkerTexture = 0

        private class UploadedTexture(
            val handle: Int,
            val size: CanvasTextureSize,
        )

        /** Onion-skin frame textures, rebuilt whenever the ghost frames change. */
        private val onionTextures = mutableListOf<Int>()
        private val onionAlphas = mutableListOf<Float>()
        private var onionTexturesReady = false

        // --- Data staged from other threads ---------------------------------------------------------

        // Ownership is transferred atomically. A producer may recycle only a superseded, unclaimed
        // bitmap; it must never recycle one that the GL thread is uploading.
        private class CompositeImage(
            val bitmap: Bitmap,
            val generation: Long,
        )

        private val pendingComposite = AtomicReference<CompositeImage?>(null)

        /** Changed areas published after the pending composite, applied in order; guarded by itself. */
        private val pendingRegions = mutableListOf<RegionUpdate>()

        private class RegionUpdate(
            val left: Int,
            val top: Int,
            val bitmap: Bitmap,
        )

        private val pendingOnionSkins = AtomicReference<List<Pair<Bitmap, Float>>?>(null)

        // GL-thread-owned images survive EGL context loss and can be reuploaded into the new context.
        private var retainedComposite: Bitmap? = null
        private var retainedCompositeGeneration = 0L
        private var retainedOnions: List<Pair<Bitmap, Float>> = emptyList()
        private var maxTextureSize = 0
        private var isInitialized = false
        private var reportedUploadFailure = false
        private val previewState = CanvasPreviewState()

        val previewAvailability: CanvasPreviewAvailability get() = previewState.availability
        val previewInterruptions: Long get() = previewState.interruptions
        val previewStatus: CanvasPreviewStatus get() = previewState.status

        /** State is published before this callback; input can read it without waiting for main. */
        var onPreviewAvailabilityChanged: ((CanvasPreviewAvailability) -> Unit)?
            get() = previewState.onChanged
            set(value) {
                previewState.onChanged = value
            }

        /** Called on the GL thread; the view forwards this to its existing status UI on main. */
        @Volatile
        var onStatusMessage: ((String) -> Unit)? = null

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
            isInitialized = false
            previewState.awaitSurface()
            try {
                initializeSurface()
            } catch (error: IllegalStateException) {
                Timber.e(error, "Canvas GL initialization failed")
                previewState.presented(previewState.generation(), false)
                reportUploadFailure()
            } catch (error: OutOfMemoryError) {
                Timber.e(error, "Insufficient memory for canvas GL initialization")
                previewState.presented(previewState.generation(), false)
                reportUploadFailure()
            }
        }

        private fun initializeSurface() {
            // Texture/program names from a previous EGL context are invalid, even if their numeric
            // values happen to be reused. Keep CPU images, but allocate all GL objects anew.
            compositeTexture = 0
            compositeTextureSize = null
            checkerTexture = 0
            onionTextures.clear()
            onionAlphas.clear()
            onionTexturesReady = false
            val textureLimit = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, textureLimit, 0)
            maxTextureSize = textureLimit[0]
            reportedUploadFailure = false
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
            quadWideColorHandle = GLES20.glGetUniformLocation(quadProgram, "uWideColor")
            quadProofHandle = GLES20.glGetUniformLocation(quadProgram, "uProof")
            quadViewHandle = GLES20.glGetUniformLocation(quadProgram, "uView")
            quadViewMatrixHandle = GLES20.glGetUniformLocation(quadProgram, "uViewMatrix")
            quadViewLevelsHandle = GLES20.glGetUniformLocation(quadProgram, "uViewLevels")
            dabProgram = createProgram(DAB_VERTEX_SHADER, DAB_FRAGMENT_SHADER)
            dabMatrixHandle = GLES20.glGetUniformLocation(dabProgram, "uMatrix")
            dabColorHandle = GLES20.glGetUniformLocation(dabProgram, "uColor")
            dabPositionHandle = GLES20.glGetAttribLocation(dabProgram, "aPosition")
            dabCornerHandle = GLES20.glGetAttribLocation(dabProgram, "aCorner")

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

            val matrix = fillProjectionMatrix(frameMatrix)

            // 1. Canvas background (checkerboard or a solid colour).
            GLES20.glUseProgram(quadProgram)
            GLES20.glUniformMatrix4fv(quadMatrixHandle, 1, false, matrix, 0)
            GLES20.glUniform2f(quadCanvasSizeHandle, canvasWidth.toFloat(), canvasHeight.toFloat())
            GLES20.glUniform1f(quadAlphaHandle, 1f)
            GLES20.glUniform4f(quadTintHandle, 1f, 1f, 1f, 1f)
            GLES20.glUniform1f(quadWideColorHandle, if (wideColor) 1f else 0f)
            GLES20.glUniform1f(quadProofHandle, proofMode.toFloat())
            val filter = viewMatrix
            GLES20.glUniform1f(quadViewHandle, if (filter == null) 0f else 1f)
            GLES20.glUniformMatrix3fv(quadViewMatrixHandle, 1, false, filter ?: IDENTITY_3, 0)
            GLES20.glUniform1f(quadViewLevelsHandle, viewLevels.toFloat())

            if (showCheckerboard && checkerTexture != 0) {
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
            val (incoming, regions) =
                synchronized(pendingRegions) {
                    val taken = if (pendingRegions.isEmpty()) emptyList() else pendingRegions.toList()
                    pendingRegions.clear()
                    pendingComposite.getAndSet(null) to taken
                }
            if (incoming != null) {
                retainedComposite?.recycle()
                retainedComposite = incoming.bitmap
                retainedCanvas = Canvas(incoming.bitmap)
                retainedCompositeGeneration = incoming.generation
            }
            // Regions without a base image have nothing to patch; the view resends the whole canvas.
            if (retainedComposite == null) regions.forEach { it.bitmap.recycle() }
            retainedComposite?.let { drawPrimaryImage(it, regions, incoming != null) }
            if (previewAvailability == CanvasPreviewAvailability.AVAILABLE) prediction?.let { drawPrediction(it, matrix) }
        }

        private fun drawPrimaryImage(
            bitmap: Bitmap,
            regions: List<RegionUpdate>,
            replaced: Boolean,
        ) {
            // Auxiliary rendering cannot turn a successful primary upload into a false failure.
            drainGlErrors("before primary preview")
            val partial = applyRegions(bitmap, regions)
            if (compositeTexture == 0) {
                val uploaded = uploadBitmap(bitmap, primary = true)
                compositeTexture = uploaded?.handle ?: 0
                compositeTextureSize = uploaded?.size
            } else if (replaced || !partial || compositeTextureSize == null) {
                compositeTextureSize = updateBitmap(compositeTexture, bitmap)
            }
            var displayed = false
            if (compositeTextureSize != null) {
                GLES20.glUniform4f(quadTintHandle, 1f, 1f, 1f, 1f)
                GLES20.glUniform1f(quadAlphaHandle, primaryAlpha)
                drawQuad(compositeTexture, textureRepeat = false)
                GLES20.glUniform1f(quadAlphaHandle, 1f)
                displayed = drainGlErrors("primary preview draw") == GLES20.GL_NO_ERROR
            }
            previewState.presented(retainedCompositeGeneration, displayed)
            if (displayed && previewAvailability == CanvasPreviewAvailability.AVAILABLE) reportedUploadFailure = false
            if (!displayed) reportUploadFailure()
        }

        /** Shows soft dabs where the pen is predicted to be; [points] are canvas x, y pairs. */
        fun setPrediction(
            points: FloatArray?,
            radius: Float,
            color: Int,
        ) {
            prediction = points?.takeIf { it.size >= 2 }?.let { Prediction(it, radius, color) }
        }

        private fun drawPrediction(
            current: Prediction,
            matrix: FloatArray,
        ) {
            val count = current.points.size / 2
            val neededFloats = count * DAB_FLOATS
            // Two triangles per dab: canvas position plus a corner offset in -1..1.
            var vertices = dabVertexArray
            if (vertices.size < neededFloats) {
                vertices = FloatArray(neededFloats)
                dabVertexArray = vertices
            }
            var buffer = dabVertexBuffer
            if (buffer == null || buffer.capacity() < neededFloats) {
                buffer =
                    ByteBuffer
                        .allocateDirect(neededFloats * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer()
                dabVertexBuffer = buffer
            }
            val corners = DAB_CORNERS
            val radius = current.radius
            val points = current.points
            for (dab in 0 until count) {
                val px = points[dab * 2]
                val py = points[dab * 2 + 1]
                val dabBase = dab * DAB_FLOATS
                for (corner in 0 until 6) {
                    val base = dabBase + corner * 4
                    val cx = corners[corner * 2]
                    val cy = corners[corner * 2 + 1]
                    vertices[base] = px + cx * radius
                    vertices[base + 1] = py + cy * radius
                    vertices[base + 2] = cx
                    vertices[base + 3] = cy
                }
            }
            buffer.clear()
            buffer.put(vertices, 0, neededFloats)
            buffer.position(0)
            GLES20.glUseProgram(dabProgram)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
            GLES20.glUniformMatrix4fv(dabMatrixHandle, 1, false, matrix, 0)
            val alpha = ((current.color ushr 24) and 0xFF) / 255f * PREDICTION_ALPHA
            GLES20.glUniform4f(
                dabColorHandle,
                ((current.color shr 16) and 0xFF) / 255f * alpha,
                ((current.color shr 8) and 0xFF) / 255f * alpha,
                (current.color and 0xFF) / 255f * alpha,
                alpha,
            )
            buffer.position(0)
            GLES20.glEnableVertexAttribArray(dabPositionHandle)
            GLES20.glVertexAttribPointer(dabPositionHandle, 2, GLES20.GL_FLOAT, false, 16, buffer)
            buffer.position(2)
            GLES20.glEnableVertexAttribArray(dabCornerHandle)
            GLES20.glVertexAttribPointer(dabCornerHandle, 2, GLES20.GL_FLOAT, false, 16, buffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count * 6)
            GLES20.glDisableVertexAttribArray(dabPositionHandle)
            GLES20.glDisableVertexAttribArray(dabCornerHandle)
        }

        private fun refreshOnionTextures() {
            val incoming = pendingOnionSkins.getAndSet(null)
            if (incoming != null) {
                retainedOnions.forEach { it.first.recycle() }
                retainedOnions = incoming
            }
            if (incoming == null && onionTexturesReady) return
            onionTexturesReady = true
            onionTextures.forEach { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
            onionTextures.clear()
            onionAlphas.clear()
            retainedOnions.forEach { (bitmap, alpha) ->
                uploadBitmap(bitmap)?.let { uploaded ->
                    onionTextures += uploaded.handle
                    onionAlphas += alpha
                }
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
            val generation = previewState.generation()
            val image = CompositeImage(BitmapPixelBridge.toBitmap(buffer), generation)
            synchronized(pendingRegions) {
                // The full image already contains every earlier region.
                pendingRegions.forEach { it.bitmap.recycle() }
                pendingRegions.clear()
                pendingComposite.getAndSet(image)
            }?.bitmap?.recycle()
        }

        /** A failed CPU update must not allow an older in-flight GL frame to re-enable input. */
        fun invalidatePrimaryPreview() {
            previewState.invalidateContent()
            synchronized(pendingRegions) {
                pendingRegions.forEach { it.bitmap.recycle() }
                pendingRegions.clear()
                pendingComposite.getAndSet(null)
            }?.bitmap?.recycle()
        }

        /** Called after the surface thread has paused; retained CPU pixels may be displayed again. */
        fun awaitPrimaryPreview() = previewState.awaitSurface()

        /**
         * Publishes only [region] of [buffer] (canvas pixels, inclusive bounds); an empty region is
         * ignored. Painting a stroke then uploads a small rectangle per frame, not the whole canvas.
         */
        fun setCompositeRegion(
            buffer: PixelBuffer,
            region: IntBounds,
        ) {
            if (region.isEmpty) return
            val bitmap =
                Bitmap.createBitmap(region.width, region.height, Bitmap.Config.ARGB_8888).apply {
                    setPremultiplied(true)
                    setPixels(buffer.pixels, region.top * buffer.width + region.left, buffer.width, 0, 0, region.width, region.height)
                }
            synchronized(pendingRegions) { pendingRegions += RegionUpdate(region.left, region.top, bitmap) }
        }

        /**
         * Copies [regions] into the retained image and, when the texture holds it at full size, into
         * the texture. Returns false when the texture must be reuploaded instead.
         */
        private fun applyRegions(
            retained: Bitmap,
            regions: List<RegionUpdate>,
        ): Boolean {
            if (regions.isEmpty()) return true
            val canvas = retainedCanvas ?: Canvas(retained).also { retainedCanvas = it }
            var direct = compositeTexture != 0 && compositeTextureSize == CanvasTextureSize(retained.width, retained.height)
            if (direct) {
                drainGlErrors("before canvas region binding")
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, compositeTexture)
                direct = drainGlErrors("canvas region binding") == GLES20.GL_NO_ERROR
            }
            regions.forEach { region ->
                canvas.drawBitmap(region.bitmap, region.left.toFloat(), region.top.toFloat(), regionCopyPaint)
                if (direct) {
                    direct = uploadRegion(region)
                }
                region.bitmap.recycle()
            }
            return direct
        }

        private fun uploadRegion(region: RegionUpdate): Boolean =
            try {
                drainGlErrors("before canvas region upload")
                GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, region.left, region.top, region.bitmap)
                drainGlErrors("canvas region upload") == GLES20.GL_NO_ERROR
            } catch (error: IllegalArgumentException) {
                Timber.w(error, "Canvas region upload rejected; resending full preview")
                false
            }

        /** Animation Assist's Blend Primary Frame: the frame being drawn shows the ghosts through it. */
        @Volatile
        private var primaryAlpha = 1f

        fun setOnionSkins(
            frames: List<Pair<PixelBuffer, Float>>,
            primaryAlpha: Float = 1f,
        ) {
            this.primaryAlpha = if (frames.isEmpty()) 1f else primaryAlpha.coerceIn(0f, 1f)
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
            primaryAlpha = 1f
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

        /** Display P3 artwork: its values are converted to sRGB on screen. */
        fun setWideColor(enabled: Boolean) {
            wideColor = enabled
        }

        /** Print soft-proofing on screen: 0 off, 1 CMYK proof, 2 proof with out-of-gamut colours greyed. */
        fun setProof(mode: Int) {
            proofMode = mode
        }

        /** Greyscale or a colour-vision check on screen: a column-major 3×3 matrix on linear sRGB, or null for none. */
        fun setViewFilter(
            columnMajor: FloatArray?,
            levels: Int = 0,
        ) {
            viewMatrix = columnMajor
            viewLevels = levels
        }

        /** Called only AFTER GLSurfaceView has stopped its GL thread and destroyed the surface. */
        fun dispose() {
            // EGL already owns destruction of context objects. Calling glDelete* here on the main
            // thread would act on no context (or the wrong context).
            compositeTexture = 0
            compositeTextureSize = null
            checkerTexture = 0
            quadProgram = 0
            onionTextures.clear()
            onionAlphas.clear()
            onionTexturesReady = false
            pendingComposite.getAndSet(null)?.bitmap?.recycle()
            synchronized(pendingRegions) {
                pendingRegions.forEach { it.bitmap.recycle() }
                pendingRegions.clear()
            }
            pendingOnionSkins.getAndSet(null)?.forEach { it.first.recycle() }
            retainedComposite?.recycle()
            retainedComposite = null
            retainedCanvas = null
            dabVertexArray = FloatArray(0)
            dabVertexBuffer = null
            retainedOnions.forEach { it.first.recycle() }
            retainedOnions = emptyList()
            isInitialized = false
            previewState.invalidateContent()
            previewState.awaitSurface()
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
        internal fun projectionMatrix(): FloatArray = fillProjectionMatrix(FloatArray(16))

        private fun fillProjectionMatrix(m: FloatArray): FloatArray {
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
            m.fill(0f)
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

        private fun uploadBitmap(
            bitmap: Bitmap,
            primary: Boolean = false,
        ): UploadedTexture? {
            drainGlErrors("before canvas texture allocation")
            val handles = IntArray(1)
            GLES20.glGenTextures(1, handles, 0)
            val texture = handles[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val bound = drainGlErrors("canvas texture allocation") == GLES20.GL_NO_ERROR
            val size = if (texture != 0 && bound) uploadPixels(bitmap, primary) else null
            if (texture != 0 && size != null) return UploadedTexture(texture, size)
            GLES20.glDeleteTextures(1, handles, 0)
            return null
        }

        /** Re-uploads into an existing texture, avoiding a texture object per frame. */
        private fun updateBitmap(
            textureId: Int,
            bitmap: Bitmap,
        ): CanvasTextureSize? {
            drainGlErrors("before primary texture binding")
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            if (drainGlErrors("primary texture binding") != GLES20.GL_NO_ERROR) return null
            return uploadPixels(bitmap, primary = true)
        }

        private fun uploadPixels(
            bitmap: Bitmap,
            primary: Boolean,
        ): CanvasTextureSize? {
            val limit = minOf(maxTextureSize, previewTextureLimit())
            val requested = CanvasTextureUpload.fit(bitmap.width, bitmap.height, limit)
            val uploaded =
                CanvasTextureUpload.upload(bitmap.width, bitmap.height, limit) { size ->
                    uploadPixelsAtSize(bitmap, size)
                }
            if (primary && uploaded != null && uploaded != requested) {
                // Keep the working preview budget for this EGL context instead of retrying the
                // rejected allocation on every stroke. A new context queries its capability anew.
                maxTextureSize = minOf(maxTextureSize, uploaded.longest)
                onStatusMessage?.invoke(
                    "Canvas preview reduced to ${uploaded.width} × ${uploaded.height} to fit this device. " +
                        "Artwork and exports keep their full resolution.",
                )
            }
            return uploaded
        }

        private fun uploadPixelsAtSize(
            bitmap: Bitmap,
            size: CanvasTextureSize,
        ): Boolean {
            drainGlErrors("before canvas texture upload")
            var upload: Bitmap? = null
            try {
                upload =
                    if (size.width == bitmap.width && size.height == bitmap.height) {
                        bitmap
                    } else {
                        Bitmap.createScaledBitmap(bitmap, size.width, size.height, true)
                    }
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, upload, 0)
                val error = drainGlErrors("canvas texture upload")
                if (error != GLES20.GL_NO_ERROR) Timber.w("Canvas texture upload failed: GL %s at %sx%s", error, size.width, size.height)
                return error == GLES20.GL_NO_ERROR
            } catch (error: IllegalArgumentException) {
                Timber.w(error, "Canvas texture upload rejected")
            } catch (error: OutOfMemoryError) {
                Timber.w(error, "Insufficient memory for canvas preview texture")
            } finally {
                if (upload !== bitmap) upload?.recycle()
            }
            return false
        }

        private fun reportUploadFailure() {
            if (!reportedUploadFailure) {
                reportedUploadFailure = true
                onStatusMessage?.invoke(
                    "This device could not display the canvas preview. Canvas input is paused. Save your artwork, then reopen the editor.",
                )
            }
        }

        /** Drain the complete bounded error queue so an unrelated earlier error cannot taint a retry. */
        private fun drainGlErrors(operation: String): Int {
            var first = GLES20.GL_NO_ERROR
            repeat(16) {
                val error = GLES20.glGetError()
                if (error == GLES20.GL_NO_ERROR) return first
                if (first == GLES20.GL_NO_ERROR) first = error
                Timber.w("GL error %s during %s", error, operation)
            }
            return first
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
            val texture = uploadBitmap(bitmap)?.handle ?: 0
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
                error("Shader link failed: $log")
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
                error("Shader compile failed: $log")
            }
            return shader
        }

        companion object {
            private val IDENTITY_3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
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

            private const val DAB_FLOATS = 24
            private const val PREDICTION_ALPHA = 0.85f
            private val DAB_CORNERS = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f)

            private const val DAB_VERTEX_SHADER = """
            uniform mat4 uMatrix;
            attribute vec2 aPosition;
            attribute vec2 aCorner;
            varying vec2 vCorner;
            void main() {
                gl_Position = uMatrix * vec4(aPosition, 0.0, 1.0);
                vCorner = aCorner;
            }
        """

            private const val DAB_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            varying vec2 vCorner;
            void main() {
                float edge = 1.0 - smoothstep(0.8, 1.0, length(vCorner));
                gl_FragColor = uColor * edge;
            }
        """

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
            uniform float uWideColor;
            uniform float uProof;
            uniform float uView;
            uniform mat3 uViewMatrix;
            uniform float uViewLevels;
            varying vec2 vTexCoord;
            // Printed process inks on white paper, as encoded sRGB (see CmykProof).
            const vec3 INK_C = vec3(0.0, 0.682, 0.937);
            const vec3 INK_M = vec3(0.925, 0.0, 0.549);
            const vec3 INK_Y = vec3(1.0, 0.949, 0.0);
            const vec3 INK_K = vec3(0.137, 0.122, 0.125);
            // Linear Display P3 to linear sRGB (column-major).
            const mat3 P3_TO_SRGB = mat3(
                1.2249402, -0.0420570, -0.0196376,
                -0.2249402, 1.0420570, -0.0786361,
                0.0, 0.0, 1.0982736);
            vec3 toLinear(vec3 c) {
                return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
            }
            vec3 toEncoded(vec3 c) {
                return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
            }
            void main() {
                vec4 sampled = texture2D(uTexture, vTexCoord);
                // uUseTexture = 1 samples the texture, 0 produces a solid tinted quad.
                vec4 texel = mix(vec4(1.0, 1.0, 1.0, 1.0), sampled, uUseTexture);
                vec4 color = texel * uTint * uAlpha;
                if (uWideColor > 0.5 && color.a > 0.0) {
                    vec3 srgb = clamp(P3_TO_SRGB * toLinear(color.rgb / color.a), 0.0, 1.0);
                    color.rgb = toEncoded(srgb) * color.a;
                }
                if (uProof > 0.5 && color.a > 0.0) {
                    vec3 rgb = clamp(color.rgb / color.a, 0.0, 1.0);
                    float k = 1.0 - max(max(rgb.r, rgb.g), rgb.b);
                    vec3 cmy = k < 0.999 ? (vec3(1.0 - k) - rgb) / (1.0 - k) : vec3(0.0);
                    vec3 printed = (1.0 - cmy.x * (1.0 - INK_C)) * (1.0 - cmy.y * (1.0 - INK_M))
                        * (1.0 - cmy.z * (1.0 - INK_Y)) * (1.0 - k * (1.0 - INK_K));
                    vec3 shift = abs(printed - rgb);
                    if (uProof > 1.5 && max(max(shift.r, shift.g), shift.b) > 0.08) printed = mix(printed, vec3(0.5), 0.6);
                    color.rgb = printed * color.a;
                }
                if (uView > 0.5 && color.a > 0.0) {
                    vec3 seen = clamp(uViewMatrix * toLinear(clamp(color.rgb / color.a, 0.0, 1.0)), 0.0, 1.0);
                    vec3 shown = toEncoded(seen);
                    if (uViewLevels > 1.5) shown = min(floor(shown * uViewLevels), uViewLevels - 1.0) / (uViewLevels - 1.0);
                    color.rgb = shown * color.a;
                }
                gl_FragColor = color;
            }
        """
        }
    }
