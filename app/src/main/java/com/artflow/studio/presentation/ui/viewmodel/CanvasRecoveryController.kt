package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Authoritative recovery state machine for the active canvas session.
 *
 * Decouples UI dialog visibility from the underlying unresolved recovery state
 * to prevent autosaves, background saves, or manual saves from destroying
 * unrecovered snapshots.
 */
class CanvasRecoveryController(
    private val canvasRepository: CanvasRepository,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val onStateRecovered: (CanvasState) -> Unit,
    private val onDialogDismissed: () -> Unit,
) {
    var hasUnresolvedRecovery: Boolean = false
        private set

    var activeProjectId: Long = 0L
        private set

    fun onProjectOpened(
        projectId: Long,
        recoveryAvailable: Boolean,
    ) {
        activeProjectId = projectId
        hasUnresolvedRecovery = recoveryAvailable
    }

    fun recover(projectId: Long) {
        if (projectId == 0L) return
        scope.launch {
            val state = canvasRepository.recoverAutosave(projectId)
            if (state == null) {
                notify("There was nothing to recover")
            } else {
                hasUnresolvedRecovery = false
                onStateRecovered(state)
                notify("Recovered the autosaved version")
            }
        }
    }

    fun discard(projectId: Long) {
        if (projectId == 0L) return
        scope.launch {
            canvasRepository.discardRecovery(projectId)
            hasUnresolvedRecovery = false
            onDialogDismissed()
        }
    }

    fun dismiss() {
        // Dismissing only hides the UI prompt; unresolved recovery remains protected.
        onDialogDismissed()
    }
}
