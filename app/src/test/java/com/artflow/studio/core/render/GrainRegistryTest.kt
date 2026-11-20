package com.artflow.studio.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GrainRegistryTest {
    @Test
    fun fullRegistryRejectsNewTexturesWithoutEvictingOrReplacingExistingBytes() {
        val registry = GrainRegistry(retainedByteLimit = 4, bufferedByteLimit = 8)
        val original = tile(2, 10)
        registry.register(FIRST, original)
        val failure = assertThrows(IllegalArgumentException::class.java) { registry.register(SECOND, tile(1, 20)) }
        assertEquals(GrainRegistry.CAPACITY_MESSAGE, failure.message)
        assertSame(original, registry.get(FIRST))
        assertNull(registry.get(SECOND))
    }

    @Test
    fun replacementsChargeTheResultingSizeAndPreserveTheOldTileOnRejection() {
        val registry = GrainRegistry(retainedByteLimit = 4, bufferedByteLimit = 8)
        registry.register(FIRST, tile(2, 10))
        val replacement = tile(2, 20)
        registry.register(FIRST, replacement)
        assertThrows(IllegalArgumentException::class.java) { registry.register(FIRST, tile(3, 30)) }
        assertSame(replacement, registry.get(FIRST))
        registry.register(FIRST, tile(1, 40))
        registry.register(SECOND, tile(1, 50))
        assertEquals(listOf(FIRST, SECOND), registry.ids())
    }

    @Test
    fun reusingAnExistingTextureDoesNotAdmitTheUnusedReplacement() {
        val registry = GrainRegistry(retainedByteLimit = 4, bufferedByteLimit = 8)
        val original = tile(2, 10)
        registry.register(FIRST, original)
        assertSame(original, registry.registerIfAbsent(FIRST, tile(3, 20)))
        assertSame(original, registry.get(FIRST))
    }

    @Test
    fun unpublishedReservationsProtectCapacityUntilClosed() {
        val registry = GrainRegistry(retainedByteLimit = 4, bufferedByteLimit = 8)
        val reservation = registry.reserve(mapOf(FIRST to 4))
        assertThrows(IllegalArgumentException::class.java) { registry.register(SECOND, tile(1, 10)) }
        assertNull(registry.get(FIRST))
        reservation.close()
        reservation.close()
        registry.register(SECOND, tile(2, 20))
        assertEquals(listOf(SECOND), registry.ids())
        assertThrows(IllegalStateException::class.java) { reservation.register(FIRST, tile(1, 30)) }
    }

    @Test
    fun overlappingImportsShareRetainedIdentityButPayForTheirOwnBufferedCopies() {
        val registry = GrainRegistry(retainedByteLimit = 4, bufferedByteLimit = 8)
        registry.reserve(mapOf(FIRST to 4), bufferedCopies = 2).use { first ->
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    registry.reserve(mapOf(FIRST to 4), bufferedCopies = 2)
                }
            assertEquals(GrainRegistry.BUFFER_MESSAGE, failure.message)
            first.register(FIRST, tile(2, 10))
        }
        registry.reserve(mapOf(FIRST to 4), bufferedCopies = 2).use { replacement ->
            replacement.register(FIRST, tile(2, 20))
        }
        assertEquals(20.toByte(), registry.get(FIRST)!!.values.first())
    }

    @Test
    fun aReservationCannotPublishAnUnadmittedIdentityOrLargerTile() {
        val registry = GrainRegistry(retainedByteLimit = 4, bufferedByteLimit = 8)
        registry.reserve(mapOf(FIRST to 1)).use { reservation ->
            assertThrows(IllegalArgumentException::class.java) { reservation.register(SECOND, tile(1, 10)) }
            assertThrows(IllegalArgumentException::class.java) { reservation.register(FIRST, tile(2, 20)) }
        }
        assertEquals(emptyList<String>(), registry.ids())
    }

    @Test
    fun concurrentAdmissionsCannotSpendTheSameRemainingCapacityTwice() {
        val registry = GrainRegistry(retainedByteLimit = 4, bufferedByteLimit = 8)
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts =
                listOf(FIRST, SECOND).map { id ->
                    executor.submit<GrainRegistry.Reservation?> {
                        ready.countDown()
                        check(ready.await(5, TimeUnit.SECONDS))
                        try {
                            registry.reserve(mapOf(id to 4))
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }
                }
            val admitted = attempts.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, admitted.size)
            admitted.forEach(GrainRegistry.Reservation::close)
            registry.register(FIRST, tile(2, 10))
        } finally {
            executor.shutdownNow()
        }
    }

    private fun tile(
        size: Int,
        value: Int,
    ): CustomGrains.Tile = CustomGrains.Tile(size, ByteArray(size * size) { value.toByte() })

    private companion object {
        const val FIRST = "custom-b10001"
        const val SECOND = "custom-b10002"
    }
}
