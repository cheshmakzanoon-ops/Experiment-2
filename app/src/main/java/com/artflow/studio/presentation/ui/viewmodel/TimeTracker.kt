package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.delay

/** Procreate's tracked time: each interval in which the artwork changed counts as time spent on it. */
internal object TimeTracker {
    const val TICK_MS = 10_000L

    suspend fun run(repository: CanvasRepository): Nothing {
        var seen = repository.contentRevision
        while (true) {
            delay(TICK_MS)
            val revision = repository.contentRevision
            if (revision != seen) {
                repository.addTrackedTime(TICK_MS)
                seen = revision
            }
        }
    }
}
