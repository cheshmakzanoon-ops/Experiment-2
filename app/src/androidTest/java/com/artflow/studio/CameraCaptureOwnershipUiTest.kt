package com.artflow.studio

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.presentation.ui.screens.canvas.rememberCameraCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Compose launchers and FileProvider destinations, with only the external camera replaced. */
@RunWith(AndroidJUnit4::class)
class CameraCaptureOwnershipUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val captures = mutableListOf<Capture>()
    private val images = mutableListOf<Pair<String, PixelBuffer>>()
    private val errors = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private var showCanvas by mutableStateOf(true)
    private var failLaunch = false
    private val registry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                captures += Capture(requestCode, input as Uri)
                if (failLaunch) throw ActivityNotFoundException("No camera installed")
            }
        }
    private val owner =
        object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }

    @Test
    fun cancellingCanvasCaptureCannotRemoveThePaletteCamerasSource() {
        content()
        compose.onNodeWithText("Canvas camera").performClick()
        compose.onNodeWithText("Palette camera").performClick()
        compose.runOnIdle {
            assertEquals(2, captures.size)
            assertNotEquals(captures[0].uri, captures[1].uri)
            write(captures[0], RED)
            write(captures[1], BLUE)
            registry.dispatchResult(captures[0].requestCode, false)
            assertFalse(file(captures[0]).exists())
            assertTrue(file(captures[1]).exists())
            registry.dispatchResult(captures[1].requestCode, true)
        }
        compose.waitUntil(5_000) { images.size == 1 && !file(captures[1]).exists() }
        compose.runOnIdle {
            assertEquals("Palette camera", images.single().first)
            assertEquals(
                BLUE,
                images
                    .single()
                    .second.pixels
                    .first(),
            )
            assertEquals(listOf("Canvas camera"), cancelled)
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun aPendingCameraRejectsDuplicateLaunchesAndARetryGetsItsOwnFile() {
        content()
        compose.onNodeWithText("Canvas camera").performClick()
        compose.onNodeWithText("Canvas camera").performClick()
        compose.runOnIdle {
            assertEquals(1, captures.size)
            registry.dispatchResult(captures.single().requestCode, false)
            assertFalse(file(captures.single()).exists())
        }
        compose.onNodeWithText("Canvas camera").performClick()
        compose.runOnIdle {
            assertEquals(2, captures.size)
            assertNotEquals(captures[0].uri, captures[1].uri)
            registry.dispatchResult(captures[1].requestCode, false)
            assertFalse(file(captures[1]).exists())
            assertEquals(2, cancelled.size)
            assertTrue(errors.isEmpty())
        }
    }

    @Test
    fun leavingACameraActionReleasesOnlyItsUnclaimedCapture() {
        content()
        compose.onNodeWithText("Canvas camera").performClick()
        compose.onNodeWithText("Palette camera").performClick()
        compose.runOnIdle { showCanvas = false }
        compose.waitUntil(5_000) { !file(captures[0]).exists() }
        compose.runOnIdle {
            assertTrue(file(captures[1]).exists())
            registry.dispatchResult(captures[1].requestCode, false)
            assertFalse(file(captures[1]).exists())
            assertTrue(images.isEmpty())
        }
    }

    @Test
    fun aFailedLaunchCleansItsFileAndDoesNotBlockTheNextAttempt() {
        content()
        compose.runOnIdle { failLaunch = true }
        compose.onNodeWithText("Canvas camera").performClick()
        compose.runOnIdle {
            assertFalse(file(captures.single()).exists())
            assertEquals(1, errors.size)
            failLaunch = false
        }
        compose.onNodeWithText("Canvas camera").performClick()
        compose.runOnIdle {
            assertEquals(2, captures.size)
            assertTrue(file(captures[1]).exists())
            registry.dispatchResult(captures[1].requestCode, false)
            assertFalse(file(captures[1]).exists())
        }
    }

    private fun content() {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {
                    Column {
                        if (showCanvas) CaptureButton("Canvas camera")
                        CaptureButton("Palette camera")
                    }
                }
            }
        }
    }

    @Composable
    private fun CaptureButton(name: String) {
        val capture =
            rememberCameraCapture(
                onImage = { images += name to it },
                onError = errors::add,
                onCancelled = { cancelled += name },
            )
        Button(onClick = capture) { Text(name) }
    }

    private fun write(
        capture: Capture,
        color: Int,
    ) {
        requireNotNull(context.contentResolver.openOutputStream(capture.uri)).use {
            it.write(PngCodec.encode(PixelBuffer.filled(2, 2, color)))
        }
    }

    private fun file(capture: Capture): File = File(File(context.filesDir, "camera"), requireNotNull(capture.uri.lastPathSegment))

    private data class Capture(
        val requestCode: Int,
        val uri: Uri,
    )

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
    }
}
