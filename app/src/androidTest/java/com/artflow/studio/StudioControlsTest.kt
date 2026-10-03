package com.artflow.studio

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.canvas.CropBox
import com.artflow.studio.presentation.ui.components.editor.CropBar
import com.artflow.studio.presentation.ui.components.editor.LayerOpacityOverlay
import com.artflow.studio.presentation.ui.components.editor.PageAssistActions
import com.artflow.studio.presentation.ui.components.editor.PageAssistBar
import com.artflow.studio.presentation.ui.components.editor.QuickAction
import com.artflow.studio.presentation.ui.components.editor.RadialQuickMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The canvas controls added for Procreate's Page Assist, QuickMenu, Crop & Resize and layer opacity. */
@RunWith(AndroidJUnit4::class)
class StudioControlsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pageStripSelectsAddsAndProtectsTheLastPage() {
        val calls = mutableListOf<String>()
        val actions =
            PageAssistActions(
                onSelect = { calls += "select $it" },
                onAdd = { calls += "add" },
                onDuplicate = { calls += "duplicate" },
                onDelete = { calls += "delete $it" },
                onMove = { from, to -> calls += "move $from $to" },
                onClose = { calls += "close" },
            )
        compose.setContent { MaterialTheme { PageAssistBar(pageCount = 2, activePage = 0, thumbnails = emptyList(), actions = actions) } }
        compose.onNodeWithText("Page 1 of 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Page 2").performClick()
        compose.onNodeWithContentDescription("Add page").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Move page later").performClick()
        compose.onNodeWithContentDescription("Move page earlier").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("select 1", "add", "move 0 1"), calls) }
    }

    @Test
    fun quickMenuRunsAnActionAndCloses() {
        var copied = false
        var closed = false
        compose.setContent {
            MaterialTheme {
                RadialQuickMenu(
                    actions = listOf(QuickAction("Copy", Icons.Default.ContentCopy) { copied = true }),
                    onMore = {},
                    onDismiss = { closed = true },
                )
            }
        }
        compose.onNodeWithContentDescription("Copy").performClick()
        compose.runOnIdle {
            assertTrue(copied)
            assertTrue(closed)
        }
    }

    @Test
    fun cropBarShowsTheNewSizeAndFinishes() {
        var done = false
        compose.setContent {
            MaterialTheme {
                CropBar(CropBox.Box(10f, 20f, 110f, 70f), onSettings = {}, onReset = {}, onCancel = {}, onDone = { done = true })
            }
        }
        compose.onNodeWithText("100 × 50 px").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertTrue(done) }
    }

    @Test
    fun slidingAcrossTheCanvasChangesLayerOpacity() {
        var opacity = 0.5f
        compose.setContent { MaterialTheme { LayerOpacityOverlay(opacity = 0.5f, onChange = { opacity = it }, onDone = {}) } }
        compose.onNodeWithContentDescription("Slide to set layer opacity").performTouchInput { swipeRight(startX = centerX, endX = right) }
        compose.runOnIdle { assertTrue("Sliding right raises the opacity", opacity > 0.5f) }
    }
}
