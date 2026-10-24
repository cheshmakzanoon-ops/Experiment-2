package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.canvas.LayerTransform
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.presentation.ui.components.editor.TransformSheet
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransformUiRegressionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun draftControlsDoNotMutateUntilApplyAndUseUniformScale() {
        var result: LayerTransform.Parameters? = null
        var closed = 0
        compose.setContent {
            MaterialTheme {
                TransformSheet("Ink layer", false, {
                    result = it
                    true
                }, { closed++ })
            }
        }
        setText("Width %", "75")
        setText("Rotation °", "25")
        compose.onNodeWithText("Flip horizontal").performScrollTo().performClick()
        compose.runOnIdle { assertNull(result) }
        awaitEnabled("Apply transform")
        compose.onNodeWithText("Apply transform").performScrollTo().performClick()
        // Apply settles in a coroutine; wait for it to reach the callback instead of assuming
        // the draft has already been applied by the next idle frame.
        compose.waitUntil(5_000) { result != null }
        compose.runOnIdle {
            val applied = requireNotNull(result)
            assertEquals(0.75f, applied.scaleX, 0f)
            assertEquals(0.75f, applied.scaleY, 0f)
            assertEquals(25f, applied.rotationDegrees, 0f)
            assertTrue(applied.flipHorizontal)
            assertEquals(1, closed)
        }
    }

    @Test fun cancelDoesNotApplyAndInvalidNumbersDisableCommit() {
        var applied = false
        var closed = false
        compose.setContent {
            MaterialTheme {
                TransformSheet("Ink layer", false, {
                    applied = true
                    true
                }, { closed = true })
            }
        }
        setText("Width %", "0")
        // The field must actually hold the typed value before the button's state means anything.
        awaitFieldText("Width %", "0")
        compose.onNodeWithText("Apply transform").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(closed)
            assertFalse(applied)
        }
    }

    @Test fun activeSelectionCannotSilentlyTransformTheWholeLayer() {
        compose.setContent {
            MaterialTheme { TransformSheet("Ink layer", true, { error("Must not apply") }, {}) }
        }
        compose.onNodeWithText("Deselect before transforming the entire layer.").assertIsDisplayed()
        compose.onNodeWithText("Apply transform").performScrollTo().assertIsNotEnabled()
    }

    /**
     * Writes a field through its SetText action instead of [performTextReplacement]. Replacing text
     * focuses the field first, which opens the IME; on a slow emulator the IME's stale echo then
     * overwrites the replacement and the test reads a field the user never saw.
     */
    private fun setText(
        label: String,
        text: String,
    ) {
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.SetText) { set -> set(AnnotatedString(text)) }
    }

    /** A slow emulator finishes a text edit a frame or two later; the button follows that state. */
    private fun awaitEnabled(text: String) {
        try {
            compose.waitUntil(5_000) {
                runCatching { compose.onNodeWithText(text).assertIsEnabled() }.isSuccess
            }
        } catch (_: ComposeTimeoutException) {
            throw AssertionError("$text stayed disabled: ${fieldsSummary()}")
        }
    }

    private fun awaitFieldText(
        label: String,
        expected: String,
    ) {
        try {
            compose.waitUntil(5_000) { fieldText(label) == expected }
        } catch (_: ComposeTimeoutException) {
            throw AssertionError("$label never held '$expected': ${fieldsSummary()}")
        }
    }

    private fun fieldText(label: String): String? =
        runCatching {
            compose
                .onNodeWithText(label)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text
        }.getOrNull()

    private fun fieldsSummary(): String =
        listOf("Width %", "Height %", "Rotation °", "Horizontal skew °", "Move X (px)", "Move Y (px)")
            .joinToString { label -> "$label='${fieldText(label) ?: "<missing>"}'" }

    @Test
    fun signedNumbersAndWrappedControlsRemainUsableWithLargeText() {
        var result: LayerTransform.Parameters? = null
        compose.setContent {
            ArtFlowTheme(AppSettings(uiScale = 1.6f, largeTouchTargets = true, reduceMotion = true)) {
                TransformSheet("Ink layer", false, {
                    result = it
                    true
                }, {})
            }
        }
        setText("Move X (px)", "-12.5")
        setText("Horizontal skew °", "-20")
        compose
            .onNodeWithText("Flip vertical")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose
            .onNodeWithText("Pixel art")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        awaitEnabled("Apply transform")
        compose
            .onNodeWithText("Apply transform")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            val applied = requireNotNull(result)
            assertEquals(-12.5f, applied.translationX, 0f)
            assertEquals(-20f, applied.skewXDegrees, 0f)
            assertTrue(applied.flipVertical)
            assertEquals(LayerTransform.Interpolation.NEAREST, applied.interpolation)
        }
    }
}
