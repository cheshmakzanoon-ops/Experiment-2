package com.artflow.studio.data

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.three.ObjParser
import com.artflow.studio.data.renderer.opengl.CanvasPreviewAvailability
import com.artflow.studio.data.renderer.opengl.CanvasTextureSize
import com.artflow.studio.data.renderer.opengl.ModelRenderer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Real GLES uploads and pixels; the smaller injected cap follows the device-queried cap. */
@RunWith(AndroidJUnit4::class)
class ModelPreviewCapabilityDeviceTest {
    @Test
    fun aFailedScalarUploadRetainsTheWholeFrameForRetryAndContextRestoration() {
        var channel = 0
        var rejectSecond = false
        val renderer = ModelRenderer { if (rejectSecond && ++channel == 2) 0 else 32 }
        val messages = mutableListOf<String>()
        renderer.onStatusMessage = messages::add
        val old = bitmaps(Color.BLUE, Color.WHITE, Color.BLACK)
        val current = bitmaps(Color.GREEN, Color.BLACK, Color.WHITE)
        var expected = ByteArray(0)
        try {
            withGl {
                initialize(renderer)
                renderer.setFrame(mesh(), old)
                renderer.onDrawFrame(null)
                assertEquals(CanvasPreviewAvailability.AVAILABLE, renderer.previewAvailability)
                assertEquals(List(3) { CanvasTextureSize(32, 16) }, renderer.textureSizes)
                val first = pixels()
                assertTrue("The real model must cover the viewport center", center(first) != background())

                rejectSecond = true
                channel = 0
                renderer.setFrame(mesh(), current)
                renderer.onDrawFrame(null)
                assertTrue("Consumed old CPU channels must be released", old.all(Bitmap::isRecycled))
                assertEquals(CanvasPreviewAvailability.UNAVAILABLE, renderer.previewAvailability)
                assertEquals("No partly updated model may be presented", background(), center(pixels()))
                assertEquals(1, messages.size)
                current.forEach { assertFalse(it.isRecycled) }
                assertEquals(Color.GREEN, current[0].getPixel(100, 40))
                assertEquals(Color.BLACK, current[1].getPixel(100, 40))
                assertEquals(Color.WHITE, current[2].getPixel(100, 40))

                rejectSecond = false
                renderer.onDrawFrame(null) // Retry the retained generation without another producer.
                assertEquals(CanvasPreviewAvailability.AVAILABLE, renderer.previewAvailability)
                expected = pixels()
                assertFalse(first.contentEquals(expected))
                assertTrue(center(expected) != background())
                assertEquals(1, messages.size)
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
            }
            withGl {
                initialize(renderer) // The earlier EGL context was destroyed, including every name.
                renderer.onDrawFrame(null)
                assertEquals(CanvasPreviewAvailability.AVAILABLE, renderer.previewAvailability)
                assertArrayEquals("All retained color/scalar channels must survive context loss", expected, pixels())
                assertEquals(List(3) { CanvasTextureSize(32, 16) }, renderer.textureSizes)
            }
            withGl {
                val fresh = ModelRenderer { 32 }
                try {
                    initialize(fresh)
                    fresh.setFrame(mesh(), bitmaps(Color.GREEN, Color.BLACK, Color.WHITE))
                    fresh.onDrawFrame(null)
                    assertArrayEquals("Recovery must equal an independently supplied complete new frame", expected, pixels())
                } finally {
                    fresh.dispose()
                }
            }
        } finally {
            renderer.dispose()
            (old + current).filterNot(Bitmap::isRecycled).forEach(Bitmap::recycle)
        }
        assertTrue(current.all(Bitmap::isRecycled))
    }

    @Test
    fun aNewerMissingFrameCannotBeReenabledByAnOlderUploadCompleting() {
        withGl {
            lateinit var renderer: ModelRenderer
            var replaceDuringUpload = true
            val mesh = mesh()
            renderer =
                ModelRenderer {
                    if (replaceDuringUpload) {
                        replaceDuringUpload = false
                        renderer.setFrame(mesh, emptyList())
                    }
                    32
                }
            try {
                initialize(renderer)
                renderer.setFrame(mesh, bitmaps(Color.RED, Color.WHITE, Color.BLACK))
                renderer.onDrawFrame(null)
                assertEquals(CanvasPreviewAvailability.WAITING_FOR_FRAME, renderer.previewAvailability)
                renderer.onDrawFrame(null)
                assertEquals(CanvasPreviewAvailability.WAITING_FOR_FRAME, renderer.previewAvailability)
                assertEquals(background(), center(pixels()))
                renderer.setFrame(mesh, bitmaps(Color.GREEN, Color.BLACK, Color.WHITE))
                renderer.onDrawFrame(null)
                assertEquals(CanvasPreviewAvailability.AVAILABLE, renderer.previewAvailability)
                assertTrue(center(pixels()) != background())
            } finally {
                renderer.dispose()
            }
        }
    }

    @Test
    fun replacingAnUnclaimedFrameReleasesOnlyTheSupersededCopies() {
        val renderer = ModelRenderer()
        val first = bitmaps(Color.RED, Color.WHITE, Color.BLACK)
        val second = bitmaps(Color.BLUE, Color.BLACK, Color.WHITE)
        try {
            renderer.setFrame(mesh(), first)
            renderer.setFrame(mesh(), second)
            assertTrue(first.all(Bitmap::isRecycled))
            assertTrue(second.none(Bitmap::isRecycled))
        } finally {
            renderer.dispose()
        }
        assertTrue(second.all(Bitmap::isRecycled))
    }

    @Test
    fun aPartialMeshBufferFailureCannotReuseThePreviousMeshIdentityWithoutReuploading() {
        withGl {
            val renderer = ModelRenderer { 32 }
            val previous = mesh()
            val changed =
                mesh().also { candidate ->
                    // The first buffer visibly differs before the deliberately invalid second binding.
                    candidate.positions.indices.forEach { candidate.positions[it] *= 0.25f }
                }
            try {
                initialize(renderer)
                renderer.setFrame(previous, bitmaps(Color.RED, Color.BLACK, Color.WHITE))
                renderer.onDrawFrame(null)
                val expected = pixels()
                assertEquals(CanvasPreviewAvailability.AVAILABLE, renderer.previewAvailability)

                // Failure injection only: binding zero makes the real second glBufferData fail,
                // after the first existing buffer already contains the candidate's smaller mesh.
                val field = ModelRenderer::class.java.getDeclaredField("buffers").apply { isAccessible = true }
                val bindings = field.get(renderer) as IntArray
                val normals = bindings[1]
                try {
                    bindings[1] = 0
                    renderer.setFrame(changed, bitmaps(Color.BLUE, Color.WHITE, Color.BLACK))
                    renderer.onDrawFrame(null)
                    assertEquals(CanvasPreviewAvailability.UNAVAILABLE, renderer.previewAvailability)
                    assertEquals(background(), center(pixels()))
                } finally {
                    bindings[1] = normals
                }
                renderer.setFrame(previous, bitmaps(Color.RED, Color.BLACK, Color.WHITE))
                renderer.onDrawFrame(null)
                assertEquals(CanvasPreviewAvailability.AVAILABLE, renderer.previewAvailability)
                assertArrayEquals("The old mesh identity must restore every buffer after partial mutation", expected, pixels())
            } finally {
                renderer.dispose()
            }
        }
    }

    private fun initialize(renderer: ModelRenderer) {
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, SIZE, SIZE)
    }

    private fun bitmaps(
        color: Int,
        metallic: Int,
        roughness: Int,
    ): List<Bitmap> =
        listOf(color, metallic, roughness).map { value ->
            Bitmap.createBitmap(256, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(value) }
        }

    private fun pixels(): ByteArray {
        val bytes = ByteBuffer.allocateDirect(SIZE * SIZE * 4)
        GLES20.glReadPixels(0, 0, SIZE, SIZE, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
        return ByteArray(bytes.capacity()).also(bytes::get)
    }

    private fun center(bytes: ByteArray): Int {
        val offset = (SIZE / 2 * SIZE + SIZE / 2) * 4
        return Color.argb(255, bytes[offset].toInt() and 255, bytes[offset + 1].toInt() and 255, bytes[offset + 2].toInt() and 255)
    }

    private fun background(): Int = Color.rgb(41, 41, 41)

    private fun mesh() = ObjParser.parse(OBJ)

    private fun withGl(block: () -> Unit) {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes =
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_DEPTH_SIZE,
                16,
                EGL14.EGL_NONE,
            )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0)
        val context =
            EGL14.eglCreateContext(
                display,
                configs[0],
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        val surface =
            EGL14.eglCreatePbufferSurface(
                display,
                configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 128, EGL14.EGL_HEIGHT, 128, EGL14.EGL_NONE),
                0,
            )
        try {
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            block()
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            EGL14.eglReleaseThread()
        }
    }

    private companion object {
        const val SIZE = 128
        const val OBJ = "v -1 -1 0\nv 1 -1 0\nv 1 1 0\nv -1 1 0\nvt 0 0\nvt 1 0\nvt 1 1\nvt 0 1\nf 1/1 2/2 3/3 4/4\n"
    }
}
