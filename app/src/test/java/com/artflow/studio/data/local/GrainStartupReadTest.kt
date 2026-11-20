package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.nio.file.Files

class GrainStartupReadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun startupRegistersValidStoredBytesBeforeReturning() {
        val directory = temporary.newFolder("grains")
        val bytes = ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it * 29).toByte() }
        val id = "custom-a24ff001"
        File(directory, id + GrainStorage.EXTENSION).writeBytes(bytes)
        GrainStorage.loadAll(directory, strict = true)
        val loaded = requireNotNull(CustomGrains.get(id))
        try {
            assertArrayEquals(bytes, loaded.values)
        } finally {
            CustomGrains.removeIfSame(id, loaded)
        }
    }

    @Test
    fun anUnreadableAssetDirectoryIsAStartupFailureRatherThanAnEmptyLibrary() {
        val file = temporary.newFile("grains")
        assertThrows(IOException::class.java) { GrainStorage.loadAll(file, strict = true) }
    }

    @Test
    fun aDamagedSavedTextureFailsStrictStartupButRetainsTheBestEffortMaintenanceOption() {
        val directory = temporary.newFolder("grains")
        val id = "custom-a24ff002"
        val file = File(directory, id + GrainStorage.EXTENSION).apply { writeBytes(byteArrayOf(12, 34)) }
        assertThrows(IOException::class.java) { GrainStorage.loadAll(directory, strict = true) }
        assertNull(CustomGrains.get(id))
        assertArrayEquals(byteArrayOf(12, 34), file.readBytes())
        GrainStorage.loadAll(directory)
        assertNull(CustomGrains.get(id))
    }

    @Test
    fun aLinkedStoredTileCannotBeRegisteredAsAReadyStartupAsset() {
        val directory = temporary.newFolder("grains")
        val target = temporary.newFile("outside").apply { writeBytes(ByteArray(CustomGrains.TILE * CustomGrains.TILE) { 23 }) }
        val id = "custom-a24ff003"
        Files.createSymbolicLink(File(directory, id + GrainStorage.EXTENSION).toPath(), target.toPath())
        assertThrows(IOException::class.java) { GrainStorage.loadAll(directory, strict = true) }
        assertNull(CustomGrains.get(id))
        assertArrayEquals(ByteArray(CustomGrains.TILE * CustomGrains.TILE) { 23 }, target.readBytes())
    }

    @Test
    fun aDamagedTileIsReportedAfterAllOtherAvailableTilesHaveBeenRegistered() {
        val directory = temporary.newFolder("grains")
        val damaged = File(directory, "custom-a24ff004" + GrainStorage.EXTENSION).apply { writeBytes(byteArrayOf(1)) }
        val bytes = ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it * 7).toByte() }
        val id = "custom-a24ff005"
        val valid = File(directory, id + GrainStorage.EXTENSION).apply { writeBytes(bytes) }
        // Control only enumeration order; both files and the complete production read path are real.
        val damagedFirst =
            object : File(directory.path) {
                override fun listFiles(filter: FileFilter?): Array<File> = arrayOf(damaged, valid)
            }
        assertThrows(IOException::class.java) { GrainStorage.loadAll(damagedFirst, strict = true) }
        val loaded = requireNotNull(CustomGrains.get(id))
        try {
            assertArrayEquals(bytes, loaded.values)
            assertArrayEquals(byteArrayOf(1), damaged.readBytes())
        } finally {
            CustomGrains.removeIfSame(id, loaded)
        }
    }

    @Test
    fun aFreshInstallWithNoStoredGrainsIsReadyWithoutCreatingAnAssetDirectory() {
        val directory = File(temporary.root, "no-grains-yet")
        GrainStorage.loadAll(directory, strict = true)
        assertFalse(directory.exists())
    }
}
