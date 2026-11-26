package com.artflow.studio.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.render.GrainRegistry
import com.artflow.studio.data.local.GrainStorage
import com.artflow.studio.data.local.StorageFileTree
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises real Android directory descriptors/sync; it does not simulate storage power loss. */
@RunWith(AndroidJUnit4::class)
class GrainPublicationDeviceTest {
    private lateinit var context: Context
    private lateinit var root: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.cacheDir, "grain-publication-${System.nanoTime()}")
        check(root.mkdir())
    }

    @After
    fun tearDown() {
        StorageFileTree.delete(root, context.cacheDir)
    }

    @Test
    fun realDirectorySyncSupportsNewAndSharedAssetsAndIndependentReopen() {
        val directory = GrainStorage.directory(root)
        assertFalse(directory.exists())
        val registry = GrainRegistry(TILE_BYTES * 2L, TILE_BYTES * 8L)
        val first = tile(29)
        val second = tile(83)
        GrainStorage.ImportTransaction(registry).use { original ->
            original.install(directory, mapOf(FIRST to first))
            GrainStorage.ImportTransaction(registry).use { shared ->
                shared.install(directory, linkedMapOf(FIRST to first, SECOND to second))
                original.close()
                assertSame(first, registry.get(FIRST))
                assertSame(second, registry.get(SECOND))
                shared.commit()
            }
        }
        assertArrayEquals(first.values, requireNotNull(GrainStorage.fileFor(directory, FIRST)).readBytes())
        assertArrayEquals(second.values, requireNotNull(GrainStorage.fileFor(directory, SECOND)).readBytes())
        assertEquals(2, directory.listFiles()!!.size)
        val reopened = GrainRegistry(TILE_BYTES * 2L, TILE_BYTES * 8L)
        GrainStorage.loadAll(directory, strict = true, reopened)
        assertEquals(setOf(FIRST, SECOND), reopened.ids().toSet())
        assertArrayEquals(first.values, requireNotNull(reopened.get(FIRST)).values)
        assertArrayEquals(second.values, requireNotNull(reopened.get(SECOND)).values)
    }

    private fun tile(level: Int) = CustomGrains.Tile(CustomGrains.TILE, ByteArray(TILE_BYTES) { level.toByte() })

    private companion object {
        const val FIRST = "custom-d09211"
        const val SECOND = "custom-d09212"
        const val TILE_BYTES = CustomGrains.TILE * CustomGrains.TILE
    }
}
