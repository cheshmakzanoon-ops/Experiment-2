package com.artflow.studio

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.ProcreateSwatches
import com.artflow.studio.core.lifecycle.retainCleanupFailure
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.IncomingFiles
import com.artflow.studio.presentation.ui.MainActivity
import com.artflow.studio.presentation.ui.components.canvas.ArtFlowCanvasView
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject

/** Real task launches must not create a competing editor over the process-wide canvas repository. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SingleEditorActivityDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createEmptyComposeRule()

    @Inject lateinit var projects: ProjectRepository

    @Inject lateinit var canvas: CanvasRepository

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var storage: ProjectStorage

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val activities = CopyOnWriteArrayList<MainActivity>()
    private val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
    private val observer =
        ActivityLifecycleCallback { activity, stage ->
            if (activity is MainActivity && stage == Stage.CREATED) activities.add(activity)
        }
    private val name = "Window-${System.nanoTime()}"
    private val ownedUris = mutableSetOf<Uri>()
    private var originalSettings: AppSettings? = null

    @Before
    fun setup() {
        hilt.inject()
        originalSettings = runBlocking { settings.settings.first() }
        runBlocking { settings.update { it.copy(seenOnboarding = true, autosaveEnabled = false, recordTimelapse = false) } }
        instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(observer) }
    }

    @After
    fun cleanup() {
        val failures = mutableListOf<Throwable>()
        runCatching {
            compose.waitUntil(TIMEOUT_MS) {
                var destroyed = false
                instrumentation.runOnMainSync {
                    activities.filterNot { it.isFinishing || it.isDestroyed }.forEach { it.finish() }
                    destroyed = activities.all { it.isDestroyed && lifecycle.getLifecycleStageOf(it) == Stage.DESTROYED }
                }
                destroyed
            }
        }.onFailure(failures::add)
        runCatching {
            instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(observer) }
        }.onFailure(failures::add)
        runCatching { releasePendingTestUris() }.onFailure(failures::add)
        runCatching {
            runBlocking {
                projects.getAllProjects().first().filter { it.name == name }.forEach {
                    projects.deleteProjectById(it.id)
                    storage.deleteProjectFiles(it.id)
                }
            }
        }.onFailure(failures::add)
        runCatching { originalSettings?.let { saved -> runBlocking { settings.update { saved } } } }.onFailure(failures::add)
        failures.firstOrNull()?.let { original ->
            failures.drop(1).forEach { retainCleanupFailure(original, it) }
            throw original
        }
    }

    @Test
    fun externalRelaunchesReuseTheEditorPreserveUnsavedWorkAndImportQueuedFilesOnceAfterRecreation() {
        // ActivityScenario matches lifecycle events against its original launch intent. This
        // test deliberately replaces that intent through real VIEW/SEND delivery, so observe
        // the created activity identities instead while retaining the same fresh-task launch.
        val originalActivity =
            instrumentation.startActivitySync(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClass(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            ) as MainActivity
        assertSame(originalActivity, activities.single())
        awaitStage(originalActivity, Stage.RESUMED)
        createArtwork()
        awaitCanvas(originalActivity)
        val projectId =
            runBlocking {
                projects
                    .getAllProjects()
                    .first()
                    .single { it.name == name }
                    .id
            }
        compose.waitUntil(TIMEOUT_MS) { runBlocking(Dispatchers.Main) { canvas.projectId() == projectId } }
        val pixels = PixelBuffer.filled(64, 64, 0xFF375FA2.toInt())
        runBlocking(Dispatchers.Main) {
            assertTrue(canvas.setLayerPixels(canvas.getActiveLayerId(), pixels, "Unsaved window test"))
        }
        val history = runBlocking(Dispatchers.Main) { canvas.historyMark }
        val expected = currentPixels()
        val palettes =
            listOf(
                Palette(name = "$name-view", colors = listOf(0xFFDD4433.toInt())),
                Palette(name = "$name-send", colors = listOf(0xFF227766.toInt())),
            )
        val uris = palettes.map { paletteUri(projectId, it) }
        val requests =
            listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uris[0], "application/octet-stream")
                    .addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
                Intent(Intent.ACTION_SEND)
                    .setType("application/octet-stream")
                    .putExtra(Intent.EXTRA_STREAM, uris[1])
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK),
            )
        requests.forEachIndexed { index, request ->
            val uri = uris.getOrNull(index - 1)
            if (uri != null) {
                request.clipData = ClipData.newRawUri("Queued palette", uri)
                request.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            relaunch(originalActivity, request, index)
            assertPreserved(projectId, history, expected)
            assertTrue(
                runBlocking {
                    settings.settings
                        .first()
                        .customPalettes
                        .none { it.name.startsWith(name) }
                },
            )
        }

        awaitStage(originalActivity, Stage.RESUMED)
        assertSendIntent(originalActivity, uris.last(), "$name-${requests.lastIndex}")
        instrumentation.runOnMainSync { originalActivity.recreate() }
        awaitStage(originalActivity, Stage.DESTROYED)
        compose.waitUntil(TIMEOUT_MS) { activities.size == 2 }
        val recreatedActivity = activities.last()
        assertNotSame(originalActivity, recreatedActivity)
        awaitStage(recreatedActivity, Stage.RESUMED)
        assertSendIntent(recreatedActivity, uris.last(), "$name-${requests.lastIndex}")
        awaitCanvas(recreatedActivity)
        assertPreserved(projectId, history, expected)
        assertTrue(
            runBlocking {
                settings.settings
                    .first()
                    .customPalettes
                    .none { it.name.startsWith(name) }
            },
        )
        compose.onNodeWithText("Gallery").performClick()
        compose.onNodeWithText("Save and leave").performClick()
        compose.waitUntil(TIMEOUT_MS) {
            val imported = runBlocking { settings.settings.first().customPalettes }
            palettes.all { wanted -> imported.count { it.name == wanted.name && it.colors == wanted.colors } == 1 }
        }
        // The gallery awaits this feedback before receiving another file. A recreated activity
        // must not have offered its last SEND a second time while both original files were queued.
        val secondFeedback = context.getString(R.string.incoming_palette_added, palettes.last().name)
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText(secondFeedback).fetchSemanticsNodes().isNotEmpty() }
        assertNull("Recreation requeued an already accepted file", runBlocking { withTimeoutOrNull(200) { IncomingFiles.files.first() } })
        compose.onNodeWithText(name).performClick()
        awaitCanvas(recreatedActivity)
        assertEquals(projectId, runBlocking(Dispatchers.Main) { canvas.projectId() })
        assertArrayEquals(expected, currentPixels())
        assertFalse(runBlocking(Dispatchers.Main) { canvas.hasUnsavedChanges() })
        assertEquals(2, activities.size)
    }

    private fun createArtwork() {
        compose.waitUntil(
            TIMEOUT_MS,
        ) { compose.onAllNodesWithText("New artwork", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("New artwork", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Name").performTextInput(name)
        compose.onNodeWithText("Custom").performClick()
        compose.onNodeWithText("Width").performTextReplacement("64")
        compose.onNodeWithText("Height").performTextReplacement("64")
        compose.onNodeWithText("Dpi").performTextReplacement("72")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithText("Gallery").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun paletteUri(
        projectId: Long,
        palette: Palette,
    ): Uri {
        val bytes = ProcreateSwatches.write(palette)
        val file = runBlocking { storage.saveExport(projectId, "${palette.name}.swatches", bytes) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).also { ownedUris.add(it) }
    }

    private fun awaitStage(
        activity: MainActivity,
        stage: Stage,
    ) {
        compose.waitUntil(TIMEOUT_MS) {
            var reached = false
            instrumentation.runOnMainSync {
                reached = lifecycle.getLifecycleStageOf(activity) == stage && (stage != Stage.DESTROYED || activity.isDestroyed)
            }
            reached
        }
    }

    private fun assertSendIntent(
        activity: MainActivity,
        uri: Uri,
        token: String,
    ) {
        instrumentation.runOnMainSync {
            assertEquals(Intent.ACTION_SEND, activity.intent.action)
            assertEquals(token, activity.intent.getStringExtra(REQUEST_TOKEN))
            assertEquals(uri, requireNotNull(activity.intent.clipData).getItemAt(0).uri)
            assertTrue(activity.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }

    private fun awaitCanvas(activity: MainActivity) {
        compose.waitUntil(TIMEOUT_MS) {
            var ready = false
            instrumentation.runOnMainSync { ready = findCanvas(activity.window.decorView)?.isPreviewAvailable == true }
            ready
        }
        compose.waitForIdle()
    }

    private fun findCanvas(view: View): ArtFlowCanvasView? {
        if (view is ArtFlowCanvasView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findCanvas(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun relaunch(
        activity: MainActivity,
        request: Intent,
        index: Int,
    ) {
        val token = "$name-$index"
        request.setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(REQUEST_TOKEN, token)
        context.startActivity(request)
        compose.waitUntil(TIMEOUT_MS) {
            var delivered = false
            instrumentation.runOnMainSync { delivered = activity.intent.getStringExtra(REQUEST_TOKEN) == token }
            delivered || activities.size > 1
        }
        assertEquals("A new launch must reuse the existing editor", 1, activities.size)
        instrumentation.runOnMainSync {
            assertEquals(token, activity.intent.getStringExtra(REQUEST_TOKEN))
            if (request.clipData != null) {
                assertTrue(activity.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(
                    requireNotNull(request.clipData).getItemAt(0).uri,
                    requireNotNull(activity.intent.clipData).getItemAt(0).uri,
                )
            }
        }
        request.clipData?.getItemAt(0)?.uri?.let { uri ->
            assertTrue(requireNotNull(context.contentResolver.openInputStream(uri)).use { it.read() >= 0 })
        }
    }

    private fun assertPreserved(
        projectId: Long,
        history: Long,
        expected: IntArray,
    ) {
        runBlocking(Dispatchers.Main) {
            assertEquals(projectId, canvas.projectId())
            assertEquals(history, canvas.historyMark)
            assertTrue(canvas.hasUnsavedChanges())
        }
        assertArrayEquals(expected, currentPixels())
    }

    private fun currentPixels(): IntArray = runBlocking(Dispatchers.Main) { requireNotNull(canvas.compositeBuffer()).pixels.copyOf() }

    private fun releasePendingTestUris() =
        runBlocking {
            val others = mutableListOf<Uri>()
            for (index in 0 until IncomingFiles.MAX_PENDING_FILES) {
                val uri = withTimeoutOrNull(100) { IncomingFiles.files.first() } ?: break
                if (uri !in ownedUris) others.add(uri)
            }
            others.forEach { IncomingFiles.offer(Intent(Intent.ACTION_VIEW, it)) }
        }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val REQUEST_TOKEN = "com.artflow.studio.test.REQUEST_TOKEN"
    }
}
