package com.artflow.studio.presentation

import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.animation.AnimationFrame
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasSize
import com.artflow.studio.presentation.ui.viewmodel.currentArtworkPreview
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock

class ExportPreviewOwnershipTest {
    @Test
    fun recoveryOrInsufficientMemoryRefusesBeforeCompositing() =
        runTest {
            val repository = PreviewRepository()
            repository.recovery = true
            assertNull(currentArtworkPreview(repository, 1, 1, { true }, { BUDGET }))
            repository.recovery = false
            assertNull(currentArtworkPreview(repository, 1, 1, { true }, { 0 }))
            assertEquals(0, repository.composites)
        }

    @Test
    fun aChangedDocumentCannotReceiveThePreviousPreview() =
        runTest {
            val repository = PreviewRepository()
            var current = true
            repository.gate = CompletableDeferred()
            val preview = async { currentArtworkPreview(repository, 1, 1, { current }, { BUDGET }) }
            repository.entered.await()
            current = false
            repository.gate?.complete(Unit)
            assertNull(preview.await())
        }

    @Test
    fun changedRevisionIsDiscardedAndANewRequestCanRender() =
        runTest {
            val repository = PreviewRepository()
            repository.gate = CompletableDeferred()
            val preview = async { currentArtworkPreview(repository, 1, 1, { true }, { BUDGET }) }
            repository.entered.await()
            repository.revision = 2
            repository.gate?.complete(Unit)
            assertNull(preview.await())
            repository.gate = null
            assertNotNull(currentArtworkPreview(repository, 1, 2, { true }, { BUDGET }))
            assertEquals(2, repository.composites)
        }

    private class PreviewRepository : CanvasRepository by mock(CanvasRepository::class.java) {
        var revision = 1L
        var recovery = false
        var composites = 0
        var gate: CompletableDeferred<Unit>? = null
        val entered = CompletableDeferred<Unit>()

        override val contentRevision: Long get() = revision

        override suspend fun hasUnresolvedRecovery(projectId: Long): Boolean = recovery

        override fun getCanvasSize(): CanvasSize = CanvasSize(32, 16, 72)

        override fun frames(): List<AnimationFrame> = emptyList()

        override fun getColorProfile(): ColorProfile = ColorProfile.SRGB

        override suspend fun compositeBuffer(): PixelBuffer {
            composites++
            entered.complete(Unit)
            gate?.await()
            return PixelBuffer.filled(32, 16, 0xFF2D83AF.toInt())
        }
    }

    private companion object {
        const val BUDGET = 64L * 1024 * 1024
    }
}
