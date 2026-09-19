package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.components.editor.AnimationSheet
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/** The actual sheet must reflect the real playback session, not a manually supplied playing flag. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AnimationPlaybackUiTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var canvas: CanvasRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var exporter: ArtworkExporter

    @Inject lateinit var storage: ProjectStorage

    private lateinit var viewModel: CanvasViewModel
    private var projectId = 0L

    @Before
    fun setup() {
        hilt.inject()
        projectId = runBlocking { projects.saveProject(Project(0L, "Playback test", "", null, 8, 6, 72, 1L, 1L)) }
        runBlocking(Dispatchers.Main) {
            viewModel = CanvasViewModel(canvas, projects, settings, exporter)
            viewModel.open(projectId)
        }
        compose.waitUntil(15_000) { viewModel.uiState.value is CanvasUiState.Ready }
        runBlocking(Dispatchers.Main) {
            canvas.addFrame(false)
            canvas.setFrameDuration(0, 200)
            canvas.setFrameDuration(1, 200)
            canvas.selectFrame(0)
        }
        compose.setContent {
            val timeline by viewModel.timeline.collectAsState()
            val preferences by viewModel.settings.collectAsState()
            MaterialTheme {
                AnimationSheet(
                    timeline = timeline,
                    onionEnabled = preferences.onionSkin,
                    onSelectFrame = viewModel::selectFrame,
                    onAddFrame = viewModel::addFrame,
                    onDeleteFrame = viewModel::deleteFrame,
                    onMoveFrame = viewModel::moveFrame,
                    onFrameDuration = viewModel::setFrameDuration,
                    onSettings = viewModel::updateAnimationSettings,
                    onToggleOnion = viewModel::toggleOnionSkin,
                    onTogglePlayback = viewModel::togglePlayback,
                )
            }
        }
    }

    @After
    fun cleanup() {
        if (::viewModel.isInitialized) {
            runBlocking(Dispatchers.Main) {
                viewModel.viewModelScope.cancel()
                canvas.dispose()
            }
        }
        if (projectId != 0L) {
            runBlocking {
                projects.deleteProjectById(projectId)
                storage.deleteProjectFiles(projectId)
            }
        }
    }

    @Test
    fun realPlayButtonBecomesPauseWhileFramesAdvanceAndReturnsAfterPause() {
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.waitUntil(5_000) { viewModel.timeline.value.activeIndex == 1 }
        compose.onNodeWithContentDescription("Pause").performClick()
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.runOnIdle { assertFalse(viewModel.timeline.value.isPlaying) }
    }

    @Test
    fun backgroundCallbackClearsTheVisiblePauseState() {
        compose.onNodeWithContentDescription("Play").performClick()
        compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
        compose.runOnIdle { viewModel.saveRecoveryOnBackground() }
        compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        compose.runOnIdle { assertFalse(viewModel.timeline.value.isPlaying) }
    }
}
