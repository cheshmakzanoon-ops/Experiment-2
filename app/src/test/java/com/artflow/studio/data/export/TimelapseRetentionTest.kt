package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.lifecycle.assertOriginalFailure
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/** Exercises thinning through the real recorder and atomic storage, including the full-recording boundary. */
class TimelapseRetentionTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var realStorage: ProjectStorage
    private lateinit var storage: ProjectStorage
    private lateinit var recorder: TimelapseRecorder
    private lateinit var recordingDir: File
    private lateinit var protectedFiles: Map<File, ByteArray>

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        realStorage = ProjectStorage(context)
        storage = spy(realStorage)
        recorder = TimelapseRecorder(storage)
        val projectDir = storage.projectDir(PROJECT)
        recordingDir = File(projectDir, TimelapseRecorder.DIR_NAME).apply { mkdirs() }
        protectedFiles =
            listOf("canvas.artflow", "autosave.artflow", "layers/1/saved.png", "layers/1/recovery.png")
                .associate { relative ->
                    val bytes = "Preserved $relative".toByteArray()
                    val file = File(projectDir, relative).apply { parentFile!!.mkdirs() }
                    file.writeBytes(bytes)
                    file to bytes
                }
    }

    @Test
    fun publicationFailureAtLimitPreservesEveryFrameAndRetryKeepsSurvivorNames() =
        runBlocking {
            val originals = seedFrames(TimelapseRecorder.MAX_FRAMES)
            val target = File(recordingDir, frameName(TimelapseRecorder.MAX_FRAMES))
            val failure = IOException("Disk failed after syncing the new JPEG")
            var failCommit = true
            var reachedCommit = false
            interceptPublication(target) {
                reachedCommit = true
                if (failCommit) throw failure
            }
            val source = PixelBuffer.filled(24, 16, INK)
            val pixels = source.pixels.copyOf()

            assertOriginalFailure(failure, runCatching { recorder.capture(PROJECT, source) }.exceptionOrNull())

            assertTrue(reachedCommit)
            assertRecording(originals)
            assertArrayEquals(pixels, source.pixels)

            failCommit = false
            recorder.capture(PROJECT, source)

            val retained = originals.filterKeys { it.substringBefore('.').toInt() % 2 == 0 }
            assertEquals(retained.keys + target.name, recordingDir.list()!!.toSet())
            retained.forEach { (name, bytes) -> assertArrayEquals(bytes, File(recordingDir, name).readBytes()) }
            assertEquals(TimelapseRecorder.MAX_FRAMES / 2 + 1, recorder.frameCount(PROJECT))
            val decoded = requireNotNull(ImageIO.read(target))
            assertEquals(source.width, decoded.width)
            assertEquals(source.height, decoded.height)
            assertProtectedFiles()
        }

    @Test
    fun recordingStoppedAfterSyncAtLimitDoesNotThinHistory() =
        runBlocking {
            val originals = seedFrames(TimelapseRecorder.MAX_FRAMES)
            val target = File(recordingDir, frameName(TimelapseRecorder.MAX_FRAMES))
            val permitted = AtomicBoolean(true)
            var reachedCommit = false
            interceptPublication(target) {
                reachedCommit = true
                permitted.set(false)
            }

            recorder.capture(PROJECT, PixelBuffer.filled(24, 16, INK), permitted = permitted::get)

            assertTrue(reachedCommit)
            assertRecording(originals)
        }

    @Test
    fun cancellationAfterSyncAtLimitPropagatesWithoutThinningHistory() =
        runBlocking {
            val originals = seedFrames(TimelapseRecorder.MAX_FRAMES)
            val target = File(recordingDir, frameName(TimelapseRecorder.MAX_FRAMES))
            lateinit var operation: Deferred<Unit>
            var reachedCommit = false
            interceptPublication(target) {
                reachedCommit = true
                operation.cancel(CancellationException("Editor closed after JPEG sync"))
            }
            operation = async(start = CoroutineStart.LAZY) { recorder.capture(PROJECT, PixelBuffer.filled(24, 16, INK)) }
            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()
            operation.join()

            assertTrue(failure is CancellationException)
            assertTrue(reachedCommit)
            assertRecording(originals)
        }

    @Test
    fun interruptedPruningCannotGrowRecordingOnEverySubsequentCapture() =
        runBlocking {
            // A previous append became durable, but none of its older files could be removed.
            val originals = seedFrames(TimelapseRecorder.MAX_FRAMES + 1)

            for (color in listOf(INK, OTHER_INK)) {
                val failure = runCatching { recorder.capture(PROJECT, PixelBuffer.filled(24, 16, color)) }.exceptionOrNull()
                assertTrue("A full recording must refuse another append", failure is IllegalStateException)
                assertRecording(originals)
            }
        }

    @Test
    fun sevenDigitSequencesRemainOrderedAndNextCaptureDoesNotOverwriteOldFrames() =
        runBlocking {
            val originals =
                listOf("000000.jpg", "999999.jpg", "1000000.jpg").associateWith { name ->
                    "Retained $name".toByteArray().also { File(recordingDir, name).writeBytes(it) }
                }

            recorder.capture(PROJECT, PixelBuffer.filled(24, 16, INK))

            assertTrue(File(recordingDir, "1000001.jpg").isFile)
            originals.forEach { (name, bytes) -> assertArrayEquals(bytes, File(recordingDir, name).readBytes()) }
            recorder.withFrameSnapshot(PROJECT) { files ->
                assertEquals(4, files.size)
                originals.values.zip(files).forEach { (expected, file) -> assertArrayEquals(expected, file.readBytes()) }
            }
            assertProtectedFiles()
        }

    @Test
    fun exhaustedSequenceRefusesCaptureWithoutOverwritingFirstFrame() =
        runBlocking {
            val originals =
                listOf("000000.jpg", "${Long.MAX_VALUE}.jpg").associateWith { name ->
                    "Retained $name".toByteArray().also { File(recordingDir, name).writeBytes(it) }
                }

            val failure = runCatching { recorder.capture(PROJECT, PixelBuffer.filled(24, 16, INK)) }.exceptionOrNull()

            assertTrue(failure is ArithmeticException)
            assertRecording(originals)
        }

    @Test
    fun collidingPixelHashesRetainDistinctEncodedFramesAndIdenticalJpegsAreStillOmitted() =
        runBlocking {
            val first = PixelBuffer(24, 16)
            val second = PixelBuffer(24, 16)
            first.pixels.indices.forEach { index ->
                // +4 red in the first pixel cancels -124 red in the next under the old 31x hash.
                first.pixels[index] = if (index % 2 == 0) 0xFF000000.toInt() else 0xFF7C0000.toInt()
                second.pixels[index] = if (index % 2 == 0) 0xFF040000.toInt() else 0xFF000000.toInt()
            }
            assertEquals(first.pixels.contentHashCode(), second.pixels.contentHashCode())
            val firstJpeg = BitmapPixelBridge.toJpegBytes(first, 85, 0xFFFFFFFF.toInt())
            val secondJpeg = BitmapPixelBridge.toJpegBytes(second, 85, 0xFFFFFFFF.toInt())
            assertFalse("The collision fixture must survive lossy JPEG encoding", firstJpeg.contentEquals(secondJpeg))

            recorder.capture(PROJECT, first)
            recorder.capture(PROJECT, second)
            recorder.capture(PROJECT, second)

            assertEquals(2, recorder.frameCount(PROJECT))
            assertArrayEquals(firstJpeg, File(recordingDir, "000000.jpg").readBytes())
            assertArrayEquals(secondJpeg, File(recordingDir, "000001.jpg").readBytes())
            assertProtectedFiles()
        }

    private fun seedFrames(count: Int): Map<String, ByteArray> =
        (0 until count).associate { index ->
            val name = frameName(index)
            val bytes = "Retained frame $index".toByteArray()
            File(recordingDir, name).writeBytes(bytes)
            name to bytes
        }

    private fun frameName(index: Int): String = String.format(Locale.ROOT, "%06d.jpg", index)

    private fun assertRecording(expected: Map<String, ByteArray>) {
        assertEquals(expected.keys, recordingDir.list()!!.toSet())
        expected.forEach { (name, bytes) -> assertArrayEquals(bytes, File(recordingDir, name).readBytes()) }
        assertProtectedFiles()
    }

    private fun assertProtectedFiles() {
        protectedFiles.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    /** The real helper still writes, syncs and owns the staged file when this failure is injected. */
    private fun interceptPublication(
        target: File,
        beforeCommit: () -> Unit,
    ) {
        doAnswer { invocation ->
            val checkCapture = invocation.getArgument<() -> Unit>(2)
            val write = invocation.getArgument<(FileOutputStream) -> Unit>(3)
            realStorage.writeAtomically(
                target,
                modifiedAfter = null,
                beforeCommit = {
                    beforeCommit()
                    checkCapture()
                },
                write = write,
            )
        }.`when`(storage).writeAtomically(
            eq(target) ?: target,
            isNull(),
            any<() -> Unit>() ?: {},
            any<(FileOutputStream) -> Unit>() ?: {},
        )
    }

    private companion object {
        const val PROJECT = 47L
        const val INK = -15_133_237
        const val OTHER_INK = -12_345_678
    }
}
