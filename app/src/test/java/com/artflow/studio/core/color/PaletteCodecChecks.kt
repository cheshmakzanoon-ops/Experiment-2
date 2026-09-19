package com.artflow.studio.core.color

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.random.Random

/** Independent byte fixtures exercise the production codec, not just its own encoder/decoder pair. */
object PaletteCodecChecks {
    fun headerDoesNotOverwriteTheFirstBlock() {
        val bytes = PaletteCodec.exportAse(palette())
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "ASEF")
        check(buffer.getShort(4).toInt() == 1)
        check(buffer.getInt(8) == 3)
        check((buffer.getShort(12).toInt() and 0xFFFF) == 0xC001)
        buffer.position(12)
        val types = mutableListOf<Int>()
        repeat(3) {
            types += buffer.short.toInt() and 0xFFFF
            val length = buffer.int
            check(length in 0..buffer.remaining())
            buffer.position(buffer.position() + length)
        }
        check(types == listOf(0xC001, 1, 0xC002))
        check(!buffer.hasRemaining())
    }

    fun everyByteChannelRoundTripsExactly() {
        val colors = (0..255).map { ColorHarmony.fromRgb(it, (it * 37) % 256, (it * 79) % 256) }
        val source = palette().copy(colors = colors)
        check(PaletteCodec.importAse(PaletteCodec.exportAse(source))?.colors == colors)
    }

    fun unicodeAndLongNamesRoundTripWithoutFixedBufferOverflow() {
        for (name in listOf("Ocean;; \"ink\" 🎨", "長".repeat(40_000), "x".repeat(65_534))) {
            val source = palette().copy(name = name)
            val restored = requireNotNull(PaletteCodec.importAse(PaletteCodec.exportAse(source)))
            check(restored.name == name)
            check(restored.colors == source.colors)
        }
    }

    fun overlongNamesFailWithAnExplicitArgumentError() {
        val source = palette().copy(name = "x".repeat(65_535))
        check(runCatching { PaletteCodec.exportAse(source) }.exceptionOrNull() is IllegalArgumentException)
    }

    fun emptyPalettesHaveAValidHeaderWithoutInventedSwatches() {
        val bytes = PaletteCodec.exportAse(palette().copy(colors = emptyList()))
        check(ByteBuffer.wrap(bytes).getInt(8) == 2)
        check(PaletteCodec.importAse(bytes) == null)
    }

    fun aseExplicitlyCarriesRgbWithoutAlpha() {
        val source = palette().copy(colors = listOf(0x40123456, 0x00112233))
        val expected = listOf(0xFF123456.toInt(), 0xFF112233.toInt())
        check(PaletteCodec.importAse(PaletteCodec.exportAse(source))?.colors == expected)
    }

    fun independentRgbGrayAndCmykFixturesDecode() {
        val file = ase(color("RGB ", 1f, 0f, 0f), color("Gray", 0.5f), color("CMYK", 1f, 0f, 0f, 0f))
        val expected = listOf(0xFFFF0000.toInt(), 0xFF808080.toInt(), 0xFF00FFFF.toInt())
        check(PaletteCodec.importAse(file)?.colors == expected)
    }

    fun labLightnessUsesNormalizedAseUnits() {
        val colors = PaletteCodec.importAse(ase(color("LAB ", 1f, 0f, 0f), color("LAB ", 0f, 0f, 0f)))!!.colors
        check((colors[0] and 0xFF) >= 254)
        check(((colors[0] ushr 8) and 0xFF) >= 254)
        check(((colors[0] ushr 16) and 0xFF) >= 254)
        check(colors[1] == 0xFF000000.toInt())
    }

    fun everyTruncatedPrefixIsRejectedInsteadOfImportedPartially() {
        val file = ase(color("RGB ", 1f, 0f, 0f), color("RGB ", 0f, 1f, 0f))
        for (length in file.indices) check(PaletteCodec.importAse(file.copyOf(length)) == null) { "Accepted prefix $length" }
        check(PaletteCodec.importAse(file)?.colors?.size == 2)
    }

    fun invalidCountsVersionsAndLengthsFailClosed() {
        val file = ase(color("RGB ", 1f, 0f, 0f))
        for (count in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            check(PaletteCodec.importAse(file.copyOf().also { ByteBuffer.wrap(it).putInt(8, count) }) == null)
        }
        for (length in listOf(-1, 0, 1, Int.MAX_VALUE)) {
            check(PaletteCodec.importAse(file.copyOf().also { ByteBuffer.wrap(it).putInt(14, length) }) == null)
        }
        check(PaletteCodec.importAse(file.copyOf().also { it[5] = 2 }) == null)
        check(PaletteCodec.importAse(file.copyOf().also { it[7] = 1 }) == null)
        check(PaletteCodec.importAse(file + byteArrayOf(0)) == null)
    }

    fun shortColorRecordsCannotBorrowBytesFromTheirNeighbors() {
        val nameAndModel =
            payload {
                name("Short")
                writeBytes("RGB ")
            }
        val file = ase(block(1, nameAndModel), color("RGB ", 0f, 1f, 0f))
        check(PaletteCodec.importAse(file) == null)
        val missingType = color("RGB ", 1f, 0f, 0f).dropLast(2).toByteArray()
        ByteBuffer.wrap(missingType).putInt(2, missingType.size - 6)
        check(PaletteCodec.importAse(ase(missingType, color("RGB ", 0f, 1f, 0f))) == null)
    }

    fun invalidUtf16NamesCannotCrossBlockBoundaries() {
        val oversized =
            payload {
                writeShort(50)
                writeChar('x'.code)
            }
        check(PaletteCodec.importAse(ase(block(1, oversized), color("RGB ", 1f, 0f, 0f))) == null)
        val unterminated =
            payload {
                writeShort(1)
                writeChar('x'.code)
                writeBytes("RGB ")
                repeat(3) { writeFloat(0f) }
                writeShort(0)
            }
        check(PaletteCodec.importAse(ase(block(1, unterminated))) == null)
    }

    fun nonFiniteChannelsNeverBecomeInventedColorsOrExceptions() {
        for ((model, count) in listOf("RGB " to 3, "Gray" to 1, "CMYK" to 4, "LAB " to 3)) {
            assertNonFiniteChannelsRejected(model, count)
        }
    }

    private fun assertNonFiniteChannelsRejected(
        model: String,
        count: Int,
    ) {
        for (index in 0 until count) {
            for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val values = FloatArray(count) { 0.5f }
                values[index] = bad
                check(PaletteCodec.importAse(ase(color(model, *values))) == null)
            }
        }
    }

    fun malformedLastRecordDoesNotReturnTheValidFirstColor() {
        val bad =
            block(
                1,
                payload {
                    name("Bad")
                    writeBytes("RGB ")
                },
            )
        check(PaletteCodec.importAse(ase(color("RGB ", 1f, 0f, 0f), bad)) == null)
        check(PaletteCodec.importAse(ase(color("RGB ", 1f, 0f, 0f), color("????", 0f))) == null)
    }

    fun blockLocalMetadataAndUnknownBlocksDoNotMisalignLaterColors() {
        val extended = color("RGB ", 1f, 0f, 0f) + byteArrayOf(5, 6, 7)
        ByteBuffer.wrap(extended).putInt(2, extended.size - 6)
        val file = ase(extended, block(0x1234, byteArrayOf(1, 2, 3)), color("RGB ", 0f, 1f, 0f))
        check(PaletteCodec.importAse(file)?.colors == listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt()))
    }

    fun groupStructureAndEmptyGroupNamesAreHandledSafely() {
        val red = color("RGB ", 1f, 0f, 0f)
        check(PaletteCodec.importAse(ase(block(0xC001, byteArrayOf()), red, block(0xC002, byteArrayOf())))?.colors?.size == 1)
        check(PaletteCodec.importAse(ase(block(0xC001, payload { name("Open") }), red)) == null)
        check(PaletteCodec.importAse(ase(red, block(0xC002, byteArrayOf()))) == null)
    }

    fun gplIsIndependentOfTheDevicesNumberingLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            val source = palette()
            val encoded = PaletteCodec.exportGpl(source)
            check(encoded.lineSequence().last { it.isNotBlank() }.trimStart().startsWith("255"))
            check(PaletteCodec.importGpl(encoded)?.colors == source.colors)
        } finally {
            Locale.setDefault(previous)
        }
    }

    fun deterministicMalformedInputsNeverEscapeTheNullableParser() {
        val random = Random(62026)
        val valid = ase(color("RGB ", 0.2f, 0.4f, 0.6f))
        repeat(5_000) {
            val bytes = random.nextBytes(random.nextInt(0, 128))
            if (bytes.size >= 4) "ASEF".toByteArray().copyInto(bytes)
            PaletteCodec.importAse(bytes)
            val mutated = valid.copyOf()
            repeat(1 + random.nextInt(4)) { mutated[random.nextInt(mutated.size)] = random.nextInt(256).toByte() }
            PaletteCodec.importAse(mutated)
        }
    }

    private fun palette(): Palette = Palette(name = "Test", colors = listOf(0xFFFF0000.toInt()))

    private fun payload(fill: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { it.fill() } }.toByteArray()

    private fun DataOutputStream.name(value: String) {
        writeShort(value.length + 1)
        value.forEach { writeChar(it.code) }
        writeShort(0)
    }

    private fun color(
        model: String,
        vararg channels: Float,
    ): ByteArray =
        block(
            1,
            payload {
                name("Fixture")
                writeBytes(model)
                channels.forEach { writeFloat(it) }
                writeShort(0)
            },
        )

    private fun block(
        type: Int,
        body: ByteArray,
    ): ByteArray =
        payload {
            writeShort(type)
            writeInt(body.size)
            write(body)
        }

    private fun ase(vararg blocks: ByteArray): ByteArray =
        payload {
            writeBytes("ASEF")
            writeShort(1)
            writeShort(0)
            writeInt(blocks.size)
            blocks.forEach { write(it) }
        }
}
