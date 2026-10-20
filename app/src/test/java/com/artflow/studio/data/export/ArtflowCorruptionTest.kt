package com.artflow.studio.data.export

import com.artflow.studio.core.export.Corruption
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ArtflowPackage.read] under corruption: a package written with its document, a preview and two layer
 * files. A damaged package must read or be refused with IllegalArgumentException, as the reader documents.
 */
class ArtflowCorruptionTest {
    private fun seed(): ByteArray {
        val document = ByteArray(2_400) { ((it * 13) % 251).toByte() }
        val layer = ByteArray(1_800) { ((it * 29) % 241).toByte() }
        return ArtflowPackage.write(
            "Fuzz artwork",
            mapOf(
                ArtflowPackage.DOCUMENT to document,
                "canvas.png" to ByteArray(700) { (it % 97).toByte() },
                "layers/0/first.png" to layer,
                "layers/1/second.png" to layer.copyOf(900),
            ),
        )
    }

    /** A package cut off inside its first entry header used to surface the zip layer's EOFException. */
    @Test
    fun aTruncatedPackageIsRefusedAsDamaged() {
        val failure = runCatching { ArtflowPackage.read(seed().copyOf(30)) }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalArgumentException)
    }

    @Test
    fun aCorruptedPackageIsReadOrRefused() {
        Corruption.check(
            "artflow package",
            seed(),
            seed = 4001L,
            accepted = { failure -> failure is IllegalArgumentException },
        ) { bytes ->
            val contents = ArtflowPackage.read(bytes)
            contents.files.values.forEach { it.size }
        }
    }
}
