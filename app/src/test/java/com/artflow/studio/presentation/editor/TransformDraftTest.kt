package com.artflow.studio.presentation.editor

import com.artflow.studio.core.canvas.LayerTransform
import com.artflow.studio.presentation.ui.components.editor.TransformDraft
import org.junit.Assert.*
import org.junit.Test

class TransformDraftTest {
    @Test fun defaultDraftIsAnExactIdentity() {
        assertTrue(requireNotNull(TransformDraft().parametersOrNull()).isIdentity)
    }

    @Test fun zeroBlankInvalidAndOutOfRangeScalesCannotProduceAnApplyRequest() {
        for (text in listOf("0", "-0", "-1", "0.5", "1601", "", "text", "NaN", "Infinity", "1e38")) {
            assertNull(text, TransformDraft(width = text).parametersOrNull())
            assertNull(text, TransformDraft(height = text, uniform = false).parametersOrNull())
        }
        assertNotNull(TransformDraft(width = "1").parametersOrNull())
        assertNotNull(TransformDraft(width = "1600").parametersOrNull())
    }

    @Test fun signedNumbersCommaDecimalsAndFreeScaleAreRetainedWithoutTouchingTheOriginal() {
        val original = TransformDraft()
        val edited = original.copy(width = "150", height = "50", uniform = false, offsetX = "-12,5", skew = "-20", flipY = true)
        val parameters = requireNotNull(edited.parametersOrNull())
        assertEquals(1.5f, parameters.scaleX, 0f)
        assertEquals(0.5f, parameters.scaleY, 0f)
        assertEquals(-12.5f, parameters.translationX, 0f)
        assertEquals(-20f, parameters.skewXDegrees, 0f)
        assertTrue(parameters.flipVertical)
        assertTrue(requireNotNull(original.parametersOrNull()).isIdentity)
    }

    @Test fun proportionalScaleIgnoresHiddenHeightButFreeScaleValidatesIt() {
        val locked = TransformDraft(width = "25", height = "invalid")
        assertEquals(0.25f, requireNotNull(locked.parametersOrNull()).scaleY, 0f)
        assertNull(locked.copy(uniform = false).parametersOrNull())
        val pixelArt = locked.copy(interpolation = LayerTransform.Interpolation.NEAREST, flipX = true)
        assertEquals(LayerTransform.Interpolation.NEAREST, requireNotNull(pixelArt.parametersOrNull()).interpolation)
        assertTrue(requireNotNull(pixelArt.parametersOrNull()).flipHorizontal)
    }

    @Test fun nonFiniteMovementAndRotationOrExcessiveSkewCannotProduceAnApplyRequest() {
        assertNull(TransformDraft(offsetX = "NaN").parametersOrNull())
        assertNull(TransformDraft(offsetY = "Infinity").parametersOrNull())
        assertNull(TransformDraft(rotation = "1e100").parametersOrNull())
        assertNull(TransformDraft(skew = "80.1").parametersOrNull())
        assertNotNull(TransformDraft(skew = "-80", rotation = "-720").parametersOrNull())
    }
}
