package com.artflow.studio

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.presentation.ui.components.editor.ReferenceCompanion
import com.artflow.studio.presentation.ui.components.editor.ReferenceImageState
import com.artflow.studio.presentation.ui.components.editor.ReferenceWindow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReferenceCompanionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pickingZoomFitAndCallbackReplacementUseTheVisibleImageWithoutPaintingBehindIt() {
        val bitmap = fixtureBitmap(200, 100)
        val oldSamples = mutableListOf<Int>()
        val newSamples = mutableListOf<Int>()
        var useNewCallback by mutableStateOf(false)
        var backgroundTaps = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { backgroundTaps++ } }) {}
                    ReferenceWindow(
                        ReferenceImageState.Ready(bitmap),
                        {},
                        {},
                        if (useNewCallback) ({ newSamples.add(it) }) else ({ oldSamples.add(it) }),
                    )
                }
            }
        }
        val image = compose.onNodeWithTag("reference-image")
        compose.onNodeWithContentDescription("Zoom reference out").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Pick reference colour").performClick().assertIsOn()
        image.performTouchInput { click(Offset(width * 0.25f, height * 0.5f)) }
        compose.runOnIdle { assertEquals(listOf(Color.RED), oldSamples) }
        compose.runOnIdle { useNewCallback = true }
        image.performTouchInput { click(Offset(width * 0.75f, height * 0.5f)) }
        compose.runOnIdle { assertEquals(listOf(Color.BLUE), newSamples) }
        compose.onNodeWithContentDescription("Zoom reference in").performClick()
        compose.onNodeWithContentDescription("Zoom reference out").assertIsEnabled()
        image.performTouchInput { swipe(center, Offset(width * 0.9f, height * 0.5f)) }
        compose.onNodeWithContentDescription("Fit reference").performClick()
        compose.onNodeWithContentDescription("Zoom reference out").assertIsNotEnabled()
        image.performTouchInput { click(Offset(width * 0.25f, height * 0.5f)) }
        compose.runOnIdle {
            assertEquals(Color.RED, newSamples.last())
            assertEquals(1, oldSamples.size)
            assertEquals(0, backgroundTaps)
        }
        TestEvidence.screenshot("reference-companion.png")
    }

    @Test
    fun letterboxTapsDoNotSampleAndCentreSamplingIsAccessible() {
        val samples = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                ReferenceWindow(ReferenceImageState.Ready(fixtureBitmap(1000, 10).apply { eraseColor(Color.BLUE) }), {}, {}, { samples.add(it) })
            }
        }
        val image = compose.onNodeWithTag("reference-image")
        compose.onNodeWithContentDescription("Pick reference colour").performClick()
        image.performTouchInput { click(Offset(width * 0.5f, 1f)) }
        compose.runOnIdle { assertTrue(samples.isEmpty()) }
        val actions = image.fetchSemanticsNode().config[SemanticsProperties.CustomActions]
        compose.runOnIdle { assertTrue(actions.single { it.label == "Sample centre colour" }.action()) }
        compose.runOnIdle { assertEquals(listOf(Color.BLUE), samples) }
    }

    @Test
    fun draggingAndChangingAvailableSpaceKeepTheWindowAndCloseControlOnScreen() {
        var compact by mutableStateOf(false)
        var closed = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(if (compact) 220.dp else 320.dp, if (compact) 200.dp else 480.dp).testTag("available-area")) {
                    ReferenceWindow(ReferenceImageState.Empty, {}, { closed = true }, {})
                }
            }
        }
        compose.onNodeWithTag("reference-handle").performTouchInput { swipe(center, center + Offset(-500f, 500f)) }
        assertInsideAvailableArea()
        compose.runOnIdle { compact = true }
        assertInsideAvailableArea()
        compose.onNodeWithContentDescription("Close reference").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test
    fun contentProviderImagesAreDownsampledAndCanBeSampled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "exports/reference-${System.nanoTime()}.png")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val original = fixtureBitmap(2048, 1024)
        file.outputStream().use { check(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        original.recycle()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val samples = mutableListOf<Int>()
        try {
            compose.setContent {
                MaterialTheme { ReferenceCompanion(7L, uri.toString(), {}, {}, { samples.add(it) }) }
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("reference-image").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("reference-image")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1024 × 512 pixel preview"))
            compose.onNodeWithContentDescription("Pick reference colour").performClick()
            compose.onNodeWithTag("reference-image").performTouchInput { click(Offset(width * 0.25f, height * 0.5f)) }
            compose.runOnIdle { assertEquals(listOf(Color.RED), samples) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun unsupportedAndUnreadableSourcesOfferRecoveryWithoutAStaleImage() {
        var uri by mutableStateOf("https://example.invalid/reference.png")
        var imports = 0
        compose.setContent {
            MaterialTheme { ReferenceCompanion(7L, uri, { imports++ }, {}, {}) }
        }
        val error = "This image could not be opened. Choose a readable image again."
        compose.onNodeWithText(error).assertExists()
        compose.onNodeWithTag("reference-image").assertDoesNotExist()
        compose.onNodeWithText("Choose image").performClick()
        compose.runOnIdle {
            assertEquals(1, imports)
            uri = "content://com.artflow.studio.fileprovider/exports/no-such-reference.png"
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reference-image").assertDoesNotExist()
    }

    private fun assertInsideAvailableArea() {
        val parent = compose.onNodeWithTag("available-area").getUnclippedBoundsInRoot()
        val window = compose.onNodeWithTag("reference-window").getUnclippedBoundsInRoot()
        assertTrue(window.left >= parent.left && window.top >= parent.top)
        assertTrue(window.right <= parent.right && window.bottom <= parent.bottom)
    }

    private fun fixtureBitmap(
        width: Int,
        height: Int,
    ): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(width * height) { if (it % width < width / 2) Color.RED else Color.BLUE }
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
