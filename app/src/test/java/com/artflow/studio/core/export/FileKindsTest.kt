package com.artflow.studio.core.export

import org.junit.Assert.assertEquals
import org.junit.Test

class FileKindsTest {
    private val none = ByteArray(0)

    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun theNameDecidesFirstWhateverTheBytesAndTypeSay() {
        val zip = bytes("PK\u0003\u0004")
        assertEquals(FileKind.PROCREATE_DOCUMENT, FileKinds.classify("Dragon.procreate", "application/octet-stream", zip))
        assertEquals(FileKind.PROCREATE_DOCUMENT, FileKinds.classify("DRAGON.PROCREATE", null, none))
        assertEquals(FileKind.PSD, FileKinds.classify("a.psd", "image/png", bytes("\u0089PNG")))
        assertEquals(FileKind.BRUSH_FILE, FileKinds.classify("Inks.brushset", null, none))
        assertEquals(FileKind.BRUSH_FILE, FileKinds.classify("tip.abr", null, none))
        assertEquals(FileKind.PALETTE_FILE, FileKinds.classify("warm.swatches", null, none))
        assertEquals(FileKind.MODEL, FileKinds.classify("scene.glb", null, none))
        assertEquals(FileKind.ARTFLOW_ARTWORK, FileKinds.classify("my.art.artflow", null, none))
        assertEquals(FileKind.IMAGE, FileKinds.classify("photo.JPG", null, none))
    }

    @Test
    fun withoutAKnownNameTheFirstBytesDecide() {
        assertEquals(FileKind.IMAGE, FileKinds.classify("download", null, bytes("\u0089PNG\r\n\u001A\n")))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertEquals(FileKind.IMAGE, FileKinds.classify(null, null, jpeg))
        assertEquals(FileKind.IMAGE, FileKinds.classify(null, null, bytes("GIF89a")))
        assertEquals(FileKind.IMAGE, FileKinds.classify(null, null, bytes("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals(FileKind.PSD, FileKinds.classify(null, null, bytes("8BPS\u0000\u0001")))
        assertEquals(FileKind.PALETTE_FILE, FileKinds.classify(null, null, bytes("ASEF\u0000\u0001")))
        assertEquals(FileKind.PALETTE_FILE, FileKinds.classify(null, null, bytes("GIMP Palette\nName: x")))
        assertEquals(FileKind.MODEL, FileKinds.classify(null, null, bytes("glTF\u0002\u0000")))
        assertEquals(FileKind.ZIP, FileKinds.classify("download.bin", "application/octet-stream", bytes("PK\u0003\u0004rest")))
    }

    @Test
    fun theClaimedTypeIsTheLastResort() {
        assertEquals(FileKind.PSD, FileKinds.classify(null, "image/vnd.adobe.photoshop", none))
        assertEquals(FileKind.IMAGE, FileKinds.classify(null, "image/heic", none))
        assertEquals(FileKind.MODEL, FileKinds.classify(null, "model/vnd.usdz+zip", none))
        assertEquals(FileKind.UNKNOWN, FileKinds.classify(null, "application/pdf", none))
        assertEquals(FileKind.UNKNOWN, FileKinds.classify("notes.txt", "text/plain", bytes("hello")))
    }

    @Test
    fun shortOrEmptyInputNeverThrows() {
        assertEquals(FileKind.UNKNOWN, FileKinds.classify(null, null, none))
        assertEquals(FileKind.UNKNOWN, FileKinds.classify("", "", bytes("P")))
        assertEquals(FileKind.UNKNOWN, FileKinds.classify("x.", null, bytes("PK")))
    }
}
