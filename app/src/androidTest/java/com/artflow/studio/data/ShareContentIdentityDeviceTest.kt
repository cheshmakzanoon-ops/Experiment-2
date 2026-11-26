package com.artflow.studio.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material3.SnackbarHostState
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.color.Palette
import com.artflow.studio.core.color.PaletteCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.BrushFiles
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.ShareExportFiles
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.entity.toEntity
import com.artflow.studio.data.renderer.BitmapPixelBridge
import com.artflow.studio.data.repository.ProjectRepositoryImpl
import com.artflow.studio.data.repository.settings.SettingsRepositoryImpl
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.presentation.ui.components.brush.shareBrushes
import com.artflow.studio.presentation.ui.screens.canvas.sharePalette
import com.artflow.studio.presentation.ui.screens.gallery.shareProjects
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Uses the real UI handoff functions, ViewModel/storage and FileProvider; only chooser launch is captured. */
@RunWith(AndroidJUnit4::class)
class ShareContentIdentityDeviceTest {
    private lateinit var context: Context
    private lateinit var storage: ProjectStorage
    private lateinit var database: ArtFlowDatabase
    private lateinit var viewModel: MainViewModel
    private var projectId = 0L
    private val sends = mutableListOf<Intent>()
    private var onLaunch: ((Intent) -> Unit)? = null

    @Before
    fun setup() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        context =
            object : ContextWrapper(application) {
                override fun startActivity(intent: Intent) {
                    assertEquals(Intent.ACTION_CHOOSER, intent.action)
                    @Suppress("DEPRECATION")
                    val send = requireNotNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                    sends += send
                    onLaunch?.invoke(send)
                }
            }
        storage = ProjectStorage(context)
        database = Room.inMemoryDatabaseBuilder(application, ArtFlowDatabase::class.java).build()
        val projects = ProjectRepositoryImpl(database.projectDao(), storage, database)
        viewModel = MainViewModel(projects, SettingsRepositoryImpl(database.settingsDao()), storage)
        projectId = System.nanoTime().coerceAtLeast(1)
    }

    @After
    fun cleanup() {
        runBlocking { viewModel.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        database.close()
        sends.flatMap(::uris).forEach { uri ->
            val path = requireNotNull(uri.path)
            check(path.startsWith("/exports/"))
            val file = File(context.filesDir, path.removePrefix("/"))
            check(!file.exists() || file.delete())
            val directory = requireNotNull(file.parentFile)
            val marker = File(directory, ShareExportFiles.OWNER_MARKER)
            check(!marker.exists() || marker.delete())
            check(!directory.exists() || directory.delete())
        }
        storage.deleteProjectFiles(projectId)
        storage.deleteProjectFiles(projectId + 1)
    }

    @Test
    fun galleryReshareKeepsTheEarlierUriPixelsAndFilename() =
        runBlocking(Dispatchers.Main) {
            val artwork = project(projectId)
            val firstBytes = savedImage(artwork, RED)
            withTimeout(10_000) { shareProjects(viewModel, listOf(artwork), context, SnackbarHostState()) }
            val first = singleUri(sends.single(), "image/png", "Ink study.png")
            // A consumer may defer opening the URI until after the next save and share.
            val nextBytes = savedImage(artwork, BLUE)
            withTimeout(10_000) { shareProjects(viewModel, listOf(artwork), context, SnackbarHostState()) }
            val next = singleUri(sends.last(), "image/png", "Ink study.png")

            assertNotEquals(first, next)
            assertArrayEquals(firstBytes, read(first))
            assertArrayEquals(nextBytes, read(next))
            assertArrayEquals(IntArray(16) { RED }, requireNotNull(BitmapPixelBridge.fromEncodedBytes(read(first))).pixels)
            assertArrayEquals(nextBytes, storage.flattenedFile(artwork.id).readBytes())
            assertPrivateDocumentsRemainOutsideProvider(artwork.id)
        }

    @Test
    fun galleryBatchGrantsOnlyItsExactUrisAndKeepsBothImagesAfterAnotherShare() =
        runBlocking(Dispatchers.Main) {
            val artworks = listOf(project(projectId), project(projectId + 1))
            val expected = listOf(savedImage(artworks[0], RED), savedImage(artworks[1], BLUE))
            withTimeout(10_000) { shareProjects(viewModel, artworks, context, SnackbarHostState()) }
            val batch = sends.single()
            assertEquals(Intent.ACTION_SEND_MULTIPLE, batch.action)
            assertEquals("image/png", batch.type)
            assertReadOnly(batch)
            val firstUris = uris(batch)

            @Suppress("DEPRECATION")
            val extras = batch.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(firstUris, extras)
            assertEquals(2, firstUris.toSet().size)
            savedImage(artworks[0], GREEN)
            withTimeout(10_000) { shareProjects(viewModel, listOf(artworks[0]), context, SnackbarHostState()) }

            firstUris.forEachIndexed { index, uri -> assertArrayEquals(expected[index], read(uri)) }
            assertFalse(firstUris.contains(uris(sends.last()).single()))
        }

    @Test
    fun paletteReshareKeepsEachVersionAndTheArtistsFilename() =
        runBlocking(Dispatchers.Main) {
            val palette = Palette(name = "Sunset: warm?", colors = listOf(RED, GREEN))
            val errors = mutableListOf<String>()
            sharePalette(context, palette, errors::add)
            val first = singleUri(sends.single(), "application/octet-stream", "Sunset warm.ase")
            val nextPalette = palette.copy(colors = listOf(BLUE))
            sharePalette(context, nextPalette, errors::add)
            val next = singleUri(sends.last(), "application/octet-stream", "Sunset warm.ase")

            assertTrue(errors.isEmpty())
            assertNotEquals(first, next)
            assertEquals(palette.colors, PaletteCodec.decode(read(first), palette.name)?.colors)
            assertEquals(nextPalette.colors, PaletteCodec.decode(read(next), palette.name)?.colors)
        }

    @Test
    fun brushSharesKeepTheirExistingIndependentContentAndReadOnlyGrants() =
        runBlocking(Dispatchers.Main) {
            val firstBrushes = listOf(SavedBrush("ink", "Ink", BrushParams(size = 17f)))
            assertTrue(shareBrushes(context, "Ink", firstBrushes))
            val first = singleUri(sends.single(), "application/octet-stream", "Ink.artbrush")
            // Even a delayed reader older than the former 24-hour cutoff keeps its exact bytes.
            assertTrue(requireNotNull(sharedFile(first).parentFile).setLastModified(1))
            val nextBrushes = listOf(firstBrushes.single().copy(parameters = BrushParams(size = 37f)))
            assertTrue(shareBrushes(context, "Ink", nextBrushes))
            val next = singleUri(sends.last(), "application/octet-stream", "Ink.artbrush")

            assertNotEquals(first, next)
            assertArrayEquals(BrushFiles.encode(firstBrushes).toByteArray(Charsets.UTF_8), read(first))
            assertArrayEquals(BrushFiles.encode(nextBrushes).toByteArray(Charsets.UTF_8), read(next))
        }

    @Test
    fun everyLiveChooserSurvivesCleanupAndItsReleasedCopyCanBeRemovedThroughSettingsViewModel() =
        runBlocking(Dispatchers.Main) {
            val artwork = project(projectId)
            val original = savedImage(artwork, RED)
            val ordinary = storage.saveExport(projectId, "Ordinary.png", byteArrayOf(6, 7))
            val snapshot = File(storage.exportsDir(projectId), "timelapse_snap_test/frame.png")
            check(requireNotNull(snapshot.parentFile).mkdirs())
            snapshot.writeBytes(byteArrayOf(8, 9))
            onLaunch = { send ->
                val files = uris(send).map(::sharedFile)
                val bytes = files.map(File::readBytes)
                val cleanup = runBlocking { storage.removeSharedCopies() }
                assertTrue(cleanup.remaining.activeCount >= files.size)
                files.forEachIndexed { index, file -> assertArrayEquals(bytes[index], file.readBytes()) }
            }
            withTimeout(10_000) { shareProjects(viewModel, listOf(artwork), context, SnackbarHostState()) }
            sharePalette(context, Palette(name = "Sunset", colors = listOf(RED))) { error(it) }
            assertTrue(shareBrushes(context, "Ink", listOf(SavedBrush("ink", "Ink", BrushParams(size = 17f)))))
            assertEquals(1, storage.sharedCopySummary().count)
            assertEquals(0, storage.sharedCopySummary().activeCount)

            viewModel.removeSharedCopies()
            withTimeout(10_000) { viewModel.sharedCopyStorage.filter { !it.busy && it.summary?.count == 0 }.first() }

            sends.flatMap(::uris).forEach { assertFalse(sharedFile(it).exists()) }
            assertArrayEquals(original, storage.flattenedFile(projectId).readBytes())
            assertArrayEquals(byteArrayOf(6, 7), ordinary.readBytes())
            assertArrayEquals(byteArrayOf(8, 9), snapshot.readBytes())
        }

    @Test
    fun failedBrushAndPaletteChoosersRemoveTheirUnpublishedCopiesAndKeepEarlierLinks() =
        runBlocking(Dispatchers.Main) {
            val brushes = listOf(SavedBrush("ink", "Ink", BrushParams(size = 17f)))
            assertTrue(shareBrushes(context, "Ink", brushes))
            val previous = uris(sends.single()).single()
            val before = read(previous)
            onLaunch = { throw ActivityNotFoundException("No receiver is available") }
            assertFalse(shareBrushes(context, "Ink", brushes))
            val errors = mutableListOf<String>()
            sharePalette(context, Palette(name = "Sunset", colors = listOf(RED)), errors::add)

            assertEquals(1, errors.size)
            assertArrayEquals(before, read(previous))
            sends.drop(1).flatMap(::uris).forEach { assertFalse(requireNotNull(sharedFile(it).parentFile).exists()) }
            assertEquals(0, storage.sharedCopySummary().activeCount)
        }

    private suspend fun savedImage(
        artwork: Project,
        color: Int,
    ): ByteArray {
        if (database.projectDao().getProjectById(artwork.id) == null) {
            database.projectDao().insertProject(artwork.toEntity())
        }
        return BitmapPixelBridge.toPngBytes(PixelBuffer.filled(4, 4, color)).also { storage.saveFlattened(artwork.id, it) }
    }

    private fun project(id: Long) =
        Project(
            id = id,
            name = "Ink study",
            filePath = "",
            thumbnailPath = null,
            width = 4,
            height = 4,
            dpi = 300,
            createdAt = 0,
            modifiedAt = 0,
        )

    private fun uris(send: Intent): List<Uri> {
        val clip = requireNotNull(send.clipData)
        return (0 until clip.itemCount).map { clip.getItemAt(it).uri }
    }

    private fun singleUri(
        send: Intent,
        mimeType: String,
        fileName: String,
    ): Uri {
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(mimeType, send.type)
        assertReadOnly(send)
        val uri = uris(send).single()

        @Suppress("DEPRECATION")
        val extra = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertEquals(uri, extra)
        requireNotNull(context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(fileName, cursor.getString(0))
        }
        return uri
    }

    private fun assertReadOnly(send: Intent) {
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags)
        uris(send).forEach { uri ->
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            assertTrue(requireNotNull(uri.path).startsWith("/exports/"))
        }
    }

    private fun assertPrivateDocumentsRemainOutsideProvider(id: Long) {
        val document = storage.documentFile(id).apply { writeText("Private editable artwork") }
        val failure = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", document) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        val provider = requireNotNull(context.packageManager.resolveContentProvider("${context.packageName}.fileprovider", 0))
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }

    private fun read(uri: Uri): ByteArray = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }

    private fun sharedFile(uri: Uri): File = File(context.filesDir, requireNotNull(uri.path).removePrefix("/"))

    private companion object {
        val RED = 0xFFFF0000.toInt()
        val GREEN = 0xFF00FF00.toInt()
        val BLUE = 0xFF0000FF.toInt()
    }
}
