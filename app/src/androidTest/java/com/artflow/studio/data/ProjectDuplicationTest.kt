package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.local.dao.ProjectDao
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.entity.ProjectEntity
import com.artflow.studio.data.repository.ProjectRepositoryImpl
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectDuplicationTest {
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage
    private lateinit var database: ArtFlowDatabase
    private lateinit var projects: ProjectRepositoryImpl
    private lateinit var canvas: CanvasRepositoryImpl

    @Before
    fun setup() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = File(application.cacheDir, "copy-test-${System.nanoTime()}").apply { check(mkdirs()) }
        val context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = directory
            }
        storage = ProjectStorage(context)
        database = Room.inMemoryDatabaseBuilder(application, ArtFlowDatabase::class.java).build()
        projects = ProjectRepositoryImpl(database.projectDao(), storage, database)
        canvas = CanvasRepositoryImpl(storage)
    }

    @After
    fun cleanup() {
        runBlocking(Dispatchers.Main) { canvas.dispose() }
        database.close()
        StorageFileTree.delete(directory)
    }

    @Test
    fun copyRetainsIndependentSavedRecoveryMasksAndFrames() =
        runBlocking(Dispatchers.Main) {
            val original = create()
            canvas.loadOrCreate(original, SIZE, SIZE, 144)
            paint(Color.RED)
            canvas.addLayerMask(canvas.getActiveLayerId())
            canvas.paintLayerMask(canvas.getActiveLayerId(), 12f, 12f, 6f, reveal = false)
            val savedPixels = canvas.compositeBuffer()!!.pixels.copyOf()
            canvas.saveCanvas(original)
            canvas.addFrame(duplicateCurrent = false)
            paint(Color.BLUE)
            canvas.setFrameDuration(1, 370)
            canvas.autosave(original)
            storage.autosaveFile(original).setLastModified(storage.documentFile(original).lastModified() + 1000)
            val copyId = projects.duplicateProject(original)
            assertNotEquals(original, copyId)
            assertTrue(storage.hasUnsavedRecovery(copyId))
            storage.deleteProjectFiles(original)
            projects.deleteProjectById(original)
            canvas.loadCanvas(copyId)
            assertArrayEquals(savedPixels, canvas.compositeBuffer()!!.pixels)
            assertEquals(1, canvas.frames().size)
            canvas.recoverAutosave(copyId)
            assertEquals(2, canvas.frames().size)
            assertEquals(370, canvas.frames()[1].durationMs)
            assertEquals(Color.BLUE, canvas.compositeBuffer()!!.getSafe(0, 0))
            assertTrue(projects.getProjectById(copyId)!!.filePath.startsWith(storage.projectDir(copyId).absolutePath))
        }

    @Test
    fun missingRasterRollsBackTheRowAndOwnedDirectory() =
        runBlocking(Dispatchers.Main) {
            val id = create()
            canvas.loadOrCreate(id, SIZE, SIZE, 144)
            paint(Color.RED)
            canvas.saveCanvas(id)
            val file =
                storage
                    .loadDocument(id)!!
                    .resolvedFrames()
                    .first()
                    .layers
                    .first()
                    .rasterFile!!
            assertTrue(storage.resolve(id, file).delete())
            val failed = runCatching { projects.duplicateProject(id) }.isFailure
            assertTrue("A corrupt original must not produce a blank successful copy", failed)
            assertEquals(1, projects.getAllProjects().first().size)
            assertFalse(storage.projectDirectoryExists(id + 1))
        }

    @Test
    fun copySkipsOrphanedStorageRatherThanOverwritingIt() =
        runBlocking(Dispatchers.Main) {
            val id = create()
            val orphan = File(storage.projectDir(id + 1), "do-not-delete.bin")
            orphan.writeText("preserve")
            val copy = projects.duplicateProject(id)
            assertTrue(copy > id + 1)
            assertEquals("preserve", orphan.readText())
            assertNotNull(projects.getProjectById(copy))
            canvas.loadOrCreate(copy, SIZE, SIZE, 144)
            assertEquals(SIZE, canvas.compositeBuffer()!!.width)
        }

    @Test
    fun unknownProjectCannotBeDuplicated() =
        runBlocking {
            assertTrue(runCatching { projects.duplicateProject(999) }.isFailure)
            assertTrue(projects.getAllProjects().first().isEmpty())
        }

    @Test
    fun newProjectsSkipOrphanedStorageEvenWithAnEmptyDatabase() =
        runBlocking(Dispatchers.Main) {
            val orphan = File(storage.projectDir(1), "do-not-delete.bin")
            orphan.writeText("preserve")
            val created = create()
            assertTrue(created > 1)
            assertEquals("preserve", orphan.readText())
            assertFalse(storage.projectDirectoryExists(created))
            canvas.loadOrCreate(created, SIZE, SIZE, 144)
            assertEquals(SIZE, canvas.compositeBuffer()!!.width)
            assertEquals("preserve", orphan.readText())
        }

    @Test
    fun newProjectsSkipOrphansAboveExistingRows() =
        runBlocking {
            val original = create()
            val orphan = File(storage.projectDir(original + 10), "preserve.bin")
            orphan.writeText("saved artwork")
            val created = create()
            assertTrue(created > original + 10)
            assertEquals("saved artwork", orphan.readText())
            assertEquals(2, projects.getAllProjects().first().size)
        }

    @Test
    fun concurrentCreatesNeverReplaceOneAnother() =
        runBlocking {
            val ids = (1..16).map { async { create() } }.awaitAll()
            assertEquals(16, ids.distinct().size)
            assertEquals(16, projects.getAllProjects().first().size)
        }

    @Test
    fun deletedProjectIdentifiersAreNeverReused() =
        runBlocking {
            val id = create()
            projects.deleteProjectById(id)
            assertFalse(storage.projectDirectoryExists(id))
            assertTrue(create() > id)
        }

    @Test
    fun explicitUnknownIdCannotClaimOrphanedArtwork() =
        runBlocking {
            val orphan = File(storage.projectDir(17), "preserve.bin")
            orphan.writeText("saved artwork")
            assertTrue(runCatching { projects.saveProject(fixture().copy(id = 17)) }.isFailure)
            assertEquals("saved artwork", orphan.readText())
            assertTrue(projects.getAllProjects().first().isEmpty())
        }

    @Test
    fun savingKnownIdUpdatesMetadataWithoutReplacingItsIdentity() =
        runBlocking {
            val id = create()
            val before = projects.getProjectById(id)!!
            val file = File(storage.projectDir(id), "preserve.bin").apply { writeText("saved artwork") }
            assertEquals(id, projects.saveProject(before.copy(name = "Renamed")))
            assertEquals("Renamed", projects.getProjectById(id)!!.name)
            assertEquals("saved artwork", file.readText())
            assertEquals(1, projects.getAllProjects().first().size)
        }

    @Test
    fun renameChangesOnlyTheNameAndNeverMovesModificationTimeBackward() =
        runBlocking {
            val id = create()
            val latest =
                projects.getProjectById(id)!!.copy(
                    width = 64,
                    height = 48,
                    dpi = 300,
                    layerCount = 3,
                    thumbnailPath = "new-thumbnail.png",
                    isFavorite = true,
                    stack = "Finished",
                    modifiedAt = 50,
                )
            projects.saveProject(latest)
            projects.renameProject(id, "Renamed", 20)
            assertEquals(latest.copy(name = "Renamed"), projects.getProjectById(id))
        }

    @Test
    fun savedMetadataPreservesTheLatestGalleryOrganizationAndIdentity() =
        runBlocking {
            val id = create()
            val stale = projects.getProjectById(id)!!
            projects.renameProject(id, "Artist's new name", 50)
            projects.toggleFavorite(id, true)
            projects.setStack(listOf(id), "Finished")
            val latest = projects.getProjectById(id)!!
            val saved = stale.copy(width = 64, height = 48, dpi = 300, layerCount = 3, thumbnailPath = "saved.png", modifiedAt = 20)
            val actual = projects.updateSavedArtwork(saved)
            val expected = latest.copy(width = 64, height = 48, dpi = 300, layerCount = 3, thumbnailPath = "saved.png")
            assertEquals(expected, actual)
            assertEquals(expected, projects.getProjectById(id))
        }

    @Test
    fun aRenameQueuedDuringASaveCannotBeOverwrittenByTheSaveTransaction() =
        runBlocking {
            val id = create()
            val stale = projects.getProjectById(id)!!
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val delegate = database.projectDao()
            val pausedDao =
                object : ProjectDao by delegate {
                    override suspend fun getProjectById(projectId: Long): ProjectEntity? {
                        val current = delegate.getProjectById(projectId)
                        entered.complete(Unit)
                        release.await()
                        return current
                    }
                }
            val saving = ProjectRepositoryImpl(pausedDao, storage, database)
            val save = async(Dispatchers.Default) { saving.updateSavedArtwork(stale.copy(thumbnailPath = "saved.png", modifiedAt = 10)) }
            entered.await()
            val rename = async(start = CoroutineStart.UNDISPATCHED) { projects.renameProject(id, "Renamed concurrently", 20) }
            release.complete(Unit)
            save.await()
            rename.await()
            assertEquals(
                stale.copy(name = "Renamed concurrently", thumbnailPath = "saved.png", modifiedAt = 20),
                projects.getProjectById(id),
            )
        }

    @Test
    fun delayedMetadataOperationsCannotRecreateADeletedArtworkOrChangeItsFiles() =
        runBlocking {
            val id = create()
            val stale = projects.getProjectById(id)!!
            val marker = File(storage.projectDir(id), "preserve.bin").apply { writeText("retained recovery") }
            projects.deleteProjectById(id)
            val rename = runCatching { projects.renameProject(id, "Too late", 20) }.exceptionOrNull()
            val save = runCatching { projects.updateSavedArtwork(stale.copy(thumbnailPath = "too-late.png")) }.exceptionOrNull()
            assertEquals("This artwork no longer exists", rename?.message)
            assertEquals("This artwork no longer exists", save?.message)
            assertTrue(projects.getAllProjects().first().isEmpty())
            assertEquals("retained recovery", marker.readText())
        }

    @Test
    fun invalidCreationInputsDoNotLeaveGalleryRows() =
        runBlocking {
            val invalid =
                listOf(
                    fixture().copy(id = -1),
                    fixture().copy(width = 0),
                    fixture().copy(height = Int.MAX_VALUE),
                    fixture().copy(dpi = 0),
                )
            invalid.forEach { assertTrue(runCatching { projects.saveProject(it) }.isFailure) }
            assertTrue(projects.getAllProjects().first().isEmpty())
        }

    @Test
    fun exhaustedIdentifiersFailWithoutWrappingOrDeletingFiles() =
        runBlocking {
            val orphan = File(storage.projectDir(Long.MAX_VALUE), "preserve.bin").apply { writeText("saved artwork") }
            assertTrue(runCatching { create() }.isFailure)
            assertTrue(projects.getAllProjects().first().isEmpty())
            assertEquals("saved artwork", orphan.readText())
        }

    private fun fixture(): Project =
        Project(
            name = "Original",
            filePath = "",
            thumbnailPath = null,
            width = SIZE,
            height = SIZE,
            dpi = 144,
            createdAt = 1,
            modifiedAt = 1,
        )

    private suspend fun create(): Long = projects.saveProject(fixture())

    private suspend fun paint(color: Int) {
        assertTrue(canvas.setLayerPixels(canvas.getActiveLayerId(), PixelBuffer.filled(SIZE, SIZE, color), "Fixture"))
    }

    companion object {
        private const val SIZE = 32
    }
}
