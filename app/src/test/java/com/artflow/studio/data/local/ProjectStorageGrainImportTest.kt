package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.util.UUID

class ProjectStorageGrainImportTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var grainDir: File
    private lateinit var storage: ProjectStorage
    private val ids = mutableListOf<String>()

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        grainDir = GrainStorage.directory(filesDir)
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(filesDir)
        storage = ProjectStorage(context)
    }

    @After
    fun tearDown() {
        ids.forEach { id -> CustomGrains.get(id)?.let { CustomGrains.removeIfSame(id, it) } }
    }

    @Test
    fun malformedDependenciesCannotReplaceOrPublishGrainsInEitherImportPath() =
        runBlocking {
            val existing = newId()
            val added = newId()
            val original = tile(10)
            GrainStorage.save(grainDir, existing, original)
            val files = packageFiles(linkedMapOf(existing to tile(20).values, added to tile(30).values), "{".toByteArray())
            for (streamed in listOf(false, true)) {
                val failure = runCatching { importPackage(files, streamed) }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertPreserved(existing, original)
                assertAbsent(added)
                assertFalse(storage.projectDirectoryExists(PROJECT))
            }
        }

    @Test
    fun conflictingExistingIdentityRejectsTheWholeBatchBeforeAnyPublication() =
        runBlocking {
            val existing = newId()
            val added = newId()
            val original = tile(40)
            GrainStorage.save(grainDir, existing, original)
            val files = packageFiles(linkedMapOf(added to tile(50).values, existing to tile(60).values))
            for (streamed in listOf(false, true)) {
                val failure = runCatching { importPackage(files, streamed) }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure?.message.orEmpty().contains("conflicts"))
                assertPreserved(existing, original)
                assertAbsent(added)
                assertFalse(storage.projectDirectoryExists(PROJECT))
            }
        }

    @Test
    fun aCacheOnlyIdentityIsProtectedWithoutCreatingAConflictingFile() =
        runBlocking {
            val existing = newId()
            val original = tile(70)
            CustomGrains.register(existing, original)
            val failure = runCatching { importPackage(packageFiles(mapOf(existing to tile(80).values)), true) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertSame(original, CustomGrains.get(existing))
            assertFalse(grainFile(existing).exists())
            assertFalse(storage.projectDirectoryExists(PROJECT))
        }

    @Test
    fun anInvalidLaterTileCannotLeaveAnEarlierValidTileBehind() =
        runBlocking {
            val first = newId()
            val second = newId()
            val files = packageFiles(linkedMapOf(first to tile(90).values, second to byteArrayOf(1)))
            val failure = runCatching { importPackage(files, true) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertAbsent(first)
            assertAbsent(second)
            assertFalse(storage.projectDirectoryExists(PROJECT))
        }

    @Test
    fun aFailureAfterStorageReturnsRollsBackOnlyNewFilesAndCacheEntries() =
        runBlocking {
            val existing = newId()
            val added = newId()
            val original = tile(100)
            GrainStorage.save(grainDir, existing, original)
            val files = packageFiles(linkedMapOf(existing to original.values.copyOf(), added to tile(110).values))
            val failure =
                runCatching {
                    GrainStorage.ImportTransaction().use { grains ->
                        importPackage(files, true, grains)
                        assertTrue(grainFile(added).isFile)
                        assertTrue(CustomGrains.get(added) != null)
                        throw IOException("The later database publication failed")
                    }
                }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertPreserved(existing, original)
            assertAbsent(added)
        }

    @Test
    fun identicalReimportPreservesTheExistingCachedTile() =
        runBlocking {
            val existing = newId()
            val original = tile(120)
            GrainStorage.save(grainDir, existing, original)
            importPackage(packageFiles(mapOf(existing to original.values.copyOf())), true)
            assertPreserved(existing, original)
        }

    @Test
    fun aSuccessfulConcurrentImportRetainsAssetsWhenTheFirstImportFails() {
        val id = newId()
        val incoming = tile(130)
        GrainStorage.ImportTransaction().use { first ->
            first.install(grainDir, mapOf(id to incoming))
            GrainStorage.ImportTransaction().use { second ->
                second.install(grainDir, mapOf(id to tile(130)))
                second.commit()
            }
        }
        assertPreserved(id, incoming)
    }

    @Test
    fun sharedProvisionalAssetsDisappearOnlyAfterBothImportsFail() {
        val id = newId()
        val incoming = tile(140)
        GrainStorage.ImportTransaction().use { second ->
            GrainStorage.ImportTransaction().use { first ->
                first.install(grainDir, mapOf(id to incoming))
                second.install(grainDir, mapOf(id to tile(140)))
            }
            assertPreserved(id, incoming)
        }
        assertAbsent(id)
    }

    private suspend fun importPackage(
        files: Map<String, ByteArray>,
        streamed: Boolean,
        grains: GrainStorage.ImportTransaction? = null,
    ) {
        if (streamed) {
            val archive = File(temporary.root, "source.artflow")
            archive.writeBytes(ArtflowPackage.write("Grain import", files))
            ArtflowPackage.openFile(archive).use { storage.importProjectStream(PROJECT, it, grains) }
        } else {
            storage.importProject(PROJECT, files, grains)
        }
    }

    private fun packageFiles(
        grains: Map<String, ByteArray>,
        dependencies: ByteArray = ArtflowPackage.encodeDependencies(ArtflowPackage.Dependencies(grainIds = grains.keys.toList())),
    ): Map<String, ByteArray> {
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
        return mapOf(ArtflowPackage.DOCUMENT to CanvasDocumentCodec.encode(document), ArtflowPackage.DEPENDENCIES to dependencies) +
            grains.mapKeys { (id, _) -> ArtflowPackage.grainEntryPath(id) }
    }

    private fun assertPreserved(
        id: String,
        tile: CustomGrains.Tile,
    ) {
        assertArrayEquals(tile.values, grainFile(id).readBytes())
        assertSame(tile, CustomGrains.get(id))
    }

    private fun assertAbsent(id: String) {
        assertFalse(grainFile(id).exists())
        assertNull(CustomGrains.get(id))
    }

    private fun newId(): String = ("custom-" + UUID.randomUUID().toString().replace("-", "")).also(ids::add)

    private fun grainFile(id: String): File = requireNotNull(GrainStorage.fileFor(grainDir, id))

    private fun tile(value: Int): CustomGrains.Tile =
        CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { value.toByte() })

    private companion object {
        const val PROJECT = 19L
    }
}
