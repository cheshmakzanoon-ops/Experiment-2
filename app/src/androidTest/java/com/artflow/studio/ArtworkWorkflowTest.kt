package com.artflow.studio

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.ProjectStorage
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/** Real navigation, ViewModels, input, Room and document storage in the unmodified app shell. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ArtworkWorkflowTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var canvas: CanvasRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var storage: ProjectStorage

    @Before
    fun setup() {
        hilt.inject()
    }

    @Test
    fun createPaintSaveReopenRecreateAndDiscardPreserveTheSavedPixels() {
        val originalSettings = runBlocking { settings.settings.first() }
        runBlocking {
            settings.update { it.copy(seenOnboarding = true, stylusOnly = false, autosaveEnabled = false) }
        }
        val name = "Workflow-${System.nanoTime()}"
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            createArtwork(name)
            awaitCanvas(scenario)
            val projectId = runBlocking { projects.getAllProjects().first().single { it.name == name }.id }
            val blank = pixels()
            paint(scenario, 0.45f)
            compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() } }
            val painted = pixels()
            assertFalse("Input through the editor must change real artwork", blank.contentEquals(painted))

            // Exercise the save-before-navigation callback, not a direct repository save.
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Save changes?").assertIsDisplayed()
            compose.onNodeWithText("Save and leave").performClick()
            awaitGallery()
            assertTrue(storage.documentFile(projectId).isFile)
            val saved = runBlocking { projects.getProjectById(projectId) }!!
            assertNotNull(saved.thumbnailPath)
            assertEquals(64, saved.width)
            assertEquals(64, saved.height)

            compose.onNodeWithText(name).performClick()
            awaitCanvas(scenario)
            assertArrayEquals("Reopening must read the saved artwork", painted, pixels())
            assertFalse(runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() })

            scenario.recreate()
            awaitCanvas(scenario)
            assertArrayEquals("Activity recreation must preserve the editor", painted, pixels())

            paint(scenario, 0.7f)
            compose.waitUntil(15_000) { runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() } }
            assertFalse(painted.contentEquals(pixels()))
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Stay").performClick()
            compose.onNodeWithText("Save changes?").assertDoesNotExist()
            compose.onNodeWithContentDescription("Back").performClick()
            compose.onNodeWithText("Discard and leave").performClick()
            awaitGallery()
            compose.onNodeWithText(name).performClick()
            awaitCanvas(scenario)
            assertArrayEquals("Discarding must not replace the last saved artwork", painted, pixels())
            assertFalse(storage.hasUnsavedRecovery(projectId))
        } finally {
            scenario.close()
            runBlocking {
                val owned = projects.getAllProjects().first().filter { it.name == name }
                owned.forEach {
                    projects.deleteProjectById(it.id)
                    storage.deleteProjectFiles(it.id)
                }
                settings.update { originalSettings }
            }
        }
    }

    private fun createArtwork(name: String) {
        compose.onNodeWithText("New artwork").performClick()
        compose.onNodeWithText("Name").performTextInput(name)
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("Width").performTextReplacement("64")
        compose.onNodeWithText("Height").performTextReplacement("64")
        compose.onNodeWithText("Dpi").performTextReplacement("72")
        compose.onNodeWithText("Create").performClick()
    }

    private fun awaitGallery() {
        compose.waitUntil(15_000) { compose.onAllNodesWithText("New artwork").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitCanvas(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(15_000) {
            var ready = false
            scenario.onActivity { activity ->
                val view = findCanvas(activity.window.decorView)
                ready = view != null && view.width > 0 && view.height > 0
            }
            ready
        }
        compose.waitForIdle()
    }

    private fun pixels(): IntArray = runBlocking(Dispatchers.Main) { requireNotNull(canvas.compositeBuffer()).pixels.copyOf() }

    private fun paint(
        scenario: ActivityScenario<MainActivity>,
        fraction: Float,
    ) {
        scenario.onActivity { activity ->
            val view = requireNotNull(findCanvas(activity.window.decorView))
            val down = SystemClock.uptimeMillis()
            val x = view.width * fraction
            val y = view.height * 0.5f
            dispatch(view, down, down, MotionEvent.ACTION_DOWN, x, y)
            dispatch(view, down, down + 20, MotionEvent.ACTION_MOVE, x + 3f, y)
            dispatch(view, down, down + 40, MotionEvent.ACTION_UP, x + 6f, y)
        }
        compose.waitForIdle()
    }

    private fun dispatch(
        view: View,
        down: Long,
        time: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        val event = MotionEvent.obtain(down, time, action, x, y, 1f, 1f, 0, 1f, 1f, 0, 0)
        try {
            assertTrue(view.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
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
