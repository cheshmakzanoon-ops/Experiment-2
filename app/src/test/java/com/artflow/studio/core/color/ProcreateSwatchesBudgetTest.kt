package com.artflow.studio.core.color

import com.artflow.studio.core.serialization.JsonLimitException
import com.artflow.studio.core.three.ModelPackage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ProcreateSwatchesBudgetTest {
    @Test
    fun ignoredExpansionStopsBeforeOpeningThePaletteOrBuildingJson() {
        val fixture = Fixture("ignored.bin" to "x".repeat(1024), "Swatches.json" to PALETTE)
        assertTrue(fixture.read(expansion = 128).isEmpty())
        assertEquals(129, fixture.stream.produced)
        assertEquals(1, fixture.stream.entries)
        fixture.assertUnparsedAndClosed()
    }

    @Test
    fun trailingIgnoredExpansionCannotReturnAnAlreadyBufferedPalette() {
        val fixture = Fixture("Swatches.json" to PALETTE, "ignored.bin" to "x".repeat(1024))
        val allowance = PALETTE.toByteArray().size + 64L
        assertTrue(fixture.read(expansion = allowance).isEmpty())
        assertEquals(allowance + 1, fixture.stream.produced.toLong())
        assertEquals(2, fixture.stream.entries)
        fixture.assertUnparsedAndClosed()
    }

    @Test
    fun aggregateExpansionAtTheExactBoundaryKeepsNestedPaletteCompatibility() {
        val fixture = Fixture("ignored.bin" to "x".repeat(64), "Palette/Swatches.json" to PALETTE)
        val allowance = PALETTE.toByteArray().size + 64L
        val palette = fixture.read(expansion = allowance).single()
        assertEquals("Tiny", palette.name)
        assertEquals(listOf(RED), palette.colors)
        assertEquals(allowance, fixture.stream.produced.toLong())
        assertEquals(1, fixture.parses)
        assertTrue(fixture.stream.closed)
    }

    @Test
    fun duplicatePaletteBasenamesAreRefusedBeforeReadingTheSecondPayload() {
        val fixture = Fixture("A/Swatches.json" to PALETTE, "B/Swatches.json" to "x".repeat(1024))
        assertTrue(fixture.read().isEmpty())
        assertEquals(PALETTE.toByteArray().size, fixture.stream.produced)
        assertEquals(2, fixture.stream.entries)
        fixture.assertUnparsedAndClosed()
    }

    @Test
    fun memberCountRefusesTheSeventeenthBodyWithoutImplicitlyInflatingIt() {
        val entries = List(16) { "ignored-$it.bin" to "x" } + ("Swatches.json" to PALETTE)
        val fixture = Fixture(*entries.toTypedArray())
        assertTrue(fixture.read().isEmpty())
        assertEquals(16, fixture.stream.produced)
        assertEquals(17, fixture.stream.entries)
        fixture.assertUnparsedAndClosed()
    }

    @Test
    fun aDirectoryWithTheMetadataNameDoesNotHideTheRealPalette() {
        val fixture = Fixture("Swatches.json/" to "", "Palette/Swatches.json" to PALETTE)
        assertEquals(listOf(RED), fixture.read().single().colors)
        assertEquals(1, fixture.parses)
    }

    @Test
    fun missingIdentityAllowsARealModelArchiveToKeepItsOwnFormatBudget() {
        val obj = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n"
        val bytes = archive("ignored.bin" to "x".repeat(1024), "model.obj" to obj, "Shape.png" to "")
        val options = ProcreateSwatches.ReadOptions(maximumExpandedBytes = 64, optionalProbe = true)
        assertTrue(ProcreateSwatches.read(bytes, options).isEmpty())
        assertTrue(ProcreateSwatches.read(bytes, ProcreateSwatches.ReadOptions(memoryBudgetBytes = 1024, optionalProbe = true)).isEmpty())
        assertEquals(obj, ModelPackage.read(bytes).objText)
    }

    @Test
    fun insufficientMemoryIsRefusedBeforeOpeningTheArchive() {
        var opens = 0
        val failure =
            assertThrows(JsonLimitException::class.java) {
                ProcreateSwatches.read(
                    archive("Swatches.json" to PALETTE),
                    ProcreateSwatches.ReadOptions(memoryBudgetBytes = 1024),
                    openArchive = {
                        opens++
                        ZipInputStream(it)
                    },
                )
            }
        assertTrue(requireNotNull(failure.message).contains("palette"))
        assertEquals(0, opens)
    }

    @Test
    fun cancellationDuringIgnoredInflationClosesTheArchiveAndDoesNotParse() {
        val fixture = Fixture("ignored.bin" to "x".repeat(32 * 1024), "Swatches.json" to PALETTE)
        val cancellation = CancellationException("Palette import cancelled")
        val observed =
            assertThrows(CancellationException::class.java) {
                fixture.read(checkActive = {
                    if (fixture.hasStream && fixture.stream.produced > 0) throw cancellation
                })
            }
        assertSame(cancellation, observed)
        assertTrue(fixture.stream.produced in 1..8192)
        fixture.assertUnparsedAndClosed()
    }

    @Test
    fun parserCancellationAndFatalFailuresPropagateUnchanged() {
        for (failure in listOf(CancellationException("Cancelled"), AssertionError("Parser failed"))) {
            val fixture = Fixture("Swatches.json" to PALETTE)
            val observed = runCatching { fixture.read(parse = { throw failure }) }.exceptionOrNull()
            assertSame(failure, observed)
            assertTrue(fixture.stream.closed)
        }
    }

    @Test
    fun ordinaryArchiveDamageAndMalformedJsonKeepTheEmptyFallback() {
        assertTrue(ProcreateSwatches.read(archive("Swatches.json" to "{broken")).isEmpty())
        assertTrue(
            ProcreateSwatches
                .read(
                    archive("Swatches.json" to PALETTE),
                    ProcreateSwatches.ReadOptions(),
                    openArchive = { throw IOException("Damaged archive") },
                ).isEmpty(),
        )
    }

    private class Fixture(
        vararg entries: Pair<String, String>,
    ) {
        private val bytes = archive(*entries)
        lateinit var stream: CountingZip
        var parses = 0
        val hasStream get() = ::stream.isInitialized

        fun read(
            expansion: Long = 8L * 1024 * 1024,
            checkActive: () -> Unit = {},
            parse: (String) -> JsonElement = Json::parseToJsonElement,
        ): List<Palette> =
            ProcreateSwatches.read(
                bytes,
                ProcreateSwatches.ReadOptions(maximumExpandedBytes = expansion, checkActive = checkActive),
                parse = {
                    parses++
                    parse(it)
                },
                openArchive = { CountingZip(it).also { opened -> stream = opened } },
            )

        fun assertUnparsedAndClosed() {
            assertEquals(0, parses)
            assertTrue(stream.closed)
        }
    }

    private class CountingZip(
        input: InputStream,
    ) : ZipInputStream(input) {
        var produced = 0
        var entries = 0
        var closed = false

        override fun getNextEntry(): ZipEntry? = super.getNextEntry()?.also { entries++ }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int = super.read(bytes, offset, length).also { if (it > 0) produced += it }

        override fun close() {
            closed = true
            super.close()
        }
    }

    internal companion object {
        const val RED = -65536
        const val SWATCH = "{\"hue\":0,\"saturation\":1,\"brightness\":1}"
        const val PALETTE = "[{\"name\":\"Tiny\",\"swatches\":[$SWATCH]}]"

        fun archive(vararg entries: Pair<String, String>): ByteArray {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                for ((name, text) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
            return output.toByteArray()
        }
    }
}
