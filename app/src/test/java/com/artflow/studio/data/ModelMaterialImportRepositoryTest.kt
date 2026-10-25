package com.artflow.studio.data

import android.content.Context
import com.artflow.studio.core.color.ColorProfile
import com.artflow.studio.core.color.ColorProfiles
import com.artflow.studio.core.export.PngCodec
import com.artflow.studio.core.export.TestPng
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.three.MaterialChannel
import com.artflow.studio.core.three.ModelPackage
import com.artflow.studio.core.three.PreparedModelImport
import com.artflow.studio.data.export.ModelImports
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException

/** A real document and filesystem exercise the staged publication and single layer-history commit. */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelMaterialImportRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context)
        installRepository()
    }

    private fun installRepository(
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        publisher: (ModelImports.StagedModel) -> Unit = { it.commit() },
    ) {
        if (::repository.isInitialized) repository.dispose()
        repository = CanvasRepositoryImpl(storage, dispatcher, publisher)
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun typedPlanesCommitOnceKeepExactPixelsAndUndoRedoTogether() =
        runTest {
            fixture()
            val before = snapshot()
            val prepared = ModelImportFixture.prepared()
            val expected = ModelImportFixture.planes(prepared).mapValues { it.value.pixels.copyOf() }
            assertTrue(ModelImports.install(repository, prepared))
            val added = repository.getAllLayers().filter { layer -> before.layers.none { it.id == layer.id } }
            assertEquals(MaterialChannel.entries.toSet(), added.map { it.materialChannel }.toSet())
            assertEquals(3, added.size)
            assertEquals(before.undo + 1, repository.undoDepth)
            assertEquals(before.history + 1, repository.historyMark)
            assertEquals(MaterialChannel.COLOR, repository.getActiveLayer()?.materialChannel)
            assertEquals(prepared.objText, repository.loadModel())
            assertArrayEquals(before.selection, requireNotNull(repository.selection()).coverage)
            // Mutating the transfer payload must not alter either live pixels or a later redo.
            ModelImportFixture.planes(prepared).values.forEach { it.pixels.fill(0) }
            for (layer in added) assertArrayEquals(expected.getValue(layer.materialChannel), pixels(layer.id))
            assertPixels(before)
            assertTrue(repository.undo())
            assertEquals(before.layers, repository.getAllLayers())
            assertEquals(before.active, repository.getActiveLayerId())
            assertArrayEquals(before.selection, requireNotNull(repository.selection()).coverage)
            assertTrue(repository.redo())
            assertEquals(MaterialChannel.COLOR, repository.getActiveLayer()?.materialChannel)
            for (layer in added) assertArrayEquals(expected.getValue(layer.materialChannel), pixels(layer.id))
            assertNoStagingFiles()
        }

    @Test
    fun importingIntoP3ConvertsOnlyColorAndKeepsScalarBytesExact() =
        runTest {
            fixture()
            assertTrue(repository.setColorProfile(ColorProfile.DISPLAY_P3))
            val prepared = ModelImportFixture.prepared()
            val oldIds = repository.getAllLayers().map { it.id }.toSet()
            assertTrue(ModelImports.install(repository, prepared))
            val added = repository.getAllLayers().filter { it.id !in oldIds }.associateBy { it.materialChannel }
            val color = ColorProfiles.convert(requireNotNull(prepared.color), ColorProfile.SRGB, ColorProfile.DISPLAY_P3)
            assertArrayEquals(color.pixels, pixels(added.getValue(MaterialChannel.COLOR).id))
            assertArrayEquals(requireNotNull(prepared.metallic).pixels, pixels(added.getValue(MaterialChannel.METALLIC).id))
            assertArrayEquals(requireNotNull(prepared.roughness).pixels, pixels(added.getValue(MaterialChannel.ROUGHNESS).id))
        }

    @Test
    fun insufficientCapacityRefusesAllPlanesAndPreservesExistingModelAndRedo() =
        runTest {
            fixture()
            while (repository.getAllLayers().size < ProjectStorage.MAX_LAYERS - 2) repository.addLayer("Keep")
            leaveRedo()
            val before = snapshot()
            assertFalse(ModelImports.install(repository, ModelImportFixture.prepared()))
            assertUnchanged(before)
        }

    @Test
    fun mismatchedDimensionsRefuseBeforeChangingAnyExistingState() =
        runTest {
            fixture()
            leaveRedo()
            val before = snapshot()
            assertFalse(ModelImports.install(repository, ModelImportFixture.prepared(width = 3)))
            assertUnchanged(before)
        }

    @Test
    fun mutatedScalarPayloadIsRejectedBeforePublishingTheModelOrAnyLayer() =
        runTest {
            fixture()
            val before = snapshot()
            val prepared = ModelImportFixture.prepared()
            requireNotNull(prepared.metallic).pixels[0] = 0xFFFF0000.toInt()
            val failure = runCatching { ModelImports.install(repository, prepared) }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertUnchanged(before)
        }

    @Test
    fun modelPublicationFailureLeavesExistingBytesLayersSelectionAndHistoryIntact() =
        runTest {
            var reachedPublication = false
            installRepository(publisher = {
                reachedPublication = true
                assertTrue(
                    storage
                        .projectDir(repository.projectId())
                        .listFiles()
                        .orEmpty()
                        .any { it.name.endsWith(".stage") },
                )
                throw IOException("Injected model publication failure")
            })
            fixture()
            leaveRedo()
            val before = snapshot()
            val failure = runCatching { ModelImports.install(repository, ModelImportFixture.prepared()) }.exceptionOrNull()
            assertTrue(reachedPublication)
            assertTrue(failure is IOException)
            assertUnchanged(before)
        }

    @Test
    fun aSelectionChangeWhilePreparingRefusesWithoutOverwritingItOrTheModel() =
        runTest {
            installRepository(StandardTestDispatcher(testScheduler))
            fixture()
            val importing = async(start = CoroutineStart.UNDISPATCHED) { ModelImports.install(repository, ModelImportFixture.prepared()) }
            assertFalse(importing.isCompleted)
            repository.setSelection(SelectionMask(4, 2, ByteArray(8) { 64 }))
            val afterSelection = snapshot()
            runCurrent()
            assertFalse(importing.await())
            assertUnchanged(afterSelection)
        }

    @Test
    fun anActiveChannelChangeWhilePreparingRefusesWithoutChangingEitherDestination() =
        runTest {
            installRepository(StandardTestDispatcher(testScheduler))
            fixture()
            val importing = async(start = CoroutineStart.UNDISPATCHED) { ModelImports.install(repository, ModelImportFixture.prepared()) }
            assertFalse(importing.isCompleted)
            val color = repository.getAllLayers().first { it.materialChannel == MaterialChannel.COLOR }
            assertTrue(repository.setActiveLayer(color.id))
            val afterChannel = snapshot()
            runCurrent()
            assertFalse(importing.await())
            assertUnchanged(afterChannel)
        }

    @Test
    fun anExistingRasterGestureIsNotCancelledByARefusedImport() =
        runTest {
            fixture()
            val session = requireNotNull(repository.beginRasterEdit(repository.getActiveLayerId()))
            session.buffer.pixels.fill(0xFF808080.toInt())
            val before = snapshot()
            assertFalse(ModelImports.install(repository, ModelImportFixture.prepared()))
            assertUnchanged(before)
            assertTrue(repository.commitRasterEdit(session, "Keep the existing gesture"))
            assertArrayEquals(IntArray(8) { 0xFF808080.toInt() }, pixels(session.layerId))
        }

    @Test
    fun cancellingPreparationDoesNotPublishTheModelOrMutateHistory() =
        runTest {
            installRepository(StandardTestDispatcher(testScheduler))
            fixture()
            val before = snapshot()
            val importing = async(start = CoroutineStart.UNDISPATCHED) { ModelImports.install(repository, ModelImportFixture.prepared()) }
            assertFalse(importing.isCompleted)
            importing.cancel()
            runCurrent()
            assertTrue(importing.isCancelled)
            assertUnchanged(before)
        }

    @Test
    fun aChangedOrPartiallyRemovedReceiptRefusesToDuplicateOrOverwriteAnyPlane() =
        runTest {
            for (removePlane in listOf(false, true)) {
                fixture()
                val projectId = repository.projectId()
                val prepared = ModelImportFixture.prepared()
                PendingImports.putModelBundle(projectId, prepared)
                try {
                    assertTrue(ModelImports.install(repository, prepared))
                    val metal = repository.getAllLayers().last { it.materialChannel == MaterialChannel.METALLIC }
                    if (removePlane) {
                        assertTrue(repository.removeLayer(metal.id))
                    } else {
                        assertTrue(repository.setLayerPixels(metal.id, PixelBuffer.filled(4, 2, -1), "Keep this later edit"))
                    }
                    val before = snapshot()
                    val failure = runCatching { ModelImports.install(repository, prepared) }.exceptionOrNull()
                    assertTrue(failure is IllegalStateException)
                    assertUnchanged(before)
                } finally {
                    PendingImports.completeModelBundle(projectId, prepared)
                }
            }
        }

    @Test
    fun absentImportedPlanesDoNotPermitRetryToOverwriteAChangedModel() =
        runTest {
            fixture()
            val projectId = repository.projectId()
            val prepared = ModelImportFixture.prepared()
            PendingImports.putModelBundle(projectId, prepared)
            try {
                assertTrue(ModelImports.install(repository, prepared))
                assertTrue(repository.undo())
                val changed = "# Preserve this replacement\n${ModelImportFixture.OBJ}"
                storage.resolve(projectId, ProjectStorage.MODEL_NAME).writeText(changed)
                val before = snapshot()
                val failure = runCatching { ModelImports.install(repository, prepared) }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertUnchanged(before)
            } finally {
                PendingImports.completeModelBundle(projectId, prepared)
            }
        }

    @Test
    fun legacyColorOnlyAndUntexturedModelsKeepTheirExistingImportBehavior() =
        runTest {
            fixture()
            val before = repository.undoDepth
            val prepared = ModelImportFixture.prepared(materials = false)
            val count = repository.getAllLayers().size
            assertTrue(ModelImports.install(repository, prepared))
            assertEquals(count + 1, repository.getAllLayers().size)
            assertEquals(before + 1, repository.undoDepth)
            assertEquals(MaterialChannel.COLOR, repository.getActiveLayer()?.materialChannel)
            assertArrayEquals(requireNotNull(prepared.color).pixels, pixels(repository.getActiveLayerId()))
            repository.createCanvas(4, 4, 72)
            val plain = PreparedModelImport.prepare(ModelPackage.Contents(ModelImportFixture.OBJ), { null }, defaultSize = 4)
            val layers = repository.getAllLayers()
            val undo = repository.undoDepth
            assertTrue(ModelImports.install(repository, plain))
            assertEquals(layers, repository.getAllLayers())
            assertEquals(undo, repository.undoDepth)
            assertEquals(MaterialChannel.COLOR, repository.getActiveLayer()?.materialChannel)
            assertEquals(ModelImportFixture.OBJ, repository.loadModel())
        }

    private suspend fun fixture() {
        repository.createCanvas(4, 2, 72)
        repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(4, 2, 0xFF204080.toInt()), "Existing color")
        val metal = requireNotNull(repository.selectMaterialChannel(MaterialChannel.METALLIC))
        repository.setLayerPixels(metal, PixelBuffer.filled(4, 2, 0xFF202020.toInt()), "Existing metallic")
        repository.setSelection(SelectionMask(4, 2, byteArrayOf(-1, -128, 0, 64, -1, -128, 0, 64)))
        storage.resolve(repository.projectId(), ProjectStorage.MODEL_NAME).writeText("# Keep this model\n${ModelImportFixture.OBJ}")
    }

    private suspend fun leaveRedo() {
        assertTrue(repository.setLayerName(repository.getActiveLayerId(), "Temporary name"))
        assertTrue(repository.undo())
        assertTrue(repository.redoDepth > 0)
    }

    private suspend fun pixels(id: Long): IntArray = requireNotNull(repository.layerPixels(id)).pixels

    private suspend fun snapshot(): DocumentSnapshot =
        DocumentSnapshot(
            repository.getAllLayers(),
            repository.getAllLayers().associate { it.id to repository.layerPixels(it.id)?.pixels?.copyOf() },
            repository.getActiveLayerId(),
            requireNotNull(repository.selection()).coverage.copyOf(),
            repository.historyMark,
            repository.undoDepth,
            repository.redoDepth,
            repository.contentRevision,
            storage.resolve(repository.projectId(), ProjectStorage.MODEL_NAME).readBytes(),
        )

    private suspend fun assertUnchanged(before: DocumentSnapshot) {
        assertEquals(before.layers, repository.getAllLayers())
        assertEquals(before.active, repository.getActiveLayerId())
        assertArrayEquals(before.selection, requireNotNull(repository.selection()).coverage)
        assertEquals(before.history, repository.historyMark)
        assertEquals(before.undo, repository.undoDepth)
        assertEquals(before.redo, repository.redoDepth)
        assertEquals(before.revision, repository.contentRevision)
        assertArrayEquals(before.model, storage.resolve(repository.projectId(), ProjectStorage.MODEL_NAME).readBytes())
        assertPixels(before)
        assertNoStagingFiles()
    }

    private suspend fun assertPixels(before: DocumentSnapshot) {
        for ((id, values) in before.pixels) {
            val actual = repository.layerPixels(id)?.pixels
            if (values == null) assertNull(actual) else assertArrayEquals(values, actual)
        }
    }

    private fun assertNoStagingFiles() {
        assertFalse(
            storage
                .projectDir(repository.projectId())
                .listFiles()
                .orEmpty()
                .any { it.name.startsWith(".model-import-") },
        )
    }

    private class DocumentSnapshot(
        val layers: List<Layer>,
        val pixels: Map<Long, IntArray?>,
        val active: Long,
        val selection: ByteArray,
        val history: Long,
        val undo: Int,
        val redo: Int,
        val revision: Long,
        val model: ByteArray,
    )
}

internal object ModelImportFixture {
    val OBJ =
        """
        mtllib artwork.mtl
        v 0 0 0
        v 1 0 0
        v 0 1 0
        vt 0 0
        vt 1 0
        vt 0 1
        usemtl Painted
        f 1/1 2/2 3/3
        """.trimIndent()

    fun prepared(
        width: Int = 4,
        height: Int = 2,
        materials: Boolean = true,
    ): PreparedModelImport {
        val color = PixelBuffer(width, height, IntArray(width * height) { 0xFF104000.toInt() or (it * 23) })
        val metal = PixelBuffer(width, height, IntArray(width * height) { MaterialChannel.gray(it.toFloat() / (width * height)) })
        val rough = PixelBuffer(width, height, IntArray(width * height) { MaterialChannel.gray(1f - it.toFloat() / (width * height)) })
        val scalars = if (materials) ModelPackage.ScalarMaps(PngCodec.encode(metal), PngCodec.encode(rough)) else null
        return PreparedModelImport.prepare(ModelPackage.Contents(OBJ, PngCodec.encode(color), scalarMaps = scalars), TestPng::decode)
    }

    fun planes(prepared: PreparedModelImport): Map<MaterialChannel, PixelBuffer> =
        listOfNotNull(
            prepared.color?.let { MaterialChannel.COLOR to it },
            prepared.metallic?.let { MaterialChannel.METALLIC to it },
            prepared.roughness?.let { MaterialChannel.ROUGHNESS to it },
        ).toMap()
}
