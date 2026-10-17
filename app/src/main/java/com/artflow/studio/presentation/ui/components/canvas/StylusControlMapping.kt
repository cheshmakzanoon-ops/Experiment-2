package com.artflow.studio.presentation.ui.components.canvas

import com.artflow.studio.core.canvas.StylusButtons
import com.artflow.studio.domain.model.settings.AppSettings

/** The stylus assignments from Prefs > Stylus, as the editor reads them from the stored preferences. */
fun AppSettings.stylusButtons(): StylusButtons =
    StylusButtons(
        primary = stylusPrimaryButton,
        secondary = stylusSecondaryButton,
        eraserEnd = stylusEraserEnd,
    )
