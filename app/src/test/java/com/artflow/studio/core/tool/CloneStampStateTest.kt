package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloneStampStateTest {
    private val settings = PixelBrushes.CloneSettings(size = 4f, hardness = 1f)
    private val source =
        PixelBuffer(64, 32).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    setUnchecked(
                        x,
                        y,
                        if (x < 16) {
                            RED
                        } else if (x < 32) {
                            GREEN
                        } else {
                            BLUE
                        },
                    )
                }
            }
        }

    @Test
    fun alignedStrokesKeepTheirOffsetAfterThePenLifts() {
        val target = PixelBuffer(64, 32)
        var state = CloneStampState().withSource(8f, 8f)
        state = state.afterStroke(stamp(state, target, 40f))
        stamp(state, target, 56f)

        assertEquals(RED, target.getSafe(40, 8))
        assertEquals(GREEN, target.getSafe(56, 8))
    }

    @Test
    fun unalignedRestartsEveryStrokeButStillTracksTheBrushWithinOneStroke() {
        val target = PixelBuffer(64, 32)
        var state = CloneStampState().withSource(8f, 8f)
        state = state.afterStroke(stamp(state, target, 40f, aligned = false))
        val second = stamp(state, target, 48f, aligned = false)
        second.session.dragTo(60f, 8f, target, source)

        assertEquals(RED, target.getSafe(40, 8))
        assertEquals(RED, target.getSafe(48, 8))
        assertEquals(GREEN, target.getSafe(60, 8))
        state = state.afterStroke(second)
        stamp(state, target, 56f, aligned = false)
        assertEquals(RED, target.getSafe(56, 8))
    }

    @Test
    fun abandonedFirstStrokeDoesNotEstablishAnOffset() {
        val state = CloneStampState().withSource(8f, 8f)
        stamp(state, PixelBuffer(64, 32), 40f) // Provisional pixels are discarded without accepting this stroke.
        val target = PixelBuffer(64, 32)
        val accepted = state.afterStroke(stamp(state, target, 56f))
        stamp(accepted, target, 48f)

        assertEquals(RED, target.getSafe(56, 8))
        assertEquals(RED, target.getSafe(48, 8))
    }

    @Test
    fun reselectingSourceRejectsLateAlignmentFromThePreviousChoice() {
        val target = PixelBuffer(64, 32)
        val previous = CloneStampState().withSource(8f, 8f)
        val finishing = stamp(previous, target, 40f)
        val current = previous.withSource(40f, 8f).afterStroke(finishing)
        stamp(current, target, 56f)

        assertEquals(BLUE, target.getSafe(56, 8))
    }

    @Test
    fun changingAlignmentModeReanchorsAtTheChosenOrigin() {
        val target = PixelBuffer(64, 32)
        val original = CloneStampState().withSource(8f, 8f)
        val first = stamp(original, target, 40f)
        val aligned = original.afterStroke(first)
        val reset = aligned.withoutAlignment().afterStroke(first)
        stamp(reset, target, 56f)

        assertEquals(RED, target.getSafe(56, 8))
    }

    @Test
    fun noStrokeCanStartBeforeASourceIsChosen() {
        assertNull(CloneStampState().beginStroke(40f, 8f, settings))
    }

    private fun stamp(
        state: CloneStampState,
        target: PixelBuffer,
        x: Float,
        aligned: Boolean = true,
    ): CloneStampState.Stroke {
        val stroke = requireNotNull(state.beginStroke(x, 8f, settings.copy(aligned = aligned)))
        stroke.session.dragTo(x, 8f, target, source)
        return stroke
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
    }
}
