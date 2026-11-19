package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.CanvasDocumentCodec
import com.artflow.studio.data.local.GrainStorage
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import com.artflow.studio.data.local.dao.ProjectDao
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.entity.ProjectEntity
import com.artflow.studio.data.repository.ProjectRepositoryImpl
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Fail after files and grains are installed but before the real Room transaction can commit. */
@RunWith(AndroidJUnit4::class)
class ProjectGrainImportRollbackDeviceTest {
    private lateinit var directory: File
    private lateinit var grainDir: File
    private lateinit var storage: ProjectStorage
    private lateinit var database: ArtFlowDatabase
    private val ids = mutableListOf<String>()

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = File(application.cacheDir, "grain-rollback-${System.nanoTime()}").apply { check(mkdirs()) }
        val context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = File(directory, "target").apply { mkdirs() }
            }
        grainDir = GrainStorage.directory(context.filesDir)
        storage = ProjectStorage(context)
        database = Room.inMemoryDatabaseBuilder(application, ArtFlowDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
        ids.forEach { id -> CustomGrains.get(id)?.let { CustomGrains.removeIfSame(id, it) } }
        StorageFileTree.delete(directory)
    }

    @Test
    fun failedDatabasePublicationRollsBackNewGrainsAndCanBeRetried() = runBlocking { verifyRollback(cancel = false) }

    @Test
    fun cancelledDatabasePublicationRollsBackNewGrainsAndCanBeRetried() = runBlocking { verifyRollback(cancel = true) }

    private suspend fun verifyRollback(cancel: Boolean) {
        val existing = newId()
        val added = newId()
        val original = tile(27)
        val incoming = tile(81)
        GrainStorage.save(grainDir, existing, original)
        val source = packageFile(mapOf(existing to original, added to incoming))
        val delegate = database.projectDao()
        var failedId = 0L
        val failingDao =
            object : ProjectDao by delegate {
                override suspend fun updateProject(project: ProjectEntity) {
                    failedId = project.id
                    assertTrue(grainFile(added).isFile)
                    assertNotNull(CustomGrains.get(added))
                    if (cancel) throw CancellationException("Navigation cancelled publication")
                    throw SQLiteException("Database publication failed")
                }
            }
        val repository = ProjectRepositoryImpl(failingDao, storage, database)
        val failure = runCatching { repository.importPackageFile(source) }.exceptionOrNull()
        assertTrue(if (cancel) failure is CancellationException else failure is SQLiteException)
        assertTrue(failedId > 0)
        assertTrue(delegate.getAllProjects().first().isEmpty())
        assertFalse(storage.projectDirectoryExists(failedId))
        assertFalse(grainFile(added).exists())
        assertNull(CustomGrains.get(added))
        assertArrayEquals(original.values, grainFile(existing).readBytes())
        assertSame(original, CustomGrains.get(existing))

        val retried = ProjectRepositoryImpl(delegate, storage, database).importPackageFile(source)
        assertEquals(1, delegate.getAllProjects().first().size)
        assertNotNull(storage.loadDocument(retried.projectId))
        assertArrayEquals(incoming.values, grainFile(added).readBytes())
        assertArrayEquals(incoming.values, requireNotNull(CustomGrains.get(added)).values)
        assertSame(original, CustomGrains.get(existing))
    }

    private fun packageFile(grains: Map<String, CustomGrains.Tile>): File {
        val document =
            CanvasDocument(
                width = 64,
                height = 64,
                dpi = 72,
                backgroundColor = -1,
                activeLayerId = 1,
                nextLayerId = 2,
                layers = listOf(Layer(1, "Ink", 0)),
            )
        val files =
            mapOf(ArtflowPackage.DOCUMENT to CanvasDocumentCodec.encode(document)) +
                grains.map { (id, tile) -> ArtflowPackage.grainEntryPath(id) to tile.values }
        return File(directory, "source.artflow").also { it.writeBytes(ArtflowPackage.write("Portable grains", files)) }
    }

    private fun newId(): String = ("custom-" + UUID.randomUUID().toString().replace("-", "")).also(ids::add)

    private fun grainFile(id: String): File = requireNotNull(GrainStorage.fileFor(grainDir, id))

    private fun tile(value: Int): CustomGrains.Tile =
        CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { value.toByte() })
}
