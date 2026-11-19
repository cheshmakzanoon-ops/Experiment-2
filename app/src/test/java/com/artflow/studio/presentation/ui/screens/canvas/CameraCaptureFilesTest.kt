package com.artflow.studio.presentation.ui.screens.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class CameraCaptureFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun scavengingRemovesOnlyOldDirectRegularCaptures() {
        val files = CameraCaptureFiles(temporary.root)
        val old = file("capture-old.jpg", NOW - DAY)
        val recent = file("capture-recent.jpg", NOW - DAY + 1)
        val unknownAge = file("capture-unknown.jpg", 0)
        val future = file("capture-future.jpg", NOW + 1)
        val artwork = file("artwork.jpg", NOW - DAY)
        val wrongSuffix = file("capture-not-a-photo.png", NOW - DAY)
        val nested = file("capture-directory.jpg/capture-nested.jpg", NOW - DAY)
        assertEquals(1, files.scavenge(NOW))
        assertFalse(old.exists())
        for (retained in listOf(recent, unknownAge, future, artwork, wrongSuffix, nested)) assertTrue(retained.isFile)
    }

    @Test
    fun activeCapturesFromOtherLaunchersAreProtectedRegardlessOfAge() {
        val canvas = CameraCaptureFiles(temporary.root)
        val palette = CameraCaptureFiles(temporary.root)
        val canvasFile = canvas.create()
        val paletteFile = palette.create()
        try {
            age(canvasFile)
            age(paletteFile)
            assertEquals(0, CameraCaptureFiles(temporary.root).scavenge(NOW))
            canvas.discard(canvasFile)
            assertFalse(canvasFile.exists())
            assertTrue(paletteFile.exists())
            assertEquals(0, canvas.scavenge(NOW))
        } finally {
            canvas.discard(canvasFile)
            palette.discard(paletteFile)
        }
    }

    @Test
    fun aRestoredPendingFileIsProtectedBeforeCleanupStarts() {
        val restored = file("capture-restored.jpg", NOW - DAY * 2)
        val abandoned = file("capture-abandoned.jpg", NOW - DAY * 2)
        val files = CameraCaptureFiles(temporary.root)
        assertEquals(restored, files.protect(restored.name))
        try {
            assertEquals(1, CameraCaptureFiles(temporary.root).scavenge(NOW))
            assertTrue(restored.exists())
            assertFalse(abandoned.exists())
            files.requireReadable(restored)
        } finally {
            files.discard(restored)
        }
    }

    @Test
    fun lateProviderWritesAreKeptForTheGracePeriodThenScavenged() {
        val files = CameraCaptureFiles(temporary.root)
        val capture = files.create()
        files.discard(capture)
        assertFalse(capture.exists())
        // The camera provider still held its destination URI when our launcher was disposed.
        capture.writeText("Late result")
        Files.setLastModifiedTime(capture.toPath(), FileTime.fromMillis(NOW))
        assertEquals(0, files.scavenge(NOW + DAY - 1))
        assertEquals("Late result", capture.readText())
        assertEquals(1, files.scavenge(NOW + DAY))
        assertFalse(capture.exists())
    }

    @Test
    fun linkedFilesAndDirectoriesAreNeverReadDeletedOrTraversed() {
        val files = CameraCaptureFiles(temporary.root)
        val outside = temporary.newFolder("artwork")
        val original = File(outside, "capture-artwork.jpg").apply { writeText("Keep this artwork") }
        age(original)
        val directory = File(temporary.root, "camera").apply { mkdir() }
        val link = File(directory, "capture-link.jpg")
        val directoryLink = File(directory, "capture-directory.jpg")
        Files.createSymbolicLink(link.toPath(), original.toPath())
        Files.createSymbolicLink(directoryLink.toPath(), outside.toPath())
        assertEquals(0, files.scavenge(NOW))
        assertTrue(runCatching { files.requireReadable(link) }.exceptionOrNull() is IOException)
        files.protect(link.name)
        assertTrue(runCatching { files.discard(link) }.exceptionOrNull() is IOException)
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertTrue(Files.isSymbolicLink(directoryLink.toPath()))
        assertEquals("Keep this artwork", original.readText())
    }

    @Test
    fun aLinkedCameraDirectoryCannotRedirectCreationOrCleanupIntoArtwork() {
        val outside = temporary.newFolder("other-project")
        val artwork = File(outside, "capture-artwork.jpg").apply { writeText("Keep this artwork") }
        age(artwork)
        val directory = File(temporary.root, "camera")
        Files.createSymbolicLink(directory.toPath(), outside.toPath())
        val files = CameraCaptureFiles(temporary.root)
        val restored = requireNotNull(files.protect(artwork.name))
        assertTrue(runCatching { files.create() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { files.scavenge(NOW) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { files.requireReadable(restored) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { files.discard(restored) }.exceptionOrNull() is IOException)
        assertEquals("Keep this artwork", artwork.readText())
        assertEquals(listOf(artwork), outside.listFiles().orEmpty().toList())
    }

    @Test
    fun restoredNamesCannotEscapeTheOwnedDirectoryOrTargetUnrelatedFiles() {
        val files = CameraCaptureFiles(temporary.root)
        for (name in listOf("../capture-old.jpg", "capture-../artwork.jpg", "capture-back\\artwork.jpg", "artwork.jpg", "capture-.jpg")) {
            assertNull(files.protect(name))
        }
        val unrelated = temporary.newFile("capture-user.jpg").apply { writeText("Keep this artwork") }
        assertTrue(runCatching { files.discard(unrelated) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals("Keep this artwork", unrelated.readText())
    }

    @Test
    fun missingCameraDirectoriesAreNotCreatedByScavenging() {
        assertEquals(0, CameraCaptureFiles(temporary.root).scavenge(NOW))
        assertFalse(File(temporary.root, "camera").exists())
    }

    @Test
    fun aFailedDirectoryCreationDoesNotRemoveTheBlockingFile() {
        val blocker = temporary.newFile("camera").apply { writeText("Keep this existing file") }
        assertTrue(runCatching { CameraCaptureFiles(temporary.root).create() }.exceptionOrNull() is IllegalStateException)
        assertEquals("Keep this existing file", blocker.readText())
    }

    private fun file(
        name: String,
        written: Long,
    ): File =
        File(temporary.root, "camera/$name").apply {
            requireNotNull(parentFile).mkdirs()
            writeText(name)
            Files.setLastModifiedTime(toPath(), FileTime.fromMillis(written))
        }

    private fun age(file: File) {
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(NOW - DAY))
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val NOW = DAY * 3
    }
}
