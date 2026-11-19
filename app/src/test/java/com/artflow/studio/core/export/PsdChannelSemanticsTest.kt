package com.artflow.studio.core.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream

class PsdChannelSemanticsTest {
    private val rgb =
        listOf(
            intArrayOf(136, 101, 255, 191),
            intArrayOf(153, 67, 255, 223),
            intArrayOf(170, 33, 255, 255),
        )
    private val gray = listOf(rgb.first())
    private val cmyk =
        listOf(
            intArrayOf(255, 0, 255, 255),
            intArrayOf(255, 255, 255, 128),
            intArrayOf(255, 255, 255, 0),
            intArrayOf(255, 255, 0, 128),
        )
    private val alpha = intArrayOf(128, 255, 0, 64)
    private val auxiliary = listOf(intArrayOf(0, 17, 255, 3), intArrayOf(255, 255, 255, 255))

    @Test
    fun rgbTransparencyUsesTheFirstExtraPlaneAndNeverASavedSelection() {
        val expected = intArrayOf(0x80123456.toInt(), 0xFF654321.toInt(), 0x00FFFFFF, 0x400080FF)
        forEachEncoding { depth, compression, psb ->
            assertComposite(expected, psd(3, rgb + listOf(alpha) + auxiliary, depth, compression, psb, -1))
        }
    }

    @Test
    fun grayscaleTransparencyUsesTheFirstExtraPlaneAndRemovesThePreviewMatte() {
        val expected = intArrayOf(0x80121212.toInt(), 0xFF656565.toInt(), 0x00FFFFFF, 0x40000000)
        forEachEncoding { depth, compression, psb ->
            assertComposite(expected, psd(1, gray + listOf(alpha) + auxiliary, depth, compression, psb, -1))
        }
    }

    @Test
    fun cmykTransparencySurvivesAdditionalChannelsAndUsesInvertedInkValues() {
        val expected = intArrayOf(0x80FFFFFF.toInt(), 0xFF00FFFF.toInt(), 0x00000000, 0x40804000)
        forEachEncoding { depth, compression, psb ->
            assertComposite(expected, psd(4, cmyk + listOf(alpha) + auxiliary, depth, compression, psb, -1))
        }
    }

    @Test
    fun positiveZeroAndAbsentLayerCountsDoNotTurnAuxiliaryChannelsIntoTransparency() {
        val examples =
            listOf(
                Triple(3, rgb, intArrayOf(0xFF8899AA.toInt(), 0xFF654321.toInt(), -1, 0xFFBFDFFF.toInt())),
                Triple(1, gray, intArrayOf(0xFF888888.toInt(), 0xFF656565.toInt(), -1, 0xFFBFBFBF.toInt())),
                Triple(4, cmyk, intArrayOf(-1, 0xFF00FFFF.toInt(), 0xFF000000.toInt(), 0xFF804000.toInt())),
            )
        for ((mode, planes, expected) in examples) {
            for (count in listOf(1, 0, null)) {
                for (compression in 0..3) {
                    assertComposite(expected, psd(mode, planes + auxiliary, compression = compression, layerCount = count))
                }
            }
        }
    }

    @Test
    fun cmykRasterLayersKeepTheirOwnAlphaAndConvertWhiteCyanBlackAndMixedInks() {
        for (depth in listOf(8, 16)) {
            val channels = cmyk.mapIndexed { index, plane -> index to plane } + listOf(-1 to alpha)
            val document = requireNotNull(PsdCodec.read(psd(4, cmyk, depth = depth, layerCount = 1, layerChannels = channels)))
            assertArrayEquals(
                intArrayOf(0x80FFFFFF.toInt(), 0xFF00FFFF.toInt(), 0x00000000, 0x40804000),
                document.layers
                    .single()
                    .pixels.pixels,
            )
            assertEquals(255, requireNotNull(document.composite).pixels[0] ushr 24)
        }
    }

    @Test
    fun explicitGlobalTransparencyMarkersSupportLayerlessPsdAndPsb() {
        val expected = intArrayOf(0x80123456.toInt(), 0xFF654321.toInt(), 0x00FFFFFF, 0x400080FF)
        forEachEncoding { depth, compression, psb ->
            val marker = taggedBlock(if (depth == 16) "Mt16" else "Mtrn", ByteArray(0), psb)
            for (globalMaskField in listOf(false, true)) {
                val bytes =
                    psd(
                        3,
                        rgb + listOf(alpha) + auxiliary,
                        depth,
                        compression,
                        psb,
                        layerCount = null,
                        tags = marker,
                        globalMaskField = globalMaskField,
                    )
                assertComposite(expected, bytes)
            }
        }
    }

    @Test
    fun alternateSixteenBitLayerCountIdentifiesTheFirstMergedAlpha() {
        val expected = intArrayOf(0x80123456.toInt(), 0xFF654321.toInt(), 0x00FFFFFF, 0x400080FF)
        for (psb in listOf(false, true)) {
            val layerInfo = layerInfo(-1, emptyList(), 16, psb)
            val tags = taggedBlock("Lr16", layerInfo, psb)
            assertComposite(expected, psd(3, rgb + listOf(alpha) + auxiliary, depth = 16, psb = psb, tags = tags))
        }
    }

    @Test
    fun unknownGlobalBlocksAndBothPaddingConventionsDoNotHideTransparencyMarkers() {
        for (padding in listOf(2, 4)) {
            val tags = taggedBlock("test", byteArrayOf(42), padding = padding) + taggedBlock("Mtrn", ByteArray(0))
            val composite = requireNotNull(PsdCodec.read(psd(3, rgb + listOf(alpha), tags = tags))?.composite)
            assertEquals(0x80123456.toInt(), composite.pixels[0])
        }
    }

    @Test
    fun insufficientBaseChannelsAndMissingDeclaredTransparencyAreRejected() {
        for ((mode, planes) in listOf(3 to rgb, 4 to cmyk)) {
            val missingColor = psd(mode, planes.dropLast(1))
            assertNull(PsdCodec.inspect(missingColor))
            assertNull(PsdCodec.read(missingColor))
        }
        for ((mode, planes) in listOf(1 to gray, 3 to rgb, 4 to cmyk)) {
            assertNull(PsdCodec.read(psd(mode, planes, layerCount = -1)))
            assertNull(PsdCodec.read(psd(mode, planes, tags = taggedBlock("Mtrn", ByteArray(0)))))
        }
    }

    @Test
    fun ignoredAuxiliaryChannelsStillRequireTheirCompleteBoundedPayload() {
        forEachEncoding { depth, compression, psb ->
            val bytes = psd(3, rgb + auxiliary, depth, compression, psb)
            assertNull(PsdCodec.read(bytes.copyOf(bytes.size - 1)))
        }
    }

    @Test
    fun malformedGlobalMetadataCannotBorrowTheCompositeOrFabricateTransparency() {
        val marker = taggedBlock("Mtrn", ByteArray(0))
        val cases =
            listOf(
                marker.copyOf(11),
                marker.copyOf().also { it[it.lastIndex] = 1 },
                taggedBlock("Mtrn", byteArrayOf(1)),
                taggedBlock("Layr", byteArrayOf(-1)),
                byteArrayOf(0, 0, 0, 0, 1),
            )
        for (tags in cases) {
            val bytes = psd(3, rgb + listOf(alpha), tags = tags)
            assertNull(PsdCodec.inspect(bytes))
            assertNull(PsdCodec.read(bytes))
        }
    }

    @Test
    fun layerlessAlphaIdentifierZeroSelectsItsExactTrailingChannelAcrossEncodings() {
        val examples =
            listOf(
                Triple(3, rgb, intArrayOf(0x80123456.toInt(), 0xFF654321.toInt(), 0x00FFFFFF, 0x400080FF)),
                Triple(1, gray, intArrayOf(0x80121212.toInt(), 0xFF656565.toInt(), 0x00FFFFFF, 0x40000000)),
                Triple(4, cmyk, intArrayOf(0x80FFFFFF.toInt(), 0xFF00FFFF.toInt(), 0x00000000, 0x40804000)),
            )
        for ((mode, planes, expected) in examples) {
            forEachEncoding { depth, compression, psb ->
                val extras = listOf(auxiliary[0], alpha, auxiliary[1])
                val bytes =
                    psd(mode, planes + extras, depth, compression, psb, resources = resource(1053, identifiers(7, 0, 8)))
                assertComposite(expected, bytes)
                val info = requireNotNull(PsdCodec.inspect(bytes))
                assertEquals(planes.size + 1, info.mergedTransparencyChannel)
                assertEquals(2, info.ignoredAuxiliaryChannels)
            }
        }
    }

    @Test
    fun resourceIdentifiersDoNotOverridePrimaryOrAlternateLayerSemantics() {
        val resource = resource(1053, identifiers(0))
        val primary = psd(3, rgb + listOf(alpha), layerCount = 1, resources = resource)
        val alternate =
            psd(3, rgb + listOf(alpha), depth = 16, tags = taggedBlock("Lr16", layerInfo(1, emptyList(), 16, false)), resources = resource)
        for (bytes in listOf(primary, alternate)) {
            assertFalse(requireNotNull(PsdCodec.inspect(bytes)).hasMergedTransparency)
            assertEquals(0xFF8899AA.toInt(), requireNotNull(PsdCodec.read(bytes)?.composite).pixels[0])
        }
        assertFalse(requireNotNull(PsdCodec.inspect(primary)).usesFlattenedAlternateLayers)
        assertTrue(requireNotNull(PsdCodec.inspect(alternate)).usesFlattenedAlternateLayers)
        val marked = psd(3, rgb + listOf(alpha) + auxiliary, layerCount = -1, resources = resource(1053, identifiers(7, 8)))
        assertEquals(0x80123456.toInt(), requireNotNull(PsdCodec.read(marked)?.composite).pixels[0])
    }

    @Test
    fun identifiersCannotTurnBaseColorOrUnmarkedSavedSelectionIntoTransparency() {
        val staleZero = psd(3, rgb, resources = resource(1053, identifiers(0)))
        val selection = psd(3, rgb + auxiliary, resources = resource(1053, identifiers(7, 8)))
        for (bytes in listOf(staleZero, selection)) {
            assertFalse(requireNotNull(PsdCodec.inspect(bytes)).hasMergedTransparency)
            assertEquals(0xFF8899AA.toInt(), requireNotNull(PsdCodec.read(bytes)?.composite).pixels[0])
        }
    }

    @Test
    fun oddAndEmptyUnknownResourcesDoNotHideResolutionProfilesOrTransparency() {
        val resolution =
            bytes {
                it.writeInt(300 shl 16)
                it.writeShort(1)
                it.write(ByteArray(10))
            }
        val resources =
            resource(2000, byteArrayOf(42), "odd") + resource(2001, ByteArray(0), "even") +
                resource(1005, resolution) + resource(1039, byteArrayOf(1, 2, 3)) + resource(1053, identifiers(0))
        val document = psd(3, rgb + listOf(alpha), resources = resources)
        val info = requireNotNull(PsdCodec.inspect(document))
        assertEquals(300, info.dpi)
        assertTrue(info.hasEmbeddedIccProfile)
        assertTrue(info.hasMergedTransparency)
        assertEquals(300, requireNotNull(PsdCodec.read(document)).dpi)
        assertEquals(0x80123456.toInt(), requireNotNull(PsdCodec.read(document)?.composite).pixels[0])
    }

    @Test
    fun partialOversizedOrAmbiguousIdentifierResourcesAreRejectedBeforeDecoding() {
        val resources =
            listOf(
                resource(1053, byteArrayOf(0, 0, 0)),
                resource(1053, identifiers(*IntArray(57) { it + 1 })),
                resource(1053, identifiers(0, 0)),
                resource(1053, identifiers(0)) + resource(1053, identifiers(7)),
            )
        for (resource in resources) {
            val bytes = psd(3, rgb + auxiliary, resources = resource)
            assertNull(PsdCodec.inspect(bytes))
            assertNull(PsdCodec.read(bytes))
        }
    }

    @Test
    fun resourceNamesPayloadsAndPaddingCannotBorrowTheLayerOrCompositeSections() {
        val alphaResource = resource(1053, identifiers(0))
        val resources =
            listOf(
                alphaResource.copyOf(11),
                alphaResource.copyOf().also { it[11] = 8 },
                alphaResource.copyOf().also { it[8] = 0x7F },
                alphaResource.copyOf().also { it[6] = 120 },
                resource(2000, byteArrayOf(42)).dropLast(1).toByteArray(),
                resource(1005, ByteArray(8)),
            )
        for (resource in resources) {
            val bytes = psd(3, rgb + listOf(alpha), resources = resource)
            assertNull(PsdCodec.inspect(bytes))
            assertNull(PsdCodec.read(bytes))
        }
    }

    @Test
    fun colorLimitationsAreMeasuredWithoutRetainingOrApplyingTheEmbeddedProfile() {
        val bytes = psd(4, cmyk + auxiliary, resources = resource(1039, ByteArray(4097) { 42 }))
        val info = requireNotNull(PsdCodec.inspect(bytes))
        assertTrue(info.isCmyk)
        assertTrue(info.hasEmbeddedIccProfile)
        assertEquals(2, info.ignoredAuxiliaryChannels)
        assertArrayEquals(
            intArrayOf(-1, 0xFF00FFFF.toInt(), 0xFF000000.toInt(), 0xFF804000.toInt()),
            requireNotNull(PsdCodec.read(bytes)?.composite).pixels,
        )
        val ordinary = requireNotNull(PsdCodec.inspect(psd(3, rgb)))
        assertFalse(ordinary.isCmyk)
        assertFalse(ordinary.hasEmbeddedIccProfile)
        assertEquals(0, ordinary.ignoredAuxiliaryChannels)
    }

    private fun identifiers(vararg ids: Int): ByteArray = bytes { out -> ids.forEach(out::writeInt) }

    private fun resource(
        id: Int,
        data: ByteArray,
        name: String = "",
    ): ByteArray =
        bytes { out ->
            out.writeBytes("8BIM")
            out.writeShort(id)
            out.writeByte(name.length)
            out.writeBytes(name)
            if ((name.length + 1) % 2 != 0) out.writeByte(0)
            out.writeInt(data.size)
            out.write(data)
            if (data.size % 2 != 0) out.writeByte(0)
        }

    private fun forEachEncoding(check: (Int, Int, Boolean) -> Unit) {
        for (depth in listOf(8, 16)) {
            for (compression in 0..3) {
                for (psb in listOf(false, true)) check(depth, compression, psb)
            }
        }
    }

    private fun assertComposite(
        expected: IntArray,
        bytes: ByteArray,
    ) {
        val document = PsdCodec.read(bytes)
        assertNotNull(document)
        assertArrayEquals(expected, requireNotNull(document?.composite).pixels)
    }

    /** Independent 4 x 1 PSD/PSB fixture, authored directly from Adobe's section layout. */
    private fun psd(
        mode: Int,
        planes: List<IntArray>,
        depth: Int = 8,
        compression: Int = 0,
        psb: Boolean = false,
        layerCount: Int? = null,
        layerChannels: List<Pair<Int, IntArray>> = emptyList(),
        tags: ByteArray = ByteArray(0),
        globalMaskField: Boolean = true,
        resources: ByteArray = ByteArray(0),
    ): ByteArray =
        bytes { out ->
            out.writeBytes("8BPS")
            out.writeShort(if (psb) 2 else 1)
            out.write(ByteArray(6))
            out.writeShort(planes.size)
            out.writeInt(1)
            out.writeInt(4)
            out.writeShort(depth)
            out.writeShort(mode)
            out.writeInt(0)
            out.writeInt(resources.size)
            out.write(resources)
            val section =
                if (layerCount != null || tags.isNotEmpty()) {
                    bytes { data ->
                        val info = layerCount?.let { layerInfo(it, layerChannels, depth, psb) } ?: ByteArray(0)
                        if (psb) data.writeLong(info.size.toLong()) else data.writeInt(info.size)
                        data.write(info)
                        if (globalMaskField) data.writeInt(0)
                        data.write(tags)
                    }
                } else {
                    ByteArray(0)
                }
            if (psb) out.writeLong(section.size.toLong()) else out.writeInt(section.size)
            out.write(section)
            out.writeShort(compression)
            out.write(mergedBody(planes, depth, compression, psb))
        }

    private fun layerInfo(
        count: Int,
        channels: List<Pair<Int, IntArray>>,
        depth: Int,
        psb: Boolean,
    ): ByteArray =
        bytes { out ->
            out.writeShort(count)
            if (count != 0) {
                out.writeInt(0)
                out.writeInt(0)
                out.writeInt(if (channels.isEmpty()) 0 else 1)
                out.writeInt(if (channels.isEmpty()) 0 else 4)
                out.writeShort(channels.size)
                for ((id, values) in channels) {
                    out.writeShort(id)
                    val size = values.size * (depth / 8) + 2
                    if (psb) out.writeLong(size.toLong()) else out.writeInt(size)
                }
                out.writeBytes("8BIMnorm")
                out.writeByte(255)
                out.write(ByteArray(3))
                out.writeInt(12)
                out.write(ByteArray(12)) // Empty mask, blend ranges and padded Pascal name.
                for ((_, values) in channels) {
                    out.writeShort(0)
                    out.write(samples(values, depth))
                }
            }
        }

    private fun taggedBlock(
        key: String,
        data: ByteArray,
        psb: Boolean = false,
        padding: Int = 4,
    ): ByteArray =
        bytes { out ->
            out.writeBytes("8BIM")
            out.writeBytes(key)
            if (psb) out.writeLong(data.size.toLong()) else out.writeInt(data.size)
            out.write(data)
            out.write(ByteArray((padding - data.size % padding) % padding))
        }

    private fun samples(
        values: IntArray,
        depth: Int,
        predict: Boolean = false,
    ): ByteArray =
        bytes { out ->
            var previous = 0
            for (value in values) {
                val sample = if (depth == 16) (value shl 8) or (255 - value) else value
                val encoded = if (predict) sample - previous else sample
                if (depth == 16) out.writeShort(encoded) else out.writeByte(encoded)
                previous = sample
            }
        }

    private fun mergedBody(
        planes: List<IntArray>,
        depth: Int,
        compression: Int,
        psb: Boolean,
    ): ByteArray {
        val raw = planes.map { samples(it, depth, predict = compression == 3) }
        if (compression == 1) {
            val rows = raw.map { byteArrayOf((it.size - 1).toByte()) + it }
            return bytes { out ->
                rows.forEach { if (psb) out.writeInt(it.size) else out.writeShort(it.size) }
                rows.forEach { out.write(it) }
            }
        }
        val allPlanes = bytes { out -> raw.forEach { out.write(it) } }
        if (compression == 0) return allPlanes
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(allPlanes) }
        return out.toByteArray()
    }

    private fun bytes(write: (DataOutputStream) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use(write)
        return out.toByteArray()
    }
}
