package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wet mix controls: each dab reads the canvas under it, and each control changes that reading in a stated way. */
class WetMixPickupTest {
    private val width = 40
    private val height = 20
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val centre = 10 * width + 20

    private fun canvas(base: Int): PixelBuffer = PixelBuffer(width, height).also { it.fill(base) }

    /** A horizontal stroke along y = 10 from [fromX] to [toX], painted over a copy of [base]. */
    private fun paint(
        params: BrushParams,
        color: Int,
        base: PixelBuffer,
        fromX: Float = 4.5f,
        toX: Float = 36.5f,
        id: Long = 3L,
    ): PixelBuffer {
        val stroke =
            Stroke(
                id = id,
                points = listOf(StrokePoint(fromX, 10f, 1f, timestamp = 0L), StrokePoint(toX, 10f, 1f, timestamp = 16L)),
                brushParams = params,
                layerId = 1,
                color = color,
            )
        return base.copy().also { StrokeRasterizer().draw(it, stroke) }
    }

    private fun redOf(argb: Int) = (argb shr 16) and 0xFF

    private fun greenOf(argb: Int) = (argb shr 8) and 0xFF

    private fun blueOf(argb: Int) = argb and 0xFF

    private val wet = BrushParams(size = 8f, spacing = 0.05f, wetMix = 1f)

    @Test
    fun brushesWithDefaultWetFieldsLeaveTheStrokeColourOverRedUnchanged() {
        val out = paint(BrushParams(size = 8f, spacing = 0.05f), blue, canvas(red))
        assertEquals(blue, out.pixels[centre])
    }

    @Test
    fun dilutionThinsTheDabTowardTheCanvasMonotonically() {
        val reds =
            listOf(0f, 0.25f, 0.5f, 1f).map { dilution ->
                redOf(paint(BrushParams(size = 8f, spacing = 0.05f, wetDilution = dilution), black, canvas(white)).pixels[centre])
            }
        for (i in 1 until reds.size) {
            assertTrue("Dilution $i should lift the canvas further: $reds", reds[i] > reds[i - 1])
        }
        // Fully diluted paint leaves the canvas as it was.
        assertEquals(white, paint(BrushParams(size = 8f, spacing = 0.05f, wetDilution = 1f), black, canvas(white)).pixels[centre])
    }

    @Test
    fun chargeBelowFullMixesInCanvasColourWithoutWetness() {
        val charged = BrushParams(size = 8f, spacing = 0.05f)
        val full = paint(charged, blue, canvas(red)).pixels[centre]
        val half = paint(charged.copy(wetCharge = 0.5f), blue, canvas(red)).pixels[centre]
        val empty = paint(charged.copy(wetCharge = 0f), blue, canvas(red)).pixels[centre]
        assertEquals("Full charge keeps the chosen colour", blue, full)
        assertEquals("No charge carries only the canvas colour", red, empty)
        assertTrue("Half charge is between them: ${blueOf(half)}", blueOf(half) in 100..160 && redOf(half) in 100..160)
    }

    @Test
    fun attackDelaysPickupAtTheStrokeStartButNotLater() {
        val early = 10 * width + 5
        val late = 10 * width + 30
        val slow = paint(wet.copy(wetAttack = 0f), blue, canvas(red))
        val immediate = paint(wet, blue, canvas(red))
        assertTrue("Pickup has not built up at the start", blueOf(slow.pixels[early]) > blueOf(immediate.pixels[early]) + 100)
        assertTrue("Pickup builds along the stroke", blueOf(slow.pixels[late]) < blueOf(slow.pixels[early]))
        assertEquals("Full attack picks up at once and stays steady", blueOf(immediate.pixels[early]), blueOf(immediate.pixels[late]))
    }

    @Test
    fun pullDragsPickedUpColourForwardAlongTheStroke() {
        // Red up to x = 20, then white. A pulled dab reads the canvas behind it, so red trails past the edge.
        val split = canvas(white).also { buffer -> for (y in 0 until height) for (x in 0..20) buffer.pixels[y * width + x] = red }
        val x = 30
        val index = 10 * width + x
        val unpulled = paint(wet, blue, split).pixels[index]
        val pulled = paint(wet.copy(wetPull = 1f), blue, split).pixels[index]
        assertTrue("Pulled pickup carries red past the edge", greenOf(pulled) < greenOf(unpulled) - 100)
        assertTrue("Unpulled pickup is the white canvas", blueOf(unpulled) > 240)
    }

    @Test
    fun pullFollowsTheDirectionOfTheStroke() {
        val split = canvas(white).also { buffer -> for (y in 0 until height) for (x in 0..20) buffer.pixels[y * width + x] = red }
        // Drawn from right to left, the drag comes from the right, which is white here.
        val leftwards = { pull: Float -> paint(wet.copy(wetPull = pull), blue, split, fromX = 36.5f, toX = 4.5f).pixels[10 * width + 10] }
        assertTrue("Reversed stroke drags the white behind it", blueOf(leftwards(1f)) > blueOf(leftwards(0f)) + 100)
    }

    @Test
    fun attackAndPullHaveNoEffectWithoutWetness() {
        val plain = BrushParams(size = 8f, spacing = 0.05f)
        val controls = plain.copy(wetAttack = 0f, wetPull = 1f, wetBlur = 1f)
        assertArrayEquals(paint(plain, blue, canvas(red)).pixels, paint(controls, blue, canvas(red)).pixels)
    }

    @Test
    fun blurSpreadsPickupFromAThinLineBeyondTheDabCentre() {
        // A one-pixel red column at x = 20. The stroke ends on x = 16.5, so its last dab (which paints the
        // pixel at x = 16) reads the column only through its blur taps.
        val column = canvas(white).also { buffer -> for (y in 0 until height) buffer.pixels[y * width + 20] = red }
        val sharp = paint(wet, blue, column, toX = 16.5f).pixels[centre - 4]
        val soft = paint(wet.copy(wetBlur = 1f), blue, column, toX = 16.5f).pixels[centre - 4]
        assertTrue("Blur pulls green down from the column: $sharp vs $soft", greenOf(soft) + 15 < greenOf(sharp))
        assertTrue("Blur never reads colour the sharp pickup did not", redOf(soft) >= redOf(sharp) - 1)
    }

    @Test
    fun blurJitterIsDeterministicForAStrokeAndVariesBetweenStrokes() {
        val jittered = wet.copy(wetBlur = 1f, wetBlurJitter = 0.8f)
        val first = paint(jittered, blue, canvas(red), id = 7L)
        assertArrayEquals(first.pixels, paint(jittered, blue, canvas(red), id = 7L).pixels)
        assertNotEquals(first.pixels.toList(), paint(jittered, blue, canvas(red), id = 8L).pixels.toList())
    }

    @Test
    fun aRegionRenderWithPullAndBlurMatchesTheFullRender() {
        val split = canvas(white).also { buffer -> for (y in 0 until height) for (x in 0..20) buffer.pixels[y * width + x] = red }
        val params = wet.copy(wetPull = 1f, wetBlur = 1f, wetBlurJitter = 0.5f, wetDilution = 0.2f)
        val stroke =
            Stroke(
                id = 9L,
                points = listOf(StrokePoint(4.5f, 10f, 1f, timestamp = 0L), StrokePoint(36.5f, 10f, 1f, timestamp = 16L)),
                brushParams = params,
                layerId = 1,
                color = blue,
            )
        val region = StrokeReach.bounds(stroke.points, params, width, height)
        val full = LayerStrokeRenderer.render(split, emptyList(), listOf(stroke), width, height, false, null)
        val partial =
            LayerStrokeRenderer.render(split, emptyList(), listOf(stroke), width, height, false, null, region = region)
        assertArrayEquals(full.pixels, partial.pixels)
        assertTrue("The region covers the canvas the pull reads", region.left <= 14 && region.right >= 36)
    }
}
