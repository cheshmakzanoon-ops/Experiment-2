package com.artflow.studio.data.repository.settings

import com.artflow.studio.core.color.Palette
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Reads new JSON arrays and the original delimiter-separated JSON objects without losing names. */
internal object StoredPaletteCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(stored: String): List<Palette> {
        val text = stored.trim()
        if (text.isEmpty()) return emptyList()
        val array = if (text.startsWith("[")) text else legacyArray(text)
        // Do not convert corrupt user data to an empty list and overwrite it on the next setting edit.
        return json.decodeFromString(ListSerializer(Palette.serializer()), array)
    }

    private fun legacyArray(text: String): String {
        val result = StringBuilder("[")
        var quoted = false
        var escaped = false
        var depth = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val betweenObjects = !quoted && depth == 0
            if (betweenObjects && char == ';' && text.getOrNull(index + 1) == ';') {
                result.append(',')
                index += 2
                continue
            }
            result.append(char)
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
            }
            index++
        }
        return result.append(']').toString()
    }
}
