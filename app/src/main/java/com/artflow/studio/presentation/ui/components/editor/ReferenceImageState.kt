package com.artflow.studio.presentation.ui.components.editor

import android.graphics.Bitmap

sealed interface ReferenceImageState {
    data object Empty : ReferenceImageState

    data object Loading : ReferenceImageState

    data object Failed : ReferenceImageState

    data class Ready(
        val bitmap: Bitmap,
    ) : ReferenceImageState
}
