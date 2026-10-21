package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.BrushShareFormat
import com.artflow.studio.presentation.ui.components.brush.BrushShareDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrushShareFormatUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun singleBrushDefaultsToArtflowAndCanExplicitlySelectProcreate() {
        var chosen: BrushShareFormat? = null
        compose.setContent {
            MaterialTheme {
                BrushShareDialog(isSet = false, onDismiss = {}, onShare = { chosen = it })
            }
        }
        compose.onNodeWithText("ArtFlow (.artbrush)").assertIsSelected()
        compose.onNodeWithText("Procreate (.brushset)").assertDoesNotExist()
        compose.onNodeWithText("Procreate (.brush)").performClick().assertIsSelected()
        compose.onNodeWithText("Share").performClick()
        compose.runOnIdle { assertEquals(BrushShareFormat.PROCREATE_BRUSH, chosen) }
    }

    @Test
    fun setChoiceUsesTheBrushsetFormat() {
        var chosen: BrushShareFormat? = null
        compose.setContent {
            MaterialTheme {
                BrushShareDialog(isSet = true, onDismiss = {}, onShare = { chosen = it })
            }
        }
        compose.onNodeWithText("ArtFlow (.artbrush)").assertIsSelected()
        compose.onNodeWithText("Procreate (.brush)").assertDoesNotExist()
        compose.onNodeWithText("Procreate (.brushset)").performClick()
        compose.onNodeWithText("Share").performClick()
        compose.runOnIdle { assertEquals(BrushShareFormat.PROCREATE_SET, chosen) }
    }

    @Test
    fun cancelDoesNotShareOrCommitAFormat() {
        var chosen: BrushShareFormat? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                BrushShareDialog(isSet = false, onDismiss = { dismissed = true }, onShare = { chosen = it })
            }
        }
        compose.onNodeWithText("Procreate (.brush)").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertNull(chosen)
        }
    }
}
