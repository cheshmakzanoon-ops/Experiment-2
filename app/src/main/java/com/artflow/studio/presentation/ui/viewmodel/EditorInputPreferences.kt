package com.artflow.studio.presentation.ui.viewmodel

import com.artflow.studio.domain.model.settings.AppSettings
import com.artflow.studio.presentation.ui.components.canvas.EditorInput
import com.artflow.studio.presentation.ui.components.canvas.gestureControls
import com.artflow.studio.presentation.ui.components.canvas.stylusButtons

/** Applies persisted input preferences without replacing the document's selected tool or guides. */
internal fun EditorInput.withPreferences(stored: AppSettings): EditorInput =
    copy(
        snapToGuides = stored.snapToGuides,
        fingerPainting = stored.fingerPainting,
        pressureCurve = stored.pressureCurve,
        stabilization = stored.stabilization,
        motionFiltering = stored.motionFiltering,
        motionExpression = stored.motionExpression,
        pressureSmoothing = stored.pressureSmoothing,
        pulledString = stored.pulledString,
        pressureResponse = stored.pressureResponse,
        haptics = stored.haptics,
        reduceMotion = stored.reduceMotion,
        brushCursor = stored.brushCursor,
        gestures = stored.gestureControls(),
        dynamicBrushScaling = stored.dynamicBrushScaling,
        stylus = stored.stylusButtons(),
        quickShape = stored.quickShape,
        touchHoldEyedropper = stored.touchHoldEyedropper,
    )
