package com.artflow.studio.core.serialization

import com.artflow.studio.core.pixels.ImportMemoryBudget

/** Per-container limits supplement the shared lexical work and nesting bounds. */
internal interface JsonReadLimits {
    val message: String
    val maximumTokens: Int get() = DEFAULT_MAX_TOKENS

    fun objectEntries(property: String?): Int = 256

    fun arrayEntries(property: String?): Int = 128

    fun stringCharacters(
        property: String?,
        owner: String?,
    ): Int = 256

    companion object {
        const val DEFAULT_MAX_TOKENS = 524_288
    }
}

internal class JsonLimitException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Admission before a JSON serializer constructs its collections. Only bounded object keys are
 * copied during this scan; strings, numbers and arrays are never materialized. The estimate is
 * for this operation's text/token/declared payload work, not the whole process heap.
 */
internal class JsonReadBudget(
    maximumBytes: Long = ImportMemoryBudget.currentBytes(),
    private val message: String = "This file needs too much memory. Try a smaller file.",
) {
    val maximumBytes = minOf(maximumBytes, ImportMemoryBudget.currentBytes())
    private var reserved = 0L
    val remainingBytes: Long get() = maximumBytes - reserved

    init {
        require(maximumBytes in 1..(Runtime.getRuntime().maxMemory() / 3)) { "Invalid JSON reading allowance" }
    }

    fun reserve(bytes: Long) {
        if (bytes < 0 || bytes > maximumBytes - reserved) throw JsonLimitException(message)
        reserved += bytes
    }

    /** Admits one complete encoded JSON document using the default per-document cap. */
    fun reserveTokens(count: Long) {
        if (count < 0 || count > JsonReadLimits.DEFAULT_MAX_TOKENS) throw JsonLimitException(message)
        reserve(count * TOKEN_BYTES)
    }

    fun check(
        text: String,
        limits: JsonReadLimits,
    ) {
        reserve(text.length.toLong() * Char.SIZE_BYTES)
        Scanner(text, limits, ::reserve).check()
    }

    private class Scanner(
        private val text: String,
        private val limits: JsonReadLimits,
        private val reserve: (Long) -> Unit,
    ) {
        private val maximumTokens = limits.maximumTokens.also { require(it > 0) { "Invalid JSON token limit" } }
        private var position = 0
        private var tokens = 0

        fun check() {
            value(0, null, null)
            whitespace()
            syntax(position == text.length)
        }

        private fun value(
            depth: Int,
            property: String?,
            owner: String?,
        ) {
            whitespace()
            syntax(position < text.length)
            token()
            when (text[position]) {
                '{' -> objectValue(depth + 1, property)
                '[' -> arrayValue(depth + 1, property)
                '"' -> string(limits.stringCharacters(property, owner), capture = false)
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        private fun objectValue(
            depth: Int,
            property: String?,
        ) {
            admitted(depth <= MAX_DEPTH)
            position++
            whitespace()
            if (take('}')) return
            val keys = hashSetOf<String>()
            var entries = 0
            do {
                admitted(++entries <= limits.objectEntries(property))
                whitespace()
                token()
                val key = requireNotNull(string(MAX_KEY_CHARACTERS, capture = true))
                admitted(keys.add(key))
                whitespace()
                syntax(take(':'))
                value(depth, key, property)
                whitespace()
            } while (take(','))
            syntax(take('}'))
        }

        private fun arrayValue(
            depth: Int,
            property: String?,
        ) {
            admitted(depth <= MAX_DEPTH)
            position++
            whitespace()
            if (take(']')) return
            var entries = 0
            do {
                admitted(++entries <= limits.arrayEntries(property))
                value(depth, null, property)
                whitespace()
            } while (take(','))
            syntax(take(']'))
        }

        private fun string(
            limit: Int,
            capture: Boolean,
        ): String? {
            syntax(take('"'))
            val captured = if (capture) StringBuilder() else null
            var characters = 0
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') return captured?.toString()
                syntax(character >= ' ')
                val decoded = if (character == '\\') escapedCharacter() else character
                admitted(++characters <= limit)
                captured?.append(decoded)
            }
            throw IllegalArgumentException("This file contains incomplete text data")
        }

        private fun escapedCharacter(): Char {
            syntax(position < text.length)
            return when (val escaped = text[position++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> unicodeCharacter()
                else -> throw IllegalArgumentException("This file contains an invalid text escape")
            }
        }

        private fun unicodeCharacter(): Char {
            syntax(text.length - position >= 4)
            var code = 0
            repeat(4) {
                val digit =
                    when (val character = text[position++]) {
                        in '0'..'9' -> character - '0'
                        in 'a'..'f' -> character - 'a' + 10
                        in 'A'..'F' -> character - 'A' + 10
                        else -> -1
                    }
                syntax(digit >= 0)
                code = code * 16 + digit
            }
            return code.toChar()
        }

        private fun number() {
            val start = position
            take('-')
            if (!take('0')) {
                syntax(position < text.length && text[position] in '1'..'9')
                digits(start)
            }
            if (take('.')) {
                syntax(position < text.length && text[position] in '0'..'9')
                digits(start)
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                syntax(position < text.length && text[position] in '0'..'9')
                digits(start)
            }
            admitted(position - start <= MAX_NUMBER_CHARACTERS)
        }

        private fun digits(start: Int) {
            while (position < text.length && text[position] in '0'..'9') {
                position++
                admitted(position - start <= MAX_NUMBER_CHARACTERS)
            }
        }

        private fun literal(expected: String) {
            syntax(text.regionMatches(position, expected, 0, expected.length))
            position += expected.length
        }

        private fun whitespace() {
            while (position < text.length && text[position] in " \t\r\n") position++
        }

        private fun take(character: Char): Boolean {
            if (position >= text.length || text[position] != character) return false
            position++
            return true
        }

        private fun token() {
            admitted(tokens < maximumTokens)
            tokens++
            reserve(TOKEN_BYTES)
        }

        private fun admitted(condition: Boolean) {
            if (!condition) throw JsonLimitException(limits.message)
        }

        private fun syntax(condition: Boolean) {
            require(condition) { "This file contains malformed text data" }
        }
    }

    private companion object {
        const val MAX_DEPTH = 16
        const val MAX_KEY_CHARACTERS = 128
        const val MAX_NUMBER_CHARACTERS = 128
        const val TOKEN_BYTES = 128L
    }
}
