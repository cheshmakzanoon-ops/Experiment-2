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
import com.artflow.studio.data.local.jvmGrainImport
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/** Independent public-boundary probes; Room itself is mocked, files and asset ownership are real. */
class ProjectDestinationOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var files: File
    private lateinit var storage: ProjectStorage
    private lateinit var dao: ProjectDao
    private lateinit var database: ArtFlowDatabase
    private lateinit var repository: ProjectRepositoryImpl
    private lateinit var source: File
    private lateinit var grain: String
    private lateinit var raster: String
    private val pixels = byteArrayOf(2, 7, 1, 8, 2, 8)
    private val row = ProjectEntity(3, "Original", "", null, 64, 64, 72, 0, 0)
    private val tile = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { 71 })

    @Before fun setup() =
        runBlocking {
            files = temporary.newFolder("files")
            val context = mock(Context::class.java)
            `when`(context.filesDir).thenReturn(files)
            `when`(context.cacheDir).thenReturn(temporary.newFolder("cache"))
            storage = spy(ProjectStorage(context))
            doAnswer { jvmGrainImport() }.`when`(storage).beginGrainImport()
            dao = mock(ProjectDao::class.java)
            database = mock(ArtFlowDatabase::class.java)
            `when`(database.transactionExecutor).thenReturn(Executor { it.run() })
            `when`(database.suspendingTransactionContext).thenReturn(ThreadLocal())
            `when`(dao.insertProject(any(ProjectEntity::class.java) ?: row)).thenReturn(21L)
            `when`(dao.getProjectById(3L)).thenReturn(row)
            repository = ProjectRepositoryImpl(dao, storage, database)
            raster = storage.writeRaster(3L, 1L, 1, pixels)
            val document =
                CanvasDocument(
                    width = 64,
                    height = 64,
                    dpi = 72,
                    backgroundColor = -1,
                    activeLayerId = 1,
                    nextLayerId = 2,
                    layers = listOf(Layer(1, "Ink", 0, rasterFile = raster)),
                )
            storage.saveDocument(3L, document)
            grain = "custom-" + UUID.randomUUID().toString().replace("-", "")
            source = temporary.newFile("input.artflow")
            source.writeBytes(
                ArtflowPackage.write(
                    "Original",
                    mapOf(
                        ArtflowPackage.DOCUMENT to CanvasDocumentCodec.encode(document),
                        raster to pixels,
                        ArtflowPackage.grainEntryPath(grain) to tile.values,
                    ),
                ),
            )
        }

    @After fun cleanup() {
        CustomGrains.get(grain)?.let { CustomGrains.removeIfSame(grain, it) }
    }

    @Test fun importRetainsACommittedRowAfterRealCoroutineCancellationAtTransactionEnd() =
        runBlocking {
            cancelAfterCommit(duplicate = false)
        }

    @Test fun duplicateRetainsACommittedRowAfterRealCoroutineCancellationAtTransactionEnd() =
        runBlocking {
            cancelAfterCommit(duplicate = true)
        }

    @Test fun knownImportRollbackRemovesOnlyItsOwnProjectAndGrain() = runBlocking { knownRollback(false) }

    @Test fun knownDuplicateRollbackPreservesItsSourceAndDeletesOnlyTheNewCopy() = runBlocking { knownRollback(true) }

    @Test fun importDoesNotDeleteAnUnownedDirectoryThatAppearsAfterTheFirstCheck() =
        runBlocking {
            occupiedDestination(duplicate = false, appearsAfterCheck = true)
        }

    @Test fun duplicateDoesNotDeleteAnUnownedDirectoryThatAppearsAfterTheFirstCheck() =
        runBlocking {
            occupiedDestination(duplicate = true, appearsAfterCheck = true)
        }

    @Test fun importPreservesAnAlreadyObservedOccupiedDestination() =
        runBlocking {
            occupiedDestination(duplicate = false, appearsAfterCheck = false)
        }

    @Test fun duplicatePreservesAnAlreadyObservedOccupiedDestination() =
        runBlocking {
            occupiedDestination(duplicate = true, appearsAfterCheck = false)
        }

    @Test
    fun importCallbackFailureReleasesItsNewDirectoryAndAllowsRetry() =
        runBlocking {
            callbackFailure(duplicate = false)
        }

    @Test
    fun copyCallbackFailureReleasesItsNewDirectoryAndPreservesSource() =
        runBlocking {
            callbackFailure(duplicate = true)
        }

    private suspend fun callbackFailure(duplicate: Boolean) {
        val original = IOException("Creation observer failed")
        var calls = 0
        val onCreated = {
            calls++
            assertTrue(File(files, "projects/21").isDirectory)
            assertTrue(File(files, "projects/21").listFiles().orEmpty().isEmpty())
            throw original
        }
        val failure =
            runCatching {
                if (duplicate) {
                    storage.copyProject(3L, 21L, onCreated)
                } else {
                    ArtflowPackage.openFile(source).use { contents ->
                        storage.importProjectStream(21L, contents, onDestinationCreated = onCreated)
                    }
                }
            }.exceptionOrNull()
        assertEquals(1, calls)
        assertTrue(generateSequence(failure) { it.cause }.any { it === original })
        assertFalse(File(files, "projects/21").exists())
        assertFalse(grainFile().exists())
        assertNull(CustomGrains.get(grain))
        assertArrayEquals(pixels, storage.readRaster(3L, raster))
        assertEquals(21L, invoke(duplicate))
        assertArrayEquals(pixels, storage.readRaster(21L, raster))
        if (!duplicate) assertArrayEquals(tile.values, grainFile().readBytes())
    }

    private suspend fun cancelAfterCommit(duplicate: Boolean) =
        supervisorScope {
            val caller = AtomicReference<Job>()
            var updated: ProjectEntity? = null
            var committed = false
            var successful = false
            doAnswer {
                updated = it.getArgument(0)
                Unit
            }.`when`(dao).updateProject(any(ProjectEntity::class.java) ?: row)
            doAnswer {
                successful = true
                Unit
            }.`when`(database).setTransactionSuccessful()
            doAnswer {
                if (successful) {
                    committed = true
                    caller.get().cancel(CancellationException("Cancelled after durable transaction completion"))
                }
                Unit
            }.`when`(database).endTransaction()
            doAnswer { if (committed) updated else null }.`when`(dao).getProjectById(21L)
            val task =
                async {
                    caller.set(currentCoroutineContext()[Job])
                    invoke(duplicate)
                }
            val failure = runCatching { task.await() }.exceptionOrNull()
            task.join()
            assertTrue(failure is CancellationException)
            assertTrue(committed)
            assertNotNull(storage.loadDocument(21L))
            assertArrayEquals(pixels, storage.readRaster(21L, raster))
            assertArrayEquals(pixels, storage.readRaster(3L, raster))
            if (!duplicate) {
                assertArrayEquals(tile.values, grainFile().readBytes())
                assertArrayEquals(tile.values, requireNotNull(CustomGrains.get(grain)).values)
            }
            verify(dao).getProjectById(21L)
            verify(storage, never()).deleteProjectFiles(21L)
            verify(dao, never()).deleteProjectById(anyLong())
        }

    private suspend fun knownRollback(duplicate: Boolean) {
        val original = IOException("Failed before transaction commit")
        doAnswer { throw original }.`when`(dao).updateProject(any(ProjectEntity::class.java) ?: row)
        val failure = runCatching { invoke(duplicate) }.exceptionOrNull()
        assertTrue(generateSequence(failure) { it.cause }.any { it === original })
        assertFalse(File(files, "projects/21").exists())
        assertArrayEquals(pixels, storage.readRaster(3L, raster))
        assertFalse(grainFile().exists())
        assertNull(CustomGrains.get(grain))
        verify(storage).deleteProjectFiles(21L)
        verify(dao, never()).deleteProjectById(anyLong())
    }

    private suspend fun occupiedDestination(
        duplicate: Boolean,
        appearsAfterCheck: Boolean,
    ) {
        val otherArtwork = File(files, "projects/21/independently-created-artwork")
        val otherBytes = byteArrayOf(10, 20, 30)
        doAnswer { invocation ->
            assertEquals(false, invocation.callRealMethod())
            check(otherArtwork.parentFile.mkdirs())
            otherArtwork.writeBytes(otherBytes)
            // The real first check saw no path; a separate owner creates it before the result is used.
            !appearsAfterCheck
        }.`when`(storage).projectDirectoryExists(21L)
        val failure = runCatching { invoke(duplicate) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("destination already contains project data"))
        assertTrue("An unowned destination must survive the refused operation", otherArtwork.isFile)
        assertArrayEquals(otherBytes, otherArtwork.readBytes())
        assertArrayEquals(pixels, storage.readRaster(3L, raster))
        assertFalse(grainFile().exists())
        verify(storage, never()).deleteProjectFiles(21L)
        verify(dao, never()).deleteProjectById(anyLong())
    }

    private suspend fun invoke(duplicate: Boolean): Long =
        if (duplicate) repository.duplicateProject(3L) else repository.importPackageFile(source).projectId

    private fun grainFile(): File = requireNotNull(GrainStorage.fileFor(GrainStorage.directory(files), grain))
}
