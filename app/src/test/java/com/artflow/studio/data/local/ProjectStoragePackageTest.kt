package com.artflow.studio.data.local

import android.content.Context
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.text.TextLayerContent
import com.artflow.studio.core.text.TextLayout
import com.artflow.studio.data.export.ArtflowPackage
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.Stroke
import com.artflow.studio.domain.model.brush.StrokePoint
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.io.File

class ProjectStoragePackageTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var sourceDir: File
    private lateinit var targetDir: File
    private lateinit var sourceStorage: ProjectStorage
    private lateinit var targetStorage: ProjectStorage

    @Before
    fun setUp() {
        sourceDir = temporary.newFolder("deviceA")
        targetDir = temporary.newFolder("deviceB")
        val sourceContext = mock(Context::class.java).apply { `when`(filesDir).thenReturn(sourceDir) }
        val targetContext = mock(Context::class.java).apply { `when`(filesDir).thenReturn(targetDir) }
        sourceStorage = ProjectStorage(sourceContext)
        targetStorage = ProjectStorage(targetContext)
    }

    @Test
    fun `streaming package round trip restores custom grains and reports missing imported font`() =
        runBlocking {
            val grainTile = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { 0x5A })
            val grainId = GrainStorage.save(GrainStorage.directory(sourceDir), grainTile)
            FontLibrary.importStream(
                filesDir = sourceDir,
                displayName = "PortableScript.ttf",
                openStream = { ByteArrayInputStream(validSfntHeaderBytes()) },
                validator = { true },
            )

            val projectId = 7L
            val stroke =
                Stroke(
                    id = 1L,
                    points = listOf(StrokePoint(0f, 0f), StrokePoint(10f, 10f)),
                    brushParams = BrushParams(textureId = grainId),
                    layerId = 1L,
                    color = 0xFF000000.toInt(),
                )
            val rasterPath = sourceStorage.writeRaster(projectId, 1L, 1, byteArrayOf(1, 2, 3, 4))
            val doc =
                CanvasDocument(
                    width = 128,
                    height = 128,
                    dpi = 72,
                    backgroundColor = -1,
                    activeLayerId = 1L,
                    nextLayerId = 2L,
                    layers =
                        listOf(
                            Layer(
                                id = 1L,
                                name = "Text + Grain",
                                index = 0,
                                rasterFile = rasterPath,
                                strokes = listOf(stroke),
                                textContent =
                                    TextLayerContent(
                                        text = "Hello",
                                        style = TextLayout.TextStyle(fontFamily = "font:PortableScript.ttf"),
                                        color = 0xFF000000.toInt(),
                                        x = 12f,
                                        y = 24f,
                                    ),
                            ),
                        ),
                )
            sourceStorage.saveDocument(projectId, doc)

            val packageFile = File(temporary.newFolder("share"), "artwork.artflow")
            val sources = sourceStorage.packageSources(projectId)
            ArtflowPackage.writeToFile("Artwork", packageFile, sources)

            // Verify no font binary is bundled, while dependencies and grain asset are included.
            ArtflowPackage.openFile(packageFile).use { opened ->
                assertFalse(opened.entries.any { it.endsWith(".ttf") || it.endsWith(".otf") })
                assertTrue(ArtflowPackage.DEPENDENCIES in opened.entries)
                assertTrue(ArtflowPackage.grainEntryPath(grainId) in opened.entries)

                val outcome = targetStorage.importProjectStream(19L, opened)
                assertEquals(listOf("PortableScript"), outcome.missingFonts)
                assertTrue(outcome.missingGrains.isEmpty())
            }

            // Custom grain restored to deviceB's filesDir/grains, not projects/19/grains.
            val restoredGrain = GrainStorage.fileFor(GrainStorage.directory(targetDir), grainId)
            assertNotNull(restoredGrain)
            assertTrue(restoredGrain!!.isFile)
            assertArrayEquals(grainTile.values, restoredGrain.readBytes())
            assertFalse(File(targetStorage.projectDir(19L), "grains").exists())
            assertEquals("PortableScript.ttf", File(FontLibrary.PREFIX + "PortableScript.ttf").name.removePrefix("font:"))

            // When deviceB also has the font installed, re-importing reports zero missing fonts.
            FontLibrary.importStream(
                filesDir = targetDir,
                displayName = "PortableScript.ttf",
                openStream = { ByteArrayInputStream(validSfntHeaderBytes()) },
                validator = { true },
            )
            ArtflowPackage.openFile(packageFile).use { openedAgain ->
                val secondOutcome = targetStorage.importProjectStream(20L, openedAgain)
                assertTrue(secondOutcome.missingFonts.isEmpty())
                assertTrue(secondOutcome.missingGrains.isEmpty())
            }
        }

    private fun validSfntHeaderBytes(): ByteArray {
        val bytes = ByteArray(12 + 16)
        bytes[0] = 0x00
        bytes[1] = 0x01
        bytes[2] = 0x00
        bytes[3] = 0x00
        bytes[4] = 0x00
        bytes[5] = 0x01
        return bytes
    }
}
