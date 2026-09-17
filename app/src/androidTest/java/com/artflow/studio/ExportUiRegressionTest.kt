package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.PdfPageSize
import com.artflow.studio.presentation.ui.components.export.ExportActions
import com.artflow.studio.presentation.ui.components.export.ExportSheet
import com.artflow.studio.presentation.ui.screens.settings.PrivacyPolicyEntry
import com.artflow.studio.presentation.ui.viewmodel.ExportUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportUiRegressionTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showExport(onExport: (ExportOptions) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                ExportSheet(
                    availableFormats = ExportFormat.entries,
                    frameCount = 2,
                    canvasWidth = 64,
                    canvasHeight = 32,
                    canvasDpi = 144,
                    previewBytes = null,
                    exportState = ExportUiState.Idle,
                    onExport = onExport,
                    actions = ExportActions({}, {}, {}, {}),
                    onDismissResult = {},
                )
            }
        }
    }

    @Test
    fun printPresetSubmitsItsResolutionAndOversampling() {
        var submitted: ExportOptions? = null
        showExport { submitted = it }
        compose.onNodeWithText("Print PDF").performScrollTo().performClick()
        compose.onNodeWithText("Export PDF").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull(submitted)
            assertEquals(300, submitted!!.dpi)
            assertEquals(2f, submitted!!.pdfOversample)
            assertEquals(PdfPageSize.A4_PORTRAIT, submitted!!.pdfPageSize)
            assertEquals(ExportFormat.PDF, submitted!!.format)
        }
    }

    @Test
    fun jpegDoesNotOfferAnUnsupportedMultiFrameFile() {
        showExport()
        compose.onNodeWithText("JPEG").performScrollTo().performClick()
        compose.onNodeWithText("All frames").assertIsNotEnabled()
    }

    @Test
    fun privacyPolicyIsAvailableOfflineAndCanBeDismissed() {
        compose.setContent { MaterialTheme { PrivacyPolicyEntry() } }
        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithText("ArtFlow Privacy Policy").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("ArtFlow Privacy Policy").assertDoesNotExist()
    }
}
