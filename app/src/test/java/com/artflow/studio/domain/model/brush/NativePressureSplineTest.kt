package com.artflow.studio.domain.model.brush

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class NativePressureSplineTest {
    @Test fun everyDerivedFixtureMatchesItsEntireTableAndLookupBits() {
        val document = fixtures()
        val provenance = document.getValue("provenance").jsonObject
        assertFalse(provenance.getValue("reference_code_executed").jsonPrimitive.boolean)
        assertEquals(
            "76d3ff1249a987b8f03ac3ecc64c54e48f618bac467e9fbb1f238b91fab1487b",
            provenance.getValue("binary_sha256").jsonPrimitive.content,
        )
        val cases = document.getValue("fixtures").jsonArray
        assertEquals(13, cases.size)
        for (fixture in cases.map { it.jsonObject }) {
            val name = fixture.getValue("name").jsonPrimitive.content
            val points =
                fixture.getValue("points").takeUnless { it == JsonNull }?.jsonArray?.map { pair ->
                    val values = pair.jsonArray
                    PressureMapping.Point(values[0].jsonPrimitive.float, values[1].jsonPrimitive.float)
                }
            val table = NativePressureSpline.prepare(points)
            assertEquals(name, fixture.getValue("resolution").jsonPrimitive.int, table.size)
            assertEquals(name, fixture.getValue("table_sha256_le_float32").jsonPrimitive.content, tableHash(table))
            for (sample in fixture.getValue("table_samples").jsonArray.map { it.jsonObject }) {
                assertBits(name, sample, table[sample.getValue("index").jsonPrimitive.int])
            }
            for (sample in fixture.getValue("lookup_samples").jsonArray.map { it.jsonObject }) {
                val pressure = sample.getValue("pressure").jsonPrimitive.float
                assertBits(name, sample, NativePressureSpline.lookup(table, pressure))
            }
        }
    }

    @Test fun explicitNativeModeChangesTheShapeWithoutChangingLegacyGraphs() {
        val points = listOf(PressureMapping.Point(0f, 0f), PressureMapping.Point(0.5f, 0.25f), PressureMapping.Point(1f, 1f))
        val legacy = PressureMapping(points)
        val native = legacy.copy(interpolation = PressureMapping.Interpolation.NATIVE_SPLINE_1024)
        assertEquals(0.125f, legacy.map(0.25f), 0f)
        assertEquals(Float.fromBits(0x3d9ff014), native.map(0.25f), 0f)
        repeat(10) { assertEquals(Float.fromBits(0x3d9ff014), native.map(0.25f), 0f) }
        assertEquals(legacy, native.copy(interpolation = PressureMapping.Interpolation.LINEAR))
        assertEquals(0.125f, native.copy(interpolation = PressureMapping.Interpolation.LINEAR).map(0.25f), 0f)
    }

    @Test fun nearCoincidentValidPointsCannotPoisonTheRendererWithNan() {
        val curves =
            listOf(
                listOf(0f to 0f, Float.MIN_VALUE to 1f, 1f to 0f),
                listOf(0f to 1f, Float.MIN_VALUE to 0f, Float.fromBits(2) to 1f, 1f to 0.5f),
                listOf(0f to 0f, 0.5f to 0.5f, Float.fromBits(0x3f000001) to 1f, 1f to 0f),
                listOf(0f to 0f, Float.fromBits(0x3f7fffff) to 1f, 1f to 0f),
            )
        for (pairs in curves) {
            val points = pairs.map { PressureMapping.Point(it.first, it.second) }
            val mapping = PressureMapping(points, PressureMapping.Interpolation.NATIVE_SPLINE_1024)
            assertTrue(NativePressureSpline.prepare(points).all { it.isFinite() && it in 0f..1f })
            for (sample in 0..1024) assertTrue(mapping.map(sample / 1024f) in 0f..1f)
        }
    }

    @Test fun strictValidationAndNonfinitePressureRejectionApplyToBothModes() {
        for (mode in PressureMapping.Interpolation.entries) {
            val valid = PressureMapping(listOf(PressureMapping.Point(0f, 0f), PressureMapping.Point(1f, 1f)), mode)
            for (pressure in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                assertThrows(IllegalArgumentException::class.java) { valid.map(pressure) }
            }
            for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.1f, 1.1f)) {
                assertThrows(IllegalArgumentException::class.java) {
                    PressureMapping(listOf(PressureMapping.Point(bad, 0f), PressureMapping.Point(1f, 1f)), mode)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                PressureMapping(listOf(PressureMapping.Point(0.5f, 0f), PressureMapping.Point(0.5f, 1f)), mode)
            }
        }
        val table = NativePressureSpline.prepare(null)
        assertThrows(IllegalArgumentException::class.java) { NativePressureSpline.lookup(table, Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { NativePressureSpline.lookup(FloatArray(1), 0f) }
    }

    private fun fixtures(): JsonObject {
        val stream = requireNotNull(javaClass.getResourceAsStream("/brush/native-spline-fixtures.json"))
        return stream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    }

    private fun assertBits(
        name: String,
        sample: JsonObject,
        actual: Float,
    ) {
        val expected =
            sample
                .getValue("binary32")
                .jsonPrimitive
                .content
                .toLong(16)
                .toInt()
        assertEquals("$name $sample", expected, actual.toRawBits())
    }

    private fun tableHash(table: FloatArray): String {
        val bytes = ByteBuffer.allocate(table.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        table.forEach(bytes::putFloat)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
