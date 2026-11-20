package com.artflow.studio.core.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.OutputStream

/**
 * Counts without retaining output before allocating a complete string. [value] must be an owned,
 * stable snapshot: both serializer passes must see the same data. Strings and primitive values
 * already satisfy this; callers with collections must copy them within the operation's budget.
 */
@OptIn(ExperimentalSerializationApi::class)
internal fun <T> Json.encodeBounded(
    serializer: SerializationStrategy<T>,
    value: T,
    maximumCharacters: Int,
    budget: JsonReadBudget,
): String {
    require(maximumCharacters > 0) { "Invalid JSON output limit" }
    // Growing/pooled UTF16 serializer buffers, their result and the lossless escape output can
    // coexist. Twelve bytes per final code unit cover these live buffers and intermediate copies.
    // Counting uses only the stream writer's small workspace, never a retained byte array.
    val characterLimit = minOf(maximumCharacters.toLong(), (budget.remainingBytes - WRITER_WORKSPACE_BYTES) / BYTES_PER_CHARACTER)
    if (characterLimit <= 0) throw JsonLimitException(OUTPUT_MESSAGE)
    val counted = CountingOutput(characterLimit.toInt())
    encodeToStream(serializer, value, counted)
    // Stream encoding replaces isolated UTF16 surrogates with '?'. Its character count is still
    // exact, but only encodeToString retains the original code units for lossless JSON escaping.
    val encoded = encodeToString(serializer, value)
    check(encoded.length == counted.characters) { "Saved data changed while it was being encoded" }
    return escapeJsonSurrogates(encoded, characterLimit.toInt())
}

private class CountingOutput(
    private val limit: Int,
) : OutputStream() {
    var characters = 0
        private set

    override fun write(value: Int) {
        // UTF8 continuation bytes add no code units; supplementary scalars occupy a UTF16 pair.
        val byte = value and 0xFF
        val count =
            if (byte in 0x80..0xBF) {
                0
            } else if (byte >= 0xF0) {
                2
            } else {
                1
            }
        if (count > limit - characters) throw JsonLimitException(OUTPUT_MESSAGE)
        characters += count
    }

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        for (index in offset until offset + length) write(bytes[index].toInt())
    }
}

private const val WRITER_WORKSPACE_BYTES = 64L * 1024
private const val BYTES_PER_CHARACTER = 12L
private const val OUTPUT_MESSAGE = "This saved data is too large for this device. Save fewer items or settings."
