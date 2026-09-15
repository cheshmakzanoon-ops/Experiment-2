package com.artflow.studio.data.renderer.opengl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import com.artflow.studio.domain.repository.canvas.CanvasSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 implementation of GLSurfaceView.Renderer
 * Handles GPU-accelerated rendering of strokes and layers
 */
@Singleton
class OpenGLCanvasRenderer @Inject constructor() : GLSurfaceView.Renderer {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // View dimensions
    private var viewWidth = 0
    private var viewHeight = 0
    
    // Canvas dimensions (logical)
    private var canvasWidth = 0
    private var canvasHeight = 0
    private var canvasDpi = 72
    
    // Viewport transformation
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var rotation = 0f
    
    // Background color (RGBA)
    private var backgroundColor = floatArrayOf(1f, 1f, 1f, 1f)
    
    // Shader program IDs
    private var programId = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var matrixHandle = 0
    private var sizeHandle = 0
    private var pressureHandle = 0
    
    // Stroke data buffers
    private val strokes = mutableListOf<Stroke>()
    private val strokeBuffers = mutableMapOf<Long, StrokeGLBuffer>()
    
    // Invalidation event flow
    private val invalidationFlow = MutableSharedFlow<CanvasInvalidationEvent>(replay = 0)
    
    // Initialized flag
    private var isInitialized = false
    
    // Dirty flag for rendering
    private var needsRedraw = true

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // Set clear color to white
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        
        // Enable blending for smooth brush strokes
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
        // Enable point size for brush dabs
        GLES20.glEnable(GLES20.GL_POINT_SMOOTH)
        GLES20.glHint(GLES20.GL_POINT_SMOOTH_HINT, GLES20.GL_NICEST)
        
        // Initialize shaders
        initializeShaders()
        
        isInitialized = true
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        
        // Set OpenGL viewport
        GLES20.glViewport(0, 0, width, height)
        
        // If canvas not initialized, set it to view size
        if (canvasWidth == 0) {
            canvasWidth = width
            canvasHeight = height
        }
        
        needsRedraw = true
    }

    override fun onDrawFrame(unused: GL10?) {
        // Clear the screen
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        
        if (!needsRedraw && strokes.isEmpty()) return
        
        // Use our shader program
        GLES20.glUseProgram(programId)
        
        // Apply transformations
        applyTransformations()
        
        // Render all strokes
        renderAllStrokes()
        
        needsRedraw = false
    }

    /**
     * Initialize OpenGL shaders
     */
    private fun initializeShaders() {
        // Vertex shader - supports position, color, size, and pressure
        val vertexShaderCode = """
            uniform mat4 u_Matrix;
            attribute vec2 a_Position;
            attribute vec4 a_Color;
            attribute float a_Size;
            attribute float a_Pressure;
            
            varying vec4 v_Color;
            varying float v_Size;
            varying float v_Pressure;
            
            void main() {
                gl_Position = u_Matrix * vec4(a_Position, 0.0, 1.0);
                gl_PointSize = a_Size * (1.0 + a_Pressure * 0.5);
                v_Color = a_Color;
                v_Size = a_Size;
                v_Pressure = a_Pressure;
            }
        """.trimIndent()
        
        // Fragment shader - smooth circle with alpha
        val fragmentShaderCode = """
            precision mediump float;
            varying vec4 v_Color;
            varying float v_Size;
            varying float v_Pressure;
            
            void main() {
                // Calculate distance from center of point
                vec2 coord = gl_PointCoord - vec2(0.5);
                float dist = length(coord);
                
                // Discard pixels outside the circle
                if (dist > 0.5) {
                    discard;
                }
                
                // Apply soft edge
                float alpha = 1.0 - smoothstep(0.3, 0.5, dist);
                alpha *= v_Color.a * (0.7 + 0.3 * v_Pressure);
                
                gl_FragColor = vec4(v_Color.rgb, alpha);
            }
        """.trimIndent()
        
        // Compile shaders
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        
        // Create program
        programId = GLES20.glCreateProgram()
        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)
        
        // Get attribute and uniform handles
        positionHandle = GLES20.glGetAttribLocation(programId, "a_Position")
        colorHandle = GLES20.glGetAttribLocation(programId, "a_Color")
        sizeHandle = GLES20.glGetAttribLocation(programId, "a_Size")
        pressureHandle = GLES20.glGetAttribLocation(programId, "a_Pressure")
        matrixHandle = GLES20.glGetUniformLocation(programId, "u_Matrix")
    }

    /**
     * Load and compile a shader
     */
    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            
            // Check compilation status
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Shader compilation failed: $error")
            }
        }
    }

    /**
     * Apply current transformation matrix
     */
    private fun applyTransformations() {
        // Create orthographic projection matrix
        val left = -offsetX / scale
        val right = (viewWidth - offsetX) / scale
        val bottom = (viewHeight - offsetY) / scale
        val top = -offsetY / scale
        
        val matrix = floatArrayOf(
            2f / (right - left), 0f, 0f, 0f,
            0f, 2f / (top - bottom), 0f, 0f,
            0f, 0f, 1f, 0f,
            -(right + left) / (right - left), -(top + bottom) / (top - bottom), 0f, 1f
        )
        
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, matrix, 0)
    }

    /**
     * Render all strokes to the canvas
     */
    private fun renderAllStrokes() {
        strokes.forEach { stroke ->
            renderStroke(stroke)
        }
    }

    /**
     * Render a single stroke using point sprites
     */
    private fun renderStroke(stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        
        val points = stroke.points
        val brushParams = stroke.brushParams
        
        // Extract color from stroke
        val r = ((stroke.color shr 16) and 0xFF) / 255f
        val g = ((stroke.color shr 8) and 0xFF) / 255f
        val b = (stroke.color and 0xFF) / 255f
        val a = brushParams.opacity
        
        // Create buffers for this stroke
        val vertexData = FloatArray(points.size * 2)
        val colorData = FloatArray(points.size * 4)
        val sizeData = FloatArray(points.size)
        val pressureData = FloatArray(points.size)
        
        points.forEachIndexed { index, point ->
            vertexData[index * 2] = point.x
            vertexData[index * 2 + 1] = point.y
            
            // Apply pressure to color alpha
            val pressureAlpha = when {
                brushParams.pressureToOpacity > 0 -> {
                    0.5f + (point.pressure * brushParams.pressureToOpacity)
                }
                else -> 1f
            }
            
            colorData[index * 4] = r
            colorData[index * 4 + 1] = g
            colorData[index * 4 + 2] = b
            colorData[index * 4 + 3] = a * pressureAlpha
            
            // Apply pressure to size
            val sizeMultiplier = when {
                brushParams.pressureToSize > 0 -> {
                    0.5f + (point.pressure * brushParams.pressureToSize)
                }
                else -> 1f
            }
            sizeData[index] = brushParams.size * sizeMultiplier
            pressureData[index] = point.pressure
        }
        
        // Create and populate buffers
        val vertexBuffer = ByteBuffer.allocateDirect(vertexData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer.put(vertexData).position(0)
        
        val colorBuffer = ByteBuffer.allocateDirect(colorData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        colorBuffer.put(colorData).position(0)
        
        val sizeBuffer = ByteBuffer.allocateDirect(sizeData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        sizeBuffer.put(sizeData).position(0)
        
        val pressureBuffer = ByteBuffer.allocateDirect(pressureData.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        pressureBuffer.put(pressureData).position(0)
        
        // Enable vertex attributes
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        
        GLES20.glEnableVertexAttribArray(colorHandle)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 0, colorBuffer)
        
        GLES20.glEnableVertexAttribArray(sizeHandle)
        GLES20.glVertexAttribPointer(sizeHandle, 1, GLES20.GL_FLOAT, false, 0, sizeBuffer)
        
        GLES20.glEnableVertexAttribArray(pressureHandle)
        GLES20.glVertexAttribPointer(pressureHandle, 1, GLES20.GL_FLOAT, false, 0, pressureBuffer)
        
        // Draw points
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, points.size)
        
        // Disable vertex attributes
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
        GLES20.glDisableVertexAttribArray(sizeHandle)
        GLES20.glDisableVertexAttribArray(pressureHandle)
    }

    /**
     * Set canvas dimensions
     */
    fun setCanvasSize(width: Int, height: Int, dpi: Int) {
        canvasWidth = width
        canvasHeight = height
        canvasDpi = dpi
        
        // Trigger full redraw
        coroutineScope.launch {
            invalidationFlow.emit(CanvasInvalidationEvent.Full)
        }
    }

    /**
     * Add a stroke to be rendered
     */
    fun addStroke(stroke: Stroke) {
        strokes.add(stroke)
        needsRedraw = true
    }

    /**
     * Remove a stroke from rendering
     */
    fun removeStroke(strokeId: Long) {
        strokes.removeAll { it.id == strokeId }
        strokeBuffers.remove(strokeId)
        needsRedraw = true
    }

    /**
     * Clear all strokes
     */
    fun clearAllStrokes() {
        strokes.clear()
        strokeBuffers.clear()
        needsRedraw = true
    }

    /**
     * Update viewport transformation
     */
    fun setTransformation(scale: Float, offsetX: Float, offsetY: Float, rotation: Float) {
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
        this.rotation = rotation
        needsRedraw = true
    }

    /**
     * Set background color
     */
    fun setBackgroundColor(r: Float, g: Float, b: Float, a: Float) {
        backgroundColor = floatArrayOf(r, g, b, a)
        GLES20.glClearColor(r, g, b, a)
    }

    /**
     * Get current canvas size
     */
    fun getCanvasSize(): CanvasSize {
        return CanvasSize(canvasWidth, canvasHeight, canvasDpi)
    }

    /**
     * Observe canvas invalidation events
     */
    override fun observeCanvasInvalidation(): Flow<CanvasInvalidationEvent> {
        return invalidationFlow
    }

    /**
     * Dispose resources
     */
    fun dispose() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        strokeBuffers.clear()
        strokes.clear()
        coroutineScope.cancel()
    }
}

/**
 * OpenGL buffer data for a stroke
 */
private data class StrokeGLBuffer(
    val vertexBuffer: java.nio.FloatBuffer,
    val colorBuffer: java.nio.FloatBuffer,
    val sizeBuffer: java.nio.FloatBuffer,
    val pressureBuffer: java.nio.FloatBuffer,
    val pointCount: Int
)
