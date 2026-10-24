package com.artflow.studio.core.export

import com.artflow.studio.domain.model.layer.BlendMode
import com.artflow.studio.domain.model.layer.LayerBlendContract

/** Native layer variants are separate from the archived brush/dual-brush blend map. */
internal object ProcreateLayerBlendModes {
    data class Decoded(
        val mode: BlendMode,
        val contract: LayerBlendContract = LayerBlendContract.STANDARD,
    )

    /** A present higher-priority key wins, including zero and unsupported values. */
    fun decode(fields: Map<String, Any?>): Decoded {
        val key = listOf("extendedBlend2", "extendedBlend", "blend").firstOrNull(fields::containsKey)
        val number = (key?.let(fields::get) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toInt()
        return when (number) {
            19 -> Decoded(BlendMode.DARKEN, LayerBlendContract.PROCREATE_DARKEN_19)
            34 -> Decoded(BlendMode.DARKEN)
            else -> Decoded(number?.let(ProcreateBlendModes::of) ?: BlendMode.NORMAL)
        }
    }

    /** Pinned native source34 writes19 to both older keys; current readers use extendedBlend2. */
    fun encode(
        mode: BlendMode,
        contract: LayerBlendContract,
    ): Map<String, Long> {
        require(contract == LayerBlendContract.STANDARD || mode == BlendMode.DARKEN) { "The native Darken contract requires Darken mode" }
        val number =
            if (mode == BlendMode.DARKEN && contract == LayerBlendContract.STANDARD) {
                34L
            } else {
                ProcreateBlendModes.numberOf(mode).toLong()
            }
        val fallback = if (number == 34L) 19L else number
        return mapOf("extendedBlend2" to number, "extendedBlend" to fallback, "blend" to fallback)
    }
}
