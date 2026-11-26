package com.artflow.studio.data.local

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class FontImportFailureBoundaryTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var installed: File
    private val original = fontBytes(1)
    private val replacement = fontBytes(2)

    @Before
    fun setUp() {
        filesDir = temporary.newFolder("files")
        installed = File(filesDir, "fonts/Poster.ttf")
        installed.parentFile.mkdirs()
        installed.writeBytes(original)
    }

    @Test
    fun revokedProviderPermissionReturnsFailureWithoutReplacingExistingFont() {
        val result = import(openStream = { throw SecurityException("The selected URI grant was revoked") })

        assertEquals(FontLibrary.FailureReason.UNREADABLE, (result as FontLibrary.ImportResult.Failure).reason)
        assertExistingFontOnly()
    }

    @Test
    fun permissionLostDuringReadClosesProviderAndKeepsExistingFont() {
        var closed = false
        val input =
            object : InputStream() {
                override fun read(): Int = throw SecurityException("Provider no longer permits this read")

                override fun close() {
                    closed = true
                }
            }

        val result = import(openStream = { input })

        assertEquals(FontLibrary.FailureReason.UNREADABLE, (result as FontLibrary.ImportResult.Failure).reason)
        assertTrue(closed)
        assertExistingFontOnly()
    }

    @Test
    fun permissionFailureOnCloseDoesNotPublishTheReadFont() {
        val input =
            object : ByteArrayInputStream(replacement) {
                override fun close() = throw SecurityException("Provider rejected close")
            }

        val result = import(openStream = { input })

        assertEquals(FontLibrary.FailureReason.UNREADABLE, (result as FontLibrary.ImportResult.Failure).reason)
        assertExistingFontOnly()
    }

    @Test
    fun cancelledOwnerDoesNotOpenTheProvider() {
        val cancelled = CancellationException("Editor closed before font import")
        var opened = false

        val failure =
            runCatching {
                import(
                    openStream = {
                        opened = true
                        ByteArrayInputStream(replacement)
                    },
                    checkActive = { throw cancelled },
                )
            }.exceptionOrNull()

        assertSame(cancelled, failure)
        assertFalse(opened)
        assertExistingFontOnly()
    }

    @Test
    fun cancellationDuringProviderReadStopsFurtherReadsAndClosesInput() {
        val cancelled = CancellationException("Editor closed while provider was reading")
        var active = true
        var reads = 0
        var closed = false
        val input =
            object : ByteArrayInputStream(replacement) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    reads++
                    active = false
                    return super.read(buffer, offset, length)
                }

                override fun close() {
                    closed = true
                }
            }

        val failure =
            runCatching {
                import(openStream = { input }, checkActive = { if (!active) throw cancelled })
            }.exceptionOrNull()

        assertSame(cancelled, failure)
        assertEquals(1, reads)
        assertTrue(closed)
        assertExistingFontOnly()
    }

    @Test
    fun cancellationWhenProviderClosesSkipsPlatformValidation() {
        val cancelled = CancellationException("Editor closed at the end of the provider read")
        var active = true
        var validated = false
        val input =
            object : ByteArrayInputStream(replacement) {
                override fun close() {
                    active = false
                }
            }

        val failure =
            runCatching {
                FontLibrary.importStream(
                    filesDir = filesDir,
                    displayName = "Poster.ttf",
                    openStream = { input },
                    checkActive = { if (!active) throw cancelled },
                    validator = {
                        validated = true
                        true
                    },
                )
            }.exceptionOrNull()

        assertSame(cancelled, failure)
        assertFalse(validated)
        assertExistingFontOnly()
    }

    @Test
    fun cancellationAfterPlatformValidationDeletesOnlyTheProvisionalFile() {
        val cancelled = CancellationException("Editor closed during font validation")
        var active = true
        var staged: File? = null

        val failure =
            runCatching {
                FontLibrary.importStream(
                    filesDir = filesDir,
                    displayName = "Poster.ttf",
                    openStream = { ByteArrayInputStream(replacement) },
                    checkActive = { if (!active) throw cancelled },
                    validator = { file ->
                        staged = file
                        assertArrayEquals(replacement, file.readBytes())
                        active = false
                        true
                    },
                )
            }.exceptionOrNull()

        assertSame(cancelled, failure)
        assertFalse(requireNotNull(staged).exists())
        assertExistingFontOnly()
    }

    @Test
    fun validatorCancellationPropagatesAndCleansItsStagedFont() {
        val cancelled = CancellationException("Platform validation was cancelled")

        val failure =
            runCatching {
                FontLibrary.importStream(filesDir, "Poster.ttf", { ByteArrayInputStream(replacement) }) { throw cancelled }
            }.exceptionOrNull()

        assertSame(cancelled, failure)
        assertExistingFontOnly()
    }

    @Test
    fun fatalValidationFailureIsNotReportedAsAnInvalidFont() {
        val fatal = LinkageError("Platform font loader could not initialize")

        val failure =
            runCatching {
                FontLibrary.importStream(filesDir, "Poster.ttf", { ByteArrayInputStream(replacement) }) { throw fatal }
            }.exceptionOrNull()

        assertSame(fatal, failure)
        assertExistingFontOnly()
    }

    private fun import(
        openStream: () -> InputStream?,
        checkActive: () -> Unit = {},
    ): FontLibrary.ImportResult =
        FontLibrary.importStream(filesDir, "Poster.ttf", openStream, checkActive) {
            error("Rejected or cancelled provider input must not reach platform validation")
        }

    private fun assertExistingFontOnly() {
        assertArrayEquals(original, installed.readBytes())
        assertEquals(listOf("Poster.ttf"), installed.parentFile.listFiles()?.map { it.name })
    }

    private fun fontBytes(seed: Int): ByteArray =
        ByteArray(36).apply {
            this[1] = 1
            this[5] = 1
            this[lastIndex] = seed.toByte()
        }
}
