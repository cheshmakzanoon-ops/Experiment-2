package com.artflow.studio.presentation.ui.components.canvas

import com.artflow.studio.core.animation.AnimationTimeline
import com.artflow.studio.core.animation.OnionSkin
import com.artflow.studio.core.animation.OnionSkinMemory
import com.artflow.studio.core.animation.OnionSkinTint
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.pixels.ImportMemoryBudget
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One full-resolution ghost compositor at a time, including while an old request is cancelling. */
internal class OnionSkinPreview(
    private val repository: CanvasRepository,
    private val retainedRendererBytes: () -> Long,
    private val availableBytes: () -> Long = ImportMemoryBudget::currentBytes,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    data class Request(
        val projectId: Long,
        val revision: Long,
        val timeline: AnimationTimeline.State,
        val profile: ColorProfile,
    )

    data class Result(
        val frames: List<Pair<PixelBuffer, Float>>,
        val primaryAlpha: Float,
        private val plan: OnionSkinMemory.Plan?,
    ) {
        fun requirePublicationFits(
            availableBytes: Long,
            retainedRendererBytes: Long,
        ) {
            check(plan == null || plan.fitsPublication(availableBytes, retainedRendererBytes)) { OnionSkinMemory.LIMIT_MESSAGE }
        }
    }

    fun capture(): Request =
        Request(repository.projectId(), repository.contentRevision, repository.timeline.value, repository.getColorProfile())

    fun isCurrent(request: Request): Boolean {
        if (repository.projectId() != request.projectId || repository.contentRevision != request.revision) return false
        val current = repository.timeline.value
        return current.activeIndex == request.timeline.activeIndex &&
            current.settings == request.timeline.settings &&
            repository.getColorProfile() == request.profile
    }

    suspend fun render(
        request: Request,
        isRequested: () -> Boolean,
    ): Result? =
        renderMutex.withLock {
            if (!isRequested() || !isCurrent(request)) return@withLock null
            val state = request.timeline
            val neighbours = AnimationTimeline.onionGhosts(state)
            val entries = neighbours.previous.asReversed() + neighbours.next.asReversed()
            if (entries.isEmpty()) return@withLock Result(emptyList(), 1f, null)
            val size = repository.getCanvasSize()
            val layers = entries.map { state.frames[it.first].layers }
            val plan =
                OnionSkinMemory.plan(size.width, size.height, layers, availableBytes(), retainedRendererBytes())
                    ?: error(OnionSkinMemory.LIMIT_MESSAGE)
            val ghosts = mutableListOf<Pair<PixelBuffer, Float>>()
            for ((position, entry) in entries.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!isRequested() || !isCurrent(request)) return@withLock null
                check(plan.fitsFrame(position, availableBytes(), retainedRendererBytes())) { OnionSkinMemory.LIMIT_MESSAGE }
                val frame = repository.compositeOnionFrame(entry.first) ?: continue
                currentCoroutineContext().ensureActive()
                if (!isRequested() || !isCurrent(request)) return@withLock null
                check(plan.fitsPreparation(position, availableBytes(), retainedRendererBytes())) { OnionSkinMemory.LIMIT_MESSAGE }
                val ghost =
                    withContext(renderDispatcher) {
                        val preview =
                            if (frame.width == plan.width && frame.height == plan.height) frame else frame.scaled(plan.width, plan.height)
                        val tint = OnionSkin.tint(state.settings, entry.first - state.activeIndex)
                        if (tint == null) preview else OnionSkinTint.silhouette(preview, tint, request.profile)
                    }
                ghosts += ghost to entry.second
            }
            currentCoroutineContext().ensureActive()
            if (!isRequested() || !isCurrent(request)) return@withLock null
            Result(ghosts, if (ghosts.isEmpty()) 1f else OnionSkin.primaryOpacity(state.settings), plan).also {
                it.requirePublicationFits(availableBytes(), retainedRendererBytes())
            }
        }

    private companion object {
        // A recreated AndroidView must also wait for the detached view's non-cooperative kernel.
        val renderMutex = Mutex()
    }
}
