package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Copy and paste of whole animation frames for the Animation panel. The copied frame is held by the
 * repository, so a paste inserts the frame as it was when it was copied.
 *
 * @param refresh reloads the layer list and the frame state after a paste changed them.
 * @param stopPlayback stops a running preview before the frames change under it.
 */
class FrameClipboardController(
    private val repository: CanvasRepository,
    private val scope: CoroutineScope,
    private val notify: (String) -> Unit,
    private val refresh: () -> Unit,
    private val stopPlayback: () -> Unit,
) {
    private val _canPaste = MutableStateFlow(false)
    private var generation = 0L
    private var operations: Job = SupervisorJob(scope.coroutineContext[Job])

    /** True once a frame has been copied in this session, so the panel offers Paste. */
    val canPaste: StateFlow<Boolean> = _canPaste.asStateFlow()

    /** Pending commands and the Paste affordance belong to the artwork being closed. */
    fun reset() {
        generation++
        operations.cancel()
        operations = SupervisorJob(scope.coroutineContext[Job])
        _canPaste.value = false
    }

    /** Copies the frame at [index]. */
    fun copy(index: Int) {
        val projectId = repository.projectId()
        val request = generation
        scope.launch(operations) {
            if (!owns(projectId, request)) return@launch
            val copied = repository.copyFrame(index)
            if (copied && owns(projectId, request)) _canPaste.value = true
        }
    }

    /** Inserts the copied frame at [insertAt] and makes it the active frame. */
    fun paste(insertAt: Int) {
        val projectId = repository.projectId()
        val request = generation
        stopPlayback()
        scope.launch(operations) {
            if (!owns(projectId, request)) return@launch
            val pasted = repository.pasteFrame(insertAt)
            if (!owns(projectId, request)) return@launch
            if (!pasted) {
                _canPaste.value = false
                notify("Copy a frame before pasting it here")
            }
            refresh()
        }
    }

    private fun owns(
        projectId: Long,
        request: Long,
    ): Boolean = projectId != 0L && generation == request && repository.projectId() == projectId
}
