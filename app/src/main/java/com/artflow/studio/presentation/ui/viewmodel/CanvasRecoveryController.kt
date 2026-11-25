package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import com.artflow.studio.domain.repository.canvas.CanvasState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var session = 0L
    private var operation: Job? = null

    /** Invalidate the old dialog's work before the next project's lookup or file reads suspend. */
    fun onProjectOpening(projectId: Long) {
        session++
        operation?.cancel()
        operation = null
        activeProjectId = projectId
        // Saving remains blocked until opening has established this project's recovery state.
        hasUnresolvedRecovery = true
    }

    fun onProjectOpened(
        projectId: Long,
        recoveryAvailable: Boolean,
    ) {
        if (activeProjectId != projectId) onProjectOpening(projectId)
        activeProjectId = projectId
        hasUnresolvedRecovery = recoveryAvailable
    }

    fun recover(projectId: Long) {
        if (projectId == 0L || projectId != activeProjectId) return
        val requestSession = session
        operation?.cancel()
        operation =
            scope.launch {
                val state = canvasRepository.recoverAutosave(projectId)
                if (requestSession != session || activeProjectId != projectId) return@launch
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
        if (projectId == 0L || projectId != activeProjectId) return
        val requestSession = session
        operation?.cancel()
        operation =
            scope.launch {
                canvasRepository.discardRecovery(projectId)
                if (requestSession != session || activeProjectId != projectId) return@launch
                hasUnresolvedRecovery = false
                onDialogDismissed()
            }
    }

    fun dismiss() {
        // Dismissing only hides the prompt. The recovery stays protected AND reachable: the editor keeps
        // offering it (Quick menu) until the artist recovers or discards it.
    }

    fun saveOnBackground(projectId: Long) {
        if (projectId != activeProjectId) return
        scope.launch {
            if (hasUnresolvedRecovery ||
                canvasRepository.hasUnresolvedRecovery(projectId) ||
                !canvasRepository.hasUnsavedChanges()
            ) {
                return@launch
            }
            // Finish an admitted recovery write even if leaving the editor cancels its scope.
            withContext(NonCancellable) { canvasRepository.autosave(projectId) }
        }
    }
}
