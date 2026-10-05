package com.artflow.studio.core.render

import java.util.Random

/**
 * [java.util.Random] with the very same sequence (the same 48-bit linear congruential generator
 * and seed scrambling), whose position can be copied. A stroke drawn live uses a copy for the dab
 * it redraws every frame, so its own sequence continues as if that dab had never been drawn.
 */
internal class ReplayableRandom(
    seed: Long,
) : Random() {
    // Set after the superclass constructor, whose own setSeed call this replaces.
    private var state: Long = scramble(seed)

    override fun setSeed(seed: Long) {
        state = scramble(seed)
    }

    override fun next(bits: Int): Int {
        state = (state * MULTIPLIER + ADDEND) and MASK
        return (state ushr (STATE_BITS - bits)).toInt()
    }

    fun copy(): ReplayableRandom = ReplayableRandom(0L).also { it.state = state }

    private companion object {
        const val MULTIPLIER = 0x5DEECE66DL
        const val ADDEND = 0xBL
        const val STATE_BITS = 48
        const val MASK = (1L shl STATE_BITS) - 1

        fun scramble(seed: Long) = (seed xor MULTIPLIER) and MASK
    }
}
