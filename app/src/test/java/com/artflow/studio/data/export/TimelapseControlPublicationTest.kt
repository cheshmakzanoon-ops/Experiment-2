package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files

/** The actual recorder must stop after a failed pause and preserve data on refused controls. */
class TimelapseControlPublicationTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var storage: ProjectStorage
    private lateinit var recorder: TimelapseRecorder
    private lateinit var project: File

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = spy(ProjectStorage(context))
        recorder = TimelapseRecorder(storage)
        project = storage.projectDir(PROJECT)
    }

    @Test
    fun failedPausePreventsCaptureUntilASuccessfulPreferenceRetryAndSurvivesReopeningTheArtworkInProcess() =
        runBlocking {
            val saved = File(project, "canvas.artflow").apply { writeText("Saved artwork") }
            val recovery = File(project, "autosave.artflow").apply { writeText("Recoverable artwork") }
            val marker = File(project, "timelapse.paused")
            var failWrite = true
            doAnswer { call ->
                if (failWrite) throw IOException("The preference could not be stored")
                call.callRealMethod()
            }.`when`(storage).writeAtomically(
                eq(marker) ?: marker,
                isNull(),
                any<() -> Unit>() ?: {},
                any<(FileOutputStream) -> Unit>() ?: {},
            )

            val failure = runCatching { recorder.setRecording(PROJECT, false) }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertEquals("The preference could not be stored", failure?.message)
            assertFalse(marker.exists())
            assertFalse(recorder.isRecording(PROJECT))
            recorder.capture(PROJECT, PixelBuffer.filled(24, 16, INK))
            assertEquals(0, recorder.frameCount(PROJECT))
            // A fresh process cannot recover a choice that failed to reach storage. The UI must
            // disclose that limitation instead of claiming the preference was saved.
            assertTrue(TimelapseRecorder(storage).isRecording(PROJECT))

            failWrite = false
            recorder.setRecording(PROJECT, false)
            assertTrue(marker.isFile)
            assertFalse(TimelapseRecorder(storage).isRecording(PROJECT))
            recorder.setRecording(PROJECT, true)
            assertTrue(recorder.isRecording(PROJECT))
            assertTrue(TimelapseRecorder(storage).isRecording(PROJECT))
            recorder.capture(PROJECT, PixelBuffer.filled(24, 16, INK))
            assertEquals(1, recorder.frameCount(PROJECT))
            assertArrayEquals("Saved artwork".toByteArray(), saved.readBytes())
            assertArrayEquals("Recoverable artwork".toByteArray(), recovery.readBytes())
        }

    @Test
    fun failedResumeIsReportedAndCannotRecordOrDeleteAnUnexpectedPreferenceDirectory() =
        runBlocking {
            val marker = File(project, "timelapse.paused").apply { mkdirs() }
            val keep = File(marker, "Keep.txt").apply { writeText("Must survive") }

            val failure = runCatching { recorder.setRecording(PROJECT, true) }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertFalse(recorder.isRecording(PROJECT))
            recorder.capture(PROJECT, PixelBuffer.filled(24, 16, INK))
            assertEquals(0, recorder.frameCount(PROJECT))
            assertArrayEquals("Must survive".toByteArray(), keep.readBytes())
            assertTrue(keep.delete())
            assertTrue(marker.delete())
            recorder.setRecording(PROJECT, true)
            recorder.capture(PROJECT, PixelBuffer.filled(24, 16, INK))
            assertEquals(1, recorder.frameCount(PROJECT))
        }

    @Test
    fun clearUnlinksARecordingRootWithoutErasingTheTargetOrResumingRecording() =
        runBlocking {
            recorder.setRecording(PROJECT, false)
            val outside = temporary.newFolder("outside-recording")
            val protected = File(outside, "000000.jpg").apply { writeText("Independent frame") }
            val recording = File(project, TimelapseRecorder.DIR_NAME)
            Files.createSymbolicLink(recording.toPath(), outside.toPath())
            val exported = File(storage.exportsDir(PROJECT), "Recording.mp4").apply { writeText("Previous export") }

            recorder.clear(PROJECT)

            assertFalse(Files.isSymbolicLink(recording.toPath()))
            assertFalse(recording.exists())
            assertArrayEquals("Independent frame".toByteArray(), protected.readBytes())
            assertArrayEquals("Previous export".toByteArray(), exported.readBytes())
            assertFalse(recorder.isRecording(PROJECT))
        }

    private companion object {
        const val PROJECT = 83L
        const val INK = -15_133_237
    }
}
