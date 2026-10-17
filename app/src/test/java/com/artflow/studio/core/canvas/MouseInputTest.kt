package com.artflow.studio.core.canvas

import org.junit.Assert.assertEquals
import org.junit.Test

class MouseInputTest {
    private val none = 0f
    private val noPinch = 1f

    @Test
    fun leftButtonOrNoButtonDrawsLikeAFinger() {
        assertEquals(MousePress.DRAW, MouseInput.press(0))
        assertEquals(MousePress.DRAW, MouseInput.press(MouseInput.BUTTON_PRIMARY))
    }

    @Test
    fun rightButtonSamplesTheColourUnderThePointer() {
        assertEquals(MousePress.SAMPLE, MouseInput.press(MouseInput.BUTTON_SECONDARY))
        assertEquals(MousePress.SAMPLE, MouseInput.press(MouseInput.BUTTON_PRIMARY or MouseInput.BUTTON_SECONDARY))
    }

    @Test
    fun middleButtonPansEvenWhenAnotherButtonIsAlsoHeld() {
        assertEquals(MousePress.PAN, MouseInput.press(MouseInput.BUTTON_TERTIARY))
        assertEquals(MousePress.PAN, MouseInput.press(MouseInput.BUTTON_SECONDARY or MouseInput.BUTTON_TERTIARY))
    }

    @Test
    fun aPlainScrollPansTheArtworkWithTheWheelDirection() {
        val scrollUp = MouseInput.wheel(ctrl = false, horizontal = none, vertical = 1f, pinchScale = noPinch)
        assertEquals(WheelAction.Pan(dx = 0f, dy = MouseInput.PAN_PER_NOTCH), scrollUp)
        val scrollRight = MouseInput.wheel(ctrl = false, horizontal = 1f, vertical = none, pinchScale = noPinch)
        assertEquals(WheelAction.Pan(dx = -MouseInput.PAN_PER_NOTCH, dy = 0f), scrollRight)
    }

    @Test
    fun ctrlWithAVerticalScrollZoomsAboutThePointerByNotches() {
        val zoomIn = MouseInput.wheel(ctrl = true, horizontal = none, vertical = 1f, pinchScale = noPinch)
        assertEquals(WheelAction.Zoom(MouseInput.ZOOM_PER_NOTCH), zoomIn)
        val zoomOut = MouseInput.wheel(ctrl = true, horizontal = none, vertical = -1f, pinchScale = noPinch)
        assertEquals(1f / MouseInput.ZOOM_PER_NOTCH, (zoomOut as WheelAction.Zoom).factor, 0.0001f)
        val twoNotches = MouseInput.wheel(ctrl = true, horizontal = none, vertical = 2f, pinchScale = noPinch)
        assertEquals(MouseInput.ZOOM_PER_NOTCH * MouseInput.ZOOM_PER_NOTCH, (twoNotches as WheelAction.Zoom).factor, 0.0001f)
    }

    @Test
    fun ctrlWithOnlyHorizontalScrollPans() {
        assertEquals(
            WheelAction.Pan(dx = -MouseInput.PAN_PER_NOTCH, dy = 0f),
            MouseInput.wheel(ctrl = true, horizontal = 1f, vertical = none, pinchScale = noPinch),
        )
    }

    @Test
    fun aTrackpadPinchZoomsByItsScaleWhetherOrNotCtrlIsHeld() {
        assertEquals(WheelAction.Zoom(1.25f), MouseInput.wheel(ctrl = false, horizontal = none, vertical = none, pinchScale = 1.25f))
        assertEquals(WheelAction.Zoom(0.8f), MouseInput.wheel(ctrl = true, horizontal = 1f, vertical = 1f, pinchScale = 0.8f))
    }

    @Test
    fun pinchesThatAreNoChangeOrNotFiniteAreIgnored() {
        assertEquals(WheelAction.None, MouseInput.wheel(ctrl = false, horizontal = none, vertical = none, pinchScale = noPinch))
        assertEquals(WheelAction.None, MouseInput.wheel(ctrl = false, horizontal = none, vertical = none, pinchScale = 0f))
        assertEquals(WheelAction.None, MouseInput.wheel(ctrl = false, horizontal = none, vertical = none, pinchScale = Float.NaN))
        assertEquals(
            WheelAction.Pan(dx = 0f, dy = MouseInput.PAN_PER_NOTCH),
            MouseInput.wheel(ctrl = false, horizontal = none, vertical = 1f, pinchScale = 0f),
        )
    }

    @Test
    fun aScrollWithNoMovementDoesNothing() {
        assertEquals(WheelAction.None, MouseInput.wheel(ctrl = true, horizontal = none, vertical = none, pinchScale = noPinch))
        assertEquals(WheelAction.None, MouseInput.wheel(ctrl = false, horizontal = Float.NaN, vertical = 1f, pinchScale = noPinch))
    }
}
