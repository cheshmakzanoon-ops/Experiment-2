package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.presentation.ui.screens.settings.PaletteRecoveryNotice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaletteRecoveryUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun cancellingConfirmationDoesNotStartBackupOrReset() {
        var requests = 0
        compose.setContent { MaterialTheme { PaletteRecoveryNotice(false) { requests++ } } }
        compose.onNodeWithText("Back up and reset palettes").performClick()
        compose.onNodeWithText("Preserve unreadable palettes?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test
    fun confirmingRequestsAUserSelectedBackupInsteadOfResettingImmediately() {
        var requests = 0
        compose.setContent { MaterialTheme { PaletteRecoveryNotice(false) { requests++ } } }
        compose.onNodeWithText("Back up and reset palettes").performClick()
        compose.onNodeWithText("Choose backup file").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithText("Preserve unreadable palettes?").assertDoesNotExist()
    }

    @Test
    fun aRunningBackupCannotBeStartedTwice() {
        compose.setContent { MaterialTheme { PaletteRecoveryNotice(true) { error("Duplicate backup") } } }
        compose.onNodeWithText("Saving palette backup…").assertIsNotEnabled()
    }
}
