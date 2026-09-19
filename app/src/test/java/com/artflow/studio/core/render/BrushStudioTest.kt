package com.artflow.studio.core.render

import org.junit.Test

class BrushStudioTest {
    @Test
    fun searchAndCategoriesUseOriginalDistinctPresets() = BrushStudioChecks.originalPresetsAreDistinctAndSearchable()

    @Test
    fun previewsAreRealDeterministicAndDifferent() = BrushStudioChecks.presetsRenderRealDistinctDeterministicMarks()

    @Test
    fun numericEntrySupportsPercentAndDecimalValues() = BrushStudioChecks.exactValuesSupportPercentAndDecimalSeparators()

    @Test
    fun invalidNumericEntryIsRejected() = BrushStudioChecks.exactValuesRejectInvalidInputRatherThanClamping()

    @Test
    fun changingParametersRerendersWithoutEditingTheSourcePath() = BrushStudioChecks.practiceRerendersTheSamePathWithoutMutatingIt()

    @Test
    fun drawingPadMatchesTheProductionRenderer() = BrushStudioChecks.practiceMatchesTheProductionRenderer()

    @Test
    fun drawingPadCapsWorkAndClearRestoresBlankPaper() = BrushStudioChecks.practiceIsBoundedAndCanBeCleared()
}
