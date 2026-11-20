package com.artflow.studio.core.render

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * Retains admitted brush tiles without eviction: a cache miss changes the brush's appearance.
 * Reservations protect publication capacity and separately charge each incoming buffered batch.
 * The limits cover tile coverage arrays, not every allocation made by the process.
 */
internal class GrainRegistry(
    private val retainedByteLimit: Long = retainedLimit(),
    private val bufferedByteLimit: Long = bufferedLimit(),
) {
    private val lock = Any()
    private val tiles = ConcurrentHashMap<String, CustomGrains.Tile>()
    private val reservations = mutableSetOf<Reservation>()
    private var bufferedBytes = 0L

    init {
        require(retainedByteLimit in 1..retainedLimit()) { "Invalid retained brush texture limit" }
        require(bufferedByteLimit in 1..bufferedLimit()) { "Invalid buffered brush texture limit" }
    }

    fun get(id: String?): CustomGrains.Tile? = id?.let(tiles::get)

    fun ids(): List<String> = tiles.keys.sorted()

    fun reserve(
        sizes: Map<String, Int>,
        bufferedCopies: Int = 0,
    ): Reservation =
        synchronized(lock) {
            require(bufferedCopies in 0..2) { "Invalid brush texture buffer allowance" }
            require(sizes.all { (id, bytes) -> CustomGrains.isCustom(id) && bytes > 0 }) { "Invalid brush texture reservation" }
            val requested = sizes.toMap()
            val obligations = tiles.mapValuesTo(mutableMapOf()) { (_, tile) -> tile.values.size }
            reservations.forEach { reservation -> mergeSizes(obligations, reservation.sizes) }
            mergeSizes(obligations, requested)
            require(obligations.values.sumOf(Int::toLong) <= retainedByteLimit) { CAPACITY_MESSAGE }
            val buffered = requested.values.sumOf(Int::toLong) * bufferedCopies
            require(buffered <= bufferedByteLimit - bufferedBytes) { BUFFER_MESSAGE }
            Reservation(requested, buffered).also {
                reservations += it
                bufferedBytes += buffered
            }
        }

    fun register(
        id: String,
        tile: CustomGrains.Tile,
    ) {
        reserve(mapOf(id to tile.values.size)).use { it.register(id, tile) }
    }

    fun registerIfAbsent(
        id: String,
        tile: CustomGrains.Tile,
    ): CustomGrains.Tile? =
        synchronized(lock) {
            // An unused replacement must not need admission when the existing texture is reused.
            tiles[id] ?: reserve(mapOf(id to tile.values.size)).use { it.registerIfAbsent(id, tile) }
        }

    fun removeIfSame(
        id: String,
        tile: CustomGrains.Tile,
    ): Boolean = synchronized(lock) { tiles.remove(id, tile) }

    inner class Reservation internal constructor(
        internal val sizes: Map<String, Int>,
        private val buffered: Long,
    ) : Closeable {
        fun register(
            id: String,
            tile: CustomGrains.Tile,
        ) = synchronized(lock) {
            requireAdmitted(id, tile)
            tiles[id] = tile
        }

        fun registerIfAbsent(
            id: String,
            tile: CustomGrains.Tile,
        ): CustomGrains.Tile? =
            synchronized(lock) {
                requireAdmitted(id, tile)
                tiles.putIfAbsent(id, tile)
            }

        private fun requireAdmitted(
            id: String,
            tile: CustomGrains.Tile,
        ) {
            check(this in reservations) { "The brush texture reservation has closed" }
            require(tile.values.size <= (sizes[id] ?: 0)) { "The brush texture was not admitted" }
        }

        override fun close() =
            synchronized(lock) {
                if (reservations.remove(this)) bufferedBytes -= buffered
            }
    }

    private fun mergeSizes(
        destination: MutableMap<String, Int>,
        source: Map<String, Int>,
    ) {
        source.forEach { (id, bytes) -> destination[id] = maxOf(destination[id] ?: 0, bytes) }
    }

    companion object {
        const val CAPACITY_MESSAGE =
            "Saved brush textures exceed this device's memory limit. " +
                "Use fewer custom textures or a device with more memory."
        const val BUFFER_MESSAGE = "This brush texture batch needs too much memory. Import fewer textures at a time."

        private fun retainedLimit(): Long = minOf(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8)

        private fun bufferedLimit(): Long = minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16)
    }
}
