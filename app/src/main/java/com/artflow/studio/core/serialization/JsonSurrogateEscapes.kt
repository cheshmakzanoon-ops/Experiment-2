package com.artflow.studio.core.serialization

/**
 * Makes already encoded JSON lossless when it crosses a UTF8 byte boundary. Valid supplementary
 * pairs stay unchanged; an isolated UTF16 code unit becomes its legal six-character JSON escape.
 * The complete expanded length is admitted before allocating a replacement string.
 */
internal fun escapeJsonSurrogates(
    encoded: String,
    maximumCharacters: Int = Int.MAX_VALUE,
): String {
    require(maximumCharacters >= 0) { "Invalid JSON output limit" }
    var length = encoded.length.toLong()
    var index = 0
    while (index < encoded.length) {
        val character = encoded[index++]
        if (character.isHighSurrogate() && index < encoded.length && encoded[index].isLowSurrogate()) {
            index++
        } else if (character.isSurrogate()) {
            length += 5
        }
        if (length > maximumCharacters) throw JsonLimitException("This saved text exceeds the supported size")
    }
    if (length == encoded.length.toLong()) return encoded
    return buildString(length.toInt()) {
        var position = 0
        while (position < encoded.length) {
            val character = encoded[position++]
            when {
                character.isHighSurrogate() && position < encoded.length && encoded[position].isLowSurrogate() -> {
                    append(character)
                    append(encoded[position++])
                }
                character.isSurrogate() -> {
                    append("\\u")
                    for (shift in 12 downTo 0 step 4) append(HEX_DIGITS[(character.code ushr shift) and 15])
                }
                else -> append(character)
            }
        }
    }
}

private const val HEX_DIGITS = "0123456789abcdef"
