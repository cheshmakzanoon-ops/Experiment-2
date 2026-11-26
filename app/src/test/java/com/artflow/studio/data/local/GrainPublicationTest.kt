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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Real files and registry ownership at injected sync boundaries; not a power-loss simulation. */
class GrainPublicationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun everyNewTileAndItsDirectoryAreSyncedBeforeRegistryPublication() {
        val fixture = Fixture()
        val incoming = linkedMapOf(FIRST to tile(11), SECOND to tile(22))
        val synced = mutableListOf<File>()
        GrainStorage
            .ImportTransaction(fixture.registry) { directory ->
                synced += directory
                assertTrue(fixture.registry.ids().isEmpty())
                incoming.forEach { (id, image) -> assertArrayEquals(image.values, fixture.file(id).readBytes()) }
                assertTrue(directory.isDirectory)
            }.use { transaction ->
                transaction.install(fixture.directory, incoming)
                assertEquals(listOf(fixture.directory, fixture.root), synced)
                assertEquals(listOf(FIRST, SECOND), fixture.registry.ids())
                transaction.commit()
            }
        incoming.forEach { (id, image) -> assertArrayEquals(image.values, fixture.file(id).readBytes()) }
        assertEquals(2, fixture.directory.listFiles()!!.size)
    }

    @Test
    fun anEmptyBatchDoesNotCreateOrSyncTextureStorage() {
        val fixture = Fixture()
        GrainStorage.ImportTransaction(fixture.registry) { error("An empty batch must not sync a directory") }.use { transaction ->
            transaction.install(fixture.directory, emptyMap())
            transaction.commit()
        }
        assertFalse(fixture.directory.exists())
        assertTrue(fixture.registry.ids().isEmpty())
    }

    @Test
    fun failedGrainOrParentSyncRemovesNewNamesAndPreservesExistingAssets() {
        for (failedCall in 1..2) {
            val fixture = Fixture()
            val original = tile(33)
            fixture.save(FIRST, original)
            val failure = IOException("Injected directory sync failure $failedCall")
            var calls = 0
            val actual =
                assertThrows(IOException::class.java) {
                    GrainStorage
                        .ImportTransaction(fixture.registry) {
                            calls++
                            assertNull(fixture.registry.get(SECOND))
                            if (calls == failedCall) throw failure
                        }.use { transaction ->
                            transaction.install(fixture.directory, linkedMapOf(FIRST to original, SECOND to tile(44)))
                            error("A failed sync cannot return an installed batch")
                        }
                }
            assertSame(failure, actual)
            assertEquals(failedCall, calls)
            assertSame(original, fixture.registry.get(FIRST))
            assertArrayEquals(original.values, fixture.file(FIRST).readBytes())
            assertFalse(fixture.file(SECOND).exists())
            assertEquals(listOf(FIRST), fixture.registry.ids())
            fixture.registry.reserve(mapOf(SECOND to TILE_BYTES), bufferedCopies = 2).close()
        }
    }

    @Test
    fun aFailedFirstCreationCanRetryWithoutSkippingItsParentSync() {
        val fixture = Fixture()
        val failure = IOException("Parent directory could not be synced")
        assertThrows(IOException::class.java) {
            GrainStorage
                .ImportTransaction(fixture.registry) { directory ->
                    if (directory == fixture.root) throw failure
                }.use { transaction -> transaction.install(fixture.directory, mapOf(FIRST to tile(55))) }
        }
        assertTrue(fixture.directory.isDirectory)
        assertTrue(fixture.directory.listFiles()!!.isEmpty())
        val synced = mutableListOf<File>()
        GrainStorage.ImportTransaction(fixture.registry) { synced += it }.use { transaction ->
            transaction.install(fixture.directory, mapOf(FIRST to tile(55)))
            transaction.commit()
        }
        assertEquals(listOf(fixture.directory, fixture.root), synced)
        assertArrayEquals(tile(55).values, fixture.file(FIRST).readBytes())
    }

    @Test
    fun failedSyncCannotRemoveAnotherProvisionalOwnersSharedTexture() {
        val fixture = Fixture()
        val original = tile(66)
        GrainStorage.ImportTransaction(fixture.registry) {}.use { first ->
            first.install(fixture.directory, mapOf(FIRST to original))
            val failure = IOException("The second import cannot sync")
            assertSame(
                failure,
                assertThrows(IOException::class.java) {
                    GrainStorage.ImportTransaction(fixture.registry) { throw failure }.use { second ->
                        second.install(fixture.directory, linkedMapOf(FIRST to original, SECOND to tile(77)))
                    }
                },
            )
            assertSame(original, fixture.registry.get(FIRST))
            assertArrayEquals(original.values, fixture.file(FIRST).readBytes())
            assertNull(fixture.registry.get(SECOND))
            assertFalse(fixture.file(SECOND).exists())
            first.commit()
        }
        assertSame(original, fixture.registry.get(FIRST))
        assertArrayEquals(original.values, fixture.file(FIRST).readBytes())
    }

    @Test
    fun failedLaterBatchRollsBackEarlierUncommittedFilesAndAllReservations() {
        val fixture = Fixture()
        var fail = false
        assertThrows(IOException::class.java) {
            GrainStorage.ImportTransaction(fixture.registry) { if (fail) throw IOException("Later batch failed") }.use { transaction ->
                transaction.install(fixture.directory, mapOf(FIRST to tile(88)))
                fail = true
                transaction.install(fixture.directory, mapOf(SECOND to tile(99)))
            }
        }
        assertTrue(fixture.directory.listFiles()!!.isEmpty())
        assertTrue(fixture.registry.ids().isEmpty())
        fixture.registry.reserve(mapOf(FIRST to TILE_BYTES, SECOND to TILE_BYTES), bufferedCopies = 2).close()
    }

    @Test
    fun cancellationAtSyncPropagatesWithoutPublishingTheProvisionalTexture() {
        val fixture = Fixture()
        val cancelled = CancellationException("Import cancelled at its sync boundary")
        val failure =
            assertThrows(CancellationException::class.java) {
                GrainStorage.ImportTransaction(fixture.registry) { throw cancelled }.use { transaction ->
                    transaction.install(fixture.directory, mapOf(FIRST to tile(111)))
                }
            }
        assertSame(cancelled, failure)
        assertTrue(fixture.registry.ids().isEmpty())
        assertTrue(fixture.directory.listFiles()!!.isEmpty())
    }

    @Test
    fun reusingAnExistingFileStillRequiresSyncButNeverOwnsItsDeletion() {
        val fixture = Fixture()
        assertTrue(fixture.directory.mkdir())
        val original = tile(123)
        fixture.file(FIRST).writeBytes(original.values)
        val calls = mutableListOf<File>()
        assertThrows(IOException::class.java) {
            GrainStorage
                .ImportTransaction(fixture.registry) { directory ->
                    calls += directory
                    if (directory == fixture.root) throw IOException("Parent sync failed")
                }.use { transaction -> transaction.install(fixture.directory, mapOf(FIRST to original)) }
        }
        assertEquals(listOf(fixture.directory, fixture.root), calls)
        assertArrayEquals(original.values, fixture.file(FIRST).readBytes())
        assertTrue(fixture.registry.ids().isEmpty())
    }

    private inner class Fixture {
        val root = temporary.newFolder()
        val directory = GrainStorage.directory(root)
        val registry = GrainRegistry(TILE_BYTES * 2L, TILE_BYTES * 8L)

        fun file(id: String): File = requireNotNull(GrainStorage.fileFor(directory, id))

        fun save(
            id: String,
            image: CustomGrains.Tile,
        ) = GrainStorage.save(directory, id, image, registry)
    }

    private fun tile(level: Int) = CustomGrains.Tile(CustomGrains.TILE, ByteArray(TILE_BYTES) { level.toByte() })

    private companion object {
        const val FIRST = "custom-d09201"
        const val SECOND = "custom-d09202"
        const val TILE_BYTES = CustomGrains.TILE * CustomGrains.TILE
    }
}
