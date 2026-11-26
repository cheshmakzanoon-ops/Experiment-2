package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

/** Exercises the recorder's JPEG capture and real atomic storage across recording and editor changes. */
class TimelapseCapturePublicationTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var realStorage: ProjectStorage
    private lateinit var storage: ProjectStorage
    private lateinit var recorder: TimelapseRecorder
    private lateinit var projectDir: File
    private lateinit var recordingDir: File
    private lateinit var protectedFiles: Map<File, ByteArray>

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        realStorage = ProjectStorage(context)
        storage = spy(realStorage)
        recorder = TimelapseRecorder(storage)
        projectDir = realStorage.projectDir(PROJECT)
        recordingDir = File(projectDir, TimelapseRecorder.DIR_NAME).apply { mkdirs() }
        protectedFiles =
            listOf("canvas.artflow", "autosave.artflow", "layers/1/saved.png", "layers/1/recovery.png")
                .associate { relative ->
                    val bytes = "Preserved $relative".toByteArray()
                    val file =
                        File(projectDir, relative).apply {
                            parentFile!!.mkdirs()
                            writeBytes(bytes)
                        }
                    file to bytes
                }
    }

    @Test
    fun recordingStoppedAfterAdmissionDoesNotThinExistingFrames() =
        runBlocking {
            val originals = seedFrames(TimelapseRecorder.MAX_FRAMES)
            val permitted = AtomicBoolean(true)
            var recordingChecks = 0
            doAnswer {
                // capture already read permitted before its second recording-marker lookup.
                // The real Actions > Video setting can change while this IO/encoding continues.
                if (++recordingChecks == 2) permitted.set(false)
                projectDir
            }.`when`(storage).projectDir(PROJECT)
            val source = PixelBuffer.filled(24, 16, INK)
            val pixels = source.pixels.copyOf()

            recorder.capture(PROJECT, source, permitted = permitted::get)

            assertTrue("The settings change must occur after capture admission", recordingChecks >= 2)
            assertRecording(originals)
            assertProtectedFiles()
            assertArrayEquals(pixels, source.pixels)
        }

    @Test
    fun recordingStoppedAfterSyncDiscardsStagingAndCanCaptureTheSameFrameAfterResume() =
        runBlocking {
            val originals = seedFrames(1)
            val target = File(recordingDir, "000001.jpg")
            val permitted = AtomicBoolean(true)
            var stopAtCommit = true
            var stagedBytes = 0L
            var reachedCommit = false
            interceptPublication(
                target,
                afterWrite = { stagedBytes = it.channel.size() },
                beforeCommit = {
                    reachedCommit = true
                    if (stopAtCommit) permitted.set(false)
                },
            )
            val source = PixelBuffer.filled(24, 16, INK)
            val pixels = source.pixels.copyOf()

            recorder.capture(PROJECT, source, permitted = permitted::get)

            assertTrue("The actual JPEG must be staged before recording stops", stagedBytes > 0)
            assertTrue("The stop must occur after the staged JPEG is synced", reachedCommit)
            assertFalse(target.exists())
            assertRecording(originals)
            assertProtectedFiles()
            assertArrayEquals(pixels, source.pixels)

            stopAtCommit = false
            permitted.set(true)
            recorder.capture(PROJECT, source, permitted = permitted::get)

            assertEquals(2, recorder.frameCount(PROJECT))
            val decoded = requireNotNull(ImageIO.read(target))
            assertEquals(source.width, decoded.width)
            assertEquals(source.height, decoded.height)
            assertArrayEquals(originals.getValue("000000.jpg"), File(recordingDir, "000000.jpg").readBytes())
            assertProtectedFiles()
        }

    @Test
    fun editorCancellationAfterSyncPropagatesWithoutPublishingOrChangingPreviousFrames() =
        runBlocking {
            val originals = seedFrames(1)
            val target = File(recordingDir, "000001.jpg")
            lateinit var operation: Deferred<Unit>
            var stagedBytes = 0L
            var reachedCommit = false
            interceptPublication(
                target,
                afterWrite = { stagedBytes = it.channel.size() },
                beforeCommit = {
                    reachedCommit = true
                    operation.cancel(CancellationException("Artwork closed while its capture was syncing"))
                },
            )
            val source = PixelBuffer.filled(24, 16, INK)
            val pixels = source.pixels.copyOf()
            operation = async(start = CoroutineStart.LAZY) { recorder.capture(PROJECT, source) }
            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()
            operation.join()

            assertTrue(failure is CancellationException)
            assertTrue("The actual JPEG must be staged before cancellation", stagedBytes > 0)
            assertTrue("Cancellation must occur after the staged JPEG is synced", reachedCommit)
            assertFalse(target.exists())
            assertRecording(originals)
            assertProtectedFiles()
            assertArrayEquals(pixels, source.pixels)
        }

    private fun seedFrames(count: Int): Map<String, ByteArray> =
        (0 until count).associate { index ->
            val name = String.format(Locale.ROOT, "%06d.jpg", index)
            val bytes = "Retained frame $index".toByteArray()
            File(recordingDir, name).writeBytes(bytes)
            name to bytes
        }

    private fun assertRecording(expected: Map<String, ByteArray>) {
        assertEquals(expected.keys, recordingDir.list()!!.toSet())
        expected.forEach { (name, bytes) -> assertArrayEquals(bytes, File(recordingDir, name).readBytes()) }
    }

    private fun assertProtectedFiles() {
        protectedFiles.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
    }

    /** The production helper still owns writing, sync, atomic rename and temporary-file cleanup. */
    private fun interceptPublication(
        target: File,
        afterWrite: (FileOutputStream) -> Unit,
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
            ) { output ->
                write(output)
                afterWrite(output)
            }
        }.`when`(storage).writeAtomically(
            eq(target) ?: target,
            isNull(),
            any<() -> Unit>() ?: {},
            any<(FileOutputStream) -> Unit>() ?: {},
        )
    }

    private companion object {
        const val PROJECT = 43L
        const val INK = -15_133_237
    }
}
