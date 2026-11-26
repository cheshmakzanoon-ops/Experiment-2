package com.artflow.studio.core.tool

/** The chosen clone origin and the offset shared by completed aligned strokes. */
class CloneStampState(
    val source: Pair<Float, Float>? = null,
    private val alignedOffset: Pair<Float, Float>? = null,
) {
    /** A new choice also invalidates a previous stroke that is still finishing asynchronously. */
    fun withSource(
        x: Float,
        y: Float,
    ): CloneStampState = CloneStampState(x to y)

    /** Changing alignment mode starts the next stroke from the chosen origin. */
    fun withoutAlignment(): CloneStampState = CloneStampState(source)

    fun beginStroke(
        targetX: Float,
        targetY: Float,
        settings: PixelBrushes.CloneSettings,
    ): Stroke? {
        val origin = source ?: return null
        val offset = alignedOffset.takeIf { settings.aligned }
        val session =
            PixelBrushes.beginCloneStamp(
                targetX,
                targetY,
                offset?.let { targetX - it.first } ?: origin.first,
                offset?.let { targetY - it.second } ?: origin.second,
                settings,
            )
        return Stroke(session, this)
    }

    /** Call only for an accepted stroke; cancelled or rejected edits must not establish alignment. */
    fun afterStroke(stroke: Stroke): CloneStampState =
        if (stroke.owner !== this || !stroke.session.settings.aligned || alignedOffset != null) {
            this
        } else {
            CloneStampState(source, stroke.session.offsetX to stroke.session.offsetY)
        }

    class Stroke internal constructor(
        val session: PixelBrushes.CloneSession,
        internal val owner: CloneStampState,
    )
}
