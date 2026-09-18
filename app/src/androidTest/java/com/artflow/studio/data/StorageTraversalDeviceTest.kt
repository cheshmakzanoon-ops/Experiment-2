package com.artflow.studio.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.StorageFileTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

@RunWith(AndroidJUnit4::class)
class StorageTraversalDeviceTest {
    private lateinit var directory: File
    private lateinit var storage: ProjectStorage

    @Before fun setUp() {
        val application = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "storage-traversal-").toFile()
        storage =
            ProjectStorage(
                object : ContextWrapper(application) {
                    override fun getFilesDir(): File = directory
                },
            )
    }

    @After fun tearDown() {
        StorageFileTree.delete(directory)
    }

    @Test fun pruneSizeAndDeleteStayInsideTheOwnedProject() =
        runBlocking(Dispatchers.IO) {
            val owned = storage.projectDir(7)
            val other = storage.projectDir(8)
            val otherArtwork = file(other, "layers/artwork.png", "irreplaceable")
            val kept = file(owned, "layers/1/kept.png", "saved")
            file(owned, "layers/2/stale.png", "stale")
            val link = File(owned, "layers/linked-project")
            Files.createSymbolicLink(link.toPath(), other.toPath())
            assertEquals(10L, storage.projectSizeBytes(7))
            assertEquals(1, storage.pruneRasters(7, setOf("layers/1/kept.png")))
            assertTrue(kept.isFile)
            assertEquals("irreplaceable", otherArtwork.readText())
            assertEquals(5L, storage.projectSizeBytes(7))
            assertEquals(18L, storage.totalStorageBytes())
            storage.deleteProjectFiles(7)
            assertFalse(owned.exists())
            assertEquals("irreplaceable", otherArtwork.readText())
        }

    @Test fun deletingARootLinkDoesNotDeleteAnotherProject() {
        val other = storage.projectDir(8)
        val artwork = file(other, "artwork.png", "irreplaceable")
        val link = File(directory, "projects/7")
        Files.createSymbolicLink(link.toPath(), other.toPath())
        assertEquals(0L, storage.projectSizeBytes(7))
        storage.deleteProjectFiles(7)
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("irreplaceable", artwork.readText())
    }

    @Test fun repeatedHousekeepingDoesNotAccumulateFilesOrFollowCycles() =
        runBlocking(Dispatchers.IO) {
            repeat(200) {
                val project = storage.projectDir(9)
                file(project, "layers/1/stale.png", "stale")
                val layers = File(project, "layers")
                Files.createSymbolicLink(File(layers, "cycle").toPath(), layers.toPath())
                assertEquals(5L, storage.projectSizeBytes(9))
                assertEquals(1, storage.pruneRasters(9, emptySet()))
                assertEquals(0L, storage.projectSizeBytes(9))
                storage.deleteProjectFiles(9)
                assertFalse(project.exists())
            }
        }

    @Test fun pruningThroughALinkedProjectCannotDeleteAnotherProject() =
        runBlocking(Dispatchers.IO) {
            val target = storage.projectDir(8)
            val artwork = file(target, "layers/irreplaceable.png", "preserve me")
            val link = File(directory, "projects/7")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            try {
                storage.pruneRasters(7, emptySet())
                throw AssertionError("Pruning through a linked project was accepted")
            } catch (_: IOException) {
                // The linked ancestor is rejected before stale rasters are pruned.
            }
            assertEquals("preserve me", artwork.readText())
            assertTrue(Files.isSymbolicLink(link.toPath()))
        }

    @Test fun linkedProjectContainerCannotRedirectSizeOrDelete() {
        val target = File(directory, "other-storage").apply { mkdirs() }
        val artwork = file(target, "7/layers/irreplaceable.png", "preserve me")
        val link = File(directory, "projects")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        try {
            storage.projectSizeBytes(7)
            throw AssertionError("Size traversal through a linked container was accepted")
        } catch (_: IOException) {
            // Not counted as this project's owned bytes.
        }
        try {
            storage.deleteProjectFiles(7)
            throw AssertionError("Delete through a linked container was accepted")
        } catch (_: IOException) {
            // No target files are removed.
        }
        assertEquals("preserve me", artwork.readText())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun linkedProjectRootCannotRedirectReadsOrWrites() =
        runBlocking(Dispatchers.IO) {
            val target = storage.projectDir(8)
            val manifest = file(target, "canvas.artflow", "original metadata")
            val original = file(target, "layers/original.png", "original pixels")
            val link = File(directory, "projects/7")
            Files.createSymbolicLink(link.toPath(), target.toPath())
            expectIo { storage.saveFlattened(7, byteArrayOf(1, 2, 3)) }
            expectIo { storage.writeRaster(7, 1, 1, byteArrayOf(1, 2, 3)) }
            expectIo { storage.loadDocument(7) }
            assertEquals("original metadata", manifest.readText())
            assertEquals("original pixels", original.readText())
            assertFalse(File(target, "canvas.png").exists())
            assertEquals(1, requireNotNull(File(target, "layers").listFiles()).size)
        }

    @Test fun metadataAndRasterLinksCannotReadOtherProjectPixels() =
        runBlocking(Dispatchers.IO) {
            val owned = storage.projectDir(7)
            val target = file(storage.projectDir(8), "artwork.png", "private pixels")
            Files.createSymbolicLink(File(owned, "canvas.artflow").toPath(), target.toPath())
            expectIo { storage.loadDocument(7) }
            val layers = File(owned, "layers").apply { mkdirs() }
            Files.createSymbolicLink(File(layers, "external.png").toPath(), target.toPath())
            expectIo { storage.readRaster(7, "layers/external.png") }
            assertEquals("private pixels", target.readText())
        }

    @Test fun exportCleanupCannotDeleteAnotherProjectsArtworkThroughADirectoryLink() {
        val project = storage.projectDir(7)
        val target = storage.projectDir(8)
        val artwork = file(target, "irreplaceable.png", "private pixels")
        check(artwork.setLastModified(1))
        Files.createSymbolicLink(File(project, "exports").toPath(), target.toPath())
        expectIo { storage.pruneOldExports(7, 0) }
        assertEquals("private pixels", artwork.readText())
    }

    @Test fun exportFileLinksAreNotListedOrPruned() {
        val exports = storage.exportsDir(7)
        val target = file(storage.projectDir(8), "irreplaceable.png", "private pixels")
        check(target.setLastModified(1))
        val link = File(exports, "shared.png")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val owned = file(exports, "old.png", "export copy")
        check(owned.setLastModified(1))
        assertEquals(listOf(owned), storage.listExports(7))
        assertEquals(1, storage.pruneOldExports(7, 0))
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals("private pixels", target.readText())
    }

    @Test fun linkedExportsContainerCannotMakeEditableArtworkShareable() =
        runBlocking(Dispatchers.IO) {
            val target = storage.projectDir(8)
            val artwork = file(target, "irreplaceable.png", "private pixels")
            Files.createSymbolicLink(File(directory, "exports").toPath(), target.toPath())
            expectIo { storage.exportedFile(artwork.absolutePath) }
            expectIo { storage.saveExport(7, "new.png", byteArrayOf(1, 2, 3)) }
            assertFalse(File(target, "7").exists())
            assertEquals("private pixels", artwork.readText())
        }

    @Test fun atomicWritesRejectLinkedParentsAndPreserveTargets() {
        val project = storage.projectDir(7)
        val target = storage.projectDir(8)
        val artwork = file(target, "irreplaceable.png", "private pixels")
        Files.createSymbolicLink(File(project, "redirect").toPath(), target.toPath())
        expectIo { storage.writeAtomically(File(project, "redirect/irreplaceable.png")) { it.write(byteArrayOf(1)) } }
        assertEquals("private pixels", artwork.readText())
        assertFalse(requireNotNull(target.listFiles()).any { it.extension == "tmp" })
    }

    private inline fun expectIo(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Linked storage path was accepted")
        } catch (_: IOException) {
            // A rejected operation must still leave its target intact, asserted by each caller.
        }
    }

    private fun file(
        root: File,
        path: String,
        text: String,
    ): File =
        File(root, path).apply {
            val parent = requireNotNull(parentFile)
            check(parent.isDirectory || parent.mkdirs())
            writeText(text)
        }
}
