package com.artflow.studio.domain.model.brush

import com.artflow.studio.core.serialization.JsonReadBudget

/** Owns every collection read by the counting and writing passes; immutable strings are shared. */
internal class BrushEncodingSnapshot(
    private val budget: JsonReadBudget,
) {
    fun copy(brushes: List<SavedBrush>): List<SavedBrush> =
        list(brushes, BrushLibraryCodec.MAX_BRUSHES) { brush ->
            budget.reserve(OBJECT_BYTES)
            brush.copy(parameters = parameters(brush.parameters, 0))
        }

    private fun parameters(
        source: BrushParams,
        depth: Int,
    ): BrushParams {
        require(depth <= 1) { "A dual brush combines exactly two brushes" }
        budget.reserve(PARAMETER_BYTES)
        return source.copy(
            importedPressure =
                source.importedPressure?.let { pressure ->
                    budget.reserve(OBJECT_BYTES)
                    pressure.copy(
                        overall = pressure.overall?.let(::curve),
                        size = pressure.size?.let(::curve),
                        opacity = pressure.opacity?.let(::curve),
                        flow = pressure.flow?.let(::curve),
                    )
                },
            importedAuxiliaryCurves = map(source.importedAuxiliaryCurves, BrushLibraryCodec.MAX_AUXILIARY_CURVES, ::curve),
            importedSourceScalars = map(source.importedSourceScalars, SourceBrushScalar.MAX_FIELDS) { it },
            importedSourceStructures = map(source.importedSourceStructures, SourceBrushStructuredField.MAX_OBJECTS, ::structure),
            dual = source.dual?.let { it.copy(params = parameters(it.params, depth + 1)) },
        )
    }

    private fun curve(source: PressureMapping): PressureMapping {
        budget.reserve(OBJECT_BYTES)
        return source.copy(points = list(source.points, PressureMapping.MAX_POINTS) { it })
    }

    private fun structure(source: SourceBrushStructuredField): SourceBrushStructuredField {
        budget.reserve(OBJECT_BYTES)
        return source.copy(
            fields = map(source.fields, SourceBrushStructuredField.MAX_FIELDS) { it },
            items = source.items?.let { items -> list(items, SourceBrushStructuredField.MAX_ITEMS) { it } },
        )
    }

    private fun <T, R> list(
        source: List<T>,
        maximumEntries: Int,
        transform: (T) -> R,
    ): List<R> {
        require(source.size <= maximumEntries) { "Too many saved brush settings" }
        budget.reserve(OBJECT_BYTES)
        val result = arrayListOf<R>()
        for (value in source) {
            require(result.size < maximumEntries) { "Too many saved brush settings" }
            budget.reserve(LIST_ENTRY_BYTES)
            result.add(transform(value))
        }
        return result
    }

    private fun <K, V, R> map(
        source: Map<K, V>,
        maximumEntries: Int,
        transform: (V) -> R,
    ): Map<K, R> {
        require(source.size <= maximumEntries) { "Too many saved brush settings" }
        budget.reserve(OBJECT_BYTES)
        val result = linkedMapOf<K, R>()
        var entries = 0
        for ((key, value) in source) {
            require(++entries <= maximumEntries) { "Too many saved brush settings" }
            budget.reserve(MAP_ENTRY_BYTES)
            result[key] = transform(value)
        }
        return result
    }

    private companion object {
        const val OBJECT_BYTES = 128L
        const val PARAMETER_BYTES = 1024L
        const val MAP_ENTRY_BYTES = 128L
        const val LIST_ENTRY_BYTES = 32L
    }
}
