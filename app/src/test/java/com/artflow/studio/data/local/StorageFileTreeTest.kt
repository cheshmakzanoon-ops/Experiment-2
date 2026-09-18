package com.artflow.studio.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

class StorageFileTreeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun missingRootsAreNotCreated() {
        val missing = File(temporary.root, "absent")
        assertEquals(0L, StorageFileTree.sizeBytes(missing))
        assertEquals(0, StorageFileTree.prune(missing) { true })
        StorageFileTree.delete(missing)
        assertFalse(missing.exists())
    }

    @Test fun sizeCountsNestedRegularFilesOnly() {
        val root = temporary.newFolder()
        file(root, "a.bin", 17)
        file(root, "nested/b.png", 29)
        file(root, "nested/empty.bin", 0)
        File(root, "empty").mkdirs()
        assertEquals(46L, StorageFileTree.sizeBytes(root))
    }

    @Test fun pruneKeepsReferencedAndNonRasterFiles() {
        val root = temporary.newFolder()
        val retained = file(root, "layer/retained.png", 3)
        val metadata = file(root, "notes.txt", 4)
        val stale = file(root, "obsolete/stale.png", 5)
        assertEquals(1, StorageFileTree.prune(root) { it.extension == "png" && it != retained })
        assertTrue(retained.isFile)
        assertTrue(metadata.isFile)
        assertFalse(stale.exists())
        assertFalse(stale.parentFile.exists())
        assertEquals(7L, StorageFileTree.sizeBytes(root))
    }

    @Test fun pruneDoesNotFollowDirectoryLinks() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        val artwork = file(outside, "irreplaceable.png", 13)
        val link = File(root, "other-project")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertEquals(0, StorageFileTree.prune(root) { true })
        assertTrue(artwork.isFile)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertEquals(0L, StorageFileTree.sizeBytes(root))
    }

    @Test fun deleteRemovesLinksWithoutDeletingTheirTargets() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        val artwork = file(outside, "artwork.png", 13)
        file(root, "nested/owned.png", 9)
        Files.createSymbolicLink(File(root, "other-project").toPath(), outside.toPath())
        StorageFileTree.delete(root)
        assertFalse(root.exists())
        assertEquals(13L, artwork.length())
    }

    @Test fun aRootLinkIsNotTreatedAsTheTargetDirectory() {
        val target = temporary.newFolder()
        val artwork = file(target, "artwork.png", 13)
        val link = File(temporary.root, "root-link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertEquals(0L, StorageFileTree.sizeBytes(link))
        assertEquals(0, StorageFileTree.prune(link) { true })
        StorageFileTree.delete(link)
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(13L, artwork.length())
    }

    @Test fun danglingAndCyclicLinksCannotLoopOrHideFailedDeletion() {
        val root = temporary.newFolder()
        file(root, "owned", 7)
        Files.createSymbolicLink(File(root, "cycle").toPath(), root.toPath())
        Files.createSymbolicLink(File(root, "dangling").toPath(), File(root, "absent").toPath())
        assertEquals(7L, StorageFileTree.sizeBytes(root))
        assertEquals(1, StorageFileTree.prune(root) { true })
        StorageFileTree.delete(root)
        assertFalse(root.exists())
    }

    @Test fun regularFileRootsAreSupported() {
        val root = temporary.newFile()
        root.writeBytes(ByteArray(11))
        assertEquals(11L, StorageFileTree.sizeBytes(root))
        assertEquals(0, StorageFileTree.prune(root) { false })
        assertEquals(1, StorageFileTree.prune(root) { true })
        StorageFileTree.delete(root)
        assertFalse(root.exists())
    }

    @Test(expected = IOException::class)
    fun prunePropagatesFailureRatherThanReportingSuccess() {
        val root = temporary.newFolder()
        file(root, "owned.png", 7)
        StorageFileTree.prune(root) { throw IOException("Injected predicate failure") }
    }

    @Test fun deepTreesCanBeSizedAndDeleted() {
        val root = temporary.newFolder()
        val nested = (1..100).joinToString("/") { "d" }
        file(root, "$nested/artwork.png", 37)
        assertEquals(37L, StorageFileTree.sizeBytes(root))
        StorageFileTree.delete(root)
        assertFalse(root.exists())
    }

    @Test fun linkedAncestorsAreRejectedBeforeAnyTraversal() {
        val boundary = temporary.newFolder()
        val target = temporary.newFolder()
        val artwork = file(target, "layers/artwork.png", 13)
        val link = File(boundary, "projects/7").apply { parentFile.mkdirs() }
        Files.createSymbolicLink(link.toPath(), target.toPath())
        val root = File(link, "layers")
        expectIo { StorageFileTree.sizeBytes(root, boundary) }
        expectIo { StorageFileTree.prune(root, boundary) { true } }
        expectIo { StorageFileTree.delete(root, boundary) }
        assertEquals(13L, artwork.length())
        assertTrue(Files.isSymbolicLink(link.toPath()))
    }

    @Test fun rootsOutsideTheExplicitBoundaryAreRejected() {
        val boundary = temporary.newFolder()
        val outside = temporary.newFolder()
        val artwork = file(outside, "artwork.png", 13)
        expectIo { StorageFileTree.sizeBytes(outside, boundary) }
        expectIo { StorageFileTree.prune(outside, boundary) { true } }
        expectIo { StorageFileTree.delete(outside, boundary) }
        assertEquals(13L, artwork.length())
    }

    @Test fun explicitBoundaryStillAllowsSafeRemovalOfARootLink() {
        val boundary = temporary.newFolder()
        val target = temporary.newFolder()
        val artwork = file(target, "artwork.png", 13)
        val link = File(boundary, "root-link")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        assertEquals(0L, StorageFileTree.sizeBytes(link, boundary))
        assertEquals(0, StorageFileTree.prune(link, boundary) { true })
        StorageFileTree.delete(link, boundary)
        assertFalse(Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals(13L, artwork.length())
    }

    @Test fun ordinaryNestedRootsWorkInsideTheBoundary() {
        val boundary = temporary.newFolder()
        val root = File(boundary, "projects/7/layers").apply { mkdirs() }
        file(root, "stale.png", 13)
        file(root, "retained.bin", 7)
        assertEquals(20L, StorageFileTree.sizeBytes(root, boundary))
        assertEquals(1, StorageFileTree.prune(root, boundary) { it.extension == "png" })
        assertEquals(7L, StorageFileTree.sizeBytes(root, boundary))
        StorageFileTree.delete(root, boundary)
        assertFalse(root.exists())
        assertTrue(root.parentFile.exists())
    }

    @Test fun readWriteGuardRejectsLeafAndAncestorLinksButAllowsMissingOwnedPaths() {
        val boundary = temporary.newFolder()
        val target = temporary.newFolder()
        val artwork = file(target, "artwork.png", 13)
        val link = File(boundary, "redirect")
        Files.createSymbolicLink(link.toPath(), target.toPath())
        expectIo { StorageFileTree.requireUnlinked(link, boundary) }
        expectIo { StorageFileTree.requireUnlinked(File(link, "artwork.png"), boundary) }
        expectIo { StorageFileTree.requireUnlinked(artwork, boundary) }
        expectIo { StorageFileTree.requireUnlinked(File(link, "../owned.png"), boundary) }
        val missing = File(boundary, "owned/layers/new.png")
        StorageFileTree.requireUnlinked(missing, boundary)
        assertFalse(missing.exists())
        assertEquals(13L, artwork.length())
    }

    private fun expectIo(operation: () -> Unit) {
        try {
            operation()
            throw AssertionError("An unsafe storage path was accepted")
        } catch (_: IOException) {
            // Expected refusal, never partial success.
        }
    }

    private fun file(
        root: File,
        path: String,
        size: Int,
    ): File =
        File(root, path).apply {
            check(parentFile.isDirectory || parentFile.mkdirs())
            writeBytes(ByteArray(size) { 23 })
        }
}
