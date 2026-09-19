package com.artflow.studio.core.canvas

import org.junit.Test

class LayerTransformTest {
    @Test fun identityAndOwnership() { LayerTransformChecks.identityAndOwnership() }
    @Test fun translationsAndClipping() { LayerTransformChecks.translationsAndClipping() }
    @Test fun quarterTurnsAndFlips() { LayerTransformChecks.quarterTurnsAndFlips() }
    @Test fun scaleSkewAndCustomPivot() { LayerTransformChecks.scaleSkewAndCustomPivot() }
    @Test fun alphaCorrectInterpolation() { LayerTransformChecks.alphaCorrectInterpolation() }
    @Test fun validationAndCancellation() { LayerTransformChecks.validationAndCancellation() }
}
