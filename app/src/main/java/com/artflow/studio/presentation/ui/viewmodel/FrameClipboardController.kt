package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.domain.repository.canvas.CanvasRepository
import kotlinx.coroutines.CoroutineScope
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

    /** True once a frame has been copied in this session, so the panel offers Paste. */
    val canPaste: StateFlow<Boolean> = _canPaste.asStateFlow()

    /** Copies the frame at [index]. */
    fun copy(index: Int) {
        scope.launch {
            if (repository.copyFrame(index)) _canPaste.value = true
        }
    }

    /** Inserts the copied frame at [insertAt] and makes it the active frame. */
    fun paste(insertAt: Int) {
        stopPlayback()
        scope.launch {
            if (!repository.pasteFrame(insertAt)) notify("Copy a frame before pasting it here")
            refresh()
        }
    }
}
