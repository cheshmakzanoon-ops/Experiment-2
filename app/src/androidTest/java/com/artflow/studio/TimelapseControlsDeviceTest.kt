package com.artflow.studio

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.lifecycle.retainCleanupFailure
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/** Actual app navigation must reach the per-artwork recorder, including legacy preferences and failures. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TimelapseControlsDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val reportFailures: TestRule = UiFailureDiagnosticsRule()

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var canvas: CanvasRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var storage: ProjectStorage

    @Inject lateinit var recorder: TimelapseRecorder

    private val owned = mutableListOf<Project>()
    private var originalSettings: AppSettings? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setup() {
        hilt.inject()
        originalSettings = runBlocking { settings.settings.first() }
        runBlocking {
            settings.update { it.copy(seenOnboarding = true, autosaveEnabled = false, recordTimelapse = true) }
        }
    }

    @After
    fun cleanup() {
        val failures = mutableListOf<Throwable>()
        runCatching { scenario?.close() }.onFailure(failures::add)
        owned.forEach { project ->
            runCatching {
                runBlocking {
                    projects.deleteProjectById(project.id)
                    storage.deleteProjectFiles(project.id)
                }
            }.onFailure(failures::add)
        }
        runCatching { originalSettings?.let { saved -> runBlocking { settings.update { saved } } } }.onFailure(failures::add)
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach { retainCleanupFailure(first, it) }
            throw first
        }
    }

    @Test
    fun artworkSwitchResumesLegacyPauseReportsRefusedResumeAndNeverChangesAnotherArtwork() {
        val first = seed("Paused")
        val second = seed("Independent")
        val marker = File(storage.projectDir(first.id), "timelapse.paused").apply { writeBytes(byteArrayOf()) }
        val saved = storage.documentFile(first.id).readBytes()
        launch()
        openVideo(first)

        recordingSwitch().assertIsEnabled().assertIsOff().performClick()
        awaitRecording(first, true)
        recordingSwitch().assertIsOn()
        assertFalse(marker.exists())
        assertTrue(runBlocking { settings.settings.first().recordTimelapse })
        assertTrue(runBlocking { recorder.isRecording(second.id) })

        recordingSwitch().performClick()
        awaitRecording(first, false)
        compose.waitUntil(15_000) { marker.isFile }
        recordingSwitch().assertIsOff()
        assertTrue(runBlocking { settings.settings.first().recordTimelapse })

        // A malformed legacy marker gives a deterministic persistence failure without permissions,
        // mocked ViewModels, or changing the current artwork's saved data.
        assertTrue(marker.delete())
        assertTrue(marker.mkdir())
        val protected = File(marker, "Keep.txt").apply { writeText("Keep the unexpected contents") }
        recordingSwitch().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("The preference could not be saved", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        recordingSwitch().assertIsOff()
        assertFalse(runBlocking { recorder.isRecording(first.id) })
        assertArrayEquals("Keep the unexpected contents".toByteArray(), protected.readBytes())
        assertArrayEquals(saved, storage.documentFile(first.id).readBytes())
        assertTrue(runBlocking { settings.settings.first().recordTimelapse })

        // Restore the malformed fixture, then prove the UI can durably pause this artwork again.
        assertTrue(protected.delete())
        assertTrue(marker.delete())
        recordingSwitch().performClick()
        awaitRecording(first, true)
        recordingSwitch().performClick()
        awaitRecording(first, false)
        compose.waitUntil(15_000) { marker.isFile }
        returnToGallery()
        openVideo(second)
        recordingSwitch().assertIsOn()
        assertTrue(runBlocking { recorder.isRecording(second.id) })
        returnToGallery()
        openVideo(first)
        recordingSwitch().assertIsOff()
        assertArrayEquals(saved, storage.documentFile(first.id).readBytes())
    }

    @Test
    fun persistedGlobalPauseIsVisibleAndCanBeEnabledInSettingsWithoutResumingPausedArtwork() {
        val paused = seed("Master-paused")
        val allowed = seed("Master-allowed")
        runBlocking {
            recorder.setRecording(paused.id, false)
            settings.update { it.copy(recordTimelapse = false) }
        }
        launch()
        openVideo(allowed)
        recordingSwitch().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("Recording is disabled for all artworks in Settings.").performScrollTo()
        compose.onNodeWithText("Recording settings").performScrollTo().performClick()

        compose
            .onNode(hasText("Allow time-lapse recording") and isToggleable())
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.waitUntil(15_000) { runBlocking { settings.settings.first().recordTimelapse } }
        assertFalse(runBlocking { recorder.isRecording(paused.id) })
        assertTrue(runBlocking { recorder.isRecording(allowed.id) })
        compose.onNodeWithContentDescription("Back").performClick()
        showVideo()
        awaitDisplayedRecording(true)
        recordingSwitch().assertIsEnabled().assertIsOn()
        returnToGallery()
        openVideo(paused)
        recordingSwitch().assertIsEnabled().assertIsOff()
    }

    private fun seed(suffix: String): Project {
        val now = System.currentTimeMillis()
        val initial = Project(0, "Timelapse-$suffix-${System.nanoTime()}", "", null, 64, 64, 72, now, now)
        val project = initial.copy(id = runBlocking { projects.saveProject(initial) })
        owned += project
        runBlocking(Dispatchers.Main) {
            checkNotNull(canvas.loadOrCreate(project.id, 64, 64, 72))
            checkNotNull(canvas.saveCanvas(project.id))
        }
        return project
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitGallery()
    }

    private fun openVideo(project: Project) {
        compose.onNodeWithText(project.name).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Actions").fetchSemanticsNodes().isNotEmpty() }
        showVideo()
        awaitDisplayedRecording(runBlocking { settings.settings.first().recordTimelapse && recorder.isRecording(project.id) })
    }

    private fun showVideo() {
        if (compose.onAllNodesWithText("Video").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithContentDescription("Actions").performClick()
        }
        compose.onNodeWithText("Video").performScrollTo().performClick()
        recordingSwitch().performScrollTo()
    }

    private fun recordingSwitch() = compose.onNodeWithContentDescription("Time-lapse Recording")

    private fun awaitRecording(
        project: Project,
        recording: Boolean,
    ) {
        compose.waitUntil(15_000) { runBlocking { recorder.isRecording(project.id) == recording } }
        awaitDisplayedRecording(recording)
    }

    private fun awaitDisplayedRecording(recording: Boolean) {
        val expected = if (recording) ToggleableState.On else ToggleableState.Off
        compose.waitUntil(15_000) {
            compose
                .onAllNodesWithContentDescription("Time-lapse Recording")
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.ToggleableState) == expected
        }
    }

    private fun returnToGallery() {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Gallery").performClick()
        awaitGallery()
    }

    private fun awaitGallery() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("New artwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
