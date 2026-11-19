package com.artflow.studio.data

import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.renderer.opengl.OpenGLCanvasRenderer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Real GLES2 rendering with a deliberately smaller preview budget than the retained document. */
@RunWith(AndroidJUnit4::class)
class CanvasTextureCapabilityDeviceTest {
    @Test
    fun reducedPreviewKeepsDocumentPixelsAndUpdatesRegionsInDocumentCoordinates() {
        withGl {
            val renderer = OpenGLCanvasRenderer(64)
            val source = PixelBuffer.filled(512, 128, Color.RED)
            val failures = mutableListOf<String>()
            renderer.onStatusMessage = { failures += it }
            try {
                initialize(renderer)
                renderer.setComposite(source)
                renderer.onDrawFrame(null)
                assertEquals(Color.RED, pixel(96, 8))
                assertArrayEquals(IntArray(512 * 128) { Color.RED }, source.pixels)

                val region = IntBounds(256, 64, 511, 127)
                for (y in region.top..region.bottom) {
                    for (x in region.left..region.right) source.pixels[y * source.width + x] = Color.GREEN
                }
                val originalPixels = source.pixels.copyOf()
                renderer.setCompositeRegion(source, region)
                renderer.onDrawFrame(null)
                assertEquals("Document bottom-right maps to the same preview quadrant", Color.GREEN, pixel(96, 8))
                assertEquals(Color.RED, pixel(32, 8))
                assertEquals(Color.RED, pixel(96, 24))
                assertEquals(512, source.width)
                assertEquals(128, source.height)
                assertArrayEquals("Preview downscaling must never alter save/export pixels", originalPixels, source.pixels)
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
                assertTrue(failures.toString(), failures.isEmpty())
            } finally {
                renderer.dispose()
            }
        }
    }

    @Test
    fun unavailableTextureCapabilityReportsOneFailureAndLeavesArtworkIntact() {
        withGl {
            val renderer = OpenGLCanvasRenderer(0)
            val source = PixelBuffer.filled(512, 128, Color.BLUE)
            val failures = mutableListOf<String>()
            renderer.onStatusMessage = { failures += it }
            try {
                initialize(renderer)
                renderer.setComposite(source)
                repeat(3) { renderer.onDrawFrame(null) }
                assertEquals(1, failures.size)
                assertTrue(failures.single().contains("could not display"))
                assertArrayEquals(IntArray(512 * 128) { Color.BLUE }, source.pixels)
            } finally {
                renderer.dispose()
            }
        }
    }

    private fun initialize(renderer: OpenGLCanvasRenderer) {
        renderer.setCanvasSize(512, 128, 72)
        renderer.setTransformation(0.25f, 0f, 0f, 0f)
        renderer.setCheckerboardVisible(false)
        renderer.onSurfaceCreated(null, null)
        renderer.onSurfaceChanged(null, 128, 32)
    }

    private fun pixel(
        x: Int,
        y: Int,
    ): Int {
        val bytes = ByteBuffer.allocateDirect(4)
        GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
        return Color.argb(
            bytes.get(3).toInt() and 255,
            bytes.get(0).toInt() and 255,
            bytes.get(1).toInt() and 255,
            bytes.get(2).toInt() and 255,
        )
    }

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
                intArrayOf(EGL14.EGL_WIDTH, 128, EGL14.EGL_HEIGHT, 32, EGL14.EGL_NONE),
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
}
