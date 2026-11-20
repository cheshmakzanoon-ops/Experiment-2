package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.File

class FontDependencyIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var sourceDir: File
    private lateinit var targetDir: File
    private lateinit var source: ProjectStorage
    private lateinit var target: ProjectStorage
    private val pixels = byteArrayOf(1, 4, 9, 16)

    @Before
    fun setUp() {
        sourceDir = temporary.newFolder("source")
        targetDir = temporary.newFolder("target")
        source = storage(sourceDir)
        target = storage(targetDir)
    }

    @Test
    fun differentFontWithTheSameFilenameIsReportedWithoutChangingRasterOrLocalFont() =
        runBlocking {
            installFont(sourceDir, 1)
            installFont(targetDir, 2)
            val files = artworkFiles()
            val dependency = ArtflowPackage.decodeDependencies(files.getValue(ArtflowPackage.DEPENDENCIES)).fonts.single()
            assertEquals(FontLibrary.fingerprint(FAMILY, sourceDir), dependency.sha256)
            assertNotNull(dependency.sha256)
            assertFalse(files.keys.any { it.startsWith("fonts/") })

            val outcome = import(2L, files)

            assertEquals(listOf("Different font: Poster"), outcome.missingFonts)
            assertRasterAndFont(2L, outcome.document, 2)
        }

    @Test
    fun identicalFontContentsSatisfyTheFingerprint() =
        runBlocking {
            installFont(sourceDir, 1)
            installFont(targetDir, 1)
            val outcome = import(2L, artworkFiles())
            assertTrue(outcome.missingFonts.isEmpty())
            assertRasterAndFont(2L, outcome.document, 1)
        }

    @Test
    fun legacyOrAbsentDependencyMetadataNeverInfersSourceIdentityFromTheLocalFont() =
        runBlocking {
            installFont(sourceDir, 1)
            installFont(targetDir, 2)
            val files = artworkFiles()
            val legacy = """{"fonts":[{"id":"font:Poster.ttf","label":"Poster"}]}""".toByteArray()
            val variants = listOf(files + (ArtflowPackage.DEPENDENCIES to legacy), files - ArtflowPackage.DEPENDENCIES)
            variants.forEachIndexed { index, entries ->
                val id = index + 2L
                val outcome = import(id, entries)
                assertEquals(listOf("Unverified font: Poster"), outcome.missingFonts)
                assertRasterAndFont(id, outcome.document, 2)
            }
        }

    @Test
    fun unavailableSourceFontExportsAnUnknownIdentityAndRetainsMissingFontNotice() =
        runBlocking {
            val files = artworkFiles()
            val dependency = ArtflowPackage.decodeDependencies(files.getValue(ArtflowPackage.DEPENDENCIES)).fonts.single()
            assertNull(dependency.sha256)
            assertEquals(listOf("Poster"), import(2L, files).missingFonts)

            installFont(targetDir, 2)
            val outcome = import(3L, files)
            assertEquals(listOf("Unverified font: Poster"), outcome.missingFonts)
            assertRasterAndFont(3L, outcome.document, 2)
        }

    @Test
    fun invalidFontFingerprintRejectsTheImportAndPreservesExistingFontBytes() =
        runBlocking {
            installFont(sourceDir, 1)
            installFont(targetDir, 2)
            val invalid = """{"fonts":[{"id":"font:Poster.ttf","label":"Poster","sha256":"invalid"}]}""".toByteArray()
            val files = artworkFiles() + (ArtflowPackage.DEPENDENCIES to invalid)
            val failure = runCatching { import(2L, files) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertFalse(target.projectDirectoryExists(2L))
            assertArrayEquals(fontBytes(2), File(targetDir, "fonts/Poster.ttf").readBytes())
        }

    @Test
    fun conflictingLegacyAliasesCannotHideADeclaredFingerprint() =
        runBlocking {
            installFont(sourceDir, 1)
            installFont(targetDir, 2)
            val original = artworkFiles()
            val fonts =
                listOf(
                    ArtflowPackage.PackagedFont(FAMILY, "Poster", "a".repeat(64)),
                    ArtflowPackage.PackagedFont("file:/old-device/fonts/Poster.ttf", "Poster", "b".repeat(64)),
                )
            for ((index, ordered) in listOf(fonts, fonts.reversed()).withIndex()) {
                val entries =
                    original + (ArtflowPackage.DEPENDENCIES to ArtflowPackage.encodeDependencies(ArtflowPackage.Dependencies(ordered)))
                val id = index + 2L
                assertTrue(runCatching { import(id, entries) }.exceptionOrNull() is IllegalArgumentException)
                assertFalse(target.projectDirectoryExists(id))
                assertArrayEquals(fontBytes(2), File(targetDir, "fonts/Poster.ttf").readBytes())
            }
        }

    @Test
    fun anOutsideLegacyReferenceCannotBeVerifiedByAnUnrelatedLocalFont() =
        runBlocking {
            installFont(targetDir, 1)
            val outside = File(temporary.newFolder("legacy"), "Poster.ttf").apply { writeBytes(fontBytes(3)) }
            val documentFamily = "file:${outside.absolutePath}"
            val files = artworkFiles(documentFamily)
            val alleged = ArtflowPackage.PackagedFont(FAMILY, "Poster", FontLibrary.fingerprint(FAMILY, targetDir))
            val entries =
                files + (ArtflowPackage.DEPENDENCIES to ArtflowPackage.encodeDependencies(ArtflowPackage.Dependencies(listOf(alleged))))
            val outcome = import(2L, entries)
            assertEquals(listOf("Unverified font: Poster"), outcome.missingFonts)
            assertEquals(
                documentFamily,
                outcome.document.layers
                    .single()
                    .textContent
                    ?.style
                    ?.fontFamily,
            )
            assertArrayEquals(fontBytes(3), outside.readBytes())
        }

    @Test
    fun unreferencedManifestEntriesDoNotCreateDependencyNotices() =
        runBlocking {
            val files = artworkFiles("sans-serif")
            val unused =
                ArtflowPackage.Dependencies(
                    fonts = List(128) { ArtflowPackage.PackagedFont("font:Unused$it.ttf", "Unused $it", "a".repeat(64)) },
                    grainIds = List(128) { "custom-${it.toString(16)}" },
                )
            val outcome = import(2L, files + (ArtflowPackage.DEPENDENCIES to ArtflowPackage.encodeDependencies(unused)))

            assertTrue(outcome.missingFonts.isEmpty())
            assertTrue(outcome.missingGrains.isEmpty())
            assertArrayEquals(
                pixels,
                target
                    .resolve(
                        2L,
                        requireNotNull(
                            outcome.document.layers
                                .single()
                                .rasterFile,
                        ),
                    ).readBytes(),
            )
        }

    @Test
    fun unrelatedDeclarationsCannotGiveAReferencedFontAKnownIdentity() =
        runBlocking {
            installFont(sourceDir, 1)
            installFont(targetDir, 1)
            val files = artworkFiles()
            val unused =
                ArtflowPackage.Dependencies(
                    fonts =
                        listOf(
                            ArtflowPackage.PackagedFont("font:Unrelated.ttf", "Unrelated", FontLibrary.fingerprint(FAMILY, targetDir)),
                        ),
                    grainIds = listOf("custom-a"),
                )
            val outcome = import(2L, files + (ArtflowPackage.DEPENDENCIES to ArtflowPackage.encodeDependencies(unused)))

            assertEquals(listOf("Unverified font: Poster"), outcome.missingFonts)
            assertTrue(outcome.missingGrains.isEmpty())
            assertRasterAndFont(2L, outcome.document, 1)
        }

    @Test
    fun unreferencedConflictingAliasesStillRejectAnAmbiguousManifest() =
        runBlocking {
            val files = artworkFiles("sans-serif")
            val conflicting =
                ArtflowPackage.Dependencies(
                    fonts =
                        listOf(
                            ArtflowPackage.PackagedFont(FAMILY, "Poster", "a".repeat(64)),
                            ArtflowPackage.PackagedFont("file:/old-device/Poster.ttf", "Poster", "b".repeat(64)),
                        ),
                )
            val entries = files + (ArtflowPackage.DEPENDENCIES to ArtflowPackage.encodeDependencies(conflicting))

            assertTrue(runCatching { import(2L, entries) }.exceptionOrNull() is IllegalArgumentException)
            assertFalse(target.projectDirectoryExists(2L))
        }

    private suspend fun artworkFiles(family: String = FAMILY): Map<String, ByteArray> {
        val raster = source.writeRaster(1L, 1L, 1, pixels)
        source.saveDocument(
            1L,
            CanvasDocument(
                width = 32,
                height = 32,
                dpi = 72,
                backgroundColor = -1,
                activeLayerId = 1L,
                nextLayerId = 2L,
                layers =
                    listOf(
                        Layer(
                            id = 1L,
                            name = "Text",
                            index = 0,
                            rasterFile = raster,
                            textContent = TextLayerContent("Poster", TextLayout.TextStyle(fontFamily = family), -1, 2f, 4f),
                        ),
                    ),
            ),
        )
        return source.packageFiles(1L)
    }

    private suspend fun import(
        id: Long,
        files: Map<String, ByteArray>,
    ): ProjectStorage.ImportedPackageOutcome {
        val archive = File(temporary.root, "$id.artflow")
        ArtflowPackage.writeToFile(
            "Font identity",
            archive,
            files.map { (path, bytes) ->
                ArtflowPackage.EntrySource(path) { ByteArrayInputStream(bytes) }
            },
        )
        return ArtflowPackage.openFile(archive).use { target.importProjectStream(id, it) }
    }

    private fun assertRasterAndFont(
        id: Long,
        document: CanvasDocument,
        fontSeed: Int,
    ) {
        val layer = document.layers.single()
        assertEquals(FAMILY, layer.textContent?.style?.fontFamily)
        assertArrayEquals(pixels, target.resolve(id, requireNotNull(layer.rasterFile)).readBytes())
        assertArrayEquals(fontBytes(fontSeed), File(targetDir, "fonts/Poster.ttf").readBytes())
    }

    private fun storage(files: File): ProjectStorage {
        val context = mock(Context::class.java).apply { `when`(filesDir).thenReturn(files) }
        return ProjectStorage(context)
    }

    private fun installFont(
        files: File,
        seed: Int,
    ) {
        val result = FontLibrary.importStream(files, "Poster.ttf", { ByteArrayInputStream(fontBytes(seed)) }) { true }
        assertTrue(result is FontLibrary.ImportResult.Success)
    }

    private fun fontBytes(seed: Int): ByteArray =
        ByteArray(36).apply {
            this[1] = 1
            this[5] = 1
            this[lastIndex] = seed.toByte()
        }

    private companion object {
        const val FAMILY = "font:Poster.ttf"
    }
}
