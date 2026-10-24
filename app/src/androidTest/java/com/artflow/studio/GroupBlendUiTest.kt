package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.presentation.ui.components.layer.BlendModeSelectorDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GroupBlendUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun groupCanReturnFromNormalToPassThrough() {
        var selected: BlendMode? = null
        compose.setContent {
            MaterialTheme {
                BlendModeSelectorDialog(
                    currentBlendMode = BlendMode.NORMAL,
                    onBlendModeSelected = { selected = it },
                    onDismiss = {},
                    isGroup = true,
                )
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(BlendMode.entries.indexOf(BlendMode.PASS_THROUGH))
        compose.onNodeWithText(BlendMode.PASS_THROUGH.displayName).performClick()
        compose.runOnIdle { assertEquals(BlendMode.PASS_THROUGH, selected) }
    }

    @Test fun pixelLayersDoNotOfferTheFolderOnlyPassThroughMode() {
        compose.setContent {
            MaterialTheme {
                BlendModeSelectorDialog(
                    currentBlendMode = BlendMode.NORMAL,
                    onBlendModeSelected = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(BlendMode.getLayerBlendModes().lastIndex)
        compose.onNodeWithText(BlendMode.PASS_THROUGH.displayName).assertDoesNotExist()
    }
}
