package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.checkedPixelCount

/** Accepted freeze coverage for one temporary Liquify context; gestures always edit their own copy. */
class LiquifyFreezeState private constructor(
    val owner: Owner,
    private val coverage: BooleanArray?,
    private val frozenCount: Int,
) {
    data class Owner(
        val projectId: Long,
        val layerId: Long,
        val frameId: Long?,
        val revision: Long,
        val width: Int,
        val height: Int,
    )

    constructor(owner: Owner) : this(owner, null, 0)

    val hasFrozenPixels: Boolean get() = frozenCount > 0
    val frozenPercent: Int = coverage?.let { (frozenCount.toLong() * 100 / it.size).toInt() } ?: 0

    /** Other edits, document/layer/frame changes and geometry changes discard old coordinate coverage. */
    fun forOwner(next: Owner): LiquifyFreezeState = if (next == owner) this else LiquifyFreezeState(next)

    fun beginSession(
        x: Float,
        y: Float,
        settings: LiquifyTool.Settings,
        maskBudgetBytes: Long = availableMaskBytes(),
    ): LiquifyTool.Session {
        val source = settings.freezeMask ?: coverage
        if (source != null || settings.editsFreezeMask) {
            val count = checkedPixelCount(owner.width, owner.height)
            // Retained coverage, the gesture, its accepted copy, and a selection snapshot can
            // coexist. Admit them before beginSession allocates or copies any mask arrays.
            val copies = MASK_WORKING_COPIES + if (coverage != null && settings.freezeMask != null) 1 else 0
            require(count.toLong() <= maskBudgetBytes / copies) {
                "Liquify freeze coverage exceeds this device's memory limit. Clear frozen areas or use a smaller canvas."
            }
        }
        return LiquifyTool.beginSession(x, y, settings.copy(freezeMask = source), owner.width, owner.height)
    }

    /** Only a successfully finished mask gesture reaches here; retaining a copy prevents later mutation. */
    fun afterMaskGesture(session: LiquifyTool.Session): LiquifyFreezeState {
        require(session.settings.editsFreezeMask) { "Only Freeze and Unfreeze change accepted coverage" }
        require(session.map.width == owner.width && session.map.height == owner.height) { "Freeze dimensions must match" }
        val mask = requireNotNull(session.settings.freezeMask)
        val count = mask.count { it }
        return if (count == 0) LiquifyFreezeState(owner) else LiquifyFreezeState(owner, mask.copyOf(), count)
    }

    /** Distortion changes the document revision while keeping the same protected canvas coordinates. */
    fun afterRasterCommit(revision: Long): LiquifyFreezeState = LiquifyFreezeState(owner.copy(revision = revision), coverage, frozenCount)

    companion object {
        private const val MASK_WORKING_COPIES = 4L
        private const val MAX_MASK_BYTES = 64L * 1024 * 1024

        private fun availableMaskBytes(): Long {
            val runtime = Runtime.getRuntime()
            val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            return minOf(MAX_MASK_BYTES, runtime.maxMemory() / 8, available.coerceAtLeast(0) / 2)
        }
    }
}
