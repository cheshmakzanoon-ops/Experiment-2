package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.R
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.GrainStorage
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.ProjectRepositoryImpl
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.presentation.ui.screens.gallery.GalleryImports
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

/** Real gallery entry points, file URIs, ViewModel, Room and package storage; the legacy buffered API is forbidden. */
@RunWith(AndroidJUnit4::class)
class GalleryPackageImportDeviceTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var storage: ProjectStorage
    private lateinit var database: ArtFlowDatabase
    private lateinit var projects: ProjectRepositoryImpl
    private lateinit var viewModel: MainViewModel
    private lateinit var scope: CoroutineScope
    private lateinit var source: File
    private lateinit var grainId: String
    private lateinit var grainBytes: ByteArray
    private lateinit var rasterBytes: ByteArray
    private val stagedPaths = mutableListOf<File>()
    private var importFailure: Exception? = null

    @Before
    fun setup() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = File(application.cacheDir, "gallery-package-${System.nanoTime()}").apply { check(mkdirs()) }
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = File(directory, "target").apply { mkdirs() }

                override fun getCacheDir(): File = File(directory, "staging").apply { mkdirs() }
            }
        storage = ProjectStorage(context)
        database = Room.inMemoryDatabaseBuilder(application, ArtFlowDatabase::class.java).build()
        projects = ProjectRepositoryImpl(database.projectDao(), storage, database)
        val checkedProjects =
            object : ProjectRepository by projects {
                override suspend fun importPackage(bytes: ByteArray): Long = error("Gallery artwork imports must stream from disk")

                override suspend fun importPackageFile(file: File): ProjectRepository.ImportedPackage {
                    assertTrue(file.isFile)
                    assertEquals(context.cacheDir, file.parentFile)
                    stagedPaths += file
                    importFailure?.let { throw it }
                    return projects.importPackageFile(file)
                }
            }
        viewModel = MainViewModel(checkedProjects, SettingsRepositoryImpl(database.settingsDao()), storage)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        source = runBlocking { writeSourcePackage(application) }
    }

    @After
    fun cleanup() {
        runBlocking {
            scope.coroutineContext[Job]?.cancelAndJoin()
            viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
        database.close()
        StorageFileTree.delete(directory)
    }

    @Test
    fun namedArtworkImportStreamsRestoresAssetsAndKeepsItsNoticeAcrossNavigation() =
        runBlocking(Dispatchers.Main) { importAndCheck(genericZip = false) }

    @Test
    fun genericZipOpenUsesTheSameStreamingPathAndNeverTheSmallFileDecoder() =
        runBlocking(Dispatchers.Main) { importAndCheck(genericZip = true) }

    @Test
    fun aRejectedPackageLeavesNeitherStagingFilesNorAPublishedArtwork() =
        runBlocking(Dispatchers.Main) {
            val damaged = File(directory, "damaged.artflow")
            damaged.writeBytes(ArtflowPackage.write("Damaged", mapOf(ArtflowPackage.DOCUMENT to "{}".toByteArray())))
            val failure = CompletableDeferred<String>()
            val importer = imports({ failure.complete(it) }) { error("Damaged artwork must not open") }
            importer.artwork(Uri.fromFile(damaged))
            assertTrue(withTimeout(20_000) { failure.await() }.isNotBlank())
            assertTrue(projects.getAllProjects().first().isEmpty())
            assertTrue(
                context.cacheDir
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun databaseFailureIsReportedAndRemovesStagingWithoutOpeningOrPublishingArtwork() =
        runBlocking(Dispatchers.Main) {
            importFailure = SQLiteException("Injected database failure")
            val failure = CompletableDeferred<String>()
            val importer = imports({ failure.complete(it) }) { error("Failed artwork must not open") }
            importer.artwork(Uri.fromFile(source))
            assertEquals(
                context.getString(R.string.galleryscreen_file_could_not_be_read),
                withTimeout(20_000) { failure.await() },
            )
            assertEquals(1, stagedPaths.size)
            assertFalse(stagedPaths.single().exists())
            assertTrue(
                context.cacheDir
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
            assertTrue(projects.getAllProjects().first().isEmpty())
        }

    private suspend fun importAndCheck(genericZip: Boolean) {
        val picked = if (genericZip) File(directory, "generic.zip").also { source.copyTo(it) } else source
        val opened = CompletableDeferred<Long>()
        val importer =
            imports({ opened.completeExceptionally(IllegalStateException(it)) }) { id ->
                opened.complete(id)
                // Navigation disposes the gallery scope while the importer still owns its file.
                scope.cancel()
            }
        if (genericZip) importer.open(Uri.fromFile(picked)) else importer.artwork(Uri.fromFile(picked))
        val id = withTimeout(20_000) { opened.await() }
        scope.coroutineContext[Job]?.join()
        assertEquals(1, stagedPaths.size)
        assertTrue(stagedPaths.none { it.exists() })
        assertTrue(
            context.cacheDir
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
        assertNotNull(projects.getProjectById(id))
        val document = requireNotNull(storage.loadDocument(id))
        assertEquals(
            "Portable text",
            document.layers
                .single()
                .textContent
                ?.text,
        )
        assertArrayEquals(rasterBytes, storage.readRaster(id, requireNotNull(document.layers.single().rasterFile)))
        assertArrayEquals(grainBytes, requireNotNull(GrainStorage.fileFor(GrainStorage.directory(context.filesDir), grainId)).readBytes())
        val notice = requireNotNull(PendingImports.takeNotice(id))
        assertTrue(notice.contains("Fonts need attention: UnavailableTestFont"))
        assertTrue(notice.contains("1 brush texture(s) are missing"))
        assertNull(PendingImports.takeNotice(id))
        assertFalse(File(storage.projectDir(id), "grains").exists())
    }

    private fun imports(
        say: suspend (String) -> Unit,
        open: (Long) -> Unit,
    ) = GalleryImports(context, scope, viewModel, say, { _, _ -> error("An artwork is not a brush") }, open)

    private suspend fun writeSourcePackage(application: Context): File {
        val sourceContext =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = File(directory, "source").apply { mkdirs() }
            }
        val original = ProjectStorage(sourceContext)
        rasterBytes = BitmapPixelBridge.toPngBytes(PixelBuffer.filled(64, 64, 0xFFAA4400.toInt()))
        val raster = original.writeRaster(7, 1, 1, rasterBytes)
        val text = TextLayerContent("Portable text", TextLayout.TextStyle(fontFamily = "font:UnavailableTestFont.ttf"), -1, 0f, 0f)
        grainBytes = ByteArray(CustomGrains.TILE * CustomGrains.TILE) { 0x5A }
        grainId = "custom-" + UUID.randomUUID().toString().replace("-", "")
        val missingGrain = "custom-" + UUID.randomUUID().toString().replace("-", "")
        // Notices concern assets the artwork actually uses, not unrelated manifest declarations.
        val strokes =
            listOf(grainId, missingGrain).mapIndexed { index, texture ->
                Stroke(
                    id = index + 1L,
                    points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 10f)),
                    brushParams = BrushParams(textureId = texture),
                    layerId = 1L,
                    color = 0xFFAA4400.toInt(),
                )
            }
        original.saveDocument(
            7,
            CanvasDocument(
                width = 64,
                height = 64,
                dpi = 72,
                backgroundColor = -1,
                activeLayerId = 1,
                nextLayerId = 2,
                layers = listOf(Layer(1, "Text", 0, rasterFile = raster, textContent = text, strokes = strokes)),
            ),
        )
        val dependencies =
            ArtflowPackage.Dependencies(
                fonts = listOf(ArtflowPackage.PackagedFont("font:UnavailableTestFont.ttf", "UnavailableTestFont")),
                grainIds = listOf(grainId, missingGrain),
            )
        return original.withPackageSnapshot(7) { snapshot ->
            val sources =
                snapshot.sources.filterNot { it.path == ArtflowPackage.DEPENDENCIES } +
                    listOf(
                        ArtflowPackage.EntrySource(ArtflowPackage.DEPENDENCIES) {
                            ByteArrayInputStream(ArtflowPackage.encodeDependencies(dependencies))
                        },
                        ArtflowPackage.EntrySource(ArtflowPackage.grainEntryPath(grainId)) { ByteArrayInputStream(grainBytes) },
                    )
            File(directory, "portable.artflow").also { ArtflowPackage.writeToFile("Portable artwork", it, sources) }
        }
    }
}
