package com.artflow.studio.core.render

import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.Layer
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupMaskTest {
    private val red = 0xFFFF0000.toInt()

    /** Two pixels: the mask reveals the left one and hides the right one. */
    private fun maskRevealingOnlyTheLeft(): PixelBuffer =
        PixelBuffer(2, 1).also {
            it.pixels[0] = 0xFFFFFFFF.toInt()
            it.pixels[1] = 0xFF000000.toInt()
        }

    private fun compositeWithGroupMask(group: Layer): PixelBuffer {
        val member = Layer(id = 1, name = "A", index = 0, parentGroupId = 10)
        val inputs =
            listOf(
                Compositor.LayerInput(member, PixelBuffer(2, 1).also { it.fill(red) }),
                Compositor.LayerInput(group, mask = maskRevealingOnlyTheLeft()),
            )
        return Compositor().composite(inputs, 2, 1)
    }

    @Test
    fun aMaskOnAGroupHidesItsMembersWhereItIsBlack() {
        val group = Layer(id = 10, name = "Group", index = 1, isGroup = true)
        val out = compositeWithGroupMask(group)
        assertEquals(red, out.pixels[0])
        assertEquals(0, out.pixels[1] ushr 24)
    }

    @Test
    fun aMaskOnAPassThroughGroupHidesItsMembersToo() {
        // A pass-through folder does not merge its members on its own, so the mask must make it merge them.
        val group = Layer(id = 10, name = "Group", index = 1, isGroup = true, blendMode = BlendMode.PASS_THROUGH)
        val out = compositeWithGroupMask(group)
        assertEquals(red, out.pixels[0])
        assertEquals(0, out.pixels[1] ushr 24)
    }
}
