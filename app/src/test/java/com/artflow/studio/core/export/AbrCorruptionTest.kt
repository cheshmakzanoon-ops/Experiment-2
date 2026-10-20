package com.artflow.studio.core.export

import org.junit.Test

/**
 * [AbrReader.read] under corruption, on the two synthetic fixtures (a version 6 file with a sampled tip in each
 * of its raw and RLE forms, and a version 2 file). A damaged file must read or be refused with
 * IllegalArgumentException, which is what [AbrReader.read] documents for files it does not accept.
 */
class AbrCorruptionTest {
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/procreate/$name")).use { it.readBytes() }

    private fun refusal(failure: Throwable) = failure is IllegalArgumentException

    @Test
    fun aCorruptedVersionSixFileIsReadOrRefused() {
        Corruption.check("abr v6", fixture("tips-v6.abr"), seed = 3001L, accepted = ::refusal) { bytes ->
            AbrReader.read(bytes).forEach { tip -> tip.values.size }
        }
    }

    @Test
    fun aCorruptedVersionTwoFileIsReadOrRefused() {
        Corruption.check("abr v2", fixture("tips-v2.abr"), seed = 3002L, accepted = ::refusal) { bytes ->
            AbrReader.read(bytes).forEach { tip -> tip.values.size }
        }
    }
}
