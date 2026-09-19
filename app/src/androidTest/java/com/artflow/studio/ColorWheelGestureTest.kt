package com.artflow.studio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.core.color.ColorWheelGeometry
import com.artflow.studio.presentation.ui.components.color.ColorWheel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorWheelGestureTest {
    @get:Rule
    val compose = createComposeRule()
    private val color = mutableIntStateOf(0x400000FF)
    private val selected = mutableListOf<Int>()

    private fun show() {
        compose.setContent {
            MaterialTheme {
                ColorWheel(color.intValue) {
                    color.intValue = it
                    selected += it
                }
            }
        }
    }

    @Test
    fun squareDragContinuesAcrossMultipleRecompositions() {
        show()
        val wheel = compose.onNodeWithTag("color-wheel")
        wheel.performTouchInput { down(center) }
        compose.waitForIdle()
        var count = 0
        compose.runOnIdle { count = selected.size }
        wheel.performTouchInput {
            val geometry = ColorWheelGeometry(width.toFloat(), height.toFloat())
            moveTo(center + Offset(geometry.inner * 0.7f, -geometry.inner * 0.7f))
        }
        compose.runOnIdle { assertTrue("The drag stopped after the first colour recomposed", selected.size > count) }
        wheel.performTouchInput {
            val geometry = ColorWheelGeometry(width.toFloat(), height.toFloat())
            moveTo(center + Offset(geometry.inner, -geometry.inner))
            up()
        }
        compose.runOnIdle { assertEquals(0x400000FF, color.intValue) }
    }

    @Test
    fun squareCornerTapDoesNotChangeHueOrAlpha() {
        show()
        compose.onNodeWithTag("color-wheel").performTouchInput {
            val geometry = ColorWheelGeometry(width.toFloat(), height.toFloat())
            click(center + Offset(-geometry.inner * 0.98f, -geometry.inner * 0.98f))
        }
        compose.runOnIdle {
            assertEquals(0x40, color.intValue ushr 24)
            assertTrue((color.intValue and 255) >= 250)
            assertTrue(((color.intValue ushr 16) and 255) >= 245)
        }
    }

    @Test
    fun ringCanChooseHueWhileBlackAndBrightnessRestoresIt() {
        show()
        compose.runOnIdle { color.intValue = 0x40000000 }
        compose.onNodeWithTag("color-wheel").performTouchInput {
            val geometry = ColorWheelGeometry(width.toFloat(), height.toFloat())
            click(center + Offset(geometry.radius, 0f))
        }
        compose.onNodeWithContentDescription("Colour brightness").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle { assertEquals(0x40FF0000, color.intValue) }
    }

    @Test
    fun squareDragKeepsItsRegionOutsideTheSquare() {
        show()
        val wheel = compose.onNodeWithTag("color-wheel")
        wheel.performTouchInput { down(center) }
        compose.waitForIdle()
        wheel.performTouchInput {
            val geometry = ColorWheelGeometry(width.toFloat(), height.toFloat())
            moveTo(center + Offset(geometry.radius, -geometry.radius))
            up()
        }
        compose.runOnIdle { assertEquals(0x400000FF, color.intValue) }
    }

    @Test
    fun blankSpaceDoesNotChooseAColour() {
        show()
        compose.onNodeWithTag("color-wheel").performTouchInput { click(Offset(1f, 1f)) }
        compose.runOnIdle { assertTrue(selected.isEmpty()) }
    }

    @Test
    fun callbackReplacementDuringTheDragIsObservedWithoutRestartingIt() {
        val generation = mutableIntStateOf(0)
        val seen = mutableListOf<Int>()
        compose.setContent {
            val current = generation.intValue
            MaterialTheme {
                ColorWheel(color.intValue) {
                    color.intValue = it
                    seen += current
                }
            }
        }
        val wheel = compose.onNodeWithTag("color-wheel")
        wheel.performTouchInput { down(center) }
        compose.runOnIdle { generation.intValue = 1 }
        wheel.performTouchInput {
            val geometry = ColorWheelGeometry(width.toFloat(), height.toFloat())
            moveTo(center + Offset(geometry.inner, -geometry.inner))
            up()
        }
        compose.runOnIdle {
            assertTrue(seen.contains(0))
            assertEquals(1, seen.last())
        }
    }
}
