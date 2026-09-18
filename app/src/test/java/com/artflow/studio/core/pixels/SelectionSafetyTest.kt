package com.artflow.studio.core.pixels

import org.junit.Test

/** The offline probe and the normal Gradle/JUnit task execute the same regression checks. */
class SelectionSafetyTest {
    @Test fun solidAndBranchingRegions() {
        SelectionKernelChecks.solidAndBranchingRegions()
    }

    @Test fun randomizedColourConnectivity() {
        SelectionKernelChecks.randomizedColourConnectivity()
    }

    @Test fun colourCoverageAndLimits() {
        SelectionKernelChecks.colourCoverageAndLimits()
    }

    @Test fun randomizedMorphology() {
        SelectionKernelChecks.randomizedMorphology()
    }

    @Test fun randomizedFeathering() {
        SelectionKernelChecks.randomizedFeathering()
    }

    @Test fun extremeAndDegenerateGeometry() {
        SelectionKernelChecks.extremeAndDegenerateGeometry()
    }

    @Test fun randomizedEllipseCoverage() {
        SelectionKernelChecks.randomizedEllipseCoverage()
    }

    @Test fun randomizedStrokeGeometry() {
        SelectionKernelChecks.randomizedStrokeGeometry()
    }

    @Test fun randomizedPolygonCoverage() {
        SelectionKernelChecks.randomizedPolygonCoverage()
    }

    @Test fun cancellationAndInvalidInput() {
        SelectionKernelChecks.cancellationAndInvalidInput()
    }

    @Test fun radiusIndependentWork() {
        SelectionKernelChecks.radiusIndependentWork()
    }
}
