package com.artflow.studio.data.export

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.ModelImportFixture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PendingRasterImportOwnershipTest {
    @Test
    fun cancellingTheGalleryReleasesAnUnclaimedPhotoAndItsCapacity() =
        runTest {
            val expired = mutableListOf<Long>()
            val pending = PendingRasterImports(maxEntries = 1, onExpired = expired::add)
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            pending.putImage(1, image(), false, owner)
            owner.cancel()
            runCurrent()
            assertNull(pending.takeImage(1))
            assertEquals(listOf(1L), expired)
            val next = image()
            pending.putImage(2, next, true, backgroundScope)
            assertSame(next, pending.takeImage(2)?.image)
        }

    @Test
    fun takingAPhotoTransfersOwnershipAndCancelsItsExpiry() =
        runTest {
            val expired = mutableListOf<Long>()
            val pending = PendingRasterImports(onExpired = expired::add)
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val original = image()
            pending.putImage(1, original, true, owner)
            val claimed = pending.takeImage(1)
            owner.cancel()
            advanceTimeBy(CLAIM_TIMEOUT)
            runCurrent()
            assertSame(original, claimed?.image)
            assertTrue(claimed?.isPrivate == true)
            assertTrue(expired.isEmpty())
        }

    @Test
    fun unclaimedPhotosAndModelsHaveAClaimDeadline() =
        runTest {
            val expired = mutableListOf<Long>()
            val pending = PendingRasterImports(onExpired = expired::add)
            pending.putImage(1, image(), false, backgroundScope)
            pending.putModel(2, ModelImportFixture.prepared(), backgroundScope)
            runCurrent()
            advanceTimeBy(CLAIM_TIMEOUT)
            runCurrent()
            assertNull(pending.takeImage(1))
            assertNull(pending.model(2))
            assertEquals(setOf(1L, 2L), expired.toSet())
        }

    @Test
    fun anOldOwnerCannotDiscardAReplacementPhoto() =
        runTest {
            val pending = PendingRasterImports()
            val oldOwner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val old = image()
            val replacement = image()
            pending.putImage(1, old, false, oldOwner)
            pending.putImage(1, replacement, false, backgroundScope)
            oldOwner.cancel()
            runCurrent()
            pending.discardImage(1, old)
            assertSame(replacement, pending.takeImage(1)?.image)
        }

    @Test
    fun anAlreadyCancelledOwnerDoesNotRetainAFileOrConsumeCapacity() =
        runTest {
            val pending = PendingRasterImports(maxEntries = 1)
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            owner.cancel()
            pending.putModel(1, ModelImportFixture.prepared(), owner)
            runCurrent()
            assertNull(pending.model(1))
            pending.putImage(2, image(), false, backgroundScope)
            assertTrue(pending.takeImage(2) != null)
        }

    @Test
    fun aClaimedModelAndItsExactReceiptSurviveCancellationAndTheDeadline() =
        runTest {
            val expired = mutableListOf<Long>()
            val pending = PendingRasterImports(onExpired = expired::add)
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val model = ModelImportFixture.prepared()
            val receipt = receipt()
            pending.putModel(1, model, owner)
            assertSame(model, pending.claimModel(1))
            pending.recordReceipt(1, model, receipt)
            owner.cancel()
            advanceTimeBy(CLAIM_TIMEOUT * 2)
            runCurrent()
            assertSame(model, pending.model(1))
            assertSame(receipt, pending.receipt(1, model))
            assertTrue(expired.isEmpty())
            assertTrue(pending.completeModel(1, model))
            assertNull(pending.model(1))
        }

    @Test
    fun recordingACommittedReceiptAlsoTransfersLifetimeToTheEditor() =
        runTest {
            val pending = PendingRasterImports()
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val model = ModelImportFixture.prepared()
            val receipt = receipt()
            pending.putModel(1, model, owner)
            pending.recordReceipt(1, model, receipt)
            owner.cancel()
            runCurrent()
            assertSame(model, pending.model(1))
            assertSame(receipt, pending.receipt(1, model))
            pending.completeModel(1, model)
        }

    @Test
    fun navigationCleanupAndReplacementCannotEvictAClaimedRetry() =
        runTest {
            val pending = PendingRasterImports()
            val model = ModelImportFixture.prepared()
            pending.putModel(1, model, backgroundScope)
            pending.claimModel(1)
            val receipt = receipt()
            pending.recordReceipt(1, model, receipt)
            pending.discardModel(1, model)
            assertTrue(runCatching { pending.putModel(1, ModelImportFixture.prepared(), backgroundScope) }.isFailure)
            assertTrue(runCatching { pending.putImage(1, image(), false, backgroundScope) }.isFailure)
            assertSame(model, pending.model(1))
            assertSame(receipt, pending.receipt(1, model))
            pending.completeModel(1, model)
        }

    @Test
    fun aStaleModelCompletionOrDiscardCannotRemoveItsReplacement() =
        runTest {
            val pending = PendingRasterImports()
            val original = ModelImportFixture.prepared()
            val replacement = ModelImportFixture.prepared()
            pending.putModel(1, original, backgroundScope)
            pending.putModel(1, replacement, backgroundScope)
            pending.discardModel(1, original)
            assertFalse(pending.completeModel(1, original))
            assertSame(replacement, pending.model(1))
            pending.discardModel(1, replacement)
            assertNull(pending.model(1))
        }

    @Test
    fun aStaleInstallerCannotClaimItsReplacementAndPreventOwnerCleanup() =
        runTest {
            val pending = PendingRasterImports()
            val owner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val original = ModelImportFixture.prepared()
            val replacement = ModelImportFixture.prepared()
            pending.putModel(1, original, backgroundScope)
            pending.putModel(1, replacement, owner)
            assertNull(pending.claimModel(1, original))
            assertSame(replacement, pending.model(1))
            owner.cancel()
            runCurrent()
            assertNull(pending.model(1))
        }

    @Test
    fun admissionRejectsNewImportsWithoutEvictingAnUnsavedModel() =
        runTest {
            val pending = PendingRasterImports(maxEntries = 1)
            val model = ModelImportFixture.prepared()
            val receipt = receipt()
            pending.putModel(1, model, backgroundScope)
            pending.recordReceipt(1, model, receipt)
            assertTrue(runCatching { pending.putImage(2, image(), false, backgroundScope) }.isFailure)
            assertSame(model, pending.model(1))
            assertSame(receipt, pending.receipt(1, model))
            pending.discardProject(1)
            pending.putImage(2, image(), false, backgroundScope)
            assertTrue(pending.takeImage(2) != null)
        }

    @Test
    fun photosAndAllModelPlanesShareOneRetainedByteBudget() =
        runTest {
            val model = ModelImportFixture.prepared()
            val modelBytes =
                model.objText.length.toLong() * 2 +
                    listOfNotNull(model.color, model.metallic, model.roughness).sumOf { it.pixels.size.toLong() * 4 }
            val pending = PendingRasterImports(maxBytes = modelBytes + 4)
            pending.putModel(1, model, backgroundScope)
            pending.putImage(2, image(), false, backgroundScope)
            assertTrue(runCatching { pending.putImage(3, image(), false, backgroundScope) }.isFailure)
            assertSame(model, pending.model(1))
            assertTrue(pending.takeImage(2) != null)
            pending.putImage(3, image(), false, backgroundScope)
            assertTrue(pending.takeImage(3) != null)
            assertTrue(pending.completeModel(1, model))
        }

    @Test
    fun anOversizedImportExplainsTheRefusalAndLeavesTheExistingHandoff() =
        runTest {
            val pending = PendingRasterImports(maxBytes = 8)
            val first = image()
            pending.putImage(1, first, false, backgroundScope)
            val failure = runCatching { pending.putImage(2, PixelBuffer(2, 2), false, backgroundScope) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message?.contains("too much memory") == true)
            assertSame(first, pending.takeImage(1)?.image)
        }

    @Test
    fun reservationsCountBeforeProjectCreationAndPublishWithoutDoubleCounting() =
        runTest {
            val pending = PendingRasterImports(maxBytes = 4, maxEntries = 1)
            val original = image()
            pending.reserveImage(original, true, backgroundScope).use { reserved ->
                assertTrue(runCatching { pending.reserveImage(image(), false, backgroundScope) }.isFailure)
                assertTrue(runCatching { pending.putImage(2, image(), false, backgroundScope) }.isFailure)
                reserved.publish(1)
            }
            assertSame(original, pending.takeImage(1)?.image)
            pending.reserveImage(image(), false, backgroundScope).close()
        }

    @Test
    fun storageFailureCancellationAndFatalFailureReleaseUnpublishedReservations() =
        runTest {
            val pending = PendingRasterImports(maxBytes = 4, maxEntries = 1)
            for (failure in listOf(IOException("Full disk"), CancellationException("Left gallery"), AssertionError("Fatal"))) {
                val result = runCatching { pending.reserveImage(image(), false, backgroundScope).use { throw failure } }.exceptionOrNull()
                assertSame(failure, result)
                pending.reserveImage(image(), false, backgroundScope).close()
            }
        }

    @Test
    fun cancellingWhileProjectCreationSuspendsReleasesTheReservation() =
        runTest {
            val pending = PendingRasterImports(maxBytes = 4, maxEntries = 1)
            val entered = CompletableDeferred<Unit>()
            val importing =
                launch {
                    pending.reserveImage(image(), false, backgroundScope).use {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
            entered.await()
            assertTrue(runCatching { pending.reserveImage(image(), false, backgroundScope) }.isFailure)
            importing.cancelAndJoin()
            pending.reserveImage(image(), false, backgroundScope).close()
        }

    @Test
    fun closingAPublishedReservationDoesNotDiscardAnEditorClaim() =
        runTest {
            val pending = PendingRasterImports(maxEntries = 1)
            val model = ModelImportFixture.prepared()
            val failure =
                runCatching {
                    pending.reserveModel(model, backgroundScope).use {
                        it.publish(1)
                        pending.claimModel(1)
                        throw CancellationException("Navigation ended after editor claim")
                    }
                }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertSame(model, pending.model(1))
            pending.completeModel(1, model)
        }

    @Test
    fun cancelledOrPublishedReservationsCannotPublishAgain() =
        runTest {
            val pending = PendingRasterImports(maxEntries = 1)
            val cancelled = pending.reserveImage(image(), false, backgroundScope)
            cancelled.close()
            assertTrue(runCatching { cancelled.publish(1) }.isFailure)
            val published = pending.reserveImage(image(), false, backgroundScope)
            published.publish(2)
            assertTrue(runCatching { published.publish(3) }.isFailure)
            published.close()
            assertTrue(pending.takeImage(2) != null)
            assertNull(pending.takeImage(3))
        }

    @Test
    fun deletingAProjectReleasesItsClaimedModelAndPendingMetadataOnly() =
        runTest {
            val project = 98_765L
            val other = project + 1
            val model = ModelImportFixture.prepared()
            try {
                PendingImports.putModelBundle(project, model, backgroundScope)
                PendingImports.claimModelBundle(project)
                PendingImports.putPsd(project, byteArrayOf(1), backgroundScope)
                PendingImports.putProfile(project, ColorProfile.SRGB)
                PendingImports.putNotice(project, "Old warning")
                PendingImports.putNotice(other, "Keep this warning")
                PendingImports.discardProject(project)
                assertNull(PendingImports.modelBundle(project))
                assertNull(PendingImports.takePsd(project))
                assertNull(PendingImports.takeProfile(project))
                assertNull(PendingImports.takeNotice(project))
                assertEquals("Keep this warning", PendingImports.takeNotice(other))
            } finally {
                PendingImports.discardProject(project)
                PendingImports.discardProject(other)
            }
        }

    private fun image() = PixelBuffer.filled(1, 1, 0xFF123456.toInt())

    private fun receipt() = ModelImports.Receipt(1, 4, 2, 0, 1, emptySet(), emptyList(), emptyMap())

    private companion object {
        const val CLAIM_TIMEOUT = 10 * 60 * 1000L
    }
}
