package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import java.io.File
import java.io.IOException
import java.util.Locale

/** Replay owns copied frames for its whole scope, independent of live capture and clearing. */
class TimelapseReplaySnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var storage: ProjectStorage
    private lateinit var recorder: TimelapseRecorder
    private lateinit var projectDir: File
    private lateinit var recordingDir: File

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = spy(ProjectStorage(context))
        recorder = TimelapseRecorder(storage)
        projectDir = storage.projectDir(PROJECT)
        recordingDir = File(projectDir, TimelapseRecorder.DIR_NAME).apply { mkdirs() }
    }

    @Test
    fun replayCopiesKeepTheirBytesAndOrderAcrossCaptureThinningAndClear() =
        runBlocking {
            val originals = seedFrames(TimelapseRecorder.MAX_FRAMES)
            val saved = File(projectDir, "canvas.artflow").apply { writeText("Saved artwork") }
            val savedBytes = saved.readBytes()
            var replayFiles = emptyList<File>()

            recorder.withFrameSnapshot(PROJECT) { files ->
                replayFiles = files
                assertNotEquals(recordingDir, files.first().parentFile)
                assertEquals(TimelapseRecorder.MAX_FRAMES, files.size)
                assertTrue(storage.listExports(PROJECT).isEmpty())
                assertEquals(0, storage.pruneOldExports(PROJECT, 0))

                recorder.capture(PROJECT, PixelBuffer.filled(24, 16, 0xFF123456.toInt()))
                assertEquals(TimelapseRecorder.MAX_FRAMES / 2 + 1, recorder.frameCount(PROJECT))
                assertCopies(originals, files)

                recorder.clear(PROJECT)
                assertEquals(0, recorder.frameCount(PROJECT))
                assertCopies(originals, files)
                assertArrayEquals(savedBytes, saved.readBytes())
            }

            assertTrue(replayFiles.none { it.exists() })
            assertTrue(storage.exportsDir(PROJECT).list()!!.isEmpty())
            assertArrayEquals(savedBytes, saved.readBytes())
        }

    @Test
    fun overlappingScopesOwnSeparateCopiesAndFailureClosesOnlyItsOwnSnapshot() =
        runBlocking {
            val originals = seedFrames(3)
            val failure = IOException("Replay failed while decoding")
            var failedFiles = emptyList<File>()

            recorder.withFrameSnapshot(PROJECT) { first ->
                val thrown =
                    runCatching {
                        recorder.withFrameSnapshot(PROJECT) { second ->
                            failedFiles = second
                            assertNotEquals(first.first().parentFile, second.first().parentFile)
                            assertEquals(2, storage.exportsDir(PROJECT).listFiles()!!.size)
                            throw failure
                        }
                    }.exceptionOrNull()

                assertSame(failure, thrown)
                assertTrue(failedFiles.none { it.exists() })
                assertEquals(1, storage.exportsDir(PROJECT).listFiles()!!.size)
                assertCopies(originals, first)
            }

            assertTrue(storage.exportsDir(PROJECT).list()!!.isEmpty())
            assertCopies(originals, recordingDir.listFiles()!!.sortedBy { it.name })
        }

    @Test
    fun cancellingAReplayClosesItsSnapshotAndKeepsOriginalFrames() =
        runBlocking {
            val originals = seedFrames(3)
            val entered = CompletableDeferred<List<File>>()
            val replay =
                async {
                    recorder.withFrameSnapshot(PROJECT) { files ->
                        entered.complete(files)
                        awaitCancellation()
                    }
                }
            val copies = entered.await()
            try {
                assertCopies(originals, copies)
            } finally {
                replay.cancel()
                replay.join()
            }

            assertTrue(copies.none { it.exists() })
            assertTrue(storage.exportsDir(PROJECT).list()!!.isEmpty())
            assertCopies(originals, recordingDir.listFiles()!!.sortedBy { it.name })
        }

    @Test
    fun cancellationDuringAcquisitionNeverExposesOrLeaksAPartialSnapshot() =
        runBlocking {
            val originals = seedFrames(3)
            lateinit var operation: Deferred<Unit>
            var shown = false
            doAnswer {
                operation.cancel(CancellationException("Replay was dismissed during acquisition"))
                projectDir
            }.`when`(storage).projectDir(PROJECT)
            operation =
                async(start = CoroutineStart.LAZY) {
                    recorder.withFrameSnapshot(PROJECT) { shown = true }
                }
            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()
            operation.join()

            assertTrue(failure is CancellationException)
            assertFalse(shown)
            assertTrue(storage.exportsDir(PROJECT).list()!!.isEmpty())
            assertCopies(originals, recordingDir.listFiles()!!.sortedBy { it.name })
        }

    private fun seedFrames(count: Int): List<ByteArray> =
        List(count) { index ->
            val bytes = "Recorded frame $index".toByteArray()
            File(recordingDir, String.format(Locale.ROOT, "%06d.jpg", index)).writeBytes(bytes)
            bytes
        }

    private fun assertCopies(
        expected: List<ByteArray>,
        files: List<File>,
    ) {
        assertEquals(expected.size, files.size)
        expected.zip(files).forEach { (bytes, file) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    private companion object {
        const val PROJECT = 53L
    }
}
