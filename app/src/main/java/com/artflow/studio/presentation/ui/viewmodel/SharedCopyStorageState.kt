package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.data.local.SharedCopySummary

internal data class SharedCopyStorageState(
    val summary: SharedCopySummary? = null,
    val refreshing: Boolean = false,
    val removing: Boolean = false,
    val error: String? = null,
) {
    val busy: Boolean get() = refreshing || removing
}
