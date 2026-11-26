package com.artflow.studio.data.renderer.opengl

/** Availability of the primary artwork image, independent of optional background/ghost textures. */
enum class CanvasPreviewAvailability {
    WAITING_FOR_FRAME,
    AVAILABLE,
    UNAVAILABLE,
}

/** Published together so input cannot pair an old availability with a newer interruption. */
data class CanvasPreviewStatus(
    val availability: CanvasPreviewAvailability,
    val interruptions: Long,
)

/**
 * A CPU composition failure invalidates older queued/drawn images. A GL context reset may reuse
 * the retained CPU image, but must display it successfully before accepting canvas input again.
 */
internal class CanvasPreviewState {
    private var contentGeneration = 0L

    @Volatile
    var status = CanvasPreviewStatus(CanvasPreviewAvailability.WAITING_FOR_FRAME, 0L)
        private set

    val availability: CanvasPreviewAvailability get() = status.availability
    val interruptions: Long get() = status.interruptions

    @Volatile
    var onChanged: ((CanvasPreviewAvailability) -> Unit)? = null

    @Synchronized
    fun generation(): Long = contentGeneration

    /** Normal queued refreshes retain the visible frame, but obsolete completions cannot change it. */
    @Synchronized
    fun advanceContent(): Long = ++contentGeneration

    @Synchronized
    fun awaitSurface() = change(CanvasPreviewAvailability.WAITING_FOR_FRAME)

    /** A failed GL draw leaves no valid presentation, even if its replacement is already queued. */
    @Synchronized
    fun presentationFailed() = change(CanvasPreviewAvailability.UNAVAILABLE)

    @Synchronized
    fun invalidateContent() {
        contentGeneration++
        change(CanvasPreviewAvailability.UNAVAILABLE)
    }

    @Synchronized
    fun presented(
        generation: Long,
        succeeded: Boolean,
    ) {
        if (generation == contentGeneration) {
            change(if (succeeded) CanvasPreviewAvailability.AVAILABLE else CanvasPreviewAvailability.UNAVAILABLE)
        }
    }

    private fun change(value: CanvasPreviewAvailability) {
        val previous = status
        if (value == previous.availability) return
        val interruptions = previous.interruptions + if (value != CanvasPreviewAvailability.AVAILABLE) 1 else 0
        status = CanvasPreviewStatus(value, interruptions)
        onChanged?.invoke(value)
    }
}
