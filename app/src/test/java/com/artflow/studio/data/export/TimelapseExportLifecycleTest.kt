package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.data.local.ProjectStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the production export's real snapshot and cleanup, before platform codec work. */
class TimelapseExportLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var storage: ProjectStorage
    private lateinit var projectDir: File
    private lateinit var frame: File
    private val projectId = 19L

    @Before
    fun setUp() {
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = spy(ProjectStorage(context))
        projectDir = storage.projectDir(projectId)
        val recording = File(projectDir, TimelapseRecorder.DIR_NAME).apply { mkdirs() }
        frame = File(recording, "000000.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun cancellationDuringSnapshotPropagatesAndRemovesEveryTemporaryFile() =
        runBlocking {
            val prior = storage.saveExport(projectId, "Artwork timelapse.mp4", byteArrayOf(4, 5, 6))
            lateinit var operation: Deferred<Result<ExportResult>>
            doAnswer {
                operation.cancel(CancellationException("Editor closed"))
                projectDir
            }.`when`(storage).projectDir(projectId)
            operation =
                async(start = CoroutineStart.LAZY) {
                    TimelapseRecorder(storage).export(projectId, "Artwork", fullLength = true)
                }
            operation.start()
            val failure = runCatching { operation.await() }.exceptionOrNull()

            assertTrue(failure is CancellationException)
            assertEquals(listOf(prior.name), storage.exportsDir(projectId).list()!!.toList())
            assertArrayEquals(byteArrayOf(4, 5, 6), prior.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), frame.readBytes())
        }

    @Test
    fun failedFrameDecodeKeepsRecordingAndPreviousVideoButRemovesSnapshot() =
        runBlocking {
            val prior = storage.saveExport(projectId, "Artwork timelapse.mp4", byteArrayOf(4, 5, 6))
            val result = TimelapseRecorder(storage).export(projectId, "Artwork", fullLength = true)

            assertTrue("Invalid JPEG must fail before any video can be published", result.isFailure)
            assertEquals(listOf(prior.name), storage.exportsDir(projectId).list()!!.toList())
            assertArrayEquals(byteArrayOf(4, 5, 6), prior.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), frame.readBytes())
        }

    @Test
    fun overlappingExportsOwnSeparateSnapshotsAndCancellationCleansBoth() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            doAnswer {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Snapshot gate timed out" }
                projectDir
            }.`when`(storage).projectDir(projectId)
            val recorder = TimelapseRecorder(storage)
            val first = async(Dispatchers.IO) { recorder.export(projectId, "First") }
            val second = async(Dispatchers.IO) { recorder.export(projectId, "Second") }
            try {
                assertTrue("First snapshot did not start", entered.await(5, TimeUnit.SECONDS))
                withTimeout(5_000) {
                    while (storage.exportsDir(projectId).listFiles()!!.count { it.isDirectory } < 2) delay(1)
                }
                val snapshots = storage.exportsDir(projectId).listFiles()!!.filter { it.isDirectory }
                assertEquals(2, snapshots.size)
                assertEquals(2, snapshots.map { it.name }.distinct().size)
            } finally {
                first.cancel()
                second.cancel()
                release.countDown()
                first.join()
                second.join()
            }
            assertTrue(storage.exportsDir(projectId).listFiles()!!.isEmpty())
            assertArrayEquals(byteArrayOf(1, 2, 3), frame.readBytes())
        }
}
