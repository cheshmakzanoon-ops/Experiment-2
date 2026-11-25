package com.artflow.studio.presentation

import com.artflow.studio.core.pixels.LiveAdjustments
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.presentation.ui.viewmodel.AdjustmentSessionController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/** An earlier Apply finishing late must never close the adjustment the artist opened after it. */
@OptIn(ExperimentalCoroutinesApi::class)
class AdjustmentSessionOwnershipTest {
    private val cancelledTokens = mutableListOf<Long>()
    private var opened = 0L
    private val commitEntered = CompletableDeferred<Unit>()
    private val commitGate = CompletableDeferred<Boolean>()
    private val secondOpened = CompletableDeferred<Unit>()
    private val committed = mutableListOf<Long>()

    private fun repository(): CanvasRepository =
        object : CanvasRepository by mock(CanvasRepository::class.java) {
            override fun getActiveLayerId(): Long = 1L

            override fun selection(): SelectionMask? = null

            override fun requestPreviewRefresh() = Unit

            override suspend fun beginRasterEdit(layerId: Long): CanvasRepository.RasterEditSession? {
                opened++
                if (opened == 2L) secondOpened.complete(Unit)
                val buffer = PixelBuffer(4, 4).also { it.fill(0xFF336699.toInt()) }
                return CanvasRepository.RasterEditSession(layerId, buffer, opened, 0L, false)
            }

            override suspend fun commitRasterEdit(
                session: CanvasRepository.RasterEditSession,
                description: String,
            ): Boolean {
                commitEntered.complete(Unit)
                committed += session.snapshotToken
                return commitGate.await()
            }

            override suspend fun cancelRasterEdit(session: CanvasRepository.RasterEditSession) {
                cancelledTokens += session.snapshotToken
            }
        }

    @Test
    fun anEarlierApplyDoesNotCloseALaterAdjustment() =
        runTest(UnconfinedTestDispatcher()) {
            val controller = AdjustmentSessionController(repository(), this, notify = {}, onApplied = {})
            controller.start(LiveAdjustments.Kind.OPACITY)
            controller.setAmount(0.5f)

            val applying = checkNotNull(controller.apply())
            commitEntered.await()

            // The artist opens another adjustment while the first Apply is still committing.
            controller.start(LiveAdjustments.Kind.OPACITY)
            commitGate.complete(true)
            applying.join()
            secondOpened.await()
            // Let the second session finish opening.
            while (controller.state.value == null) kotlinx.coroutines.yield()

            assertEquals("Only the first session was committed", 1, committed.size)
            assertNotNull("The newer adjustment must still be open", controller.state.value)
            assertTrue(
                "Only the finished session may be cancelled; the newer one must survive: $cancelledTokens",
                cancelledTokens.size <= 1,
            )
        }
}
