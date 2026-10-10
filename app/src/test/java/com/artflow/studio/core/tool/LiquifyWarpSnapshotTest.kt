package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.IntBounds
import com.artflow.studio.core.pixels.PixelBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The live preview renders a snapshot off the UI thread, so it must match the full warp where the warp applies. */
class LiquifyWarpSnapshotTest {
    private val width = 65
    private val center = 32.5f
    private val warpModes =
        listOf(
            LiquifyTool.Mode.PUSH,
            LiquifyTool.Mode.TWIRL_CLOCKWISE,
            LiquifyTool.Mode.PINCH,
            LiquifyTool.Mode.BLOAT,
            LiquifyTool.Mode.CRYSTALS,
            LiquifyTool.Mode.EDGE,
        )

    @Test
    fun snapshotMatchesTheFullWarpInsideItsBoundsForEveryWarpMode() {
        val source = source()
        for (mode in warpModes) {
            for (alphaLock in listOf(false, true)) {
                val session =
                    session(mode, alphaLock).apply {
                        dragTo(center + 6f, center + 3f)
                        dragTo(center + 10f, center + 8f)
                    }
                val bounds = checkNotNull(session.dirtyBounds)
                val full = session.render(source)
                assertArrayEquals("$mode alphaLock=$alphaLock", inside(full, bounds), session.snapshotWarp(bounds).render(source))
            }
        }
    }

    @Test
    fun pixelsOutsideTheDirtyBoundsKeepTheirSource() {
        val source = source()
        val session = session(LiquifyTool.Mode.PUSH).apply { dragTo(center + 6f, center) }
        val bounds = checkNotNull(session.dirtyBounds)
        val full = session.render(source)
        for (y in 0 until width) {
            for (x in 0 until width) {
                if (!bounds.contains(x, y)) assertEquals("($x, $y)", source.pixels[y * width + x], full.pixels[y * width + x])
            }
        }
    }

    @Test
    fun aSnapshotIsUnaffectedByDragsThatFollowIt() {
        val source = source()
        val session = session(LiquifyTool.Mode.PUSH).apply { dragTo(center + 6f, center) }
        val snapshot = session.snapshotWarp(checkNotNull(session.dirtyBounds))
        val before = snapshot.render(source)
        session.dragTo(center - 12f, center + 9f)
        assertArrayEquals(before, snapshot.render(source))
    }

    @Test(expected = IllegalArgumentException::class)
    fun reconstructIsNotSnapshotted() {
        session(LiquifyTool.Mode.RECONSTRUCT).snapshotWarp(IntBounds(0, 0, 4, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRegionOutsideTheMapIsRefused() {
        session(LiquifyTool.Mode.PUSH).snapshotWarp(IntBounds(0, 0, width, 4))
    }

    private fun session(
        mode: LiquifyTool.Mode,
        alphaLock: Boolean = false,
    ): LiquifyTool.Session =
        LiquifyTool.beginSession(
            center,
            center,
            LiquifyTool.Settings(mode = mode, size = 40f, alphaLock = alphaLock),
            width,
            width,
        )

    /** Varied colour and alpha, including clear pixels, so the alpha lock has something to keep. */
    private fun source(): PixelBuffer =
        PixelBuffer(width, width).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val alpha = if ((x + y) % 7 == 0) 0 else 255 - (x * 3) % 128
                    setUnchecked(x, y, (alpha shl 24) or ((x * 3) shl 16) or ((y * 3) shl 8) or (x xor y))
                }
            }
        }

    private fun inside(
        buffer: PixelBuffer,
        bounds: IntBounds,
    ): IntArray {
        val out = IntArray(bounds.width * bounds.height)
        for (row in 0 until bounds.height) {
            for (column in 0 until bounds.width) {
                out[row * bounds.width + column] = buffer.pixels[(bounds.top + row) * width + bounds.left + column]
            }
        }
        return out
    }
}
