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
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.repository.ProjectRepositoryImpl
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.Project
import kotlinx.coroutines.Dispatchers
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

    private suspend fun create(): Long =
        projects.saveProject(
            Project(
                name = "Original",
                filePath = "",
                thumbnailPath = null,
                width = SIZE,
                height = SIZE,
                dpi = 144,
                createdAt = 1,
                modifiedAt = 1,
            ),
        )

    private suspend fun paint(color: Int) {
        assertTrue(canvas.setLayerPixels(canvas.getActiveLayerId(), PixelBuffer.filled(SIZE, SIZE, color), "Fixture"))
    }

    companion object {
        private const val SIZE = 32
    }
}
