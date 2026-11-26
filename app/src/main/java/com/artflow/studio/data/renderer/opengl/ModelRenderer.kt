package com.artflow.studio.data.renderer.opengl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.artflow.studio.core.three.Mesh
import com.artflow.studio.core.three.ModelLighting
import com.artflow.studio.core.three.OrbitCamera
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** A complete mesh/color/material frame is owned across uploads and EGL context restoration. */
class ModelRenderer internal constructor(
    private val previewTextureLimit: () -> Int,
) : GLSurfaceView.Renderer {
    constructor() : this({ Int.MAX_VALUE })

    @Volatile
    var camera: OrbitCamera = OrbitCamera()

    @Volatile
    var lighting: ModelLighting = ModelLighting()

    @Volatile
    var showMesh: Boolean = false

    private class Frame(
        val mesh: Mesh,
        val bitmaps: List<Bitmap>,
        val generation: Long,
    ) {
        fun recycle() = bitmaps.forEach(Bitmap::recycle)
    }

    private val pendingFrame = AtomicReference<Frame?>(null)
    private var retainedFrame: Frame? = null
    private var textureSet: ModelTextureSet? = null
    private var texturesNeedUpload = true
    private var maxTextureSize = 0
    private var initialized = false
    private var reportedFailureGeneration = -1L
    private val previewState = CanvasPreviewState()

    val previewStatus: CanvasPreviewStatus get() = previewState.status
    val previewAvailability: CanvasPreviewAvailability get() = previewState.availability
    internal val textureSizes: List<CanvasTextureSize> get() = textureSet?.sizes.orEmpty()

    var onPreviewAvailabilityChanged: ((CanvasPreviewAvailability) -> Unit)?
        get() = previewState.onChanged
        set(value) {
            previewState.onChanged = value
        }

    @Volatile
    var onStatusMessage: ((String) -> Unit)? = null

    private var program = 0
    private var wireProgram = 0
    private val buffers = IntArray(3)
    private var corners = 0
    private val wireBuffer = IntArray(1)
    private var wireVertices = 0
    private var wireSource: Mesh? = null
    private var uploadedMesh: Mesh? = null
    private var width = 1
    private var height = 1
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    /** Owns all three copied channels together; an empty set explicitly invalidates a missing preview. */
    fun setFrame(
        mesh: Mesh,
        bitmaps: List<Bitmap>,
    ) {
        require(bitmaps.isEmpty() || bitmaps.size == 3)
        val generation = previewState.advanceContent()
        if (bitmaps.isEmpty()) previewState.awaitSurface()
        pendingFrame.getAndSet(Frame(mesh, bitmaps, generation))?.recycle()
    }

    fun invalidateFrame() {
        previewState.invalidateContent()
        reportFailure(previewState.generation())
    }

    fun awaitPrimaryPreview(invalidateContent: Boolean = false) {
        if (invalidateContent) previewState.advanceContent()
        previewState.awaitSurface()
    }

    override fun onSurfaceCreated(
        unused: GL10?,
        config: EGLConfig?,
    ) {
        initialized = false
        previewState.awaitSurface()
        // Numeric names belong to the old context. Retain CPU frames, not old GL names.
        textureSet = null
        texturesNeedUpload = true
        uploadedMesh = null
        wireSource = null
        corners = 0
        program = 0
        wireProgram = 0
        buffers.fill(0)
        wireBuffer.fill(0)
        try {
            drainModelGlErrors()
            val limit = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
            maxTextureSize = limit[0]
            program = createProgram(VERTEX, FRAGMENT)
            wireProgram = createProgram(WIRE_VERTEX, WIRE_FRAGMENT)
            GLES20.glGenBuffers(3, buffers, 0)
            GLES20.glGenBuffers(1, wireBuffer, 0)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            initialized = buffers.all { it != 0 } && wireBuffer[0] != 0 && drainModelGlErrors() == GLES20.GL_NO_ERROR
            if (!initialized) reportFailure(previewState.generation())
        } catch (failure: IllegalStateException) {
            Timber.w(failure, "Model preview initialization failed")
            reportFailure(previewState.generation())
        } catch (failure: OutOfMemoryError) {
            Timber.w(failure, "Model preview initialization did not fit")
            reportFailure(previewState.generation())
        }
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
        GLES20.glClearColor(BACKGROUND, BACKGROUND, BACKGROUND, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val incoming = pendingFrame.getAndSet(null)
        if (incoming != null) {
            retainedFrame?.recycle()
            retainedFrame = incoming
            texturesNeedUpload = true
        }
        val frame = retainedFrame ?: return
        if (frame.bitmaps.isEmpty()) return
        if (!initialized) {
            reportFailure(frame.generation)
            return
        }
        try {
            drainModelGlErrors()
            if (!prepareFrame(frame)) {
                reportFailure(frame.generation)
                return
            }
            // A complete older image can remain visible while a newer refresh is queued.
            // Its presentation cannot restore availability for a newer missing/failed frame.
            drawModel(requireNotNull(textureSet))
            if (drainModelGlErrors() == GLES20.GL_NO_ERROR) {
                previewState.presented(frame.generation, true)
                reportedFailureGeneration = -1L
            } else {
                uploadedMesh = null
                texturesNeedUpload = true
                reportFailure(frame.generation)
            }
        } catch (failure: IllegalArgumentException) {
            Timber.w(failure, "Model preview frame rejected")
            reportFailure(frame.generation)
        } catch (failure: OutOfMemoryError) {
            Timber.w(failure, "Model preview frame did not fit")
            reportFailure(frame.generation)
        }
    }

    private fun prepareFrame(frame: Frame): Boolean {
        if (uploadedMesh !== frame.mesh && !upload(frame.mesh)) return false
        if (texturesNeedUpload || textureSet == null) {
            val uploaded = ModelTextureSet.upload(frame.bitmaps) { minOf(maxTextureSize, previewTextureLimit()) } ?: return false
            val previous = textureSet
            textureSet = uploaded
            texturesNeedUpload = false
            previous?.delete()
        }
        return corners > 0
    }

    private fun reportFailure(generation: Long) {
        // GL draws are serial: a failed draw cleared the surface, even when Main queued a new
        // generation during its upload. That queued frame can restore input only after drawing.
        previewState.presentationFailed()
        if (reportedFailureGeneration != generation) {
            reportedFailureGeneration = generation
            onStatusMessage?.invoke("The 3D preview could not be displayed. Painting is paused. Close and reopen the 3D view to retry.")
        }
    }

    private fun drawModel(textures: ModelTextureSet) {
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
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRoughness"), light.roughness)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uExposure"), light.exposure)
        val second = light.secondDirection()
        GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uLight2Dir"), second.x, second.y, second.z)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLight2"), light.secondIntensity.coerceIn(0f, ModelLighting.MAX_INTENSITY))
        textures.handles.forEachIndexed { index, name ->
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, name)
            val uniform =
                when (index) {
                    0 -> "uTexture"
                    1 -> "uMetallicMap"
                    else -> "uRoughnessMap"
                }
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, uniform), index)
        }
        attribute("aPosition", buffers[0], 3)
        attribute("aNormal", buffers[1], 3)
        attribute("aUv", buffers[2], 2)
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glPolygonOffset(1f, 1f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, corners)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        if (showMesh) drawWire()
    }

    private fun drawWire() {
        val mesh = uploadedMesh ?: return
        if (wireSource !== mesh) {
            val edges = edgeVertices(mesh.positions, corners)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, wireBuffer[0])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, edges.size * 4, floats(edges), GLES20.GL_STATIC_DRAW)
            wireVertices = edges.size / 3
            wireSource = mesh
        }
        GLES20.glUseProgram(wireProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(wireProgram, "uMvp"), 1, false, mvp, 0)
        attribute("aPosition", wireBuffer[0], 3, wireProgram)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, wireVertices)
    }

    private fun upload(mesh: Mesh): Boolean {
        // Each glBufferData changes existing storage. A partial failure must not leave the old
        // identity eligible for the fast path when the artist returns to that same mesh object.
        uploadedMesh = null
        corners = 0
        wireSource = null
        listOf(mesh.positions, mesh.normals, mesh.uvs).forEachIndexed { index, values ->
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[index])
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, values.size * 4, floats(values), GLES20.GL_STATIC_DRAW)
        }
        if (drainModelGlErrors() != GLES20.GL_NO_ERROR) return false
        corners = mesh.triangleCount * 3
        uploadedMesh = mesh
        return true
    }

    /** Call after GLSurfaceView has paused/stopped its renderer; no GL operation runs on the UI thread. */
    fun dispose() {
        pendingFrame.getAndSet(null)?.recycle()
        retainedFrame?.recycle()
        retainedFrame = null
        textureSet = null
        uploadedMesh = null
        wireSource = null
        initialized = false
        previewState.invalidateContent()
        previewState.awaitSurface()
    }

    private fun attribute(
        name: String,
        buffer: Int,
        size: Int,
        owner: Int = program,
    ) {
        val location = GLES20.glGetAttribLocation(owner, name)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer)
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, size, GLES20.GL_FLOAT, false, 0, 0)
    }

    private fun floats(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }

    private fun createProgram(
        vertex: String,
        fragment: String,
    ): Int {
        val id = GLES20.glCreateProgram()
        val shaders = mutableListOf<Int>()
        var complete = false
        try {
            shaders += shader(GLES20.GL_VERTEX_SHADER, vertex)
            shaders += shader(GLES20.GL_FRAGMENT_SHADER, fragment)
            shaders.forEach { GLES20.glAttachShader(id, it) }
            GLES20.glLinkProgram(id)
            val status = IntArray(1)
            GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "Model shader link failed: ${GLES20.glGetProgramInfoLog(id)}" }
            complete = true
            return id
        } finally {
            shaders.forEach(GLES20::glDeleteShader)
            if (!complete) GLES20.glDeleteProgram(id)
        }
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
        if (status[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            error("Model shader compile failed: $message")
        }
        return id
    }

    private companion object {
        const val WIRE_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            void main() {
                gl_Position = uMvp * vec4(aPosition, 1.0);
            }
        """

        const val WIRE_FRAGMENT = """
            precision mediump float;
            void main() {
                gl_FragColor = vec4(0.36, 0.72, 1.0, 1.0);
            }
        """

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
            const float PI = 3.14159265359;
            uniform sampler2D uTexture;
            uniform sampler2D uMetallicMap;
            uniform sampler2D uRoughnessMap;
            uniform vec3 uLightDir;
            uniform vec3 uLightColour;
            uniform vec3 uEye;
            uniform float uAmbient;
            uniform float uMetallic;
            uniform float uRoughness;
            uniform float uExposure;
            uniform vec3 uLight2Dir;
            uniform float uLight2;
            varying vec3 vNormal;
            varying vec3 vPosition;
            varying vec2 vUv;

            float distributionGGX(vec3 N, vec3 H, float roughness) {
                float a = roughness * roughness;
                float a2 = a * a;
                float NdotH = max(dot(N, H), 0.0);
                float NdotH2 = NdotH * NdotH;
                float denom = (NdotH2 * (a2 - 1.0) + 1.0);
                return a2 / (PI * denom * denom + 0.00001);
            }

            float geometrySchlickGGX(float NdotV, float roughness) {
                float r = (roughness + 1.0);
                float k = (r * r) / 8.0;
                return NdotV / (NdotV * (1.0 - k) + k + 0.00001);
            }

            float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
                float NdotV = max(dot(N, V), 0.0);
                float NdotL = max(dot(N, L), 0.0);
                float ggx2 = geometrySchlickGGX(NdotV, roughness);
                float ggx1 = geometrySchlickGGX(NdotL, roughness);
                return ggx1 * ggx2;
            }

            vec3 fresnelSchlick(float cosTheta, vec3 F0) {
                return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
            }

            void main() {
                vec4 albedo = texture2D(uTexture, vUv);
                vec4 metalMap = texture2D(uMetallicMap, vUv);
                vec4 roughMap = texture2D(uRoughnessMap, vUv);

                float metallic = clamp(mix(uMetallic, metalMap.r / max(metalMap.a, 0.00001), metalMap.a), 0.0, 1.0);
                float roughness = clamp(mix(uRoughness, roughMap.r / max(roughMap.a, 0.00001), roughMap.a), 0.04, 1.0);

                vec3 N = normalize(vNormal);
                vec3 V = normalize(uEye - vPosition);
                if (dot(N, V) < 0.0) N = -N;

                vec3 F0 = mix(vec3(0.04), albedo.rgb, metallic);

                // Key Light Cook-Torrance BRDF
                vec3 L = normalize(uLightDir);
                vec3 H = normalize(V + L);
                float NdotL = max(dot(N, L), 0.0);
                float NdotV = max(dot(N, V), 0.0);

                float NDF = distributionGGX(N, H, roughness);
                float G = geometrySmith(N, V, L, roughness);
                vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);

                vec3 kS = F;
                vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);

                vec3 numerator = NDF * G * F;
                float denominator = 4.0 * NdotV * NdotL + 0.0001;
                vec3 specular = numerator / denominator;

                vec3 directLight = (kD * albedo.rgb / PI + specular) * uLightColour * NdotL;

                // Secondary Light BRDF
                vec3 direct2 = vec3(0.0);
                if (uLight2 > 0.0) {
                    vec3 L2 = normalize(uLight2Dir);
                    vec3 H2 = normalize(V + L2);
                    float NdotL2 = max(dot(N, L2), 0.0);
                    float NDF2 = distributionGGX(N, H2, roughness);
                    float G2 = geometrySmith(N, V, L2, roughness);
                    vec3 F2 = fresnelSchlick(max(dot(H2, V), 0.0), F0);
                    vec3 kS2 = F2;
                    vec3 kD2 = (vec3(1.0) - kS2) * (1.0 - metallic);
                    vec3 spec2 = (NDF2 * G2 * F2) / (4.0 * NdotV * NdotL2 + 0.0001);
                    direct2 = (kD2 * albedo.rgb / PI + spec2) * uLight2 * NdotL2;
                }

                // Ambient & IBL reflection approximation
                vec3 R = reflect(-V, N);
                vec3 ambientDiff = uAmbient * albedo.rgb * (1.0 - metallic);
                vec3 ambientSpec = mix(F0, albedo.rgb, metallic) * pow(max(dot(R, L), 0.0), 32.0 * (1.0 - roughness) + 1.0) * 0.3;
                vec3 ambient = ambientDiff + ambientSpec;

                vec3 color = directLight + direct2 + ambient;
                gl_FragColor = vec4(clamp(color * uExposure, 0.0, 1.0), albedo.a);
            }
        """
    }
}

/** Three edges of two vertices of three floats. */
private const val EDGE_FLOATS = 18

/** Each of the first [corners] / 3 triangles' three edges as pairs of line vertices (x, y, z each). */
internal fun edgeVertices(
    positions: FloatArray,
    corners: Int,
): FloatArray {
    val triangles = corners / 3
    val out = FloatArray(triangles * EDGE_FLOATS)
    for (t in 0 until triangles) {
        for (edge in 0 until 3) {
            val from = (t * 3 + edge) * 3
            val to = (t * 3 + (edge + 1) % 3) * 3
            val at = t * EDGE_FLOATS + edge * 6
            positions.copyInto(out, at, from, from + 3)
            positions.copyInto(out, at + 3, to, to + 3)
        }
    }
    return out
}
