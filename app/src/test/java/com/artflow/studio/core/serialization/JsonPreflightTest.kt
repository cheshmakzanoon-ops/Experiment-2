package com.artflow.studio.core.serialization

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonPreflightTest {
    private val limits =
        object : JsonReadLimits {
            override val message = "JSON admission refused"
        }

    @Test
    fun escapedQuotesAndDelimitersRemainStringData() {
        check("""{"text":"[{}],\\\"", "other":"\u005b\u007b\u0022"}""")
    }

    @Test
    fun escapedDuplicateKeysCannotHideRepeatedDeclarations() {
        assertThrows(JsonLimitException::class.java) { check("""{"tiles":{},"ti\u006ces":{}}""") }
        assertThrows(JsonLimitException::class.java) { check("""{"tiles":{"custom-a":"", "custom-\u0061":""}}""") }
    }

    @Test
    fun syntaxErrorsCannotBypassTheContainerCount() {
        listOf("[0,]", "{\"a\":0,}", "[00]", "[1e+]", "[truefalse]", "{\"a\":\"\\x\"}").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { check(invalid) }
        }
    }

    @Test
    fun unicodeEscapesAcceptOnlyAsciiHexadecimalDigits() {
        for (encoded in listOf("\"\\u１２３４\"", "\"\\u٠٠٠١\"", "\"\\uＦＦＦＦ\"", "\"\\uG000\"")) {
            assertThrows(IllegalArgumentException::class.java) { check(encoded) }
        }
        for (encoded in listOf("\"\\u0000\"", "\"\\uAbCd\"", "\"\\uFFFF\"", "\"\\ud800\"", "\"\\uDC00\"")) {
            check(encoded)
            Json.parseToJsonElement(encoded)
        }
    }

    @Test
    fun nestedCollectionsAreRefusedBeforeRecursiveDeserialization() {
        check("[".repeat(16) + "0" + "]".repeat(16))
        assertThrows(JsonLimitException::class.java) { check("[".repeat(17) + "0" + "]".repeat(17)) }
    }

    @Test
    fun excessiveNumberAndDecodedKeyLengthsAreBounded() {
        assertThrows(JsonLimitException::class.java) { check("[" + "1".repeat(129) + "]") }
        assertThrows(JsonLimitException::class.java) { check("{\"" + "\\u0061".repeat(129) + "\":0}") }
    }

    @Test
    fun callerPoliciesCountObjectAndArrayEntriesBeforeTheirValues() {
        val narrow =
            object : JsonReadLimits {
                override val message = "Collection limit"

                override fun objectEntries(property: String?) = 1

                override fun arrayEntries(property: String?) = 2
            }
        JsonReadBudget().check("""{"items":[0,1]}""", narrow)
        assertThrows(JsonLimitException::class.java) { JsonReadBudget().check("""{"items":[0,1,2]}""", narrow) }
        assertThrows(JsonLimitException::class.java) { JsonReadBudget().check("""{"a":0,"b":{}}""", narrow) }
    }

    @Test
    fun cumulativeWorkRemainsChargedAcrossMultipleDocuments() {
        val budget = JsonReadBudget(1024)
        budget.check("[0,1]", limits)
        budget.check("[0,1]", limits)
        assertThrows(JsonLimitException::class.java) { budget.check("[0,1]", limits) }
    }

    @Test
    fun genericScanningKeepsItsOriginalTokenBoundary() {
        val defaults =
            object : JsonReadLimits {
                override val message = "Default token ceiling"

                override fun arrayEntries(property: String?) = 524_288
            }
        // One array token plus 524,287 scalar tokens reaches the default ceiling exactly.
        val boundary = "[" + "0,".repeat(524_286) + "0]"
        JsonReadBudget(72L * 1024 * 1024).check(boundary, defaults)
        val failure =
            assertThrows(JsonLimitException::class.java) {
                JsonReadBudget(72L * 1024 * 1024).check(boundary.dropLast(1) + ",0]", defaults)
            }
        assertEquals(defaults.message, failure.message)
    }

    @Test
    fun boundedOutputPreservesExactBoundaryTextAndRejectsLargerText() {
        val expected = "\"" + "a".repeat(62) + "\""
        assertEquals(expected, Json.encodeBounded(String.serializer(), "a".repeat(62), 64, JsonReadBudget()))
        assertThrows(JsonLimitException::class.java) {
            Json.encodeBounded(String.serializer(), "a".repeat(63), 64, JsonReadBudget())
        }
        assertThrows(JsonLimitException::class.java) {
            Json.encodeBounded(String.serializer(), "筆".repeat(128), 64, JsonReadBudget())
        }
    }

    @Test
    fun boundedOutputChecksItsWorkspaceBeforeRunningTheSerializer() {
        assertThrows(JsonLimitException::class.java) {
            Json.encodeBounded(String.serializer(), "small", 64, JsonReadBudget(1024))
        }
    }

    private fun check(text: String) = JsonReadBudget().check(text, limits)
}
