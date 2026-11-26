package com.artflow.studio

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.ArtworkExporter
import com.artflow.studio.data.export.TimelapseRecorder
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.ProjectRepositoryImpl
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.domain.model.Project
import com.artflow.studio.presentation.ui.components.export.ExportSheet
import com.artflow.studio.presentation.ui.components.export.rememberExportActions
import com.artflow.studio.presentation.ui.viewmodel.CanvasUiState
import com.artflow.studio.presentation.ui.viewmodel.CanvasViewModel
import com.artflow.studio.presentation.ui.viewmodel.ExportUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Actual export-sheet controls, editor states, encoders and FileProvider; only external launch is captured. */
@RunWith(AndroidJUnit4::class)
class EditorExportHandoffDeviceTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var storage: ProjectStorage
    private lateinit var database: ArtFlowDatabase
    private lateinit var canvas: CanvasRepositoryImpl
    private lateinit var viewModel: CanvasViewModel
    private var projectId = 0L
    private val attempts = CopyOnWriteArrayList<Intent>()

    @Volatile private var missingViewer = false

    @Before
    fun setup() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        context =
            object : ContextWrapper(application) {
                override fun startActivity(intent: Intent) {
                    assertSame(Looper.getMainLooper(), Looper.myLooper())
                    attempts += intent
                    if (missingViewer) throw ActivityNotFoundException("No viewer installed")
                }
            }
        storage = ProjectStorage(context)
        database = Room.inMemoryDatabaseBuilder(application, ArtFlowDatabase::class.java).build()
        val projects = ProjectRepositoryImpl(database.projectDao(), storage, database)
        val settings = SettingsRepositoryImpl(database.settingsDao())
        canvas = CanvasRepositoryImpl(storage)
        runBlocking {
            settings.update { it.copy(autosaveEnabled = false, recordTimelapse = false) }
            projectId = projects.saveProject(Project(0, "Ink study", "", null, WIDTH, HEIGHT, 72, 0, 0))
        }
        viewModel =
            CanvasViewModel(
                canvas,
                projects,
                settings,
                ArtworkExporter(context, storage),
                TimelapseRecorder(storage).apply { isEnabled = false },
            )
        runBlocking(Dispatchers.Main) {
            viewModel.open(projectId)
            val ready = withTimeout(10_000) { viewModel.uiState.first { it is CanvasUiState.Ready || it is CanvasUiState.Error } }
            assertTrue(ready.toString(), ready is CanvasUiState.Ready)
        }
        paint(RED)
        compose.setContent {
            val activityContext = LocalContext.current
            val launchContext =
                remember(activityContext) {
                    object : ContextWrapper(activityContext) {
                        override fun startActivity(intent: Intent) {
                            this@EditorExportHandoffDeviceTest.context.startActivity(intent)
                        }
                    }
                }
            CompositionLocalProvider(LocalContext provides launchContext) {
                MaterialTheme {
                    val state by viewModel.exportState.collectAsState()
                    ExportSheet(
                        availableFormats = listOf(ExportFormat.PNG),
                        frameCount = 1,
                        canvasWidth = WIDTH,
                        canvasHeight = HEIGHT,
                        canvasDpi = 72,
                        previewBytes = null,
                        exportState = state,
                        actions = rememberExportActions(viewModel),
                    )
                }
            }
        }
        compose.onNodeWithText("File name (optional)").performScrollTo().performTextInput("Ink study")
    }

    @After
    fun cleanup() {
        runBlocking(Dispatchers.Main) { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        canvas.dispose()
        database.close()
        storage.deleteProjectFiles(projectId)
    }

    @Test
    fun shareAndOpenKeepTheirPixelsAfterTheNextSameNameEditorExport() {
        val first = export()
        val firstBytes = File(first.filePath).readBytes()
        val beforeShare = storage.projectSizeBytes(projectId)
        clickHandoff("Share", 1)
        val shared = uri(attempts[0], Intent.ACTION_SEND)
        assertTrue(storage.projectSizeBytes(projectId) >= beforeShare + first.byteCount)

        compose.onNodeWithText("Export another").performScrollTo().performClick()
        paint(BLUE)
        val second = export()
        assertEquals(first.filePath, second.filePath)
        val secondBytes = File(second.filePath).readBytes()
        assertFalse(firstBytes.contentEquals(secondBytes))
        clickHandoff("Open", 2)
        val opened = uri(attempts[1], Intent.ACTION_VIEW)

        compose.onNodeWithText("Export another").performScrollTo().performClick()
        paint(GREEN)
        val third = export()
        assertEquals(first.filePath, third.filePath)

        assertNotEquals(shared, opened)
        assertArrayEquals(firstBytes, read(shared))
        assertArrayEquals(secondBytes, read(opened))
        assertPixels(RED, read(shared))
        assertPixels(BLUE, read(opened))
        assertPixels(GREEN, File(third.filePath).readBytes())
    }

    @Test
    fun aFailedViewerLaunchRemovesOnlyItsCopyAndAllowsRetry() {
        val result = export()
        val sourceBytes = File(result.filePath).readBytes()
        clickHandoff("Share", 1)
        val shared = uri(attempts[0], Intent.ACTION_SEND)

        missingViewer = true
        clickHandoff("Open", 2)
        val failed = uri(attempts[1], Intent.ACTION_VIEW)
        compose.waitUntil(10_000) { !requireNotNull(exportFile(failed).parentFile).exists() }
        assertFalse(exportFile(failed).exists())
        assertEquals(
            setOf(File(result.filePath), exportFile(shared)),
            storage
                .exportsDir(projectId)
                .walkTopDown()
                .filter { it.isFile && it.extension == "png" }
                .toSet(),
        )
        val message = runBlocking { withTimeout(10_000) { viewModel.messageFlow.first() } }
        assertEquals("No app can open this file here. Use Save file or install a compatible viewer.", message)
        assertArrayEquals(sourceBytes, read(shared))
        assertArrayEquals(sourceBytes, File(result.filePath).readBytes())

        missingViewer = false
        clickHandoff("Open", 3)
        val retried = uri(attempts[2], Intent.ACTION_VIEW)
        assertNotEquals(failed, retried)
        assertArrayEquals(sourceBytes, read(retried))
    }

    @Test
    fun anOldOrReconstructedResultCannotLaunchAfterExportAnother() {
        val result = export()
        runBlocking(Dispatchers.Main) { viewModel.launchExport(result.copy(), open = false, context::startActivity) }
        assertTrue(attempts.isEmpty())

        compose.onNodeWithText("Export another").performScrollTo().performClick()
        runBlocking(Dispatchers.Main) { viewModel.launchExport(result, open = true, context::startActivity) }

        assertTrue(attempts.isEmpty())
        assertEquals(listOf(result.fileName), storage.exportsDir(projectId).list()!!.toList())
        assertPixels(RED, File(result.filePath).readBytes())
    }

    @Test
    fun cancellationAfterTheActivityStartsRetainsItsAlreadyGrantedCopy() {
        val result = export()
        runBlocking {
            val operation =
                async {
                    val owner = currentCoroutineContext()
                    viewModel.launchExport(result, open = true) { intent ->
                        context.startActivity(intent)
                        owner.cancel(CancellationException("Editor leaves after opening the viewer"))
                    }
                }
            operation.join()
            assertTrue(operation.isCancelled)
        }

        val opened = uri(attempts.single(), Intent.ACTION_VIEW)
        compose.onNodeWithText("Export another").performScrollTo().performClick()
        paint(BLUE)
        val next = export()

        assertEquals(result.filePath, next.filePath)
        assertPixels(RED, read(opened))
        assertPixels(BLUE, File(next.filePath).readBytes())
    }

    private fun export(): ExportResult {
        compose.onNodeWithText("Export PNG").performScrollTo().performClick()
        compose.waitUntil(
            10_000,
        ) { viewModel.exportState.value is ExportUiState.Done || viewModel.exportState.value is ExportUiState.Failed }
        val state = viewModel.exportState.value
        assertTrue(state.toString(), state is ExportUiState.Done)
        return (state as ExportUiState.Done).result
    }

    private fun clickHandoff(
        label: String,
        count: Int,
    ) {
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitUntil(10_000) { attempts.size == count }
    }

    private fun paint(color: Int) =
        runBlocking(Dispatchers.Main) {
            assertTrue(canvas.setLayerPixels(canvas.getActiveLayerId(), PixelBuffer.filled(WIDTH, HEIGHT, color), "Paint"))
        }

    private fun uri(
        launched: Intent,
        action: String,
    ): Uri {
        val intent =
            if (launched.action == Intent.ACTION_CHOOSER) {
                @Suppress("DEPRECATION")
                requireNotNull(launched.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            } else {
                launched
            }
        assertEquals(action, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        val clip = requireNotNull(intent.clipData)
        assertEquals(1, clip.itemCount)
        val uri = clip.getItemAt(0).uri
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        if (action == Intent.ACTION_VIEW) {
            assertEquals(uri, intent.data)
        } else {
            @Suppress("DEPRECATION")
            val extra = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(uri, extra)
        }
        // A failed activity still has a URI, but its unpublished file may already have been removed.
        if (exportFile(uri).exists()) {
            requireNotNull(context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Ink study.png", cursor.getString(0))
            }
        }
        return uri
    }

    private fun exportFile(uri: Uri): File {
        val path = requireNotNull(uri.path)
        check(path.startsWith("/exports/$projectId/share-"))
        return File(context.filesDir, path.removePrefix("/"))
    }

    private fun read(uri: Uri): ByteArray = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }

    private fun assertPixels(
        color: Int,
        bytes: ByteArray,
    ) {
        assertArrayEquals(IntArray(WIDTH * HEIGHT) { color }, requireNotNull(BitmapPixelBridge.fromEncodedBytes(bytes)).pixels)
    }

    private companion object {
        const val WIDTH = 16
        const val HEIGHT = 16
        val RED = 0xFFFF0000.toInt()
        val BLUE = 0xFF0000FF.toInt()
        val GREEN = 0xFF00FF00.toInt()
    }
}
