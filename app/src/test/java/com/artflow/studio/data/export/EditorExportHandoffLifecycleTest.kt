package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.local.ShareExportFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Real file copies and dispatcher deliveries; Android intent construction is exercised on device. */
@OptIn(ExperimentalCoroutinesApi::class)
class EditorExportHandoffLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun anAlreadyReplacedResultDoesNotReadOrCopyItsOldPath() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                var launched = false
                fixture.exporter.launchExport(fixture.result, open = false, isCurrent = { false }) { launched = true }

                assertFalse(launched)
                assertFalse(fixture.source.exists())
                assertFalse(fixture.directory.exists())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aResultReplacedDuringCopyRemovesOnlyItsUnpublishedVideo() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.writeSource()
                val earlier = ShareExportFiles.copy(fixture.directory, fixture.source.name, fixture.source)
                ShareExportFiles.markPublished(earlier)
                ShareExportFiles.finish(earlier)
                var checks = 0
                var launched = false
                fixture.exporter.launchExport(fixture.result, open = true, isCurrent = { ++checks == 1 }) { launched = true }

                assertEquals(2, checks)
                assertFalse(launched)
                fixture.assertOnlyOriginalAnd(earlier)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun providerFailureRemovesOnlyTheNewCopyAndPreservesTheOriginalFailure() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.writeSource()
                val earlier = ShareExportFiles.copy(fixture.directory, fixture.source.name, fixture.source)
                ShareExportFiles.markPublished(earlier)
                ShareExportFiles.finish(earlier)
                val denied = SecurityException("FileProvider is unavailable")
                `when`(fixture.context.packageName).thenThrow(denied)

                val failure =
                    runCatching {
                        fixture.exporter.launchExport(fixture.result, open = false, isCurrent = { true }) {
                            error("A failed provider must never launch an activity")
                        }
                    }.exceptionOrNull()

                assertTrue(failure is SecurityException)
                assertEquals(denied.message, failure?.message)
                fixture.assertOnlyOriginalAnd(earlier)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun cleanupFailureAfterAStaleRequestStillReachesTheCaller() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.writeSource()
                var checks = 0
                var sibling: File? = null
                val failure =
                    runCatching {
                        fixture.exporter.launchExport(
                            fixture.result,
                            open = false,
                            isCurrent = {
                                if (++checks == 1) {
                                    true
                                } else {
                                    sibling = fixture.obstructCleanup()
                                    false
                                }
                            },
                        ) { error("A stale request must never launch") }
                    }.exceptionOrNull()

                assertEquals(2, checks)
                assertNotNull("Cleanup-only failure must not be silently logged as success", failure)
                assertArrayEquals(fixture.bytes, fixture.source.readBytes())
                assertArrayEquals(byteArrayOf(9, 7, 5), requireNotNull(sibling).readBytes())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun launchFailureRetainsCleanupDiagnosticsAcrossDispatcherRecovery() =
        retainsCleanupFailure(SecurityException("FileProvider denied this handoff"))

    @Test
    fun cancellationRetainsCleanupDiagnosticsAcrossDispatcherRecovery() =
        retainsCleanupFailure(CancellationException("Editor left during handoff"))

    private fun retainsCleanupFailure(original: Exception) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture(temporary.newFolder("files"))
            try {
                fixture.writeSource()
                var sibling: File? = null
                `when`(fixture.context.packageName).thenAnswer {
                    sibling = fixture.obstructCleanup()
                    throw original
                }

                val failure =
                    runCatching {
                        withContext(Dispatchers.Default) {
                            fixture.exporter.launchExport(fixture.result, open = true, isCurrent = { true }) {
                                error("A failed provider must never launch")
                            }
                        }
                    }.exceptionOrNull()

                assertNotNull(failure)
                assertEquals(original.javaClass, failure?.javaClass)
                assertEquals(original.message, failure?.message)
                assertTrue("Retain cleanup diagnostics across dispatcher stack recovery", requireNotNull(failure).suppressed.isNotEmpty())
                assertArrayEquals(fixture.bytes, fixture.source.readBytes())
                assertArrayEquals(byteArrayOf(9, 7, 5), requireNotNull(sibling).readBytes())
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun cancellationBeforeUiDeliveryDeletesTheCompletedCopyWithoutLaunching() =
        runBlocking {
            val resumes = LinkedBlockingQueue<Runnable>()
            val ui =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        resumes.add(block)
                    }
                }
            Dispatchers.setMain(ui)
            val fixture = Fixture(temporary.newFolder("files"))
            var operation: Deferred<Unit>? = null
            var delivery: Runnable? = null
            try {
                fixture.writeSource()
                var launched = false
                val pending =
                    async(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
                        fixture.exporter.launchExport(fixture.result, open = true, isCurrent = { true }) { launched = true }
                    }
                operation = pending
                // First return resolves the validated source; the next return owns the completed copy.
                requireNotNull(resumes.poll(10, TimeUnit.SECONDS)).run()
                val copied = requireNotNull(resumes.poll(10, TimeUnit.SECONDS))
                delivery = copied
                val staged = File(fixture.directory.listFiles()!!.single { it.isDirectory }, fixture.source.name)
                assertArrayEquals(fixture.bytes, staged.readBytes())

                pending.cancel(CancellationException("Editor closed before opening the video"))
                copied.run()
                delivery = null
                while (!pending.isCompleted) requireNotNull(resumes.poll(10, TimeUnit.SECONDS)).run()

                assertTrue(pending.isCancelled)
                assertFalse(launched)
                assertEquals(listOf(fixture.source), fixture.directory.listFiles()!!.toList())
                assertArrayEquals(fixture.bytes, fixture.source.readBytes())
            } finally {
                operation?.cancel()
                delivery?.run()
                while (operation?.isCompleted == false) requireNotNull(resumes.poll(10, TimeUnit.SECONDS)).run()
                Dispatchers.resetMain()
            }
        }

    private class Fixture(
        files: File,
    ) {
        val context = mock(Context::class.java).also { `when`(it.filesDir).thenReturn(files) }
        val storage = ProjectStorage(context)
        val exporter = ArtworkExporter(context, storage)
        val directory = File(files, "exports/19")
        val source = File(directory, "Replay.mp4")
        val bytes = ByteArray(8192 * 33 + 5) { (it * 31).toByte() }
        val result = ExportResult(ExportFormat.MP4, source.absolutePath, source.name, bytes.size.toLong(), 32, 16)

        suspend fun writeSource() {
            storage.saveExport(19, source.name, bytes)
        }

        fun obstructCleanup(): File =
            File(directory.listFiles()!!.single { it.isDirectory }, "not-owned.bin").apply {
                writeBytes(byteArrayOf(9, 7, 5))
            }

        fun assertOnlyOriginalAnd(earlier: File) {
            assertArrayEquals(bytes, source.readBytes())
            assertArrayEquals(bytes, earlier.readBytes())
            assertEquals(setOf(source, earlier.parentFile), directory.listFiles()!!.toSet())
        }
    }
}
