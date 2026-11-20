package com.artflow.studio.data.repository

import android.content.Context
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.CanvasDocumentCodec
import com.artflow.studio.data.local.GrainStorage
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.dao.ProjectDao
import com.artflow.studio.data.local.database.ArtFlowDatabase
import com.artflow.studio.data.local.entity.ProjectEntity
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.any
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor

/** Public repository calls with real files and grain ownership; actual Room rollback is covered on device. */
class ProjectPublicationCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var storage: ProjectStorage
    private lateinit var dao: ProjectDao
    private lateinit var repository: ProjectRepositoryImpl
    private val grainIds = mutableListOf<String>()
    private val rasterBytes = byteArrayOf(4, 8, 15, 16, 23, 42)
    private val sourceRow = ProjectEntity(3, "Source", "", null, 64, 64, 72, 0, 0)

    @Before
    fun setUp() =
        runBlocking {
            filesDir = temporary.newFolder("files")
            val context = mock(Context::class.java)
            `when`(context.filesDir).thenReturn(filesDir)
            `when`(context.cacheDir).thenReturn(temporary.newFolder("cache"))
            storage = ProjectStorage(context)
            dao = mock(ProjectDao::class.java)
            val database = mock(ArtFlowDatabase::class.java)
            `when`(database.transactionExecutor).thenReturn(Executor { it.run() })
            `when`(database.suspendingTransactionContext).thenReturn(ThreadLocal())
            `when`(dao.insertProject(any(ProjectEntity::class.java) ?: sourceRow)).thenReturn(21L)
            `when`(dao.getProjectById(sourceRow.id)).thenReturn(sourceRow)
            repository = ProjectRepositoryImpl(dao, storage, database)
        }

    @After
    fun cleanUp() {
        grainIds.forEach { id -> CustomGrains.get(id)?.let { CustomGrains.removeIfSame(id, it) } }
    }

    @Test
    fun failedImportKeepsItsOriginalFailureAndAssetsWhenCommitStatusCannotBeRead() =
        runBlocking { verifyFailure(cancel = false, duplicate = false) }

    @Test
    fun cancelledImportKeepsItsCancellationAndAssetsWhenCommitStatusCannotBeRead() =
        runBlocking { verifyFailure(cancel = true, duplicate = false) }

    @Test
    fun failedDuplicateKeepsItsOriginalFailureAndBothArtworksWhenCommitStatusCannotBeRead() =
        runBlocking { verifyFailure(cancel = false, duplicate = true) }

    @Test
    fun cancelledDuplicateKeepsItsCancellationAndBothArtworksWhenCommitStatusCannotBeRead() =
        runBlocking { verifyFailure(cancel = true, duplicate = true) }

    @Test
    fun cleanupFailureAfterSuccessfulWorkRemainsVisibleWithoutRemovingPublishedData() =
        runBlocking {
            val published = File(filesDir, "published")
            val cleanupFailure = IOException("Cannot release ownership")
            val failure =
                runCatching {
                    completeWithCleanup(
                        action = { published.writeBytes(rasterBytes) },
                        cleanup = { throw cleanupFailure },
                    )
                }.exceptionOrNull()
            assertSame(cleanupFailure, failure)
            assertArrayEquals(rasterBytes, published.readBytes())
        }

    @Test
    fun nestedCleanupErrorsRemainAttachedToTheOriginalFatalFailure() =
        runBlocking {
            val original = AssertionError("Publication invariant failed")
            val lookup = IOException("Cannot determine commit")
            val release = IOException("Cannot finish asset ownership")
            val failure =
                runCatching {
                    completeWithCleanup(
                        action = { throw original },
                        cleanup = {
                            completeWithCleanup(action = { throw lookup }, cleanup = { throw release })
                        },
                    )
                }.exceptionOrNull()
            assertSame(original, failure)
            assertSame(lookup, original.suppressed.single())
            assertSame(release, lookup.suppressed.single())
        }

    @Test
    fun rethrowingTheSameFailureDuringCleanupDoesNotReplaceItWithSelfSuppression() =
        runBlocking {
            val original = IOException("Storage is unavailable")
            val failure =
                runCatching {
                    completeWithCleanup(action = { throw original }, cleanup = { throw original })
                }.exceptionOrNull()
            assertSame(original, failure)
            assertTrue(original.suppressed.isEmpty())
        }

    private suspend fun verifyFailure(
        cancel: Boolean,
        duplicate: Boolean,
    ) {
        val original = if (cancel) CancellationException("Publication was cancelled") else IOException("Database publication failed")
        val lookup = IOException("Commit status is unavailable")
        doAnswer { throw original }.`when`(dao).updateProject(any(ProjectEntity::class.java) ?: sourceRow)
        doAnswer { throw lookup }.`when`(dao).getProjectById(21L)
        val raster = storage.writeRaster(sourceRow.id, 1L, 1, rasterBytes)
        val document = document(raster)
        storage.saveDocument(sourceRow.id, document)
        val existing = newGrainId()
        val added = newGrainId()
        val oldTile = tile(31)
        val addedTile = tile(77)
        GrainStorage.save(GrainStorage.directory(filesDir), existing, oldTile)
        val source = packageFile(document, raster, mapOf(existing to oldTile, added to addedTile))

        val failure =
            runCatching {
                if (duplicate) repository.duplicateProject(sourceRow.id) else repository.importPackageFile(source)
            }.exceptionOrNull()

        assertEquals(original.javaClass, failure?.javaClass)
        assertEquals(original.message, failure?.message)
        // Coroutine debug recovery can copy a failure and keep the thrown instance in its cause chain.
        val chain = generateSequence(failure) { it.cause }.toList()
        assertTrue(chain.any { it === original })
        assertTrue(chain.flatMap { it.suppressed.toList() }.any { it === lookup || it.cause === lookup })
        assertTrue(storage.projectDirectoryExists(21L))
        assertNotNull(storage.loadDocument(21L))
        assertArrayEquals(rasterBytes, storage.readRaster(21L, raster))
        assertArrayEquals(rasterBytes, storage.readRaster(sourceRow.id, raster))
        assertArrayEquals(oldTile.values, grainFile(existing).readBytes())
        assertSame(oldTile, CustomGrains.get(existing))
        if (duplicate) {
            assertFalse(grainFile(added).exists())
        } else {
            assertArrayEquals(addedTile.values, grainFile(added).readBytes())
            assertArrayEquals(addedTile.values, requireNotNull(CustomGrains.get(added)).values)
        }
        verify(dao, never()).deleteProjectById(anyLong())
        verify(dao, never()).deleteProject(any(ProjectEntity::class.java) ?: sourceRow)
    }

    private fun document(raster: String): CanvasDocument =
        CanvasDocument(
            width = 64,
            height = 64,
            dpi = 72,
            backgroundColor = -1,
            activeLayerId = 1,
            nextLayerId = 2,
            layers = listOf(Layer(1, "Ink", 0, rasterFile = raster)),
        )

    private fun packageFile(
        document: CanvasDocument,
        raster: String,
        grains: Map<String, CustomGrains.Tile>,
    ): File {
        val files =
            mapOf(ArtflowPackage.DOCUMENT to CanvasDocumentCodec.encode(document), raster to rasterBytes) +
                grains.map { (id, tile) -> ArtflowPackage.grainEntryPath(id) to tile.values }
        return temporary.newFile("source.artflow").also { it.writeBytes(ArtflowPackage.write("Source", files)) }
    }

    private fun newGrainId(): String = ("custom-" + UUID.randomUUID().toString().replace("-", "")).also(grainIds::add)

    private fun grainFile(id: String): File = requireNotNull(GrainStorage.fileFor(GrainStorage.directory(filesDir), id))

    private fun tile(value: Int): CustomGrains.Tile =
        CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { value.toByte() })
}
