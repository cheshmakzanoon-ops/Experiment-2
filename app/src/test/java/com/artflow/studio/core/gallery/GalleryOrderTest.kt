package com.artflow.studio.core.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryOrderTest {
    private fun ids(
        items: List<Long>,
        order: List<Long>,
    ) = GalleryOrder.arrange(items, order) { it }

    @Test
    fun arrangeFollowsTheOrderAndPutsNewArtworksFirst() {
        // 9 and 8 were made after the order was saved; they lead, in the order they came.
        assertEquals(listOf(9L, 8L, 3L, 1L, 2L), ids(listOf(9, 1, 8, 2, 3), listOf(3, 1, 2)))
    }

    @Test
    fun arrangeIgnoresIdsThatAreGoneAndRepeats() {
        assertEquals(listOf(2L, 1L), ids(listOf(1, 2), listOf(7, 2, 2, 1, 5)))
    }

    @Test
    fun arrangeWithoutAnOrderKeepsTheGivenOrder() {
        assertEquals(listOf(5L, 4L, 6L), ids(listOf(5, 4, 6), emptyList()))
    }

    @Test
    fun moveGoesBeforeOrAfterTheTarget() {
        val order = listOf(1L, 2L, 3L, 4L)
        assertEquals(listOf(1L, 4L, 2L, 3L), GalleryOrder.move(order, moved = 4, target = 2, after = false))
        assertEquals(listOf(2L, 3L, 1L, 4L), GalleryOrder.move(order, moved = 1, target = 3, after = true))
        assertEquals(listOf(2L, 1L, 3L, 4L), GalleryOrder.move(order, moved = 1, target = 2, after = true))
        assertEquals(listOf(4L, 1L, 2L, 3L), GalleryOrder.move(order, moved = 4, target = 1, after = false))
    }

    @Test
    fun moveLeavesTheOrderAloneForNonsense() {
        val order = listOf(1L, 2L, 3L)
        assertEquals(order, GalleryOrder.move(order, moved = 2, target = 2, after = true))
        assertEquals(order, GalleryOrder.move(order, moved = 9, target = 2, after = true))
        assertEquals(order, GalleryOrder.move(order, moved = 2, target = 9, after = false))
    }

    @Test
    fun moveKeepsEveryIdExactlyOnce() {
        var order = (1L..20L).toList()
        val random = java.util.Random(7)
        repeat(500) {
            order = GalleryOrder.move(order, random.nextInt(20) + 1L, random.nextInt(20) + 1L, random.nextBoolean())
            assertEquals((1L..20L).toList(), order.sorted())
        }
    }

    @Test
    fun tidyDropsMissingRepeatedAndExcessIds() {
        assertEquals(listOf(3L, 1L), GalleryOrder.tidy(listOf(3, 9, 1, 3, 8), setOf(1L, 3L)))
        val many = (1L..GalleryOrder.MAX_IDS + 10L).toList()
        assertEquals(GalleryOrder.MAX_IDS, GalleryOrder.tidy(many, many.toSet()).size)
    }

    @Test
    fun storedTextRoundTripsAndSurvivesDamage() {
        assertEquals("3,1,2", GalleryOrder.encode(listOf(3, 1, 2)))
        assertEquals(listOf(3L, 1L, 2L), GalleryOrder.decode("3, 1,x,,2,1"))
        assertEquals(emptyList<Long>(), GalleryOrder.decode(null))
        assertEquals(emptyList<Long>(), GalleryOrder.decode(""))
    }
}
