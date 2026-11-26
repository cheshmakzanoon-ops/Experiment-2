package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.core.export.ArtworkPreview
import com.artflow.studio.core.pixels.ImportMemoryBudget
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Captures only committed artwork and refuses to publish a different document or revision. */
internal suspend fun currentArtworkPreview(
    repository: CanvasRepository,
    projectId: Long,
    revision: Long,
    isCurrent: () -> Boolean,
    availableBytes: () -> Long = ImportMemoryBudget::currentBytes,
): ByteArray? {
    fun current() = isCurrent() && repository.contentRevision == revision
    if (!current() || repository.hasUnresolvedRecovery(projectId) || !current()) return null
    val size = repository.getCanvasSize()
    val layers = repository.frames().flatMap { it.layers }
    if (!ArtworkPreview.canRender(size.width, size.height, layers, availableBytes())) return null
    val profile = repository.getColorProfile()
    val composite = repository.compositeBuffer() ?: return null
    currentCoroutineContext().ensureActive()
    if (!current()) return null
    val bytes = withContext(Dispatchers.Default) { ArtworkPreview.encode(composite, profile) }
    return bytes.takeIf { current() }
}
