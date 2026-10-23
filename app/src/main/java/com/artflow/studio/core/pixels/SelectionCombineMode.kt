package com.artflow.studio.core.pixels

/**
 * How a new selection combines with the existing one, as in the selection tool's Add, Remove and
 * Intersect actions. The enum lives in the pixel core because the combination is a mask operation;
 * the canvas view, panels and input state all share this one definition.
 */
enum class SelectionCombineMode(
    val displayName: String,
) {
    REPLACE("Replace"),
    ADD("Add"),
    SUBTRACT("Subtract"),
    INTERSECT("Intersect"),
}

/**
 * Combines [added] into [existing] according to [mode].
 *
 * A null [existing] is Procreate's "nothing is selected": Replace and Add produce the new mask, while
 * Subtract and Intersect have nothing to remove from or intersect with and therefore select nothing.
 * They must never fall back to Add, which is what the old canvas-view copy did.
 */
fun SelectionMask.Companion.combine(
    existing: SelectionMask?,
    added: SelectionMask,
    mode: SelectionCombineMode,
    checkActive: () -> Unit = {},
): SelectionMask {
    checkActive()
    if (mode == SelectionCombineMode.REPLACE) return added
    if (existing == null) {
        return if (mode == SelectionCombineMode.ADD) added else SelectionMask(added.width, added.height)
    }
    val usable = existing.width == added.width && existing.height == added.height
    if (!usable) {
        return if (mode == SelectionCombineMode.ADD) added else SelectionMask(added.width, added.height)
    }
    if (mode == SelectionCombineMode.INTERSECT) return intersect(existing, added, checkActive)

    val result = existing.copy()
    for (i in result.coverage.indices) {
        if (i % 8192 == 0) checkActive()
        val a = result.coverage[i].toInt() and 0xFF
        val b = added.coverage[i].toInt() and 0xFF
        val value = if (mode == SelectionCombineMode.ADD) maxOf(a, b) else maxOf(0, a - b)
        result.coverage[i] = value.toByte()
    }
    return result
}
