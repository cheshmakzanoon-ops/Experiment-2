package com.artflow.studio.core.render

import org.junit.Test

class BrushAttributeDynamicsTest {
    @Test
    fun everyBrushAttributeHasExactlyOneRoute() = BrushDynamicsChecks.attributeRoutesAreCompleteAndDisjoint()

    @Test
    fun speedControlsRespectTheParameterContracts() = BrushDynamicsChecks.speedControlsAffectSizeAndOpacityWithinTheirContracts()

    @Test
    fun pressureAndSpeedColourChangesPreserveAlpha() = BrushDynamicsChecks.speedHueAndPressureBrightnessRetainSourceAlpha()

    @Test
    fun speedSizeAffectsActualRenderedInk() = BrushDynamicsChecks.speedSizeChangesRenderedInkRatherThanJustTheSlider()

    @Test
    fun speedOpacityAffectsActualRenderedAlpha() = BrushDynamicsChecks.speedOpacityChangesRenderedAlphaWithoutChangingFootprint()

    @Test
    fun colourDynamicsAreConnectedToTheRasterizer() = BrushDynamicsChecks.colourDynamicsChangeActualRenderedPixels()

    @Test
    fun dynamicStrokesAreDeterministicAndIndependent() = BrushDynamicsChecks.rendererRetainsDeterminismAndUnrelatedSettings()
}
