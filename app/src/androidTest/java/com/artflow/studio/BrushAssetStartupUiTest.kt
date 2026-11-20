package com.artflow.studio

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.lifecycle.AssetInitialization
import com.artflow.studio.core.lifecycle.AssetStartupState
import com.artflow.studio.presentation.ui.BrushAssetGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The production gate must withhold dependent input until loading or an explicit user choice. */
@RunWith(AndroidJUnit4::class)
class BrushAssetStartupUiTest {
    @get:Rule val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val texture = AtomicReference("available fallback")
    private val paintedTexture = AtomicReference<String?>(null)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun firstPaintUsesTheLoadedAssetAfterTheLoadingGateCompletes() {
        val release = CompletableDeferred<Unit>()
        val initialization =
            AssetInitialization(scope, initialize = {
                release.await()
                texture.set("saved texture")
            })
        content(initialization)
        compose.onNodeWithText("Loading saved brushes…").assertIsDisplayed()
        compose.onNodeWithText("Paint").assertDoesNotExist()
        assertEquals(null, paintedTexture.get())

        release.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { initialization.state.value == AssetStartupState.READY }
        compose.onNodeWithText("Paint").performClick()
        assertEquals("saved texture", paintedTexture.get())
    }

    @Test
    fun failedStartupAndAnUnfinishedRetryCannotExposePaintControls() {
        val attempts = AtomicInteger()
        val releaseRetry = CompletableDeferred<Unit>()
        val initialization =
            AssetInitialization(scope, initialize = {
                if (attempts.incrementAndGet() == 1) throw IOException("Texture unavailable")
                releaseRetry.await()
                texture.set("recovered texture")
            })
        content(initialization)
        compose.waitUntil(timeoutMillis = 5_000) { initialization.state.value == AssetStartupState.FAILED }
        compose.onNodeWithText("Paint").assertDoesNotExist()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Loading saved brushes…").assertIsDisplayed()
        compose.onNodeWithText("Paint").assertDoesNotExist()

        releaseRetry.complete(Unit)
        compose.waitUntil(timeoutMillis = 5_000) { initialization.state.value == AssetStartupState.READY }
        compose.onNodeWithText("Paint").performClick()
        assertEquals("recovered texture", paintedTexture.get())
        assertEquals(2, attempts.get())
    }

    @Test
    fun failureRequiresAnExplainedExplicitChoiceToUseAvailableBrushes() {
        val initialization = AssetInitialization(scope, initialize = { throw IOException("Texture unavailable") })
        content(initialization)
        compose.waitUntil(timeoutMillis = 5_000) { initialization.state.value == AssetStartupState.FAILED }
        compose.onNodeWithText("Paint").assertDoesNotExist()
        compose
            .onNodeWithText(
                "Retry, or open with the available brushes. Brushes with missing textures or shapes may paint differently.",
            ).assertIsDisplayed()
        compose.onNodeWithText("Open with available brushes").performClick()
        compose.onNodeWithText("Paint").performClick()
        assertEquals(AssetStartupState.CONTINUING_WITH_AVAILABLE, initialization.state.value)
        assertEquals("available fallback", paintedTexture.get())
    }

    private fun content(initialization: AssetInitialization) {
        initialization.start()
        compose.setContent {
            val state by initialization.state.collectAsState()
            MaterialTheme {
                BrushAssetGate(
                    state = state,
                    onRetry = initialization::retry,
                    onContinueWithAvailable = initialization::continueWithAvailableAssets,
                ) {
                    Button(onClick = { paintedTexture.set(texture.get()) }) { Text("Paint") }
                }
            }
        }
    }
}
