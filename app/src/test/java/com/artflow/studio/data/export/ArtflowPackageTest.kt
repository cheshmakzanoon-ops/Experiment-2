package com.artflow.studio.data.export

import com.artflow.studio.core.render.CustomGrains
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArtflowPackageTest {
    @get:Rule val temporary = TemporaryFolder()

    private val files =
        mapOf(
            "canvas.artflow" to "{}".toByteArray(),
            "layers/3/v2-ab12.png" to byteArrayOf(1, 2, 3),
            "thumbnail.png" to byteArrayOf(9),
            "model.obj" to "v 0 0 0".toByteArray(),
        )

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun anArtworkSurvivesTheRoundTrip() {
        val read = ArtflowPackage.read(ArtflowPackage.write("Sunset", files))
        assertEquals("Sunset", read.name)
        assertEquals(files.keys, read.files.keys)
        files.forEach { (path, bytes) -> assertArrayEquals(bytes, read.files.getValue(path)) }
    }

    @Test fun onlyKnownFilesInsideTheProjectAreAccepted() {
        val marker = "artflow-package.json" to """{"format":"artflow-package","version":1,"name":"x"}""".toByteArray()
        val document = "canvas.artflow" to "{}".toByteArray()
        assertEquals("x", ArtflowPackage.read(zip(marker, document)).name)
        listOf(
            "../evil.png",
            "layers/1/../../x.png",
            "/abs.png",
            "layers/a/v1.png",
            "notes.txt",
            "fonts/LicensedFont.ttf",
            "fonts/OpenFont.otf",
            "grains/bad.grain",
            "grains/custom-xyz.grain",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.read(zip(marker, document, path to byteArrayOf(1))) }
        }
        assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.read(zip(document)) }
        assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.read(zip(marker)) }
        assertThrows(IllegalArgumentException::class.java) { ArtflowPackage.read(ArtflowPackage.write("Big", files), maxTotalBytes = 4) }
    }

    @Test fun streamingWriteAndOpenSupportsDependenciesAndGrainsWithoutFontBinaries() {
        val grainId = "custom-deadbeef1234"
        val grainBytes = ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it and 0xFF).toByte() }
        val deps =
            ArtflowPackage.Dependencies(
                fonts = listOf(ArtflowPackage.PackagedFont(id = "font:Display.ttf", label = "Display")),
                grainIds = listOf(grainId),
            )
        val depsBytes = ArtflowPackage.encodeDependencies(deps)
        val destination = File(temporary.newFolder("out"), "streamed.artflow")
        val sources =
            listOf(
                ArtflowPackage.EntrySource(ArtflowPackage.DOCUMENT) { ByteArrayInputStream("{}".toByteArray()) },
                ArtflowPackage.EntrySource("layers/1/v1.png") { ByteArrayInputStream(byteArrayOf(5, 6, 7)) },
                ArtflowPackage.EntrySource(ArtflowPackage.grainEntryPath(grainId)) { ByteArrayInputStream(grainBytes) },
                ArtflowPackage.EntrySource(ArtflowPackage.DEPENDENCIES) { ByteArrayInputStream(depsBytes) },
            )

        ArtflowPackage.writeToFile("Portable", destination, sources)

        ArtflowPackage.openFile(destination).use { contents ->
            assertEquals("Portable", contents.name)
            assertFalse("fonts/" in contents.entries.joinToString())
            assertEquals("{}", contents.readBytes(ArtflowPackage.DOCUMENT).decodeToString())
            assertArrayEquals(byteArrayOf(5, 6, 7), contents.readBytes("layers/1/v1.png"))
            assertArrayEquals(grainBytes, contents.readBytes("grains/$grainId.grain"))
            assertEquals(deps, ArtflowPackage.decodeDependencies(contents.readBytes(ArtflowPackage.DEPENDENCIES)))
        }
    }

    @Test fun streamingOpenEnforcesCumulativeSizeLimitAndRejectsCorruptZip() {
        val destination = File(temporary.newFolder("limited"), "big.artflow")
        ArtflowPackage.writeToFile(
            "Big",
            destination,
            files.map { (path, bytes) -> ArtflowPackage.EntrySource(path) { ByteArrayInputStream(bytes) } },
        )

        ArtflowPackage.openFile(destination, maxTotalBytes = 8).use { contents ->
            assertEquals("{}", contents.readBytes(ArtflowPackage.DOCUMENT).decodeToString())
            assertThrows(IllegalArgumentException::class.java) {
                contents.readBytes("model.obj")
            }
        }

        val corrupt = File(temporary.newFolder("corrupt"), "damaged.artflow")
        corrupt.writeBytes(destination.readBytes().copyOf(24))
        assertThrows(IllegalArgumentException::class.java) {
            ArtflowPackage.openFile(corrupt)
        }
    }
}
