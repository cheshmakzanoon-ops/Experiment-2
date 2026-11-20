package com.artflow.studio.core.serialization

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class BoundedJsonOutputTest {
    @Test
    fun acceptedCodeUnitsSurviveAnActualStrictUtf8WireRoundTrip() {
        val originals =
            listOf(
                "before\uD83Dafter",
                "before\uDE00after",
                "before\uD83D\uDE00after",
                "\uD800\uD800\uDC00\uDC00\uDFFF\uDBFF",
                "line\nquote\"slash\\ literal \\ud800\t\u0000",
                ("a".repeat(79) + "\uD83D\uDE00").take(80),
            )
        originals.forEach { original ->
            val encoded = Json.encodeBounded(String.serializer(), original, 1024, JsonReadBudget())
            val wire = encoded.encodeToByteArray(throwOnInvalidSequence = true)
            assertEquals(original, Json.decodeFromString(String.serializer(), wire.decodeToString(throwOnInvalidSequence = true)))
        }
    }

    @Test
    fun unicodeByteLengthsDoNotReduceTheSupportedCharacterBoundary() {
        for (original in listOf("é".repeat(62), "筆".repeat(62), "\uD83D\uDE00".repeat(31))) {
            val encoded = Json.encodeBounded(String.serializer(), original, 64, JsonReadBudget())
            assertEquals(64, encoded.length)
            assertEquals(original, Json.decodeFromString(String.serializer(), encoded))
        }
    }

    @Test
    fun theCountRemainsExactAcrossStreamBufferBoundaries() {
        val original = "aé筆\uD83D\uDE00".repeat(4000)
        val encoded = Json.encodeBounded(String.serializer(), original, original.length + 2, JsonReadBudget())
        assertEquals(original, Json.decodeFromString(String.serializer(), encoded))
    }

    @Test
    fun escapedCodeUnitsMustFitBothTheCharacterAndMemoryAllowance() {
        val original = "\uD800"
        val expected = "\"\\ud800\""
        assertEquals(expected, Json.encodeBounded(String.serializer(), original, 8, JsonReadBudget(WRITER_BYTES + 8 * 12)))
        assertThrows(JsonLimitException::class.java) {
            Json.encodeBounded(String.serializer(), original, 7, JsonReadBudget())
        }
        assertThrows(JsonLimitException::class.java) {
            Json.encodeBounded(String.serializer(), original, 8, JsonReadBudget(WRITER_BYTES + 8 * 12 - 1))
        }
    }

    @Test
    fun previouslyReservedStorageCannotBeReusedByTheOutputBuffer() {
        val budget = JsonReadBudget(WRITER_BYTES + 1024 + 8 * 12)
        budget.reserve(1025)
        assertThrows(JsonLimitException::class.java) { Json.encodeBounded(String.serializer(), "\uD800", 8, budget) }
    }

    @Test
    fun aCountingRefusalNeverStartsTheStringProducingSerializerPass() {
        val serializer = CountingSerializer()
        assertThrows(JsonLimitException::class.java) {
            Json.encodeBounded(serializer, "a".repeat(100_000), 64, JsonReadBudget())
        }
        assertEquals(1, serializer.calls)
    }

    @Test
    fun anInsufficientWriterAllowanceNeverInvokesTheSerializer() {
        val serializer = CountingSerializer()
        assertThrows(JsonLimitException::class.java) { Json.encodeBounded(serializer, "small", 64, JsonReadBudget(1024)) }
        assertEquals(0, serializer.calls)
    }

    @Test
    fun alreadySafeJsonNeedsNoReplacementString() {
        val encoded = Json.encodeToString(String.serializer(), "café 筆 \uD83D\uDE00 \\ud800")
        assertSame(encoded, escapeJsonSurrogates(encoded, encoded.length))
        assertThrows(JsonLimitException::class.java) { escapeJsonSurrogates(encoded, encoded.length - 1) }
    }

    private class CountingSerializer : SerializationStrategy<String> {
        override val descriptor = String.serializer().descriptor
        var calls = 0

        override fun serialize(
            encoder: Encoder,
            value: String,
        ) {
            calls++
            encoder.encodeString(value)
        }
    }

    private companion object {
        const val WRITER_BYTES = 64L * 1024
    }
}
