package com.artflow.studio

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.domain.model.settings.ThemeMode
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/** The same real UI/storage/export assertions run in debug and release-optimized qualification. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class OptimizedArtworkWorkflowTest {
    private val diagnostics = UiFailureDiagnosticsRule()

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val reportFailures: TestRule = diagnostics

    @get:Rule(order = 2)
    val compose = createEmptyComposeRule()

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var canvas: CanvasRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var storage: ProjectStorage

    @Inject lateinit var database: ArtFlowDatabase

    @Before
    fun setup() {
        hilt.inject()
    }

    @Test
    fun realPointerPaintSurvivesSaveFreshActivityReopenAndUiPngExport() {
        if (PackageUpgradeWorkflow(compose, projects, canvas, settings, storage, database).runIfRequested()) return
        val originalSettings = runBlocking { settings.settings.first() }
        val name = "Optimized-workflow-${System.nanoTime()}"
        runBlocking {
            settings.update { it.copy(themeMode = ThemeMode.LIGHT, seenOnboarding = true, stylusOnly = false, autosaveEnabled = false) }
        }
        try {
            var projectId = 0L
            lateinit var painted: IntArray
            diagnostics.action("Create, paint and save through the editor") {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    createArtwork(name)
                    awaitCanvas(scenario)
                    projectId =
                        runBlocking {
                            projects
                                .getAllProjects()
                                .first()
                                .single { it.name == name }
                                .id
                        }
                    val blank = pixels()
                    paint(scenario)
                    compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() } }
                    painted = pixels()
                    assertFalse("Real pointer input must change the artwork", blank.contentEquals(painted))
                    compose.onNodeWithText("Gallery").performClick()
                    compose.onNodeWithText("Save changes?").assertIsDisplayed()
                    compose.onNodeWithText("Save and leave").performClick()
                    awaitGallery()
                    assertTrue("Saving through navigation must create the document", storage.documentFile(projectId).isFile)
                }
            }
            val savedBytes = storage.documentFile(projectId).readBytes()
            diagnostics.action("Reopen from a fresh Activity and export through the UI") {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    awaitGallery()
                    compose.onNodeWithText(name).performClick()
                    awaitCanvas(scenario)
                    assertArrayEquals("Reopen must restore the saved pixels", painted, pixels())
                    assertFalse(runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() })
                    exportPng(projectId, painted)
                    assertArrayEquals(
                        "Export must leave the editable document intact",
                        savedBytes,
                        storage.documentFile(projectId).readBytes(),
                    )
                    assertArrayEquals("Export must leave live artwork intact", painted, pixels())
                    assertFalse(runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() })
                    TestEvidence.screenshot("optimized-workflow-export-ui.png")
                }
            }
        } finally {
            runBlocking {
                projects.getAllProjects().first().filter { it.name == name }.forEach {
                    projects.deleteProjectById(it.id)
                    storage.deleteProjectFiles(it.id)
                }
                settings.update { originalSettings }
            }
        }
    }

    private fun exportPng(
        projectId: Long,
        expectedPixels: IntArray,
    ) {
        compose.onNodeWithContentDescription("Actions").performClick()
        compose.onNodeWithText("Share").performScrollTo().performClick()
        compose.onNodeWithText("Share or export artwork…").performScrollTo().performClick()
        compose.onNodeWithText("PNG").performScrollTo().performClick()
        // The editor composite includes its white paper. PNG defaults to transparent export;
        // select the matching opaque mode through the UI before comparing every decoded pixel.
        compose
            .onNode(isToggleable() and hasAnySibling(hasText("Flatten onto white")), useUnmergedTree = true)
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOn()
        compose.onNodeWithText("File name (optional)").performScrollTo().performTextReplacement("optimized-reopen")
        compose.onNodeWithText("Export PNG").performScrollTo().performClick()
        val exported = File(storage.exportsDir(projectId), "optimized-reopen.png")
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(exported.name).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(exported.name).performScrollTo().assertIsDisplayed()
        assertTrue("The UI result must name a non-empty exported file", exported.isFile && exported.length() > 0)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(exported.absolutePath)) { "Android could not decode the exported PNG" }
        try {
            assertEquals(64, bitmap.width)
            assertEquals(64, bitmap.height)
            val decoded = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(decoded, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertArrayEquals("The UI-exported PNG must contain the reopened artwork", expectedPixels, decoded)
        } finally {
            bitmap.recycle()
        }
        val evidence = TestEvidence.directory()
        exported.copyTo(File(evidence, "optimized-workflow-export.png"), overwrite = true)
        File(evidence, "optimized-workflow.json").writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("buildType", BuildConfig.BUILD_TYPE)
                .put("width", 64)
                .put("height", 64)
                .put("paintSaveReopenExportPixelsEqual", true)
                .toString(2),
        )
    }

    private fun createArtwork(name: String) {
        awaitGallery()
        compose.onNodeWithText("New artwork", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Name").performTextInput(name)
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("Width").performTextReplacement("64")
        compose.onNodeWithText("Height").performTextReplacement("64")
        compose.onNodeWithText("Dpi").performTextReplacement("72")
        compose.onNodeWithText("Create").performClick()
    }

    private fun awaitGallery() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("New artwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitCanvas(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(15_000) {
            var ready = false
            scenario.onActivity {
                val view = findCanvas(it.window.decorView)
                ready = view != null && view.width > 0 && view.height > 0
            }
            ready
        }
        compose.waitForIdle()
    }

    private fun pixels(): IntArray = runBlocking(Dispatchers.Main) { requireNotNull(canvas.compositeBuffer()).pixels.copyOf() }

    private fun paint(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            val view = requireNotNull(findCanvas(it.window.decorView))
            val down = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event =
                    MotionEvent.obtain(
                        down,
                        down + index * 20,
                        action,
                        view.width * (0.4f + index * 0.1f),
                        view.height * 0.5f,
                        1f,
                        1f,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                    )
                try {
                    assertTrue(view.dispatchTouchEvent(event))
                } finally {
                    event.recycle()
                }
            }
        }
        compose.waitForIdle()
    }

    private fun findCanvas(view: View): ArtFlowCanvasView? {
        if (view is ArtFlowCanvasView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findCanvas(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
