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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** Fail after files and grains are installed but before the real Room transaction can commit. */
@RunWith(AndroidJUnit4::class)
class ProjectGrainImportRollbackDeviceTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var grainDir: File
    private lateinit var storage: ProjectStorage
    private lateinit var database: ArtFlowDatabase
    private val ids = mutableListOf<String>()

    @Before
    fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = File(application.cacheDir, "grain-rollback-${System.nanoTime()}").apply { check(mkdirs()) }
        context =
            object : ContextWrapper(application) {
                override fun getFilesDir(): File = File(directory, "target").apply { mkdirs() }

                override fun getCacheDir(): File = File(directory, "staging").apply { mkdirs() }
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

    @Test
    fun failedByteArrayImportPublicationRollsBackGrainsAndStagedInput() = runBlocking { verifyRollback(cancel = false, fromMemory = true) }

    @Test
    fun cancelledByteArrayImportPublicationRollsBackGrainsAndStagedInput() =
        runBlocking { verifyRollback(cancel = true, fromMemory = true) }

    @Test
    fun failedImportPreservesItsFailureAndAssetsWhenCommitStatusLookupAlsoFails() =
        runBlocking { verifyUnavailableCommitStatus(cancel = false) }

    @Test
    fun cancelledImportPreservesItsCancellationAndAssetsWhenCommitStatusLookupAlsoFails() =
        runBlocking { verifyUnavailableCommitStatus(cancel = true) }

    @Test
    fun refusedImportPreservesAnUnownedDestinationCreatedAfterItsFirstCheck() = runBlocking { verifyDestinationRace(duplicate = false) }

    @Test
    fun refusedDuplicationPreservesAnUnownedDestinationCreatedAfterItsFirstCheck() = runBlocking { verifyDestinationRace(duplicate = true) }

    private suspend fun verifyDestinationRace(duplicate: Boolean) {
        val texture = newId()
        val incoming = tile(61)
        val source = packageFile(mapOf(texture to incoming))
        val sourceBytes = source.readBytes()
        val delegate = database.projectDao()
        val ordinary = ProjectRepositoryImpl(delegate, storage, database)
        val sourceId = if (duplicate) ordinary.importPackageFile(source).projectId else null
        val originalDocument = sourceId?.let { storage.documentFile(it).readBytes() }
        val reserved = AtomicLong()
        val armed = AtomicBoolean()
        val otherBytes = byteArrayOf(10, 20, 30)
        val dispatcher =
            object : CoroutineDispatcher() {
                override fun dispatch(
                    context: CoroutineContext,
                    block: Runnable,
                ) {
                    if (armed.compareAndSet(true, false)) {
                        // Storage's IO entry occurs after the repository's synchronous absent-path check.
                        val other = File(directory, "target/projects/${reserved.get()}/independently-created-artwork")
                        check(other.parentFile!!.mkdirs())
                        other.writeBytes(otherBytes)
                    }
                    Dispatchers.IO.dispatch(context, block)
                }
            }
        val deleted = mutableListOf<Long>()
        val reserving =
            object : ProjectDao by delegate {
                override suspend fun insertProject(project: ProjectEntity): Long =
                    delegate.insertProject(project).also {
                        reserved.set(it)
                        armed.set(true)
                    }

                override suspend fun deleteProjectById(projectId: Long) {
                    deleted += projectId
                    delegate.deleteProjectById(projectId)
                }
            }
        val raced = ProjectRepositoryImpl(reserving, ProjectStorage(context, dispatcher), database)
        val failure =
            runCatching {
                if (sourceId == null) raced.importPackageFile(source).projectId else raced.duplicateProject(sourceId)
            }.exceptionOrNull()
        val failedId = reserved.get()
        assertTrue(failedId > 0)
        assertFalse(armed.get())
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("destination already contains project data"))
        assertNull(delegate.getProjectById(failedId))
        assertEquals(if (duplicate) 1 else 0, delegate.getAllProjects().first().size)
        assertTrue(deleted.isEmpty())
        val other = File(directory, "target/projects/$failedId/independently-created-artwork")
        assertTrue(other.isFile)
        assertArrayEquals(otherBytes, other.readBytes())
        assertArrayEquals(sourceBytes, source.readBytes())
        if (sourceId == null) {
            assertFalse(grainFile(texture).exists())
            assertNull(CustomGrains.get(texture))
        } else {
            assertArrayEquals(originalDocument, storage.documentFile(sourceId).readBytes())
            assertArrayEquals(incoming.values, grainFile(texture).readBytes())
        }

        val retried = if (sourceId == null) ordinary.importPackageFile(source).projectId else ordinary.duplicateProject(sourceId)
        assertTrue(retried > failedId)
        assertEquals(if (duplicate) 2 else 1, delegate.getAllProjects().first().size)
        assertNotNull(storage.loadDocument(retried))
        assertArrayEquals(otherBytes, other.readBytes())
        assertArrayEquals(incoming.values, grainFile(texture).readBytes())
    }

    private suspend fun verifyUnavailableCommitStatus(cancel: Boolean) {
        val existing = newId()
        val added = newId()
        val originalTile = tile(27)
        val incoming = tile(81)
        GrainStorage.save(grainDir, existing, originalTile)
        val source = packageFile(mapOf(existing to originalTile, added to incoming))
        val original = if (cancel) CancellationException("Publication was cancelled") else SQLiteException("Database publication failed")
        val lookup = SQLiteException("Commit status is unavailable")
        val delegate = database.projectDao()
        var failedId = 0L
        val deletedIds = mutableListOf<Long>()
        val failingDao =
            object : ProjectDao by delegate {
                override suspend fun updateProject(project: ProjectEntity) {
                    failedId = project.id
                    assertTrue(grainFile(added).isFile)
                    assertNotNull(storage.loadDocument(failedId))
                    throw original
                }

                override suspend fun getProjectById(projectId: Long): ProjectEntity? {
                    if (projectId == failedId) throw lookup
                    return delegate.getProjectById(projectId)
                }

                override suspend fun deleteProjectById(projectId: Long) {
                    deletedIds += projectId
                    delegate.deleteProjectById(projectId)
                }
            }
        val failure = runCatching { ProjectRepositoryImpl(failingDao, storage, database).importPackageFile(source) }.exceptionOrNull()

        assertEquals(original.javaClass, failure?.javaClass)
        assertEquals(original.message, failure?.message)
        val chain = generateSequence(failure) { it.cause }.toList()
        assertTrue(chain.any { it === original })
        assertTrue(chain.flatMap { it.suppressed.toList() }.any { it === lookup || it.cause === lookup })
        assertTrue(failedId > 0)
        assertTrue(delegate.getAllProjects().first().isEmpty())
        assertTrue(deletedIds.isEmpty())
        // The repository could not observe rollback, so it must preserve possibly committed assets.
        assertNotNull(storage.loadDocument(failedId))
        assertArrayEquals(incoming.values, grainFile(added).readBytes())
        assertArrayEquals(incoming.values, requireNotNull(CustomGrains.get(added)).values)
        assertArrayEquals(originalTile.values, grainFile(existing).readBytes())
        assertSame(originalTile, CustomGrains.get(existing))

        val retried = ProjectRepositoryImpl(delegate, storage, database).importPackageFile(source).projectId
        assertTrue(retried > failedId)
        assertEquals(1, delegate.getAllProjects().first().size)
        assertNotNull(storage.loadDocument(failedId))
        assertNotNull(storage.loadDocument(retried))
        assertArrayEquals(incoming.values, grainFile(added).readBytes())
        assertSame(originalTile, CustomGrains.get(existing))
    }

    private suspend fun verifyRollback(
        cancel: Boolean,
        fromMemory: Boolean = false,
    ) {
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
        val failure = runCatching { importSource(repository, source, fromMemory) }.exceptionOrNull()
        assertTrue(File(directory, "staging").listFiles().orEmpty().isEmpty())
        assertTrue(if (cancel) failure is CancellationException else failure is SQLiteException)
        assertTrue(failedId > 0)
        assertTrue(delegate.getAllProjects().first().isEmpty())
        assertFalse(storage.projectDirectoryExists(failedId))
        assertFalse(grainFile(added).exists())
        assertNull(CustomGrains.get(added))
        assertArrayEquals(original.values, grainFile(existing).readBytes())
        assertSame(original, CustomGrains.get(existing))

        val retried = importSource(ProjectRepositoryImpl(delegate, storage, database), source, fromMemory)
        assertEquals(1, delegate.getAllProjects().first().size)
        assertNotNull(storage.loadDocument(retried))
        assertTrue(File(directory, "staging").listFiles().orEmpty().isEmpty())
        assertArrayEquals(incoming.values, grainFile(added).readBytes())
        assertArrayEquals(incoming.values, requireNotNull(CustomGrains.get(added)).values)
        assertSame(original, CustomGrains.get(existing))
    }

    private suspend fun importSource(
        repository: ProjectRepositoryImpl,
        source: File,
        fromMemory: Boolean,
    ): Long = if (fromMemory) repository.importPackage(source.readBytes()) else repository.importPackageFile(source).projectId

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
