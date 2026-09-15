package com.artflow.studio.data.renderer.opengl

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.artflow.studio.domain.repository.canvas.CanvasInvalidationEvent
import com.artflow.studio.domain.repository.canvas.CanvasSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

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
    
    // Invalidation event flow
    private val invalidationFlow = MutableSharedFlow<CanvasInvalidationEvent>(replay = 0)
    
    // Initialized flag
    private var isInitialized = false

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // Set clear color to white
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        
        // Enable blending for smooth brush strokes
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        
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
    }

    override fun onDrawFrame(unused: GL10?) {
        // Clear the screen
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        
        // Use our shader program
        GLES20.glUseProgram(programId)
        
        // Apply transformations
        applyTransformations()
        
        // TODO: Render all layers and strokes here
        // This will be implemented in subsequent phases
        
        // Draw placeholder for now
        drawPlaceholder()
    }

    /**
     * Initialize OpenGL shaders
     */
    private fun initializeShaders() {
        // Vertex shader - simple pass-through
        val vertexShaderCode = """
            uniform mat4 u_Matrix;
            attribute vec4 a_Position;
            attribute vec4 a_Color;
            varying vec4 v_Color;
            
            void main() {
                gl_Position = u_Matrix * a_Position;
                v_Color = a_Color;
            }
        """.trimIndent()
        
        // Fragment shader - solid color with alpha
        val fragmentShaderCode = """
            precision mediump float;
            varying vec4 v_Color;
            
            void main() {
                gl_FragColor = v_Color;
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
        // Simple identity matrix for now
        // Will implement proper matrix math in next phase
        val matrix = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
        
        GLES20.glUniformMatrix4fv(matrixHandle, 1, false, matrix, 0)
    }

    /**
     * Draw a placeholder to verify rendering works
     */
    private fun drawPlaceholder() {
        // Simple test triangle
        val triangle = floatArrayOf(
            // X, Y, Z, R, G, B, A
            0f, 0.5f, 0f, 1f, 0f, 0f, 1f,  // Top vertex (red)
            -0.5f, -0.5f, 0f, 0f, 1f, 0f, 1f,  // Bottom left (green)
            0.5f, -0.5f, 0f, 0f, 0f, 1f, 1f   // Bottom right (blue)
        )
        
        val vertexBuffer = java.nio.ByteBuffer.allocateDirect(triangle.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
        vertexBuffer.put(triangle)
        vertexBuffer.position(0)
        
        // Set position data
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 7 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        
        // Set color data
        vertexBuffer.position(3)
        GLES20.glVertexAttribPointer(colorHandle, 4, GLES20.GL_FLOAT, false, 7 * 4, vertexBuffer)
        GLES20.glEnableVertexAttribArray(colorHandle)
        
        // Draw
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(colorHandle)
    }

    /**
     * Set canvas dimensions
     */
    fun setCanvasSize(width: Int, height: Int, dpi: Int) {
        canvasWidth = width
        canvasHeight = height
        canvasDpi = dpi
        
        // Trigger full redraw
        coroutineScope.launchWhenStarted {
            invalidationFlow.emit(CanvasInvalidationEvent.Full)
        }
    }

    /**
     * Update viewport transformation
     */
    fun setTransformation(scale: Float, offsetX: Float, offsetY: Float, rotation: Float) {
        this.scale = scale
        this.offsetX = offsetX
        this.offsetY = offsetY
        this.rotation = rotation
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
        coroutineScope.cancel()
    }
}
