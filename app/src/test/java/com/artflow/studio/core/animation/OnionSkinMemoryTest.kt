package com.artflow.studio.core.animation

import com.artflow.studio.domain.model.layer.FilterType
import com.artflow.studio.domain.model.layer.Layer
import com.artflow.studio.domain.model.layer.LayerEffects
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnionSkinMemoryTest {
    private val plain = listOf(Layer(1, "Drawing", 0))

    @Test
    fun smallOutputDoesNotAdmitAnUnsafeFullResolutionCompositor() {
        assertNull(OnionSkinMemory.plan(8192, 4096, listOf(plain), Long.MAX_VALUE, 0))
        assertNull(OnionSkinMemory.plan(Int.MAX_VALUE, Int.MAX_VALUE, listOf(plain), Long.MAX_VALUE, 0))
        assertNotNull(OnionSkinMemory.plan(1024, 1024, listOf(plain), 64 * MIB, 0))
    }

    @Test
    fun manyGhostsReduceTheOutputBeforeAccumulatingTheirImagesAndTextures() {
        val single = requireNotNull(OnionSkinMemory.plan(1024, 1024, listOf(plain), 80 * MIB, 0))
        val many = requireNotNull(OnionSkinMemory.plan(1024, 1024, List(24) { plain }, 80 * MIB, 0))
        assertTrue(many.width < single.width)
        assertTrue(many.width >= 64)
        assertTrue(many.rendererBytes > many.width.toLong() * many.height * 4L * 24)
    }

    @Test
    fun retainedGenerationsAndChangingHeadroomCanRefuseEvenASmallPreview() {
        assertNull(OnionSkinMemory.plan(64, 32, listOf(plain), 32 * MIB, 32 * MIB))
        val plan = requireNotNull(OnionSkinMemory.plan(64, 32, listOf(plain), 32 * MIB, 0))
        assertFalse(plan.fitsFrame(0, 0, 0))
        assertFalse(plan.fitsPreparation(0, 32 * MIB, 32 * MIB))
        assertFalse(plan.fitsPublication(0, 0))
        assertTrue(plan.fitsPublication(32 * MIB, 0))
    }

    @Test
    fun masksNestedGroupsAndEffectsAreChargedBeforeTheFirstRender() {
        val complex =
            listOf(
                Layer(2, "Group", 0, isGroup = true),
                plain.single().copy(
                    parentGroupId = 2,
                    maskFile = "rasters/mask.png",
                    maskFeather = 8f,
                    effects = LayerEffects(outline = LayerEffects.Outline(), shadow = LayerEffects.Shadow()),
                    filterType = FilterType.GAUSSIAN_BLUR,
                ),
            )
        assertNotNull(OnionSkinMemory.plan(1024, 1024, listOf(plain), 32 * MIB, 0))
        assertNull(OnionSkinMemory.plan(1024, 1024, listOf(complex), 32 * MIB, 0))
    }

    private companion object {
        const val MIB = 1024L * 1024
    }
}
