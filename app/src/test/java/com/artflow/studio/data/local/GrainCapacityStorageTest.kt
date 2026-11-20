package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.render.GrainRegistry
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileFilter

class GrainCapacityStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun rejectedSavePreservesExistingDiskAndCacheBytesWithoutPublishingANewFile() {
        val registry = registry()
        val directory = temporary.newFolder("grains")
        val original = tile(10)
        GrainStorage.save(directory, FIRST, original, registry)
        assertThrows(IllegalArgumentException::class.java) { GrainStorage.save(directory, SECOND, tile(20), registry) }
        assertThrows(IllegalArgumentException::class.java) {
            GrainStorage.save(directory, FIRST, CustomGrains.Tile(512, ByteArray(512 * 512)), registry)
        }
        assertSame(original, registry.get(FIRST))
        assertArrayEquals(original.values, grainFile(directory, FIRST).readBytes())
        assertFalse(grainFile(directory, SECOND).exists())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test
    fun failedDiskWriteReleasesAdmissionForALaterSave() {
        val registry = registry()
        val invalidDirectory = temporary.newFile("not-a-directory")
        assertThrows(IllegalStateException::class.java) { GrainStorage.save(invalidDirectory, FIRST, tile(10), registry) }
        assertNull(registry.get(FIRST))
        val directory = temporary.newFolder("grains")
        val next = tile(20)
        GrainStorage.save(directory, SECOND, next, registry)
        assertSame(next, registry.get(SECOND))
        assertArrayEquals(next.values, grainFile(directory, SECOND).readBytes())
    }

    @Test
    fun startupReportsCapacityAfterProcessingOtherAdmissibleStoredTextures() {
        val registry = registry()
        registry.register(FIRST, tile(10))
        val directory = temporary.newFolder("grains")
        val rejected = grainFile(directory, SECOND).apply { writeBytes(tile(20).values) }
        val replacement = grainFile(directory, FIRST).apply { writeBytes(tile(30).values) }
        val rejectedFirst =
            object : File(directory.path) {
                override fun listFiles(filter: FileFilter?): Array<File> = arrayOf(rejected, replacement)
            }
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                GrainStorage.loadAll(rejectedFirst, strict = true, registry)
            }
        assertEquals(GrainRegistry.CAPACITY_MESSAGE, failure.message)
        assertArrayEquals(tile(30).values, registry.get(FIRST)!!.values)
        assertNull(registry.get(SECOND))
        assertArrayEquals(tile(20).values, rejected.readBytes())
        assertArrayEquals(tile(30).values, replacement.readBytes())
    }

    @Test
    fun oversizedBatchCannotWriteOrRegisterItsEarlierTiles() {
        val registry = registry()
        val directory = File(temporary.root, "grains")
        GrainStorage.ImportTransaction(registry).use { transaction ->
            assertThrows(IllegalArgumentException::class.java) {
                transaction.install(directory, linkedMapOf(FIRST to tile(10), SECOND to tile(20)))
            }
        }
        assertFalse(directory.exists())
        assertEquals(emptyList<String>(), registry.ids())
    }

    @Test
    fun concurrentPreparedImportsKeepTheirCapacityUntilRollback() {
        val registry = registry()
        val directory = File(temporary.root, "grains")
        GrainStorage.ImportTransaction(registry).use { second ->
            GrainStorage.ImportTransaction(registry).use { first ->
                first.prepare(listOf(FIRST))
                assertThrows(IllegalArgumentException::class.java) { second.prepare(listOf(SECOND)) }
            }
            second.prepare(listOf(SECOND))
            second.install(directory, mapOf(SECOND to tile(20)))
        }
        assertNull(registry.get(SECOND))
        assertFalse(grainFile(directory, SECOND).exists())
        GrainStorage.save(directory, FIRST, tile(30), registry)
        assertEquals(listOf(FIRST), registry.ids())
    }

    @Test
    fun committedSharedImportSurvivesTheOtherImportRollbackAtCapacity() {
        val registry = registry(bufferedTiles = 4)
        val directory = temporary.newFolder("grains")
        val original = tile(10)
        GrainStorage.ImportTransaction(registry).use { first ->
            first.install(directory, mapOf(FIRST to original))
            GrainStorage.ImportTransaction(registry).use { second ->
                second.install(directory, mapOf(FIRST to tile(10)))
                second.commit()
            }
        }
        assertSame(original, registry.get(FIRST))
        assertArrayEquals(original.values, grainFile(directory, FIRST).readBytes())
        GrainStorage.ImportTransaction(registry).use { retry -> retry.install(directory, mapOf(FIRST to tile(10))) }
        assertSame(original, registry.get(FIRST))
    }

    @Test
    fun cancellationClosesTheUnpublishedBatchReservation() {
        val registry = registry()
        assertThrows(CancellationException::class.java) {
            GrainStorage.ImportTransaction(registry).use { transaction ->
                transaction.prepare(listOf(FIRST))
                throw CancellationException("Import cancelled before payload read")
            }
        }
        GrainStorage.ImportTransaction(registry).use { transaction -> transaction.prepare(listOf(SECOND)) }
        assertEquals(emptyList<String>(), registry.ids())
    }

    private fun registry(bufferedTiles: Int = 2): GrainRegistry =
        GrainRegistry(retainedByteLimit = TILE_BYTES.toLong(), bufferedByteLimit = TILE_BYTES.toLong() * bufferedTiles)

    private fun tile(value: Int): CustomGrains.Tile = CustomGrains.Tile(CustomGrains.TILE, ByteArray(TILE_BYTES) { value.toByte() })

    private fun grainFile(
        directory: File,
        id: String,
    ): File = requireNotNull(GrainStorage.fileFor(directory, id))

    private companion object {
        const val FIRST = "custom-b20001"
        const val SECOND = "custom-b20002"
        const val TILE_BYTES = CustomGrains.TILE * CustomGrains.TILE
    }
}
