package com.artflow.studio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.presentation.ui.components.layer.BlendModePreview
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Inspect rendered preview pixels, using independent expected values for the same two input colours. */
@RunWith(AndroidJUnit4::class)
class BlendModePreviewDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun modeChipsShowTheActualBlendOfConsistentSampleColours() {
        val expected =
            mapOf(
                BlendMode.NORMAL to 0xFF4080C0.toInt(),
                BlendMode.MULTIPLY to 0xFF304030.toInt(),
                BlendMode.SCREEN to 0xFFD0C0D0.toInt(),
                BlendMode.DIFFERENCE to 0xFF800080.toInt(),
            )
        compose.setContent {
            Column {
                expected.keys.forEach { mode -> BlendModePreview(mode, Modifier.size(64.dp)) }
            }
        }
        expected.forEach { (mode, colour) ->
            val pixels = compose.onNodeWithContentDescription("${mode.displayName} blend preview").captureToImage().toPixelMap()
            assertEquals("$mode overlap", colour, pixels[pixels.width / 2, pixels.height / 2].toArgb())
            assertEquals("$mode backdrop", 0xFFC08040.toInt(), pixels[pixels.width / 5, pixels.height / 5].toArgb())
            assertEquals("$mode source", 0xFF4080C0.toInt(), pixels[pixels.width * 4 / 5, pixels.height * 4 / 5].toArgb())
        }
        TestEvidence.screenshot("blend-mode-previews.png")
    }
}
