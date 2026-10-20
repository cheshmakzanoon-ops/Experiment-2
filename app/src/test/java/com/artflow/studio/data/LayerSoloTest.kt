package com.artflow.studio.data

import android.content.Context
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

/** Touch and hold a visibility box: show only that layer, then bring the others back. */
@OptIn(ExperimentalCoroutinesApi::class)
class LayerSoloTest {
    private lateinit var repository: CanvasRepositoryImpl

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)))
    }

    @After
    fun cleanup() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    private fun visibility(): Map<Long, Boolean> =
        repository
            .getAllLayers()
            .filterNot { it.isInternal }
            .associate { it.id to it.isVisible }

    @Test
    fun soloHidesTheOthersAndTogglingAgainRestoresThem() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val first = repository.getActiveLayerId()
            val second = repository.addLayer(null, null, 1f).id
            val third = repository.addLayer(null, null, 1f).id
            assertTrue(repository.setLayerVisibility(third, false))
            val before = visibility()

            assertTrue(repository.toggleLayerSolo(second))
            val solo = visibility()
            assertTrue(solo.getValue(second))
            assertFalse(solo.getValue(first))
            assertFalse(solo.getValue(third))

            assertTrue(repository.toggleLayerSolo(second))
            assertEquals(before, visibility())

            assertTrue(repository.undo())
            assertEquals(solo, visibility())
        }

    @Test
    fun soloKeepsTheLayersGroupVisible() =
        runTest {
            repository.createCanvas(8, 6, 72)
            val outside = repository.getActiveLayerId()
            val inside = repository.addLayer(null, null, 1f).id
            val group = repository.groupLayers(listOf(inside))!!

            assertTrue(repository.toggleLayerSolo(inside))
            val solo = visibility()
            assertTrue(solo.getValue(inside))
            assertTrue(solo.getValue(group))
            assertFalse(solo.getValue(outside))
        }
}
