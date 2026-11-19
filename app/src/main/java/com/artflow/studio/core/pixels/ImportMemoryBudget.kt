package com.artflow.studio.core.pixels

/** Leaves headroom for the open editor and for allocations outside an import's estimate. */
internal object ImportMemoryBudget {
    fun currentBytes(): Long {
        val runtime = Runtime.getRuntime()
        val maximum = runtime.maxMemory()
        val firstCommitted = runtime.totalMemory()
        val free = runtime.freeMemory()
        // Heap expansion or GC can happen between reads; do not attribute a new heap's free
        // bytes to an earlier, smaller committed snapshot.
        val committed = maxOf(firstCommitted, runtime.totalMemory()).coerceIn(0, maximum)
        return availableBytes(maximum, committed, free.coerceIn(0, committed))
    }

    fun availableBytes(
        maximum: Long,
        committed: Long,
        free: Long,
    ): Long {
        require(maximum > 0 && committed in 0..maximum && free in 0..committed) { "Invalid import memory limits" }
        val used = committed - free
        return minOf(maximum / 3, (maximum - used) / 2)
    }
}
