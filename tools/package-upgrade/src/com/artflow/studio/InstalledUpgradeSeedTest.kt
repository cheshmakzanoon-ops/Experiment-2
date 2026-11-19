package com.artflow.studio

import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.repository.BrushLibraryStore
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
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import kotlin.math.abs

/** Test-only overlay for the pinned Room-v1 app; every seed mutation uses its real UI. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class InstalledUpgradeSeedTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var canvas: CanvasRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var storage: ProjectStorage

    @Inject lateinit var database: ArtFlowDatabase

    private val arguments = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        hilt.inject()
    }

    @Test
    fun seedSavedArtworkNewerRecoveryAndPreferencesThroughOldUi() {
        val token = requireNotNull(arguments.getString("artflowPackageUpgradeToken"))
        check(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}").matches(token))
        val name = "Package upgrade ${token.take(8)}"
        val expectedApk = requireNotNull(arguments.getString("artflowOldApkSha256"))
        assertEquals(expectedApk, digest(File(context.applicationInfo.sourceDir).readBytes()))
        assertEquals(OLD_SOURCE, arguments.getString("artflowOldSource"))
        assertEquals(1, runBlocking(Dispatchers.IO) { database.openHelper.writableDatabase.version })
        assertTrue("The dedicated old-app UI seed must start with no projects", runBlocking { projects.getAllProjects().first().isEmpty() })
        // Android creates the app-specific external root through Context. A configured
        // output subdirectory cannot create that root itself on a fresh scoped-storage install.
        check(requireNotNull(context.getExternalFilesDir(null)).isDirectory) { "The old app's external evidence root is unavailable" }
        val output = TestEvidence.directory()
        check(File(output, "old-ui-seed-started").createNewFile()) { "Retain the first seed attempt" }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val attempt =
            runCatching {
                configureThroughUi()
                createArtwork(name)
                awaitCanvas(scenario)
                val projectId =
                    runBlocking {
                        projects
                            .getAllProjects()
                            .first()
                            .single { it.name == name }
                            .id
                    }
                savePaletteThroughUi()
                saveBrushThroughUi()
                val blank = pixels()
                paint(scenario, 0.43f)
                compose.waitUntil(TIMEOUT) { !pixels().contentEquals(blank) }
                val savedPixels = pixels()
                compose.onNodeWithText("Gallery").performClick()
                compose.onNodeWithText("Save changes?").assertIsDisplayed()
                compose.onNodeWithText("Save and leave").performClick()
                awaitGallery()
                assertTrue(storage.documentFile(projectId).isFile)
                assertTrue(storage.flattenedFile(projectId).isFile)
                assertTrue(storage.thumbnailFile(projectId).isFile)
                val savedBytes = storage.documentFile(projectId).readBytes()
                compose.onNodeWithText(name).performClick()
                awaitCanvas(scenario)
                assertArrayEquals("The old application must reopen its own saved picture", savedPixels, pixels())
                assertFalse(runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() })
                TestEvidence.screenshot("old-ui-saved-reopened.png")
                paint(scenario, 0.62f)
                compose.waitUntil(TIMEOUT) { !pixels().contentEquals(savedPixels) }
                // Wait for the application's configured timer; never invoke repository autosave here.
                compose.waitUntil(45_000) {
                    val recovery = storage.autosaveFile(projectId)
                    recovery.isFile &&
                        recovery.lastModified() / 1000 > storage.documentFile(projectId).lastModified() / 1000 &&
                        !recovery.readBytes().contentEquals(savedBytes)
                }
                assertArrayEquals(
                    "Autosave must preserve the explicitly saved document",
                    savedBytes,
                    storage.documentFile(projectId).readBytes(),
                )
                assertTrue(runBlocking { storage.hasUnsavedRecovery(projectId) })
                val preferences = runBlocking { settings.settings.first() }
                assertTrue(preferences.seenOnboarding)
                assertEquals(ThemeMode.LIGHT, preferences.themeMode)
                assertFalse(preferences.stylusOnly)
                assertTrue(preferences.customPalettes.any { it.name == "My palette" && COLOR in it.colors })
                assertTrue(runBlocking { BrushLibraryStore(database.settingsDao()).brushes.first().any { it.name == BRUSH_NAME } })
                TestEvidence.screenshot("old-ui-newer-recovery.png")
                val report =
                    JSONObject()
                        .put("schemaVersion", 1)
                        .put("mode", "automated-old-app-ui")
                        .put("campaignToken", token)
                        .put("packageName", context.packageName)
                        .put("oldSource", OLD_SOURCE)
                        .put("oldApkSha256", expectedApk)
                        .put("projectName", name)
                        .put("projectId", projectId)
                        .put("databaseVersion", 1)
                        .put("pid", Process.myPid())
                        .put("success", true)
                        .put("assertions", JSONObject().apply { ASSERTIONS.forEach { put(it, true) } })
                File(output, "old-app-ui-seed.json").apply {
                    check(!exists())
                    writeText(report.toString(2))
                }
            }
        val diagnostics =
            runCatching {
                File(output, "old-ui-final-tree.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
                TestEvidence.screenshot("old-ui-final.png")
            }.exceptionOrNull()
        // Closing the Activity may request another legitimate lifecycle autosave. The host
        // freezes after this test/process finishes, and preserves that actual final state.
        val closing = runCatching { scenario.close() }.exceptionOrNull()
        val errors = listOfNotNull(attempt.exceptionOrNull(), diagnostics, closing)
        if (errors.isNotEmpty()) {
            errors.drop(1).forEach(errors.first()::addSuppressed)
            throw errors.first()
        }
    }

    private fun configureThroughUi() {
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("Skip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Skip").performClick()
        awaitGallery()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Light").performClick()
        val stylus = toggle("Stylus only").performScrollTo()
        stylus
            .assertIsOff()
            .performClick()
            .assertIsOn()
            .performClick()
            .assertIsOff()
        toggle("Autosave").performScrollTo().assertIsOn()
        compose
            .onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress) and hasAnySibling(hasText("Autosave every", substring = true)),
                useUnmergedTree = true,
            ).performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(5_000f) }
        compose.waitUntil(TIMEOUT) { runBlocking { settings.settings.first().autosaveIntervalMs == 5_000L } }
        compose.onNodeWithContentDescription("Back").performClick()
        awaitGallery()
    }

    private fun toggle(label: String) =
        compose.onNode(isToggleable() and hasClickAction() and hasAnyDescendant(hasText(label)), useUnmergedTree = true)

    private fun createArtwork(name: String) {
        compose.onNodeWithText("New artwork", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Name").performTextInput(name)
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("Width").performTextReplacement("64")
        compose.onNodeWithText("Height").performTextReplacement("64")
        compose.onNodeWithText("Dpi").performTextReplacement("72")
        compose.onNodeWithText("Create").performClick()
    }

    private fun savePaletteThroughUi() {
        // This pinned historical UI has no label on its 34dp colour chip. Require its unique
        // actual semantic bounds, retain the tree, and verify the panel opened after clicking.
        val size = 34f * context.resources.displayMetrics.density
        val chip =
            SemanticsMatcher("historical 34dp unlabelled colour chip") {
                it.config.getOrNull(SemanticsProperties.Text) == null &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == null &&
                    abs(it.boundsInRoot.width - size) < 2f &&
                    abs(it.boundsInRoot.height - size) < 2f
            }
        File(TestEvidence.directory(), "old-ui-before-colour-tree.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
        compose.onNode(hasClickAction() and chip, useUnmergedTree = true).performClick()
        compose.onNodeWithText("Colours").assertIsDisplayed()
        compose.onNodeWithText("Value").performScrollTo().performClick()
        compose
            .onNodeWithText("Hex")
            .performScrollTo()
            .performTextReplacement("AA2211")
        compose.onNodeWithText("Hex").performImeAction()
        closeSoftKeyboard()
        compose.waitUntil(TIMEOUT) { runBlocking { COLOR in settings.settings.first().recentColors } }
        compose.onNodeWithText("Save as palette").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) {
            runBlocking {
                settings.settings
                    .first()
                    .customPalettes
                    .any { COLOR in it.colors }
            }
        }
        compose.onNodeWithContentDescription("Close Colour").performClick()
    }

    private fun saveBrushThroughUi() {
        compose.onNodeWithContentDescription("Paint").performClick()
        compose.onNodeWithText("Brush studio").assertIsDisplayed()
        compose.onNodeWithText("Save a copy").performScrollTo().performClick()
        compose.onNodeWithText("Brush name").performTextInput(BRUSH_NAME)
        compose.onNodeWithText("Save copy").performClick()
        compose.waitUntil(
            TIMEOUT,
        ) { runBlocking { BrushLibraryStore(database.settingsDao()).brushes.first().any { it.name == BRUSH_NAME } } }
        compose.onNodeWithContentDescription("Cancel brush changes").performClick()
    }

    private fun awaitGallery() {
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("New artwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitCanvas(scenario: ActivityScenario<MainActivity>) {
        compose.waitUntil(TIMEOUT) {
            var ready = false
            scenario.onActivity { ready = findCanvas(it.window.decorView)?.let { view -> view.width > 0 && view.height > 0 } == true }
            ready
        }
        compose.waitForIdle()
    }

    private fun pixels(): IntArray = runBlocking(Dispatchers.Main) { requireNotNull(canvas.compositeBuffer()).pixels.copyOf() }

    private fun paint(
        scenario: ActivityScenario<MainActivity>,
        fraction: Float,
    ) {
        scenario.onActivity {
            val view = requireNotNull(findCanvas(it.window.decorView))
            val down = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
                val event = MotionEvent.obtain(down, down + index * 20, action, view.width * fraction + index * 3f, view.height * 0.5f, 0)
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
        for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val OLD_SOURCE = "ca9ce535c09d35ceca6aba9f686c60f58f93bc09"
        private const val TIMEOUT = 15_000L
        private const val COLOR = 0xFFAA2211.toInt()
        private const val BRUSH_NAME = "Upgrade saved ink"
        private val ASSERTIONS =
            listOf(
                "createdThroughOldAppUi",
                "savedAndReopenedThroughOldAppUi",
                "customPaletteCreatedThroughOldAppUi",
                "savedBrushCreatedThroughOldAppUi",
                "newerRecoveryCreatedThroughOldAppUi",
            )
    }
}
