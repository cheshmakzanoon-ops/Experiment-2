package com.artflow.studio.data.export

import com.artflow.studio.core.export.ProcreateReadBudget
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ProcreateZipIndexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun actualDirectoryEntriesCannotExceedTheirDeclaredCount() {
        val source = archive(2)
        val footer = source.size - 22
        ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(footer + 8, 1)
            putShort(footer + 10, 1)
        }
        assertDamaged(source)
    }

    @Test
    fun variableDirectoryFieldsCannotEscapeTheirAdmittedSpan() {
        val source = archive(1)
        val bytes = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN)
        val directory = bytes.getInt(source.size - 22 + 16)
        bytes.putShort(directory + 32, 32_000)
        assertDamaged(source)
    }

    @Test
    fun archiveEntryLimitIsAppliedBeforeDirectoryTraversal() {
        val source = archive(1)
        val footer = source.size - 22
        ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(footer + 8, 10_001)
            putShort(footer + 10, 10_001)
        }
        val failure =
            assertThrows(IllegalArgumentException::class.java) { check(source) }
        assertTrue(failure.message.orEmpty().contains("too many entries"))
    }

    @Test
    fun directoryIndexReservationReducesTheRemainingDecodeAllowance() {
        val budget = ProcreateReadBudget(BUDGET)
        val before = budget.thumbnailDecodeBytes
        check(archive(3), budget)
        assertTrue(budget.thumbnailDecodeBytes in 1 until before)
    }

    @Test
    fun cancelledIndexInspectionStopsBeforeZipFileConstruction() {
        val cancelled = CancellationException("No longer opening")
        var checks = 0
        val budget =
            ProcreateReadBudget(BUDGET) {
                if (++checks == 3) throw cancelled
            }
        val failure = assertThrows(CancellationException::class.java) { check(archive(3), budget) }
        assertSame(cancelled, failure)
        assertEquals(3, checks)
    }

    private fun assertDamaged(source: ByteArray) {
        val failure = assertThrows(IllegalArgumentException::class.java) { check(source) }
        assertTrue(failure.message.orEmpty().contains("index is damaged"))
    }

    private fun check(
        source: ByteArray,
        budget: ProcreateReadBudget = ProcreateReadBudget(BUDGET),
    ) {
        val file = temporary.newFile()
        file.writeBytes(source)
        ProcreateZipIndex.check(file, budget, 10_000)
    }

    private fun archive(entries: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            repeat(entries) { index ->
                zip.putNextEntry(ZipEntry("entry-$index"))
                zip.write(index)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private companion object {
        const val BUDGET = 4L * 1024 * 1024
    }
}
