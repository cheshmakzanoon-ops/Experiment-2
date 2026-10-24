package com.artflow.studio

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.perspective.PerspectiveGuide
import com.artflow.studio.core.symmetry.SymmetryEngine
import com.artflow.studio.core.tool.ToolType
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import com.artflow.studio.presentation.ui.screens.canvas.CanvasScreen
import com.artflow.studio.presentation.ui.theme.ArtFlowTheme
import com.artflow.studio.presentation.ui.theme.studioColorScheme
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import kotlin.math.abs

/** The real editor's status follows active visible guides and stays readable over its work area. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class GuideStatusUiTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var canvas: CanvasRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var exporter: ArtworkExporter

    @Inject lateinit var timelapse: TimelapseRecorder

    @Inject lateinit var storage: ProjectStorage

    private val preferences =
        AppSettings(
            themeMode = ThemeMode.LIGHT,
            showSymmetryGuides = true,
            showPerspectiveGuides = true,
            rightHandedInterface = true,
            reduceMotion = true,
            uiScale = 1.6f,
            largeTouchTargets = true,
        )
    private lateinit var viewModel: CanvasViewModel
    private var previousSettings: AppSettings? = null
    private var projectId = 0L
    private val guideLabel get() = compose.activity.getString(R.string.canvasscreen_guides_on)

    @Before fun setUp() {
        hilt.inject()
        previousSettings = settings.current()
        runBlocking {
            settings.update { preferences }
            projectId = projects.saveProject(Project(0L, "Guide status", "", null, 128, 128, 72, 1L, 1L))
        }
        runBlocking(Dispatchers.Main) { viewModel = CanvasViewModel(canvas, projects, settings, exporter, timelapse) }
        compose.runOnUiThread {
            compose.activity.setContent {
                ArtFlowTheme(preferences) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        CanvasScreen(projectId, {}, viewModel = viewModel)
                    }
                }
            }
        }
        compose.waitUntil(15_000) { viewModel.uiState.value is CanvasUiState.Ready }
        compose.waitUntil(5_000) {
            val current = viewModel.settings.value
            current.themeMode == ThemeMode.LIGHT &&
                current.rightHandedInterface &&
                current.showSymmetryGuides &&
                current.showPerspectiveGuides
        }
    }

    @After fun cleanUp() {
        if (::viewModel.isInitialized) {
            compose.runOnUiThread { compose.activity.setContent {} }
            compose.waitForIdle()
            runBlocking(Dispatchers.Main) {
                // Lifecycle disposal may start a save. Finish cancellation before deleting storage.
                viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
                canvas.dispose()
            }
        }
        if (projectId != 0L) {
            runBlocking { projects.deleteProjectById(projectId) }
            storage.deleteProjectFiles(projectId)
        }
        previousSettings?.let { previous -> runBlocking { settings.update { previous } } }
    }

    @Test fun statusRequiresAnActiveVisibleGuideAndHidesInFocusMode() {
        compose.runOnIdle {
            assertEquals(SymmetryEngine.SymmetryType.NONE, viewModel.input.value.symmetry.type)
            assertEquals(PerspectiveGuide.GuideType.NONE, viewModel.input.value.perspective.type)
        }
        compose.onNodeWithText(guideLabel).assertDoesNotExist()

        compose.runOnIdle { viewModel.setSymmetry(SymmetryEngine.SymmetryType.VERTICAL) }
        compose.onNodeWithText(guideLabel).assertIsDisplayed()
        assertOpaqueReadableStatus()
        TestEvidence.screenshot("guide-status-light.png")
        assertStatusAllowsCanvasNavigation()

        compose.runOnIdle { viewModel.setSymmetryGuidesVisible(false) }
        compose.waitUntil(5_000) { !viewModel.settings.value.showSymmetryGuides }
        compose.onNodeWithText(guideLabel).assertDoesNotExist()

        compose.runOnIdle {
            viewModel.setSymmetry(SymmetryEngine.SymmetryType.NONE)
            viewModel.setSymmetryGuidesVisible(true)
            viewModel.setPerspectiveSettings(PerspectiveGuide.Settings(type = PerspectiveGuide.GuideType.ONE_POINT))
        }
        compose.waitUntil(5_000) { viewModel.settings.value.showSymmetryGuides }
        compose.onNodeWithText(guideLabel).assertIsDisplayed()
        compose.runOnIdle { viewModel.setPerspectiveGuidesVisible(false) }
        compose.waitUntil(5_000) { !viewModel.settings.value.showPerspectiveGuides }
        compose.onNodeWithText(guideLabel).assertDoesNotExist()

        compose.runOnIdle { viewModel.setPerspectiveGuidesVisible(true) }
        compose.waitUntil(5_000) { viewModel.settings.value.showPerspectiveGuides }
        compose.onNodeWithText(guideLabel).assertIsDisplayed()
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Prefs").performScrollTo().performClick()
        compose.onNodeWithText("Full screen").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Exit focus mode").assertIsDisplayed()
        compose.onNodeWithText(guideLabel).assertDoesNotExist()
        TestEvidence.screenshot("guide-status-focus.png")
        compose.onNodeWithContentDescription("Exit focus mode").performClick()
        compose.onNodeWithText(guideLabel).assertIsDisplayed()

        compose.runOnIdle { viewModel.setPerspectiveSettings(PerspectiveGuide.Settings()) }
        compose.onNodeWithText(guideLabel).assertDoesNotExist()
    }

    private fun assertStatusAllowsCanvasNavigation() {
        compose.runOnIdle { viewModel.setTool(ToolType.ZOOM) }
        val view =
            compose.runOnIdle {
                requireNotNull(findCanvas(compose.activity.findViewById(android.R.id.content)))
            }
        val before = compose.runOnIdle { Triple(view.currentScale(), canvas.undoDepth, canvas.historyMark) }
        // Inject actual pointer events at the caption, not a semantics click or direct View call.
        compose.onNodeWithText(guideLabel).performTouchInput { click() }
        compose.runOnIdle {
            assertEquals("The status must let Zoom receive the touch beneath it", before.first * 1.25f, view.currentScale(), 0.0001f)
            assertEquals("Navigation must not add an undo entry", before.second, canvas.undoDepth)
            assertEquals("Navigation must not alter document history", before.third, canvas.historyMark)
            viewModel.setTool(ToolType.BRUSH)
        }
    }

    private fun findCanvas(view: View): ArtFlowCanvasView? {
        if (view is ArtFlowCanvasView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun assertOpaqueReadableStatus() {
        val scheme = studioColorScheme(preferences.accent, false, preferences.highContrast)
        val background = scheme.surface.toArgb()
        val foreground = scheme.onSurface.toArgb()
        var observed = emptyMap<Int, Int>()
        var contrast = 0.0
        try {
            compose.waitUntil(10_000) {
                val pixels = compose.onNodeWithText(guideLabel).captureToImage().toPixelMap()
                val counts = mutableMapOf<Int, Int>()
                // Keep rounded corners and the surrounding work area out of the text sample.
                for (y in pixels.height / 4 until pixels.height * 3 / 4) {
                    for (x in pixels.width / 10 until pixels.width * 9 / 10) {
                        val color = pixels[x, y].toArgb()
                        counts[color] = (counts[color] ?: 0) + 1
                    }
                }
                observed = counts
                val paper = counts.filterKeys { closeColor(it, background) }.maxByOrNull { it.value }
                val ink = counts.filterKeys { closeColor(it, foreground) }.maxByOrNull { it.value }
                if (paper == null || ink == null || paper.value < counts.values.sum() / 2 || ink.value < 4) {
                    false
                } else {
                    contrast = ColorUtils.calculateContrast(ink.key, paper.key)
                    contrast >= 4.5
                }
            }
        } catch (timeout: ComposeTimeoutException) {
            TestEvidence.screenshot("guide-status-contrast-timeout.png")
            val common = observed.entries.sortedByDescending { it.value }.take(6)
            throw AssertionError("Guide status needs opaque light surface and readable text; contrast=$contrast, pixels=$common", timeout)
        }
        assertTrue("Rendered guide status contrast must be at least 4.5:1", contrast >= 4.5)
    }

    private fun closeColor(
        actual: Int,
        expected: Int,
    ): Boolean = (0..3).all { channel -> abs((actual ushr (channel * 8) and 255) - (expected ushr (channel * 8) and 255)) <= 2 }
}
