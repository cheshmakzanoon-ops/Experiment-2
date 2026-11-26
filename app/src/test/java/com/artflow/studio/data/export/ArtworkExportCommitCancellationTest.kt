package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
import javax.imageio.ImageIO

/** Uses the real animation encoder, file writes, sync and rename with deterministic cancellation. */
class ArtworkExportCommitCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var realStorage: ProjectStorage
    private lateinit var storage: ProjectStorage
    private lateinit var exporter: ArtworkExporter

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        realStorage = ProjectStorage(context)
        storage = spy(realStorage)
        exporter = ArtworkExporter(context, storage)
    }

    @Test
    fun animationCancelledAfterWritingKeepsPreviousExportAndSourceFrames() = cancelledAnimation(afterSync = false)

    @Test
    fun animationCancelledAfterSyncKeepsPreviousExportAndSourceFrames() = cancelledAnimation(afterSync = true)

    @Test
    fun streamedFileCancelledAfterSyncKeepsPreviousExportAndEncoderSource() =
        runBlocking {
            val prior = byteArrayOf(9, 8, 7, 6)
            val target = realStorage.saveExport(PROJECT, "Video.mp4", prior)
            val sourceBytes = ByteArray(8192 * 17 + 3) { (it * 31).toByte() }
            val source = temporary.newFile("encoded.mp4").apply { writeBytes(sourceBytes) }
            lateinit var operation: Deferred<File>
            var stagedBytes = 0L
            var reachedCommit = false
            interceptPublication(
                target,
                afterWrite = { stagedBytes = it.channel.size() },
                beforeCommit = {
                    reachedCommit = true
                    operation.cancel(CancellationException("Editor closed during file sync"))
                },
            )
            operation = async(start = CoroutineStart.LAZY) { storage.saveExportFromFile(PROJECT, target.name, source) }
            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()
            operation.join()

            assertTrue(failure is CancellationException)
            assertTrue("Cancellation must occur after the staged file was synced", reachedCommit)
            assertEquals(sourceBytes.size.toLong(), stagedBytes)
            assertArrayEquals(prior, target.readBytes())
            assertArrayEquals(sourceBytes, source.readBytes())
            assertEquals(listOf(target.name), realStorage.exportsDir(PROJECT).list()!!.toList())
        }

    @Test
    fun successfulAnimationReplacesPreviousExportAndReopensEveryFrame() =
        runBlocking {
            val frames = frames()
            val originals = frames.map { it.pixels.copyOf() }
            val target = realStorage.saveExport(PROJECT, "Animation.gif", byteArrayOf(9, 8, 7))

            val result = exporter.exportAnimation(PROJECT, "Animation", frames, listOf(100, 250), options()).getOrThrow()

            assertEquals(ExportFormat.GIF, result.format)
            assertEquals(target.absolutePath, result.filePath)
            assertEquals(target.length(), result.byteCount)
            assertEquals(2, result.frameCount)
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            try {
                ImageIO.createImageInputStream(target).use { input ->
                    reader.input = input
                    assertEquals(frames.size, reader.getNumImages(true))
                    frames.forEachIndexed { index, frame ->
                        val decoded = reader.read(index)
                        assertEquals(frame.width, decoded.width)
                        assertEquals(frame.height, decoded.height)
                        assertArrayEquals(originals[index], decoded.getRGB(0, 0, frame.width, frame.height, null, 0, frame.width))
                        assertArrayEquals(originals[index], frame.pixels)
                    }
                }
            } finally {
                reader.dispose()
            }
            assertEquals(listOf(target.name), realStorage.exportsDir(PROJECT).list()!!.toList())
        }

    private fun cancelledAnimation(afterSync: Boolean) =
        runBlocking {
            val frames = frames()
            val originals = frames.map { it.pixels.copyOf() }
            val prior = byteArrayOf(9, 8, 7, 6)
            val target = realStorage.saveExport(PROJECT, "Animation.gif", prior)
            lateinit var operation: Deferred<Result<ExportResult>>
            var stagedBytes = 0L
            var reachedCommit = false
            interceptPublication(
                target,
                afterWrite = {
                    stagedBytes = it.channel.size()
                    if (!afterSync) operation.cancel(CancellationException("Editor closed during export write"))
                },
                beforeCommit = {
                    reachedCommit = true
                    if (afterSync) operation.cancel(CancellationException("Editor closed during file sync"))
                },
            )
            operation =
                async(start = CoroutineStart.LAZY) {
                    exporter.exportAnimation(PROJECT, "Animation", frames, listOf(100, 250), options())
                }
            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()
            operation.join()

            assertTrue(failure is CancellationException)
            assertTrue("The real GIF must be written before cancellation", stagedBytes > prior.size)
            assertTrue("The final cancellation check must run after sync", reachedCommit)
            assertArrayEquals(prior, target.readBytes())
            assertEquals(listOf(target.name), realStorage.exportsDir(PROJECT).list()!!.toList())
            frames.forEachIndexed { index, frame -> assertArrayEquals(originals[index], frame.pixels) }
        }

    /** Wraps only the caller's callbacks; the real atomic helper still owns the complete write. */
    private fun interceptPublication(
        target: File,
        afterWrite: (FileOutputStream) -> Unit = {},
        beforeCommit: () -> Unit = {},
    ) {
        doAnswer { invocation ->
            val checkActive = invocation.getArgument<() -> Unit>(2)
            val write = invocation.getArgument<(FileOutputStream) -> Unit>(3)
            realStorage.writeAtomically(
                target,
                null,
                beforeCommit = {
                    beforeCommit()
                    checkActive()
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

    private fun frames() = listOf(PixelBuffer.filled(24, 16, RED), PixelBuffer.filled(24, 16, BLUE))

    private fun options() = ExportOptions(ExportFormat.GIF, fileName = "Animation.gif")

    private companion object {
        const val PROJECT = 41L
        const val RED = -65536
        const val BLUE = -16776961
    }
}
