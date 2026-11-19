package com.artflow.studio.data.repository.canvas

import android.content.Context
import com.artflow.studio.core.pixels.SelectionMask
import com.artflow.studio.core.pixels.SelectionMaskCodec
import com.artflow.studio.data.local.CanvasDocument
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.domain.model.layer.Layer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasSavedSelectionStorageBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var storage: ProjectStorage
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.newFolder("files"))
        storage = ProjectStorage(context, dispatcher)
        // One 16x16 slot fits its worst-case encoded allowance; two do not. All fit memory.
        val budget = CanvasRasterBudget(512L * 1024, savedSelectionMetadataBytes = 400)
        repository = CanvasRepositoryImpl(storage, dispatcher, rasterBudget = budget)
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun rejectedAdditionPreservesSlotsAndRevision() =
        runTest {
            repository.loadOrCreate(PROJECT, SIZE, SIZE, 72)
            repository.setSavedSelections(listOf(mask(1)))
            val revision = repository.contentRevision

            val failure = runCatching { repository.setSavedSelections(listOf(mask(1), mask(2))) }.exceptionOrNull()

            assertEquals(CanvasRasterBudget.SAVED_SELECTION_METADATA_LIMIT_MESSAGE, failure?.message)
            assertEquals(listOf(1), values())
            assertEquals(revision, repository.contentRevision)
        }

    @Test
    fun readableLegacySlotsCanBeOpenedLeftUnchangedAndRemovedAboveTheNewAllowance() =
        runTest {
            openLegacySlots()
            val revision = repository.contentRevision
            repository.setSavedSelections(listOf(mask(1), mask(2), mask(3)))
            assertEquals(revision, repository.contentRevision)

            repository.setSavedSelections(listOf(mask(1), mask(2)))
            assertEquals(listOf(1, 2), values())
            assertTrue(repository.contentRevision > revision)
            repository.setSavedSelections(listOf(mask(2)))
            assertEquals(listOf(2), values())
            repository.setSavedSelections(emptyList())
            assertTrue(repository.savedSelections().isEmpty())
        }

    @Test
    fun smallerUnrelatedOrDuplicatedReplacementCannotMasqueradeAsLegacyRemoval() =
        runTest {
            openLegacySlots()
            val revision = repository.contentRevision
            for (replacement in listOf(listOf(mask(8), mask(9)), listOf(mask(1), mask(1)), listOf(mask(1), mask(2), mask(8)))) {
                val failure = runCatching { repository.setSavedSelections(replacement) }.exceptionOrNull()
                assertEquals(CanvasRasterBudget.SAVED_SELECTION_METADATA_LIMIT_MESSAGE, failure?.message)
                assertEquals(listOf(1, 2, 3), values())
                assertEquals(revision, repository.contentRevision)
            }
        }

    private suspend fun openLegacySlots() {
        storage.saveDocument(
            PROJECT,
            CanvasDocument(
                width = SIZE,
                height = SIZE,
                dpi = 72,
                backgroundColor = -1,
                activeLayerId = 1L,
                nextLayerId = 2L,
                layers = listOf(Layer(1L, "Legacy", 0)),
                savedSelections = (1..3).map { SelectionMaskCodec.encode(mask(it)) },
            ),
        )
        assertNotNull(repository.loadCanvas(PROJECT))
        assertEquals(listOf(1, 2, 3), values())
    }

    private fun values(): List<Int> = repository.savedSelections().map { it.coverageAt(0, 0) }

    private fun mask(value: Int): SelectionMask = SelectionMask(SIZE, SIZE).apply { coverage.fill(value.toByte()) }

    private companion object {
        const val PROJECT = 72L
        const val SIZE = 16
    }
}
