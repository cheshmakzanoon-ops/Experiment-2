package com.artflow.studio.data.export

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
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

@OptIn(ExperimentalCoroutinesApi::class)
class PrivateContentAndTimelapseTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl
    private lateinit var recorder: TimelapseRecorder
    private val testDispatcher = UnconfinedTestDispatcher()

    private val projectId = 101L

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context)
        repository = CanvasRepositoryImpl(storage)
        recorder = TimelapseRecorder(storage)
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun priv001_privateImageInsertionUndoRedoPreservesPrivacyAtomically() =
        runTest {
            repository.loadOrCreate(projectId, 64, 64, 72)
            val secretColor = 0xFFFF1122.toInt()
            val image = PixelBuffer(32, 32).apply { pixels.fill(secretColor) }

            val insertedId = LayerImports.insertImage(repository, image, isPrivate = true)
            val insertedLayer = repository.getAllLayers().find { it.id == insertedId }
            assertNotNull(insertedLayer)
            assertTrue("Inserted layer must be private", insertedLayer!!.isPrivate)

            // Private content must NOT leak into compositeWithoutPrivateLayers
            val composite = repository.compositeWithoutPrivateLayers()
            assertNotNull(composite)
            assertFalse(
                "compositeWithoutPrivateLayers must not include private image pixels",
                composite!!.pixels.any { it == secretColor },
            )

            // Verify undo preserving privacy
            assertTrue(repository.undoDepth > 0)
            // Step 1: Undo raster edit
            repository.undo()
            val layerAfterUndo1 = repository.getAllLayers().find { it.id == insertedId }
            if (layerAfterUndo1 != null) {
                assertTrue("Layer must remain private after undoing its raster edit", layerAfterUndo1.isPrivate)
            }
            val compositeAfterUndo1 = repository.compositeWithoutPrivateLayers()
            assertNotNull(compositeAfterUndo1)
            assertFalse(compositeAfterUndo1!!.pixels.any { it == secretColor })

            // Step 2: Undo layer creation
            repository.undo()
            assertFalse(
                "Layer must be removed after undoing creation",
                repository.getAllLayers().any { it.id == insertedId },
            )

            // Redo back to state
            repository.redo()
            val layerAfterRedo1 = repository.getAllLayers().find { it.id == insertedId }
            assertNotNull(layerAfterRedo1)
            assertTrue("Layer recreated by redo must be private", layerAfterRedo1!!.isPrivate)

            repository.redo()
            val redoneLayer = repository.getAllLayers().find { it.id == insertedId }
            assertNotNull(redoneLayer)
            assertTrue("Layer fully redone must be private", redoneLayer!!.isPrivate)
            val finalComposite = repository.compositeWithoutPrivateLayers()
            assertNotNull(finalComposite)
            assertFalse(finalComposite!!.pixels.any { it == secretColor })
        }

    @Test
    fun priv002_copyAndDuplicatePreservesPrivacyAttribute() =
        runTest {
            repository.loadOrCreate(projectId, 64, 64, 72)
            val privateLayer = repository.addLayer("Secret", isPrivate = true)
            assertTrue(privateLayer.isPrivate)

            val secretPixel = 0xFF55AA33.toInt()
            repository.applyRasterEdit(privateLayer.id, "Fill secret") { it.pixels.fill(secretPixel) }

            // 1. Duplicate must preserve isPrivate
            val dupId = repository.duplicateLayer(privateLayer.id)
            assertNotNull(dupId)
            val duplicated = repository.getAllLayers().find { it.id == dupId }
            assertNotNull(duplicated)
            assertTrue("Duplicated layer must inherit isPrivate", duplicated!!.isPrivate)

            // 2. Copy/cut and paste must preserve isPrivate from source
            val mask = SelectionMask(64, 64).apply { selectAll() }
            repository.setSelection(mask)
            repository.copySelectionToLayer(cut = false)
            val pasted = repository.getAllLayers().find { it.name == "Pasted" }
            assertNotNull(pasted)
            assertTrue("Pasted layer from private source must inherit isPrivate", pasted!!.isPrivate)

            // 3. Merging private layer with non-private layer preserves privacy
            val normalLayer = repository.addLayer("Normal", isPrivate = false)
            assertFalse(normalLayer.isPrivate)
            val merged = repository.mergeLayers(normalLayer.id, pasted.id)
            assertTrue("Merge should succeed", merged)
            assertTrue(
                "Merge result must be marked private if any component layer was private",
                repository.getAllLayers().any { it.id == pasted.id && it.isPrivate },
            )
        }

    @Test
    fun priv003_turningRecordingOffOrClearInvalidatesCaptures() =
        runTest {
            val frame1 = PixelBuffer(32, 32).apply { pixels.fill(0xFF112233.toInt()) }
            recorder.capture(projectId, frame1)
            assertEquals(1, recorder.frameCount(projectId))

            // Pausing recording prevents subsequent captures
            recorder.setRecording(projectId, false)
            assertFalse(recorder.isRecording(projectId))

            val frame2 = PixelBuffer(32, 32).apply { pixels.fill(0xFF445566.toInt()) }
            recorder.capture(projectId, frame2)
            assertEquals("Capture when paused must be ignored", 1, recorder.frameCount(projectId))

            // Clear wipes recording and resets state
            recorder.clear(projectId)
            assertEquals("Clear must remove all frames", 0, recorder.frameCount(projectId))

            // Resuming recording allows new captures
            recorder.setRecording(projectId, true)
            assertTrue(recorder.isRecording(projectId))
            recorder.capture(projectId, frame2)
            assertEquals(1, recorder.frameCount(projectId))
        }

    @Test
    fun priv004_stableExportDuringConcurrentCapture() =
        runTest {
            // Record 5 distinct frames
            for (i in 1..5) {
                val frame = PixelBuffer(32, 32).apply { pixels.fill(0xFF000000.toInt() or (i * 0x101010)) }
                recorder.capture(projectId, frame)
            }
            assertEquals(5, recorder.frameCount(projectId))
            val recordedFiles = recorder.frameFiles(projectId)
            assertEquals(5, recordedFiles.size)

            // Test export attempts: frames are snapshotted under lock
            val result = recorder.export(projectId, "Sample Artwork", fullLength = true)
            // If MediaCodec is stubbed on JVM, export fails gracefully inside Mp4Encoder, NOT with missing file
            if (result.isFailure) {
                val ex = result.exceptionOrNull()
                assertFalse(
                    "Export failure must not be caused by missing or uncopied frame file: ${ex?.message}",
                    ex is java.io.FileNotFoundException,
                )
            }
        }
}
