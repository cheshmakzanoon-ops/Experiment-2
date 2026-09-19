package com.artflow.studio.core.color

import org.junit.Test

class PaletteCodecTest {
    @Test
    fun headerDoesNotOverwriteTheFirstBlock() = PaletteCodecChecks.headerDoesNotOverwriteTheFirstBlock()

    @Test
    fun everyByteChannelRoundTripsExactly() = PaletteCodecChecks.everyByteChannelRoundTripsExactly()

    @Test
    fun unicodeAndLongNamesRoundTripWithoutFixedBufferOverflow() =
        PaletteCodecChecks.unicodeAndLongNamesRoundTripWithoutFixedBufferOverflow()

    @Test
    fun overlongNamesFailWithAnExplicitArgumentError() = PaletteCodecChecks.overlongNamesFailWithAnExplicitArgumentError()

    @Test
    fun emptyPalettesHaveAValidHeaderWithoutInventedSwatches() = PaletteCodecChecks.emptyPalettesHaveAValidHeaderWithoutInventedSwatches()

    @Test
    fun aseExplicitlyCarriesRgbWithoutAlpha() = PaletteCodecChecks.aseExplicitlyCarriesRgbWithoutAlpha()

    @Test
    fun independentRgbGrayAndCmykFixturesDecode() = PaletteCodecChecks.independentRgbGrayAndCmykFixturesDecode()

    @Test
    fun labLightnessUsesNormalizedAseUnits() = PaletteCodecChecks.labLightnessUsesNormalizedAseUnits()

    @Test
    fun everyTruncatedPrefixIsRejectedInsteadOfImportedPartially() =
        PaletteCodecChecks.everyTruncatedPrefixIsRejectedInsteadOfImportedPartially()

    @Test
    fun invalidCountsVersionsAndLengthsFailClosed() = PaletteCodecChecks.invalidCountsVersionsAndLengthsFailClosed()

    @Test
    fun shortColorRecordsCannotBorrowBytesFromTheirNeighbors() = PaletteCodecChecks.shortColorRecordsCannotBorrowBytesFromTheirNeighbors()

    @Test
    fun invalidUtf16NamesCannotCrossBlockBoundaries() = PaletteCodecChecks.invalidUtf16NamesCannotCrossBlockBoundaries()

    @Test
    fun nonFiniteChannelsNeverBecomeInventedColorsOrExceptions() =
        PaletteCodecChecks.nonFiniteChannelsNeverBecomeInventedColorsOrExceptions()

    @Test
    fun malformedLastRecordDoesNotReturnTheValidFirstColor() = PaletteCodecChecks.malformedLastRecordDoesNotReturnTheValidFirstColor()

    @Test
    fun blockLocalMetadataAndUnknownBlocksDoNotMisalignLaterColors() =
        PaletteCodecChecks.blockLocalMetadataAndUnknownBlocksDoNotMisalignLaterColors()

    @Test
    fun groupStructureAndEmptyGroupNamesAreHandledSafely() = PaletteCodecChecks.groupStructureAndEmptyGroupNamesAreHandledSafely()

    @Test
    fun gplIsIndependentOfTheDevicesNumberingLocale() = PaletteCodecChecks.gplIsIndependentOfTheDevicesNumberingLocale()

    @Test
    fun deterministicMalformedInputsNeverEscapeTheNullableParser() =
        PaletteCodecChecks.deterministicMalformedInputsNeverEscapeTheNullableParser()
}
