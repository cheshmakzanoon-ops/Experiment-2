package com.artflow.studio.core.export

import org.junit.Assert.assertTrue
import kotlin.random.Random

/**
 * Corrupted copies of a valid file, for the importers' robustness tests: the file cut at many lengths (dense
 * near the start, where headers are) and copies with seeded byte changes. Every case uses a fixed seed, so a
 * failure names a case that reproduces.
 */
internal object Corruption {
    private const val CHANGES = 300
    private const val HEADER_CUTS = 96
    private const val BODY_CUTS = 48
    private const val SLOW_MILLIS = 2_000L

    fun mutants(
        valid: ByteArray,
        seed: Long,
    ): List<ByteArray> {
        val cuts = (0 until minOf(valid.size, HEADER_CUTS)) + (HEADER_CUTS until valid.size step maxOf(1, valid.size / BODY_CUTS))
        val random = Random(seed)
        val changed =
            List(CHANGES) { index ->
                val copy = valid.copyOf()
                if (index % 3 == 2) {
                    // Declared sizes and counts are four-byte fields: write the extremes where they may sit.
                    val at = random.nextInt(maxOf(1, copy.size - 3))
                    val extreme = EXTREMES[random.nextInt(EXTREMES.size)]
                    for (k in 0 until 4) if (at + k < copy.size) copy[at + k] = (extreme ushr (8 * (3 - k))).toByte()
                } else {
                    repeat(1 + random.nextInt(MAX_CHANGED_BYTES)) { copy[random.nextInt(copy.size)] = random.nextInt(256).toByte() }
                }
                copy
            }
        return cuts.map { valid.copyOf(it) } + changed
    }

    /**
     * Runs [parse] on every mutant of [valid]. A case passes when it returns or throws something [accepted]
     * allows; anything else (a null dereference, an index error, an allocation failure, a hang) fails the test
     * and names the case.
     */
    fun check(
        label: String,
        valid: ByteArray,
        seed: Long,
        accepted: (Throwable) -> Boolean,
        parse: (ByteArray) -> Unit,
    ) {
        mutants(valid, seed).forEachIndexed { index, bytes ->
            val started = System.nanoTime()
            val failure = runCatching { parse(bytes) }.exceptionOrNull()
            val millis = (System.nanoTime() - started) / 1_000_000
            if (failure != null && !accepted(failure)) {
                throw AssertionError("$label case $index (${bytes.size} bytes): $failure", failure)
            }
            assertTrue("$label case $index took $millis ms", millis < SLOW_MILLIS)
        }
    }

    private const val MAX_CHANGED_BYTES = 4
    private val EXTREMES = intArrayOf(0xFFFFFFFF.toInt(), 0x80000000.toInt(), 0x7FFFFFFF, 0, 1, -2, 0x00010000, 0x00FFFFFF)
}
