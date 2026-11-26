package com.artflow.studio.core.text

/**
 * Unicode 17.0.0 extended grapheme boundaries (UAX #29, revision 47):
 * https://www.unicode.org/reports/tr29/tr29-47.html#Grapheme_Cluster_Boundary_Rules
 *
 * Android's regex/ICU and the JDK ship different Unicode versions. All properties here are pinned
 * in [GraphemeData]. One forward UTF-16 pass carries the context for GB9c, GB11 and GB12/13 instead
 * of rescanning earlier text. The fixed table lookup is logarithmic in its 833 ranges.
 * Unpaired surrogates retain their original code units and the default Other property.
 */
internal object GraphemeClusters {
    /** UTF-16 ranges suitable for slicing the unchanged source, including space-based clusters. */
    fun ranges(text: String): Sequence<IntRange> =
        sequence {
            if (text.isEmpty()) return@sequence
            val state = BoundaryState()
            var start = 0
            var offset = 0
            while (offset < text.length) {
                val codePoint = Character.codePointAt(text, offset)
                val properties = GraphemeData.properties(codePoint)
                if (offset > 0 && state.breakBefore(properties)) {
                    yield(start until offset)
                    start = offset
                }
                state.append(properties)
                offset += Character.charCount(codePoint)
            }
            yield(start until text.length)
        }

    private class BoundaryState {
        private var previous = GraphemeData.OTHER
        private var oddRegionalIndicators = false
        private var pictographicExtendSuffix = false
        private var pictographicZwjSuffix = false
        private var indicConsonantSuffix = false
        private var indicLinkerSuffix = false

        fun breakBefore(properties: Int): Boolean {
            val current = properties and GraphemeData.BREAK_MASK
            return when {
                previous == GraphemeData.CR && current == GraphemeData.LF -> false // GB3
                isControl(previous) || isControl(current) -> true // GB4, GB5 precede extending rules.
                conjoins(previous, current) -> false // GB6..GB8
                current == GraphemeData.EXTEND || current == GraphemeData.ZWJ -> false // GB9
                current == GraphemeData.SPACING_MARK || previous == GraphemeData.PREPEND -> false // GB9a, GB9b
                indicLinkerSuffix && properties and GraphemeData.INDIC_MASK == GraphemeData.CONSONANT -> false // GB9c
                pictographicZwjSuffix && properties and GraphemeData.PICTOGRAPHIC != 0 -> false // GB11
                oddRegionalIndicators && current == GraphemeData.REGIONAL_INDICATOR -> false // GB12, GB13
                else -> true // GB999
            }
        }

        fun append(properties: Int) {
            val current = properties and GraphemeData.BREAK_MASK
            oddRegionalIndicators = current == GraphemeData.REGIONAL_INDICATOR && !oddRegionalIndicators
            pictographicZwjSuffix = current == GraphemeData.ZWJ && pictographicExtendSuffix
            pictographicExtendSuffix =
                properties and GraphemeData.PICTOGRAPHIC != 0 ||
                current == GraphemeData.EXTEND &&
                pictographicExtendSuffix
            when (properties and GraphemeData.INDIC_MASK) {
                GraphemeData.CONSONANT -> {
                    indicConsonantSuffix = true
                    indicLinkerSuffix = false
                }
                GraphemeData.LINKER -> indicLinkerSuffix = indicConsonantSuffix
                GraphemeData.INDIC_EXTEND -> Unit
                else -> {
                    indicConsonantSuffix = false
                    indicLinkerSuffix = false
                }
            }
            previous = current
        }

        private fun isControl(property: Int): Boolean =
            property == GraphemeData.CONTROL || property == GraphemeData.CR || property == GraphemeData.LF

        private fun conjoins(
            before: Int,
            after: Int,
        ): Boolean =
            when (before) {
                GraphemeData.L ->
                    after == GraphemeData.L ||
                        after == GraphemeData.V ||
                        after == GraphemeData.LV ||
                        after == GraphemeData.LVT
                GraphemeData.LV, GraphemeData.V -> after == GraphemeData.V || after == GraphemeData.T
                GraphemeData.LVT, GraphemeData.T -> after == GraphemeData.T
                else -> false
            }
    }
}
