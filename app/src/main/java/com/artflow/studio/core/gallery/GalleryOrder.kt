package com.artflow.studio.core.gallery

/** The gallery's hand-arranged order, as artwork ids from the first card to the last. */
object GalleryOrder {
    /** The most ids kept; the oldest placements beyond it fall back to "new artwork" order. */
    const val MAX_IDS = 5000

    /** [items] in the order of [order]; items it does not mention (artworks made since) come first, in the order given. */
    fun <T> arrange(
        items: List<T>,
        order: List<Long>,
        idOf: (T) -> Long,
    ): List<T> {
        val rank = HashMap<Long, Int>(order.size * 2)
        order.forEachIndexed { index, id -> rank.putIfAbsent(id, index) }
        val (placed, fresh) = items.partition { idOf(it) in rank }
        return fresh + placed.sortedBy { rank.getValue(idOf(it)) }
    }

    /** [order] with [moved] taken out and put just before [target], or just after it when [after]; unchanged if either is missing. */
    fun move(
        order: List<Long>,
        moved: Long,
        target: Long,
        after: Boolean,
    ): List<Long> {
        if (moved == target || moved !in order) return order
        val rest = order.filter { it != moved }
        val at = rest.indexOf(target)
        if (at < 0) return order
        return rest.toMutableList().also { it.add(if (after) at + 1 else at, moved) }
    }

    /** [order] without ids that no longer exist, repeats or anything past [MAX_IDS]. */
    fun tidy(
        order: List<Long>,
        existing: Set<Long>,
    ): List<Long> = order.filter { it in existing }.distinct().take(MAX_IDS)

    fun encode(order: List<Long>): String = order.joinToString(",")

    /** Reads a stored order, ignoring anything that is not an id. */
    fun decode(text: String?): List<Long> =
        text
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .distinct()
            .take(MAX_IDS)
}
