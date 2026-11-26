package com.artflow.studio

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.util.DisplayMetrics
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.presentation.ui.components.canvas.CanvasProjection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Read actual Presentation output while frames are replaced and the second screen is reopened. */
@RunWith(AndroidJUnit4::class)
class CanvasProjectionLifecycleDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun replacedFramesAndReopenedProjectionDrawTheLatestArtwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val enabled = mutableStateOf(false)
        val image = mutableStateOf<PixelBuffer?>(null)
        val projectedSide = AtomicReference<Int?>(null)
        ImageReader.newInstance(SIDE, SIDE, PixelFormat.RGBA_8888, 3).use { frames ->
            val display =
                requireNotNull(
                    manager.createVirtualDisplay(
                        "ArtFlow projection lifetime regression",
                        SIDE,
                        SIDE,
                        DisplayMetrics.DENSITY_DEFAULT,
                        frames.surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    ),
                )
            try {
                compose.setContent {
                    CanvasProjection(enabled.value, image.value) { projectedSide.set(it) }
                }
                compose.runOnIdle {
                    image.value = artwork(Color.RED)
                    enabled.value = true
                }
                compose.waitUntil(TIMEOUT_MS) { projectedSide.get() == SIDE }
                awaitColor(frames, Color.RED)

                listOf(Color.GREEN, Color.BLUE, Color.MAGENTA).forEach { color ->
                    compose.runOnIdle { image.value = artwork(color) }
                    awaitColor(frames, color)
                }

                compose.runOnIdle { enabled.value = false }
                compose.waitUntil(TIMEOUT_MS) { projectedSide.get() == null }
                compose.runOnIdle {
                    image.value = artwork(Color.YELLOW)
                    enabled.value = true
                }
                compose.waitUntil(TIMEOUT_MS) { projectedSide.get() == SIDE }
                awaitColor(frames, Color.YELLOW)
            } finally {
                try {
                    compose.runOnIdle { enabled.value = false }
                    compose.waitForIdle()
                } finally {
                    display.release()
                }
            }
        }
    }

    private fun artwork(color: Int) = PixelBuffer(SIDE, SIDE, IntArray(SIDE * SIDE) { color })

    private fun awaitColor(
        frames: ImageReader,
        expected: Int,
    ) {
        var actual: Int? = null
        compose.waitUntil(TIMEOUT_MS) {
            frames.acquireLatestImage()?.use { frame ->
                val plane = frame.planes[0]
                val offset = frame.height / 2 * plane.rowStride + frame.width / 2 * plane.pixelStride
                val pixels = plane.buffer
                actual =
                    Color.argb(
                        pixels.get(offset + 3).toInt() and 0xFF,
                        pixels.get(offset).toInt() and 0xFF,
                        pixels.get(offset + 1).toInt() and 0xFF,
                        pixels.get(offset + 2).toInt() and 0xFF,
                    )
            }
            actual == expected
        }
        assertEquals("The presentation must draw its current artwork", expected, actual)
    }

    private companion object {
        const val SIDE = 64
        const val TIMEOUT_MS = 15_000L
    }
}
