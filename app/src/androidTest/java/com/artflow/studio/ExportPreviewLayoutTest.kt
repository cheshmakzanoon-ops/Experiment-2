package com.artflow.studio

import android.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.presentation.ui.components.export.ExportActions
import com.artflow.studio.presentation.ui.components.export.ExportSheet
import com.artflow.studio.presentation.ui.viewmodel.ExportUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A preview can finish after the user has already scrolled to a successful export. */
@RunWith(AndroidJUnit4::class)
class ExportPreviewLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aLatePreviewAndReplacementKeepTheExportResultAndControlsInPlace() {
        val preview = mutableStateOf<ByteArray?>(null)
        showExport(preview)
        compose.onNodeWithContentDescription(PREVIEW).assertDoesNotExist()

        // Deliberately withhold the real PNG until after the only scroll action. This is the
        // tablet failure's ordering without relying on device speed or a timed sleep.
        compose.onNodeWithText(FILE_NAME).performScrollTo().assertIsDisplayed()
        val before = geometry()
        compose.runOnIdle { preview.value = png(64, 64, Color.RED) }
        awaitPreview()
        assertUnmoved(before)

        // A refresh may clear the preview before publishing a different-sized bitmap.
        compose.runOnIdle { preview.value = null }
        awaitUnavailable()
        assertUnmoved(before)
        compose.runOnIdle { preview.value = png(512, 32, Color.BLUE) }
        awaitPreview()
        assertUnmoved(before)

        compose.runOnIdle { preview.value = null }
        awaitUnavailable()
        assertUnmoved(before)
        compose.runOnIdle { preview.value = png(32, 512, Color.GREEN) }
        awaitPreview()
        assertUnmoved(before)
    }

    @Test
    fun anUnavailableOrUndecodablePreviewDoesNotMoveTheResultOrPretendToBeLoading() {
        val preview = mutableStateOf<ByteArray?>(png(64, 64, Color.RED))
        showExport(preview)
        awaitPreview()
        compose.onNodeWithText(FILE_NAME).performScrollTo().assertIsDisplayed()
        val before = geometry()

        compose.runOnIdle { preview.value = byteArrayOf(1, 2, 3, 4) }
        awaitUnavailable()
        assertUnmoved(before)
        assertNoIndefiniteProgress()

        compose.runOnIdle { preview.value = null }
        awaitUnavailable()
        assertUnmoved(before)
        assertNoIndefiniteProgress()

        compose.runOnIdle { preview.value = png(128, 64, Color.BLUE) }
        awaitPreview()
        assertUnmoved(before)
    }

    private fun showExport(preview: MutableState<ByteArray?>) {
        compose.setContent {
            MaterialTheme {
                ExportSheet(
                    availableFormats = ExportFormat.entries,
                    frameCount = 2,
                    canvasWidth = 64,
                    canvasHeight = 64,
                    canvasDpi = 72,
                    previewBytes = preview.value,
                    exportState = ExportUiState.Done(ExportResult(ExportFormat.PNG, FILE_NAME, FILE_NAME, 519, 64, 64)),
                    actions = ExportActions({}, {}, {}, {}, exportTimelapse = {}, clearTimelapse = {}),
                    modifier = Modifier.width(400.dp).height(500.dp).testTag("export-sheet"),
                )
            }
        }
    }

    private fun awaitPreview() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(PREVIEW).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Preview unavailable").assertDoesNotExist()
    }

    private fun awaitUnavailable() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(PREVIEW).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Preview unavailable").assertExists()
    }

    private fun assertNoIndefiniteProgress() {
        compose
            .onNode(
                SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate),
            ).assertDoesNotExist()
    }

    private fun geometry(): Geometry {
        val scroll = compose.onNodeWithTag("export-sheet").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        return Geometry(
            result = compose.onNodeWithText(FILE_NAME).getUnclippedBoundsInRoot(),
            export = compose.onNodeWithText("Export PNG").getUnclippedBoundsInRoot(),
            dpi = compose.onNodeWithText("DPI").getUnclippedBoundsInRoot(),
            scrollOffset = scroll.value(),
            scrollMaximum = scroll.maxValue(),
        )
    }

    private fun assertUnmoved(before: Geometry) {
        compose.onNodeWithText(FILE_NAME).assertIsDisplayed()
        assertEquals("Preview changes must preserve control bounds and scroll geometry", before, geometry())
    }

    private fun png(
        width: Int,
        height: Int,
        color: Int,
    ): ByteArray = PngCodec.encode(PixelBuffer.filled(width, height, color))

    private data class Geometry(
        val result: DpRect,
        val export: DpRect,
        val dpi: DpRect,
        val scrollOffset: Float,
        val scrollMaximum: Float,
    )

    private companion object {
        const val PREVIEW = "Current artwork preview"
        const val FILE_NAME = "late-preview.png"
    }
}
