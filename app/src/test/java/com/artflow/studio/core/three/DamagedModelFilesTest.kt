package com.artflow.studio.core.three

import org.junit.Assert.assertThrows
import org.junit.Test

/** Damaged packages are refused with the same error as any other unreadable model, never an unrelated exception. */
class DamagedModelFilesTest {
    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResourceAsStream("/models/$name")).use { it.readBytes() }

    @Test
    fun aCrateCutShortIsRefusedWhereverItBreaks() {
        val bytes = fixture("ops.usdc")
        for (length in listOf(9, 10, 12, 20, 24, 40, 88, 120, 200, bytes.size / 3, bytes.size / 2)) {
            assertThrows("length $length", IllegalArgumentException::class.java) { ModelPackage.read(bytes.copyOf(length)) }
        }
    }

    @Test
    fun aDamagedPackageIsEitherReadOrRefusedWithTheRefusalError() {
        // A changed byte may land in data nothing reads, so the package can still load. Whatever fails must be a refusal.
        val bytes = fixture("box.usdz")
        for (offset in listOf(40, 100, bytes.size / 2, bytes.size - 30)) {
            val damaged = bytes.copyOf().also { it[offset] = (it[offset] + 1).toByte() }
            try {
                ModelPackage.read(damaged)
            } catch (refused: IllegalArgumentException) {
                // The documented refusal.
            }
        }
    }
}
