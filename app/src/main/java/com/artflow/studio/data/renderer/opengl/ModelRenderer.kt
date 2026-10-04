package com.artflow.studio.data.renderer.opengl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.artflow.studio.core.three.Mesh
import com.artflow.studio.core.three.ModelLighting
import com.artflow.studio.core.three.OrbitCamera
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws a painted model: the artwork is its texture, lit from the camera's side so its form reads.
 * The mesh and texture arrive from the UI thread and are uploaded on the GL thread.
 */
class ModelRenderer : GLSurfaceView.Renderer {
    @Volatile
    var camera: OrbitCamera = OrbitCamera()

    @Volatile
    var lighting: ModelLighting = ModelLighting()

    private val pendingMesh = AtomicReference<Mesh?>(null)
    private val pendingTexture = AtomicReference<Bitmap?>(null)
    private var program = 0
    private var buffers = IntArray(3)
    private var corners = 0
    private var texture = 0
    private var width = 1
    private var height = 1
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    @Volatile
    private var lastMesh: Mesh? = null

    fun setMesh(mesh: Mesh) {
        lastMesh = mesh
        pendingMesh.set(mesh)
    }

    /** A copy of the artwork to paint the model with; the renderer recycles it after upload. */
    fun setTexture(bitmap: Bitmap) {
        pendingTexture.getAndSet(bitmap)?.recycle()
    }

    override fun onSurfaceCreated(
        unused: GL10?,
        config: EGLConfig?,
    ) {
        program = program(VERTEX, FRAGMENT)
        GLES20.glGenBuffers(3, buffers, 0)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // A plain white texture until the artwork arrives.
        val white = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, white, 0)
        white.recycle()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // A recreated context lost every upload, so the mesh is sent again.
        corners = 0
        lastMesh?.let { pendingMesh.compareAndSet(null, it) }
    }

    override fun onSurfaceChanged(
        unused: GL10?,
        width: Int,
        height: Int,
    ) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        pendingMesh.getAndSet(null)?.let(::upload)
        pendingTexture.getAndSet(null)?.let { bitmap ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
        }
        GLES20.glClearColor(BACKGROUND, BACKGROUND, BACKGROUND, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (corners == 0) return
        val cam = camera
        Matrix.perspectiveM(projection, 0, cam.fieldOfViewDegrees, width.toFloat() / height, NEAR, FAR)
        val eye = cam.eye
        Matrix.setLookAtM(view, 0, eye.x, eye.y, eye.z, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        val light = lighting
        val direction = light.direction()
        val colour = light.colour()
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uLightDir"), direction.x, direction.y, direction.z)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uLightColour"), colour.x, colour.y, colour.z)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uEye"), eye.x, eye.y, eye.z)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uAmbient"), light.ambient)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMetallic"), light.metallic)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uPower"), light.highlightPower())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uSpecular"), light.highlightStrength())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uExposure"), light.exposure)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        attribute("aPosition", buffers[0], 3)
        attribute("aNormal", buffers[1], 3)
        attribute("aUv", buffers[2], 2)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, corners)
    }

    private fun upload(mesh: Mesh) {
        listOf(mesh.positions, mesh.normals, mesh.uvs).forEachIndexed { index, values ->
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[index])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, values.size * 4, floats(values), GLES20.GL_STATIC_DRAW)
        }
        corners = mesh.triangleCount * 3
    }

    private fun attribute(
        name: String,
        buffer: Int,
        size: Int,
    ) {
        val location = GLES20.glGetAttribLocation(program, name)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, size, GLES20.GL_FLOAT, false, 0, 0)
    }

    private fun floats(values: FloatArray): FloatBuffer =
        ByteBuffer
            .allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun program(
        vertex: String,
        fragment: String,
    ): Int {
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, shader(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(id, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(id)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Model shader link failed: ${GLES20.glGetProgramInfoLog(id)}" }
        return id
    }

    private fun shader(
        type: Int,
        source: String,
    ): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "Model shader compile failed: ${GLES20.glGetShaderInfoLog(id)}" }
        return id
    }

    private companion object {
        const val NEAR = 0.05f
        const val FAR = 50f
        const val BACKGROUND = 0.16f

        const val VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            attribute vec2 aUv;
            varying vec3 vNormal;
            varying vec3 vPosition;
            varying vec2 vUv;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
                vNormal = aNormal;
                vPosition = aPosition;
                // OBJ texture coordinates start at the bottom; the artwork's first row is its top.
                vUv = vec2(aUv.x, 1.0 - aUv.y);
            }
        """

        const val FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec3 uLightDir;
            uniform vec3 uLightColour;
            uniform vec3 uEye;
            uniform float uAmbient;
            uniform float uMetallic;
            uniform float uPower;
            uniform float uSpecular;
            uniform float uExposure;
            varying vec3 vNormal;
            varying vec3 vPosition;
            varying vec2 vUv;
            void main() {
                vec4 colour = texture2D(uTexture, vUv);
                vec3 toEye = normalize(uEye - vPosition);
                vec3 normal = normalize(vNormal);
                // Light both faces of open meshes: turn the normal toward the viewer.
                if (dot(normal, toEye) < 0.0) normal = -normal;
                float diffuse = max(dot(normal, uLightDir), 0.0);
                float highlight = pow(max(dot(normal, normalize(uLightDir + toEye)), 0.0), uPower) * uSpecular * diffuse;
                // Metals have no diffuse body colour; their highlights take the surface colour instead.
                vec3 highlightColour = mix(uLightColour, colour.rgb * uLightColour, uMetallic);
                vec3 body = colour.rgb * (uAmbient + diffuse * uLightColour * (1.0 - 0.8 * uMetallic));
                vec3 lit = body + highlight * highlightColour * 4.0;
                gl_FragColor = vec4(clamp(lit * uExposure, 0.0, 1.0), 1.0);
            }
        """
    }
}
