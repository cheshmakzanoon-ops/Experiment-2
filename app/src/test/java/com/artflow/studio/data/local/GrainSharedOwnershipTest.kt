package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.render.GrainRegistry
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

class GrainSharedOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = CustomGrains.TILE * CustomGrains.TILE
    private val first = "custom-c11001"
    private val second = "custom-c11002"
    private val third = "custom-c11003"

    private fun tile(level: Int) = CustomGrains.Tile(CustomGrains.TILE, ByteArray(bytes) { level.toByte() })

    private fun file(
        directory: File,
        id: String,
    ) = requireNotNull(GrainStorage.fileFor(directory, id))

    @Test fun repeatedBatchesReleaseEveryReservationAndProvisionalAsset() {
        val registry = GrainRegistry(bytes * 2L, bytes * 4L)
        val directory = temporary.newFolder()
        jvmGrainImport(registry).use { import ->
            import.prepare(listOf(first))
            import.install(directory, mapOf(first to tile(11)))
            import.prepare(listOf(second))
            import.install(directory, mapOf(second to tile(22)))
            assertThrows(IllegalArgumentException::class.java) { registry.reserve(mapOf(third to bytes)) }
        }
        assertTrue(registry.ids().isEmpty())
        assertTrue(directory.listFiles()!!.isEmpty())
        registry.reserve(mapOf(first to bytes, second to bytes), bufferedCopies = 2).close()
    }

    @Test fun independentReplacementSurvivesOriginalImportRollback() {
        val registry = GrainRegistry(bytes.toLong(), bytes * 2L)
        val directory = temporary.newFolder()
        val replacement = tile(44)
        jvmGrainImport(registry).use { import ->
            import.install(directory, mapOf(first to tile(33)))
            GrainStorage.save(directory, first, replacement, registry)
        }
        assertSame(replacement, registry.get(first))
        assertArrayEquals(replacement.values, file(directory, first).readBytes())
        registry.reserve(mapOf(first to bytes), bufferedCopies = 2).close()
    }

    @Test fun committedOneOfThreeSharedImportsSurvivesAllCloseOrders() {
        for (commitIndex in 0..2) {
            val registry = GrainRegistry(bytes.toLong(), bytes * 6L)
            val directory = temporary.newFolder()
            val imports = List(3) { jvmGrainImport(registry) }
            try {
                imports.forEach { it.install(directory, mapOf(first to tile(55))) }
                imports[commitIndex].commit()
            } finally {
                imports.reversed().forEach { it.close() }
            }
            assertEquals(listOf(first), registry.ids())
            assertArrayEquals(tile(55).values, file(directory, first).readBytes())
            registry.reserve(mapOf(first to bytes), bufferedCopies = 2).close()
        }
    }

    @Test fun failureInLaterInstallationReleasesEarlierBatchOnClose() {
        val registry = GrainRegistry(bytes * 2L, bytes * 4L)
        val directory = temporary.newFolder()
        val existing = tile(66)
        GrainStorage.save(directory, second, existing, registry)
        jvmGrainImport(registry).use { import ->
            import.install(directory, mapOf(first to tile(77)))
            assertThrows(IllegalArgumentException::class.java) {
                import.install(directory, mapOf(second to tile(88)))
            }
        }
        assertSame(existing, registry.get(second))
        assertNull(registry.get(first))
        assertFalse(file(directory, first).exists())
        assertArrayEquals(existing.values, file(directory, second).readBytes())
        registry.reserve(mapOf(first to bytes), bufferedCopies = 2).close()
    }

    @Test fun startupCannotStealProvisionalClaimBeforeRollback() {
        val registry = GrainRegistry(bytes.toLong(), bytes * 4L)
        val directory = temporary.newFolder()
        val incoming = tile(99)
        jvmGrainImport(registry).use { import ->
            import.install(directory, mapOf(first to incoming))
            GrainStorage.loadAll(directory, strict = true, registry)
            assertSame(incoming, registry.get(first))
        }
        assertNull(registry.get(first))
        assertFalse(file(directory, first).exists())
    }

    @Test fun startupCanReloadRetainedSharedClaimWithoutLaterRollbackRemovingIt() {
        val registry = GrainRegistry(bytes.toLong(), bytes * 6L)
        val directory = temporary.newFolder()
        jvmGrainImport(registry).use { firstImport ->
            firstImport.install(directory, mapOf(first to tile(111)))
            jvmGrainImport(registry).use { secondImport ->
                secondImport.install(directory, mapOf(first to tile(111)))
                secondImport.commit()
            }
            GrainStorage.loadAll(directory, strict = true, registry)
        }
        assertArrayEquals(tile(111).values, registry.get(first)!!.values)
        assertArrayEquals(tile(111).values, file(directory, first).readBytes())
        registry.reserve(mapOf(first to bytes), bufferedCopies = 2).close()
    }
}
