package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.serialization.JsonLimitException
import com.artflow.studio.core.serialization.JsonReadBudget
import com.artflow.studio.domain.model.brush.BrushLibraryCodec
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.DualBrush
import com.artflow.studio.domain.model.brush.PressureMapping
import com.artflow.studio.domain.model.brush.SavedBrush
import com.artflow.studio.domain.model.brush.SourceBrushScalar
import com.artflow.studio.domain.model.brush.SourceBrushStructuredField
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BrushJsonAdmissionTest {
    @Test
    fun fullSupportedLibraryPreservesDefaultsAndUnicodeThroughBothCodecs() {
        val brushes = List(BrushLibraryCodec.MAX_BRUSHES) { SavedBrush("brush-$it", "筆 $it", BrushParams()) }
        assertEquals(brushes, BrushLibraryCodec.decode(BrushLibraryCodec.encode(brushes)))
        assertEquals(brushes, BrushFiles.decode(BrushFiles.encode(brushes)).first)
    }

    @Test
    fun maximumSourceCollectionsAndDualParametersKeepTheirExactValues() {
        val parameters = maximalSourceParameters()
        val brush = SavedBrush("source", "Source metadata", parameters.copy(dual = DualBrush(parameters)))
        assertEquals(listOf(brush), BrushFiles.decode(BrushFiles.encode(listOf(brush))).first)
    }

    @Test
    fun tooManyBrushesAreRejectedBeforeConstructingTheFirstIncompleteBrush() {
        val encoded = """{"version":1,"brushes":[${List(129) { "{}" }.joinToString()}]}"""
        assertThrows(JsonLimitException::class.java) { BrushLibraryCodec.decode(encoded) }
    }

    @Test
    fun excessiveSourceCollectionsAreRejectedBeforeTheirIncompleteFields() {
        val collections =
            mapOf(
                "importedSourceScalars" to SourceBrushScalar.MAX_FIELDS,
                "importedSourceStructures" to SourceBrushStructuredField.MAX_OBJECTS,
                "importedAuxiliaryCurves" to 35,
            )
        collections.forEach { (property, limit) ->
            val entries = (0..limit).joinToString { "\"field$it\":{}" }
            val encoded = """{"version":1,"brushes":[{"id":"x","name":"Ink","parameters":{"$property":{$entries}}}]}"""
            assertThrows(JsonLimitException::class.java) { BrushLibraryCodec.decode(encoded) }
        }
        val points = List(PressureMapping.MAX_POINTS + 1) { "{}" }.joinToString()
        val encoded = """{"version":1,"brushes":[{"parameters":{"importedPressure":{"overall":{"points":[$points]}}}}]}"""
        assertThrows(JsonLimitException::class.java) { BrushLibraryCodec.decode(encoded) }
    }

    @Test
    fun deeplyNestedDualBrushesAreRefusedBeforeConstructingDefaultParameters() {
        val nested = """{"dual":{"params":""".repeat(40) + "{}" + "}}".repeat(40)
        val encoded = """{"version":1,"brushes":[{"id":"x","name":"Ink","parameters":$nested}]}"""
        assertThrows(JsonLimitException::class.java) { BrushLibraryCodec.decode(encoded) }
    }

    @Test
    fun excessiveUnusedTileDeclarationsAreRejectedBeforeTheInvalidLibrary() {
        val entries = (0..512).joinToString { "\"custom-${it.toString(16)}\":\"\"" }
        val encoded = """{"format":"artflow-brush-1","library":"not JSON","ti\u006ces":{$entries}}"""
        assertThrows(JsonLimitException::class.java) { BrushFiles.decode(encoded) }
    }

    @Test
    fun unusedTileDeclarationsDoNotDecodeOrConsumeRetainedTileCapacity() {
        val brushes = listOf(SavedBrush("plain", "Plain", BrushParams()))
        val tiles = (0 until 512).associate { "custom-${it.toString(16)}" to "" }
        var decoded = 0
        val result =
            BrushFiles.decode(file(brushes, tiles), 512 * 1024L) {
                decoded++
                error("Unused tiles must not be decoded")
            }
        assertEquals(0, decoded)
        assertEquals(brushes, result.first)
        assertTrue(result.second.isEmpty())
    }

    @Test
    fun escapedDuplicateTextureIdentitiesCannotBeOverwrittenByTheSerializer() {
        val encoded = """{"format":"artflow-brush-1","library":"","tiles":{"custom-a":"","custom-\u0061":""}}"""
        assertThrows(JsonLimitException::class.java) { BrushFiles.decode(encoded) }
        assertThrows(JsonLimitException::class.java) { BrushLibraryCodec.decode("""{"version":1,"brushes":[],"brush\u0065s":[]}""") }
    }

    @Test
    fun oversizedAndNoncanonicalTextureStringsNeverReachTheBase64Decoder() {
        val valid = Base64.getEncoder().encodeToString(ByteArray(TILE_BYTES))
        val invalid =
            listOf(
                valid + "A",
                valid.dropLast(2),
                "?" + valid.drop(1),
                valid.dropLast(3) + "B==",
            )
        var decoded = 0
        invalid.forEach { encoded ->
            assertThrows(IllegalArgumentException::class.java) {
                BrushFiles.decode(file(brushesWithImages(listOf("custom-a")), mapOf("custom-a" to encoded)), 8 * MIB) {
                    decoded++
                    Base64.getDecoder().decode(it)
                }
            }
        }
        assertEquals(0, decoded)
    }

    @Test
    fun allReferencedTextureEncodingsAreValidatedBeforeTheFirstDecode() {
        val valid = Base64.getEncoder().encodeToString(ByteArray(TILE_BYTES))
        val source = file(brushesWithImages(listOf("custom-a", "custom-b")), mapOf("custom-a" to valid, "custom-b" to "bad"))
        var decoded = 0
        assertThrows(IllegalArgumentException::class.java) {
            BrushFiles.decode(source, 8 * MIB) {
                decoded++
                Base64.getDecoder().decode(it)
            }
        }
        assertEquals(0, decoded)
    }

    @Test
    fun allReferencedTextureIdentitiesAreValidatedBeforeTheFirstDecode() {
        val valid = Base64.getEncoder().encodeToString(ByteArray(TILE_BYTES))
        val source = file(brushesWithImages(listOf("custom-a", "paper")), mapOf("custom-a" to valid, "paper" to valid))
        var decoded = 0
        assertThrows(IllegalArgumentException::class.java) {
            BrushFiles.decode(source, 8 * MIB) {
                decoded++
                Base64.getDecoder().decode(it)
            }
        }
        assertEquals(0, decoded)
    }

    @Test
    fun cumulativeTextureAdmissionHappensBeforeAnyCoverageArrayIsDecoded() {
        val encoded = Base64.getEncoder().encodeToString(ByteArray(TILE_BYTES))
        val ids = (1..5).map { "custom-${it.toString(16)}" }
        var decoded = 0
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                BrushFiles.decode(file(brushesWithImages(ids), ids.associateWith { encoded }), MIB) {
                    decoded++
                    Base64.getDecoder().decode(it)
                }
            }
        assertTrue(failure.message.orEmpty().contains("textures need too much memory"))
        assertEquals(0, decoded)
    }

    @Test
    fun canonicalReferencedTilePreservesEveryCoverageByte() {
        val bytes = ByteArray(TILE_BYTES) { (it % 251).toByte() }
        val brushes = brushesWithImages(listOf("custom-a"))
        val result = BrushFiles.decode(file(brushes, mapOf("custom-a" to Base64.getEncoder().encodeToString(bytes))))
        assertEquals(brushes, result.first)
        assertArrayEquals(bytes, result.second.getValue("custom-a").values)
    }

    @Test
    fun nestedLibraryDoesNotResetTheOuterJsonAllowance() {
        val brushes = listOf(SavedBrush("one", "Ink", BrushParams()))
        val library = BrushLibraryCodec.encode(brushes)
        val minimum = minimumLibraryAllowance(library)
        val source = file(brushes, emptyMap())
        val allowance = minimum + source.length
        assertEquals(brushes, BrushLibraryCodec.decode(library, JsonReadBudget(allowance)))
        assertThrows(JsonLimitException::class.java) { BrushFiles.decode(source, allowance) }
    }

    @Test
    fun encoderRefusesAnOversizedTextureBatchWithoutChangingItsCachedAssets() {
        val tile = CustomGrains.Tile(CustomGrains.TILE, ByteArray(TILE_BYTES) { 67 })
        val ids = (1..48).map { "custom-f13${it.toString(16).padStart(3, '0')}" }
        try {
            ids.forEach { CustomGrains.register(it, tile) }
            assertThrows(IllegalArgumentException::class.java) { BrushFiles.encode(brushesWithImages(ids)) }
            ids.forEach { assertTrue(CustomGrains.get(it) === tile) }
        } finally {
            ids.forEach { CustomGrains.removeIfSame(it, tile) }
        }
    }

    @Test
    fun encoderChecksUtf8FileBytesAsWellAsLibraryCharacters() {
        val parameters =
            BrushParams(
                importedSourceScalars =
                    (0 until SourceBrushScalar.MAX_FIELDS).associate {
                        "field$it" to SourceBrushScalar(SourceBrushScalar.Type.TEXT, "筆".repeat(SourceBrushScalar.MAX_VALUE_LENGTH))
                    },
            )
        val brushes = List(21) { SavedBrush("unicode-$it", "Unicode", parameters) }
        val library = BrushLibraryCodec.encode(brushes)
        assertTrue(library.length < BrushLibraryCodec.MAX_CHARACTERS)
        assertTrue(library.toByteArray().size > BrushFiles.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) { BrushFiles.encode(brushes) }
    }

    private fun file(
        brushes: List<SavedBrush>,
        tiles: Map<String, String>,
    ): String =
        buildJsonObject {
            put("format", "artflow-brush-1")
            put("library", BrushLibraryCodec.encode(brushes))
            put("tiles", JsonObject(tiles.mapValues { JsonPrimitive(it.value) }))
        }.toString()

    private fun brushesWithImages(ids: List<String>): List<SavedBrush> =
        ids.mapIndexed { index, id -> SavedBrush("brush-$index", "Texture $index", BrushParams(textureId = id)) }

    private fun minimumLibraryAllowance(encoded: String): Long {
        var low = 1L
        var high = MIB
        while (low < high) {
            val middle = (low + high) / 2
            val accepted = runCatching { BrushLibraryCodec.decode(encoded, JsonReadBudget(middle)) }.isSuccess
            if (accepted) high = middle else low = middle + 1
        }
        return low
    }

    private fun maximalSourceParameters(): BrushParams {
        val scalar = SourceBrushScalar(SourceBrushScalar.Type.TEXT, "[{}]\\\"".repeat(40).take(SourceBrushScalar.MAX_VALUE_LENGTH))
        val numeric = SourceBrushScalar(SourceBrushScalar.Type.REAL, "0.25")
        val points = List(PressureMapping.MAX_POINTS) { PressureMapping.Point(it / 15f, it / 15f) }
        val structures =
            SourceBrushStructuredField.ALLOWED_NAMES.associateWith { name ->
                if (name in SourceBrushStructuredField.ARRAY_NAMES) {
                    SourceBrushStructuredField("NSArray", SourceBrushStructuredField.Kind.ARRAY, items = List(16) { numeric })
                } else {
                    val className = if (name == "creationDate") "NSDate" else "ValkyrieBrushRollParameters"
                    SourceBrushStructuredField(
                        className,
                        SourceBrushStructuredField.Kind.FIELDS,
                        fields = (0 until SourceBrushStructuredField.MAX_FIELDS).associate { "field$it" to scalar },
                    )
                }
            }
        return BrushParams(
            importedSourceScalars = (0 until SourceBrushScalar.MAX_FIELDS).associate { "field$it" to scalar },
            importedAuxiliaryCurves = (0 until 35).associate { "dynamicsPressureField$it" to PressureMapping(points) },
            importedSourceStructures = structures,
        )
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val TILE_BYTES = CustomGrains.TILE * CustomGrains.TILE
    }
}
