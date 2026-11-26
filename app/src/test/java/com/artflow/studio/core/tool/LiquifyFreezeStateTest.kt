package com.artflow.studio.core.tool

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquifyFreezeStateTest {
    private val owner = LiquifyFreezeState.Owner(17, 2, 4, 8, 64, 64)
    private val freeze = LiquifyTool.Settings(mode = LiquifyTool.Mode.FREEZE, size = 24f, momentum = 1f)

    @Test
    fun acceptedFreezeTapProtectsLaterDistortionWithoutChangingPixels() {
        val state = LiquifyFreezeState(owner)
        val gesture = state.beginSession(32f, 32f, freeze)
        val source = PixelBuffer(64, 64, IntArray(64 * 64) { 0xff000000.toInt() or it })
        assertTrue(gesture.map.isEmpty())
        assertArrayEquals(source.pixels, gesture.render(source).pixels)
        val accepted = state.afterMaskGesture(gesture)
        assertTrue(accepted.hasFrozenPixels)
        assertTrue(accepted.frozenPercent > 0)

        val push = accepted.beginSession(18f, 32f, LiquifyTool.Settings(size = 40f, strength = 1f))
        push.dragTo(40f, 32f)
        assertFalse(push.map.hasDisplacement(32 * 64 + 32))
        assertTrue(push.map.hasDisplacement(32 * 64 + 16))
    }

    @Test
    fun unacceptedThawCannotChangeCoverageAndAcceptedThawRestoresDistortion() {
        val empty = LiquifyFreezeState(owner)
        val accepted = empty.afterMaskGesture(empty.beginSession(32f, 32f, freeze))
        val thaw = accepted.beginSession(32f, 32f, freeze.copy(mode = LiquifyTool.Mode.UNFREEZE))
        assertFalse(requireNotNull(thaw.settings.freezeMask)[32 * 64 + 32])
        assertTrue(requireNotNull(accepted.beginSession(18f, 32f, LiquifyTool.Settings()).settings.freezeMask)[32 * 64 + 32])
        val cleared = accepted.afterMaskGesture(thaw)
        assertFalse(cleared.hasFrozenPixels)
        val push = cleared.beginSession(18f, 32f, LiquifyTool.Settings(size = 40f, strength = 1f))
        push.dragTo(40f, 32f)
        assertTrue(push.map.hasDisplacement(32 * 64 + 32))
    }

    @Test
    fun acceptedCoverageDoesNotAliasTheCompletedOrNextGesture() {
        val state = LiquifyFreezeState(owner)
        val first = state.beginSession(16f, 32f, freeze)
        val accepted = state.afterMaskGesture(first)
        first.dragTo(48f, 32f)
        val later = accepted.beginSession(16f, 32f, LiquifyTool.Settings())
        assertFalse(requireNotNull(later.settings.freezeMask)[32 * 64 + 40])
        requireNotNull(later.settings.freezeMask).fill(false)
        assertTrue(requireNotNull(accepted.beginSession(16f, 32f, LiquifyTool.Settings()).settings.freezeMask)[32 * 64 + 16])
    }

    @Test
    fun freezeAndThawRespectSelectionAndNeverContinueWithMomentum() {
        val selection = SelectionMask(64, 64, ByteArray(64 * 64) { if (it % 64 < 32) -1 else 0 })
        val state = LiquifyFreezeState(owner)
        val gesture = state.beginSession(32f, 32f, freeze.copy(mask = selection))
        val bits = requireNotNull(gesture.settings.freezeMask)
        assertTrue(bits[32 * 64 + 28])
        assertFalse(bits[32 * 64 + 36])
        gesture.dragTo(33f, 32f)
        val beforeRelease = bits.copyOf()
        assertNull(gesture.release())
        assertArrayEquals(beforeRelease, bits)

        val thaw =
            state.beginSession(
                32f,
                32f,
                freeze.copy(mode = LiquifyTool.Mode.UNFREEZE, mask = selection, freezeMask = BooleanArray(64 * 64) { true }),
            )
        assertFalse(requireNotNull(thaw.settings.freezeMask)[32 * 64 + 28])
        assertTrue(requireNotNull(thaw.settings.freezeMask)[32 * 64 + 36])
    }

    @Test
    fun ownershipChangesClearCoverageWhileAcceptedDistortionsAdvanceItsRevision() {
        val empty = LiquifyFreezeState(owner)
        val accepted = empty.afterMaskGesture(empty.beginSession(32f, 32f, freeze))
        assertSame(accepted, accepted.forOwner(owner))
        val owners =
            listOf(
                owner.copy(projectId = 18),
                owner.copy(layerId = 3),
                owner.copy(frameId = 5),
                owner.copy(revision = 9),
                owner.copy(width = 65),
                owner.copy(height = 65),
            )
        owners.forEach { changed ->
            val reset = accepted.forOwner(changed)
            assertNotSame(accepted, reset)
            assertFalse(reset.hasFrozenPixels)
        }
        val advanced = accepted.afterRasterCommit(9)
        assertEquals(9L, advanced.owner.revision)
        assertEquals(accepted.frozenPercent, advanced.frozenPercent)
        assertSame(advanced, advanced.forOwner(owner.copy(revision = 9)))
    }

    @Test
    fun maskAdmissionIncludesRetainedWorkingAcceptedAndSelectionCopies() {
        val state = LiquifyFreezeState(owner)
        val limit = 4L * 64 * 64
        assertThrows(IllegalArgumentException::class.java) { state.beginSession(32f, 32f, freeze, limit - 1) }
        val gesture = state.beginSession(32f, 32f, freeze, limit)
        val accepted = state.afterMaskGesture(gesture)
        assertThrows(IllegalArgumentException::class.java) {
            accepted.beginSession(32f, 32f, LiquifyTool.Settings(), limit - 1)
        }
        val replacement = LiquifyTool.Settings(freezeMask = BooleanArray(64 * 64))
        assertThrows(IllegalArgumentException::class.java) { accepted.beginSession(32f, 32f, replacement, limit) }
        accepted.beginSession(32f, 32f, replacement, 5L * 64 * 64)
        assertNull(state.beginSession(32f, 32f, LiquifyTool.Settings(), 0).settings.freezeMask)
        assertFalse(state.hasFrozenPixels)
    }
}
