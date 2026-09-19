package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.presentation.ui.components.editor.StudioToolDock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudioToolDockTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everydayToolsHaveSelectedSemanticsAndSecondaryToolsRemainReachable() {
        compose.setContent {
            var active by remember { mutableStateOf(ToolType.BRUSH) }
            var expanded by remember { mutableStateOf(false) }
            MaterialTheme {
                StudioToolDock(active, expanded, { active = it }, { expanded = it })
            }
        }
        compose.onNodeWithContentDescription("Brush").assertIsOn()
        compose.onNodeWithContentDescription("Smudge").performClick().assertIsOn()
        compose.onNodeWithContentDescription("Brush").assertIsOff()
        compose.onNodeWithContentDescription("Clone Stamp").assertDoesNotExist()
        compose.onNodeWithText("All tools").performClick()
        compose.onNodeWithContentDescription("Clone Stamp").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Clone Stamp").assertIsDisplayed().assertIsOn()
        compose.onNodeWithText("Hide tools").assertDoesNotExist()
        compose.onNodeWithText("All tools").assertExists()
    }
}
