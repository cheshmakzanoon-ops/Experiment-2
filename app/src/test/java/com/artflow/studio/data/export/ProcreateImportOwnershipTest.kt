package com.artflow.studio.data.export

import androidx.lifecycle.viewModelScope
import com.artflow.studio.core.export.ProcreateWriter
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.Project
import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.domain.repository.ProjectRepository
import com.artflow.studio.domain.repository.settings.SettingsRepository
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ProcreateImportOwnershipTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun consumerFailureClosesTheAlreadyOpenedArchive() =
        runTest {
            val failure =
                runCatching {
                    ProcreateImport.useOpened(temporary.root, { source() }) { document, _ ->
                        assertEquals(4, document.document.width)
                        assertEquals(
                            1,
                            temporary.root
                                .listFiles()
                                .orEmpty()
                                .size,
                        )
                        throw IOException("Project storage is full")
                    }
                }.exceptionOrNull()
            assertEquals("Project storage is full", failure?.message)
            assertEmpty()
        }

    @Test
    fun aProviderCloseFailureStillReleasesTheOpenedArchive() =
        runTest {
            val input =
                object : ByteArrayInputStream(bytes()) {
                    override fun close(): Unit = throw IOException("Provider failed to close")
                }
            val failure =
                runCatching {
                    ProcreateImport.useOpened(temporary.root, { input }) { _, _ -> error("Must not deliver a failed stream") }
                }.exceptionOrNull()
            assertEquals("Provider failed to close", failure?.message)
            assertEmpty()
        }

    @Test
    fun cancellationBetweenOpeningAndDispatcherDeliveryDoesNotLeak() =
        runTest {
            lateinit var worker: Job
            var consumed = false
            val input =
                object : ByteArrayInputStream(bytes()) {
                    override fun close() {
                        worker.cancel()
                        super.close()
                    }
                }
            worker =
                launch(start = CoroutineStart.LAZY) {
                    ProcreateImport.useOpened(temporary.root, { input }) { _, _ -> consumed = true }
                }
            worker.start()
            worker.join()
            assertTrue(worker.isCancelled)
            assertFalse(consumed)
            assertEmpty()
        }

    @Test
    fun projectCreationFailurePropagatesToTheImportOwnerAndCleansUp() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val projects =
                mock(ProjectRepository::class.java) { call ->
                    when (call.method.name) {
                        "getAllProjects" -> flowOf(emptyList<Project>())
                        "saveProject" -> throw IOException("Database rejected the new artwork")
                        else -> error("Unexpected project call: ${call.method.name}")
                    }
                }
            val settings = mock(SettingsRepository::class.java)
            `when`(settings.settings).thenReturn(flowOf(AppSettings()))
            val storage = mock(ProjectStorage::class.java)
            `when`(storage.totalStorageBytes()).thenReturn(0L)
            val viewModel = MainViewModel(projects, settings, storage)
            try {
                val failure =
                    runCatching {
                        ProcreateImport.useOpened(temporary.root, { source() }) { document, _ ->
                            val size = document.document
                            viewModel.createImportedProject("Imported", size.width, size.height, size.dpi)
                        }
                    }.exceptionOrNull()
                assertEquals("Database rejected the new artwork", failure?.message)
                assertEmpty()
            } finally {
                viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aFailedNavigationDiscardsOnlyItsUnclaimedResource() =
        runTest {
            ProcreateImport.useOpened(temporary.root, { source() }) { document, transfer ->
                PendingImports.putProcreate(1L, document, backgroundScope)
                transfer()
                PendingImports.discardProcreate(1L, document)
            }
            assertNull(PendingImports.takeProcreate(1L))
            assertEmpty()
        }

    @Test
    fun claimingTransfersOwnershipAndCancellationCannotCloseTheEditorResource() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            var claimed: ProcreateImport? = null
            try {
                ProcreateImport.useOpened(temporary.root, { source() }) { document, transfer ->
                    PendingImports.putProcreate(2L, document, owner)
                    transfer()
                    claimed = requireNotNull(PendingImports.takeProcreate(2L))
                    assertSame(document, claimed)
                    PendingImports.discardProcreate(2L, document)
                }
                owner.cancel()
                runCurrent()
                assertEquals(
                    1,
                    temporary.root
                        .listFiles()
                        .orEmpty()
                        .size,
                )
                assertNotNull(requireNotNull(claimed).document.composite())
            } finally {
                owner.cancel()
                claimed?.close()
            }
            assertEmpty()
        }

    @Test
    fun anAbandonedHandoffExpiresWithAnActionableNotice() =
        runTest {
            ProcreateImport.useOpened(temporary.root, { source() }) { document, transfer ->
                PendingImports.putProcreate(3L, document, backgroundScope)
                transfer()
            }
            runCurrent()
            advanceTimeBy(10 * 60 * 1000L + 1)
            runCurrent()
            assertNull(PendingImports.takeProcreate(3L))
            assertTrue(PendingImports.takeNotice(3L).orEmpty().contains("Import the original file again"))
            assertEmpty()
        }

    @Test
    fun cancellingTheGalleryOwnerClosesOnlyUnclaimedHandoffs() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            ProcreateImport.useOpened(temporary.root, { source() }) { document, transfer ->
                PendingImports.putProcreate(4L, document, owner)
                transfer()
            }
            owner.cancel()
            runCurrent()
            assertNull(PendingImports.takeProcreate(4L))
            assertNotNull(PendingImports.takeNotice(4L))
            assertEmpty()
        }

    @Test
    fun cancellationDuringCopyStopsBeforeReadingTheRemainingProviderBytes() =
        runTest {
            lateinit var worker: Job
            var reads = 0
            val input =
                object : ByteArrayInputStream(bytes()) {
                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        reads++
                        if (reads == 2) worker.cancel()
                        return super.read(buffer, offset, minOf(1, length))
                    }
                }
            worker =
                launch(start = CoroutineStart.LAZY) {
                    ProcreateImport.useOpened(temporary.root, { input }) { _, _ -> error("Cancelled copy must not be consumed") }
                }
            worker.start()
            worker.join()
            assertTrue(worker.isCancelled)
            assertEquals(2, reads)
            assertEmpty()
        }

    @Test
    fun cleanupFailureDoesNotReplaceTheOriginalCancellation() =
        runTest {
            val cancellation = CancellationException("Import cancelled")
            val failure =
                runCatching {
                    ProcreateImport.useOpened(temporary.root, { source() }) { _, _ ->
                        val staged =
                            temporary.root
                                .listFiles()
                                .orEmpty()
                                .single()
                        check(staged.delete())
                        check(staged.mkdir())
                        File(staged, "refuses-deletion").writeText("test")
                        throw cancellation
                    }
                }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(cancellation.suppressed.single() is IOException)
            temporary.root
                .listFiles()
                .orEmpty()
                .forEach { assertTrue(it.deleteRecursively()) }
            assertEmpty()
        }

    @Test
    fun replacingAHandoffCannotLetItsOldCancellationCloseTheReplacement() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val first = ProcreateImport.open(source(), temporary.root)
            val replacement = ProcreateImport.open(source(), temporary.root)
            try {
                PendingImports.putProcreate(5L, first, owner)
                PendingImports.putProcreate(5L, replacement, owner)
                runCurrent()
                PendingImports.discardProcreate(5L, first)
                assertEquals(
                    1,
                    temporary.root
                        .listFiles()
                        .orEmpty()
                        .size,
                )
                assertSame(replacement, PendingImports.takeProcreate(5L))
                owner.cancel()
                runCurrent()
                assertNotNull(replacement.document.composite())
            } finally {
                owner.cancel()
                first.close()
                replacement.close()
            }
            assertEmpty()
        }

    @Test
    fun anAlreadyCancelledOwnerCannotStrandANewPendingArchive() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            owner.cancel()
            ProcreateImport.useOpened(temporary.root, { source() }) { document, transfer ->
                PendingImports.putProcreate(6L, document, owner)
                transfer()
            }
            runCurrent()
            assertNull(PendingImports.takeProcreate(6L))
            assertNotNull(PendingImports.takeNotice(6L))
            assertEmpty()
        }

    @Test
    fun aClaimedImportIsClosedWhenTheScheduledEditorJobNeverStarts() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            var consumed = false
            ProcreateImport.useOpened(temporary.root, { source() }) { document, transfer ->
                PendingImports.putProcreate(7L, document, backgroundScope)
                transfer()
                val claimed = requireNotNull(PendingImports.takeProcreate(7L))
                val editorJob = owner.launch { claimed.use { consumed = true } }
                claimed.closeWhenCompleted(editorJob)
                owner.cancel()
                editorJob.join()
            }
            assertFalse(consumed)
            assertEmpty()
        }

    @Test
    fun staleCacheCleanupPreservesLiveOwnersAndUnrelatedFiles() {
        val orphan = File(temporary.root, "artflow-procreate-import-abandoned.procreate").apply { writeText("old process") }
        val unrelated = File(temporary.root, "import-unrelated.procreate").apply { writeText("not owned by this importer") }
        val live = ProcreateImport.open(source(), temporary.root)
        try {
            assertFalse(orphan.exists())
            ProcreateImport.open(source(), temporary.root).use {
                assertNotNull(live.document.composite())
                assertNotNull(it.document.composite())
                assertEquals(
                    3,
                    temporary.root
                        .listFiles()
                        .orEmpty()
                        .size,
                )
            }
            assertEquals("not owned by this importer", unrelated.readText())
        } finally {
            live.close()
            assertTrue(unrelated.delete())
        }
        assertEmpty()
    }

    @Test
    fun failedPsdNavigationCannotDiscardANewerOrClaimedPayload() =
        runTest {
            val original = byteArrayOf(1)
            val replacement = byteArrayOf(2)
            PendingImports.putPsd(8L, original, backgroundScope)
            PendingImports.putPsd(8L, replacement, backgroundScope)
            PendingImports.discardPsd(8L, original)
            assertSame(replacement, PendingImports.takePsd(8L))
            PendingImports.discardPsd(8L, replacement)
            assertNull(PendingImports.takePsd(8L))
            assertNull(PendingImports.takeNotice(8L))
        }

    @Test
    fun anUnclaimedPsdPayloadExpiresEvenAfterNavigationReportsSuccess() =
        runTest {
            PendingImports.putPsd(9L, byteArrayOf(1), backgroundScope)
            runCurrent()
            advanceTimeBy(10 * 60 * 1000L + 1)
            runCurrent()
            assertNull(PendingImports.takePsd(9L))
            assertTrue(PendingImports.takeNotice(9L).orEmpty().contains("Import the original file again"))
        }

    @Test
    fun clearingTheGalleryOwnerReleasesOnlyItsUnclaimedPsdPayloads() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val claimed = byteArrayOf(1)
            PendingImports.putPsd(10L, claimed, owner)
            PendingImports.putPsd(11L, byteArrayOf(2), owner)
            assertSame(claimed, PendingImports.takePsd(10L))
            owner.cancel()
            runCurrent()
            assertNull(PendingImports.takePsd(10L))
            assertNull(PendingImports.takeNotice(10L))
            assertNull(PendingImports.takePsd(11L))
            assertNotNull(PendingImports.takeNotice(11L))
            assertEquals(1, claimed.single().toInt())
        }

    @Test
    fun anOldGalleryOwnerCannotExpireAReplacementPsdPayload() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val replacement = byteArrayOf(2)
            PendingImports.putPsd(12L, byteArrayOf(1), owner)
            PendingImports.putPsd(12L, replacement, backgroundScope)
            owner.cancel()
            runCurrent()
            assertSame(replacement, PendingImports.takePsd(12L))
            assertNull(PendingImports.takeNotice(12L))
        }

    @Test
    fun anAlreadyCancelledGalleryOwnerCannotRetainAPsdPayload() =
        runTest {
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            owner.cancel()
            PendingImports.putPsd(13L, byteArrayOf(1), owner)
            runCurrent()
            assertNull(PendingImports.takePsd(13L))
            assertNotNull(PendingImports.takeNotice(13L))
        }

    private fun source(): ByteArrayInputStream = ByteArrayInputStream(bytes())

    private fun bytes(): ByteArray {
        val pixels = PixelBuffer.filled(4, 3, 0xFF123456.toInt())
        return ProcreateWriter.write(4, 3, listOf(ProcreateWriter.Layer("Layer", pixels)), pixels)
    }

    private fun assertEmpty() {
        assertTrue(
            temporary.root
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
    }
}
