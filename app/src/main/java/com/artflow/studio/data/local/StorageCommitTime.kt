package com.artflow.studio.data.local

import java.io.File
import java.io.IOException

/** Establishes saved/recovery ordering on the temporary inode before it is synced and published. */
internal object StorageCommitTime {
    fun ensureAfter(
        file: File,
        previous: Long,
    ) {
        if (file.lastModified() > previous) return
        if (previous > Long.MAX_VALUE - COARSE_CLOCK_STEP_MS) {
            throw IOException("Cannot order artwork timestamps beyond the filesystem range")
        }
        // A full second also accommodates coarse filesystem timestamps and a backward wall clock.
        val next = previous + COARSE_CLOCK_STEP_MS
        if (!file.setLastModified(next) || file.lastModified() <= previous) {
            throw IOException("Cannot preserve saved artwork and recovery ordering")
        }
    }

    private const val COARSE_CLOCK_STEP_MS = 1000L
}
