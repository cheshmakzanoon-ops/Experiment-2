package com.artflow.studio.presentation.ui.components.canvas

import com.artflow.studio.core.canvas.PointerGestureRouter
import com.artflow.studio.core.canvas.TwoFingerLayerGesture

/** The canvas touch gestures that [PointerGestureRouter] may recognise under these switches. */
fun GestureControls.canvasGestures(): Set<PointerGestureRouter.Gesture> =
    buildSet {
        if (twoFingerTapUndo) add(PointerGestureRouter.Gesture.TWO_FINGER_TAP_UNDO)
        if (threeFingerTapRedo) add(PointerGestureRouter.Gesture.THREE_FINGER_TAP_REDO)
        if (fourFingerFullScreen) add(PointerGestureRouter.Gesture.FOUR_FINGER_TAP_FULL_SCREEN)
        if (holdRapidUndoRedo) add(PointerGestureRouter.Gesture.HOLD_RAPID_UNDO_REDO)
        if (pinchNavigation) add(PointerGestureRouter.Gesture.PINCH_NAVIGATION)
        if (quickPinchFit) add(PointerGestureRouter.Gesture.QUICK_PINCH_FIT)
        if (swipeCopyPaste) add(PointerGestureRouter.Gesture.THREE_FINGER_SWIPE_COPY_PASTE)
        if (scrubToClear) add(PointerGestureRouter.Gesture.THREE_FINGER_SCRUB_CLEAR)
    }

/** The two-finger layer-row gestures these switches allow. */
fun GestureControls.layerGestures(): Set<TwoFingerLayerGesture.Kind> =
    buildSet {
        if (layerTwoFingerOpacity) add(TwoFingerLayerGesture.Kind.TAP_OPACITY)
        if (layerTwoFingerAlphaLock) add(TwoFingerLayerGesture.Kind.SWIPE_ALPHA_LOCK)
        if (layerTwoFingerSelect) add(TwoFingerLayerGesture.Kind.HOLD_SELECT_CONTENTS)
    }
