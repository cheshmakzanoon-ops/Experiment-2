package com.artflow.studio.data.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Checked preference and recording deletion on actual files, without permission-dependent faults. */
class TimelapseControlFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun anEmptyLegacyPauseMarkerStillStopsRecordingAndCheckedResumeRemovesIt() {
        val project = temporary.newFolder("project")
        assertTrue(TimelapseFiles.recordingAllowed(project))
        TimelapseFiles.pauseFile(project).writeBytes(byteArrayOf())
        assertFalse(TimelapseFiles.recordingAllowed(project))

        TimelapseFiles.removePause(project)

        assertTrue(TimelapseFiles.recordingAllowed(project))
        TimelapseFiles.removePause(project)
        assertTrue(TimelapseFiles.recordingAllowed(project))
    }

    @Test
    fun resumeRefusesAnUnexpectedDirectoryWithoutDeletingItsContents() {
        val project = temporary.newFolder("project")
        val marker = File(project, "timelapse.paused").apply { mkdirs() }
        val keep = File(marker, "Keep.txt").apply { writeText("Unrelated data") }

        assertFalse(TimelapseFiles.recordingAllowed(project))
        assertTrue(runCatching { TimelapseFiles.removePause(project) }.exceptionOrNull() is IOException)
        assertTrue(marker.isDirectory)
        assertArrayEquals("Unrelated data".toByteArray(), keep.readBytes())
    }

    @Test
    fun aDanglingPreferenceLinkIsPausedAndCannotBeOverwrittenOrFollowed() {
        val project = temporary.newFolder("project")
        val outside = File(temporary.root, "absent-outside")
        val marker = File(project, "timelapse.paused")
        Files.createSymbolicLink(marker.toPath(), outside.toPath())

        assertFalse(TimelapseFiles.recordingAllowed(project))
        assertTrue(runCatching { TimelapseFiles.pauseFile(project) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { TimelapseFiles.removePause(project) }.exceptionOrNull() is IOException)
        assertTrue(Files.isSymbolicLink(marker.toPath()))
        assertFalse(outside.exists())
    }

    @Test
    fun clearDeletesRecordingLeavesButPreservesThePreferenceArtworkAndLinkedTargets() {
        val project = temporary.newFolder("project")
        val frames = TimelapseFiles.directory(project)
        File(frames, "000000.jpg").writeText("Recorded frame")
        val outside = temporary.newFolder("outside")
        val outsideFrame = File(outside, "Protected.jpg").apply { writeText("Independent recording") }
        Files.createSymbolicLink(File(frames, "linked").toPath(), outside.toPath())
        val saved = File(project, "canvas.artflow").apply { writeText("Saved artwork") }
        val recovery = File(project, "autosave.artflow").apply { writeText("Recovery artwork") }
        val marker = TimelapseFiles.pauseFile(project).apply { writeBytes(byteArrayOf()) }

        TimelapseFiles.clear(project)

        assertFalse(frames.exists())
        assertArrayEquals("Independent recording".toByteArray(), outsideFrame.readBytes())
        assertArrayEquals("Saved artwork".toByteArray(), saved.readBytes())
        assertArrayEquals("Recovery artwork".toByteArray(), recovery.readBytes())
        assertTrue(marker.isFile)
        assertFalse(TimelapseFiles.recordingAllowed(project))
    }

    @Test
    fun clearRemovesOnlyALinkAtTheRecordingRoot() {
        val project = temporary.newFolder("project")
        val outside = temporary.newFolder("outside")
        val keep = File(outside, "000000.jpg").apply { writeText("Another artwork") }
        val frames = File(project, TimelapseFiles.DIRECTORY)
        Files.createSymbolicLink(frames.toPath(), outside.toPath())

        TimelapseFiles.clear(project)

        assertFalse(Files.isSymbolicLink(frames.toPath()))
        assertTrue(outside.isDirectory)
        assertArrayEquals("Another artwork".toByteArray(), keep.readBytes())
    }

    @Test
    fun linkedArtworkRootIsRejectedAndTheOriginalRecordingCanBeClearedAfterRecovery() {
        val project = temporary.newFolder("project")
        val frames = TimelapseFiles.directory(project)
        File(frames, "000000.jpg").writeText("Owned frame")
        val moved = File(temporary.root, "moved-project")
        val outside = temporary.newFolder("outside")
        val keep =
            File(outside, "timelapse/Protected.jpg").apply {
                parentFile!!.mkdirs()
                writeText("Protected target")
            }
        Files.move(project.toPath(), moved.toPath())
        Files.createSymbolicLink(project.toPath(), outside.toPath())
        try {
            assertTrue(runCatching { TimelapseFiles.clear(project) }.exceptionOrNull() is IOException)
            assertArrayEquals("Owned frame".toByteArray(), File(moved, "timelapse/000000.jpg").readBytes())
            assertArrayEquals("Protected target".toByteArray(), keep.readBytes())
        } finally {
            Files.delete(project.toPath())
            Files.move(moved.toPath(), project.toPath())
        }
        TimelapseFiles.clear(project)
        assertFalse(frames.exists())
        assertArrayEquals("Protected target".toByteArray(), keep.readBytes())
    }

    @Test
    fun openingFramesRejectsALinkOrAFileInsteadOfFollowingOrHidingIt() {
        val project = temporary.newFolder("project")
        val frames = File(project, TimelapseFiles.DIRECTORY).apply { writeText("Unexpected file") }
        assertTrue(runCatching { TimelapseFiles.directory(project) }.exceptionOrNull() is IllegalStateException)
        assertArrayEquals("Unexpected file".toByteArray(), frames.readBytes())
        assertTrue(frames.delete())
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(frames.toPath(), outside.toPath())
        assertTrue(runCatching { TimelapseFiles.directory(project) }.exceptionOrNull() is IOException)
        assertTrue(Files.isSymbolicLink(frames.toPath()))
    }
}
