package com.artflow.studio.data.local

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

class FontLibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        FontLibrary.configure(filesDir)
    }

    @After
    fun tearDown() {
        FontLibrary.configure(null)
    }

    @Test
    fun `invalid or corrupt font import never overwrites or deletes an existing valid font`() {
        val validBytes = validSfntBytes(seed = 1)
        val first =
            FontLibrary.importStream(
                filesDir = filesDir,
                displayName = "BrandDisplay.ttf",
                openStream = { ByteArrayInputStream(validBytes) },
                validator = { true },
            )
        check(first is FontLibrary.ImportResult.Success)
        assertEquals("font:BrandDisplay.ttf", first.family)
        assertFalse(first.renamedFromCollision)

        val fontsDir = File(filesDir, "fonts")
        val existingFile = File(fontsDir, "BrandDisplay.ttf")
        assertTrue(existingFile.isFile)
        assertArrayEquals(validBytes, existingFile.readBytes())

        // Case 1: invalid SFNT payload sharing the exact same display name.
        val invalidHeader =
            FontLibrary.importStream(
                filesDir = filesDir,
                displayName = "BrandDisplay.ttf",
                openStream = { ByteArrayInputStream("not-a-font-payload".toByteArray()) },
                validator = { true },
            )
        assertTrue(invalidHeader is FontLibrary.ImportResult.Failure)
        assertEquals(FontLibrary.FailureReason.INVALID_FONT, (invalidHeader as FontLibrary.ImportResult.Failure).reason)
        assertArrayEquals(validBytes, existingFile.readBytes())

        // Case 2: SFNT header passes but Typeface validator rejects it.
        val badGlyphs =
            FontLibrary.importStream(
                filesDir = filesDir,
                displayName = "BrandDisplay.ttf",
                openStream = { ByteArrayInputStream(validSfntBytes(seed = 99)) },
                validator = { false },
            )
        assertTrue(badGlyphs is FontLibrary.ImportResult.Failure)
        assertEquals(FontLibrary.FailureReason.INVALID_FONT, (badGlyphs as FontLibrary.ImportResult.Failure).reason)
        assertArrayEquals(validBytes, existingFile.readBytes())

        // Case 3: aborted stream mid-read with same display name.
        val aborted =
            FontLibrary.importStream(
                filesDir = filesDir,
                displayName = "BrandDisplay.ttf",
                openStream = {
                    object : InputStream() {
                        private var offset = 0

                        override fun read(): Int =
                            if (offset < 4) {
                                offset++
                                validBytes[offset - 1].toInt() and 0xFF
                            } else {
                                throw IOException("Simulated interrupted transfer")
                            }
                    }
                },
                validator = { true },
            )
        assertTrue(aborted is FontLibrary.ImportResult.Failure)
        assertEquals(FontLibrary.FailureReason.UNREADABLE, (aborted as FontLibrary.ImportResult.Failure).reason)
        assertArrayEquals(validBytes, existingFile.readBytes())

        // Zero staged temp files remain in fontsDir.
        assertEquals(listOf("BrandDisplay.ttf"), fontsDir.listFiles()?.map { it.name })
    }

    @Test
    fun `valid duplicate display names receive non-destructive suffixes and portable ids`() {
        val firstBytes = validSfntBytes(seed = 1)
        val secondBytes = validSfntBytes(seed = 2)
        val thirdBytes = validSfntBytes(seed = 3)

        val first =
            FontLibrary.importStream(filesDir, "Poster.otf", { ByteArrayInputStream(firstBytes) }) { true }
                as FontLibrary.ImportResult.Success
        val second =
            FontLibrary.importStream(filesDir, "Poster.otf", { ByteArrayInputStream(secondBytes) }) { true }
                as FontLibrary.ImportResult.Success
        val third =
            FontLibrary.importStream(filesDir, "Poster.otf", { ByteArrayInputStream(thirdBytes) }) { true }
                as FontLibrary.ImportResult.Success

        assertEquals("font:Poster.otf", first.family)
        assertFalse(first.renamedFromCollision)
        assertEquals("font:Poster (2).otf", second.family)
        assertTrue(second.renamedFromCollision)
        assertEquals("font:Poster (3).otf", third.family)
        assertTrue(third.renamedFromCollision)

        val fontsDir = File(filesDir, "fonts")
        assertArrayEquals(firstBytes, File(fontsDir, "Poster.otf").readBytes())
        assertArrayEquals(secondBytes, File(fontsDir, "Poster (2).otf").readBytes())
        assertArrayEquals(thirdBytes, File(fontsDir, "Poster (3).otf").readBytes())

        assertEquals(
            listOf("font:Poster (2).otf", "font:Poster (3).otf", "font:Poster.otf"),
            FontLibrary.families(filesDir),
        )
    }

    @Test
    fun `empty oversized and unsupported extension inputs fail cleanly`() {
        val badExt =
            FontLibrary.importStream(filesDir, "readme.txt", { ByteArrayInputStream(validSfntBytes(1)) }) { true }
        assertEquals(
            FontLibrary.FailureReason.UNSUPPORTED_EXTENSION,
            (badExt as FontLibrary.ImportResult.Failure).reason,
        )

        val empty =
            FontLibrary.importStream(filesDir, "Empty.ttf", { ByteArrayInputStream(ByteArray(0)) }) { true }
        assertEquals(FontLibrary.FailureReason.EMPTY, (empty as FontLibrary.ImportResult.Failure).reason)

        val oversizedStream =
            object : InputStream() {
                private var remaining = 21 * 1024 * 1024

                override fun read(): Int = if (remaining-- > 0) 0 else -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (remaining <= 0) return -1
                    val count = minOf(len, remaining)
                    remaining -= count
                    return count
                }
            }
        val oversized =
            FontLibrary.importStream(filesDir, "Huge.ttf", { oversizedStream }) { true }
        assertEquals(FontLibrary.FailureReason.TOO_LARGE, (oversized as FontLibrary.ImportResult.Failure).reason)
    }

    @Test
    fun `portable and legacy font references resolve safely without path traversal`() {
        FontLibrary.importStream(filesDir, "SerifCustom.ttf", { ByteArrayInputStream(validSfntBytes(5)) }) { true }

        assertTrue(FontLibrary.exists("font:SerifCustom.ttf"))
        assertTrue(FontLibrary.exists("file:/old-device/data/user/0/com.artflow.studio/files/fonts/SerifCustom.ttf"))
        assertEquals(
            "font:SerifCustom.ttf",
            FontLibrary.portableId("file:/old-device/data/user/0/com.artflow.studio/files/fonts/SerifCustom.ttf"),
        )
        assertEquals("SerifCustom", FontLibrary.label("font:SerifCustom.ttf"))
        assertEquals("SerifCustom", FontLibrary.label("file:/old/path/SerifCustom.ttf"))

        assertFalse(FontLibrary.exists("font:../secret.ttf"))
        assertFalse(FontLibrary.exists("font:sub/SerifCustom.ttf"))
        assertNull(FontLibrary.resolveFile("font:../../etc/passwd.ttf"))
        assertNull(FontLibrary.portableId("sans-serif"))
    }

    private fun validSfntBytes(seed: Int): ByteArray {
        val bytes = ByteArray(12 + 16 + 8)
        bytes[0] = 0x00
        bytes[1] = 0x01
        bytes[2] = 0x00
        bytes[3] = 0x00
        bytes[4] = 0x00
        bytes[5] = 0x01 // numTables = 1 -> requires at least 12 + 16 = 28 bytes
        bytes[12] = 'h'.code.toByte()
        bytes[13] = 'e'.code.toByte()
        bytes[14] = 'a'.code.toByte()
        bytes[15] = 'd'.code.toByte()
        bytes[bytes.lastIndex] = seed.toByte()
        return bytes
    }
}
