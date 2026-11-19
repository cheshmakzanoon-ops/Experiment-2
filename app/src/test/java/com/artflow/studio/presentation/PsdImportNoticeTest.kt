package com.artflow.studio.presentation

import android.content.Context
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.repository.canvas.CanvasRepositoryImpl
import com.artflow.studio.presentation.ui.viewmodel.CanvasImportController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** The real picker/repository path publishes limitations with success, never with a rejected import. */
@OptIn(ExperimentalCoroutinesApi::class)
class PsdImportNoticeTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: CanvasRepositoryImpl
    private val messages = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = CanvasRepositoryImpl(ProjectStorage(mock(Context::class.java)), dispatcher)
    }

    @After
    fun tearDown() {
        repository.dispose()
        Dispatchers.resetMain()
    }

    @Test
    fun cmykProfileAndSpotLimitationsAreVisibleAfterOneUndoablePublication() =
        runTest {
            repository.createCanvas(2, 1, 72)
            assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(2, 1, BLUE), "Existing artwork"))
            val before = requireNotNull(repository.compositeBuffer()).pixels.copyOf()
            val originalLayerIds = repository.getAllLayers().map { it.id }.toSet()
            val history = repository.undoDepth
            var imported = 0
            val controller = CanvasImportController(repository, backgroundScope, messages::add, { imported++ }, dispatcher)
            val request = controller.beginPsd()
            request.complete { cmykWithProfileAndSpot() }
            request.job!!.join()
            val layers = repository.getAllLayers()
            assertTrue("The preexisting artwork must keep its layers", layers.map { it.id }.containsAll(originalLayerIds))
            val added = layers.single { it.id !in originalLayerIds }
            assertEquals("Background", added.name)
            assertArrayEquals(intArrayOf(-1, 0xFF00FFFF.toInt()), requireNotNull(repository.layerPixels(added.id)).pixels)
            assertEquals(history + 1, repository.undoDepth)
            assertEquals(1, imported)
            val notice = messages.single()
            assertTrue(notice.startsWith("Imported 1 layer(s) from PSD"))
            assertTrue(notice.contains("CMYK colors were approximated"))
            assertTrue(notice.contains("ICC profile was not applied"))
            assertTrue(notice.contains("alpha/spot channels were not imported"))
            assertTrue(repository.undo())
            assertArrayEquals(before, requireNotNull(repository.compositeBuffer()).pixels)
            assertTrue(repository.redo())
            assertArrayEquals(intArrayOf(-1, 0xFF00FFFF.toInt()), requireNotNull(repository.layerPixels(added.id)).pixels)
        }

    @Test
    fun rejectedDestinationDoesNotEmitAnImportSuccessOrColorNotice() =
        runTest {
            repository.createCanvas(2, 1, 72)
            val started = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var imported = false
            val controller = CanvasImportController(repository, backgroundScope, messages::add, { imported = true }, dispatcher)
            val request = controller.beginPsd()
            request.complete {
                started.complete(Unit)
                resume.await()
                cmykWithProfileAndSpot()
            }
            started.await()
            repository.createCanvas(2, 1, 72)
            assertTrue(repository.setLayerPixels(repository.getActiveLayerId(), PixelBuffer.filled(2, 1, BLUE), "New artwork"))
            val history = repository.undoDepth
            resume.complete(Unit)
            request.job!!.join()
            assertFalse(imported)
            assertEquals(history, repository.undoDepth)
            assertArrayEquals(intArrayOf(BLUE, BLUE), requireNotNull(repository.compositeBuffer()).pixels)
            assertTrue(messages.single().contains("artwork changed"))
            assertFalse(messages.single().contains("CMYK"))
        }

    @Test
    fun malformedColorResourceCannotPublishPixelsOrAColorNotice() =
        runTest {
            repository.createCanvas(2, 1, 72)
            val before = requireNotNull(repository.compositeBuffer()).pixels.copyOf()
            val controller = CanvasImportController(repository, backgroundScope, messages::add, { error("Must not publish") }, dispatcher)
            val request = controller.beginPsd()
            request.complete { cmykWithProfileAndSpot().also { it[45] = 8 } } // Declared ICC payload exceeds its resource section.
            request.job!!.join()
            assertArrayEquals(before, requireNotNull(repository.compositeBuffer()).pixels)
            assertEquals(0, repository.undoDepth)
            assertEquals("This file is not a supported PSD document", messages.single())
        }

    @Test
    fun alternateLayerCompositeIsExplicitlyReportedAsFlattened() =
        runTest {
            repository.createCanvas(2, 1, 72)
            val controller = CanvasImportController(repository, backgroundScope, messages::add, {}, dispatcher)
            val request = controller.beginPsd()
            request.complete { withAlternateLayerRecord(cmykWithProfileAndSpot()) }
            request.job!!.join()
            assertEquals(2, repository.getAllLayers().size)
            assertEquals(1, repository.undoDepth)
            assertTrue(messages.single().contains("alternate layer records were imported as one flattened image"))
            assertArrayEquals(intArrayOf(-1, 0xFF00FFFF.toInt()), requireNotNull(repository.compositeBuffer()).pixels)
        }

    private fun withAlternateLayerRecord(original: ByteArray): ByteArray {
        val records =
            ByteArrayOutputStream()
                .also { bytes ->
                    DataOutputStream(bytes).use { out ->
                        out.writeShort(1)
                        out.write(ByteArray(16)) // Empty rectangle.
                        out.writeShort(0) // No layer channel payloads.
                        out.writeBytes("8BIMnorm")
                        out.writeByte(255)
                        out.write(ByteArray(3))
                        out.writeInt(12)
                        out.write(ByteArray(12)) // Empty mask, blend ranges and padded Pascal name.
                    }
                }.toByteArray()
        val section =
            ByteArrayOutputStream()
                .also { bytes ->
                    DataOutputStream(bytes).use { out ->
                        out.writeInt(0) // Empty primary layer info.
                        out.writeInt(0) // Empty global mask.
                        out.writeBytes("8BIMLayr")
                        out.writeInt(records.size)
                        out.write(records)
                    }
                }.toByteArray()
        return ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.write(original, 0, 48) // Header and the fixture's complete resource section.
                    out.writeInt(section.size)
                    out.write(section)
                    out.write(original, 52, original.size - 52) // Original merged-image bytes.
                }
            }.toByteArray()
    }

    /** Two raw CMYK pixels (white/cyan), one unrelated spot plane and a declared, unhandled ICC resource. */
    private fun cmykWithProfileAndSpot(): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeBytes("8BPS")
                    out.writeShort(1)
                    out.write(ByteArray(6))
                    out.writeShort(5)
                    out.writeInt(1)
                    out.writeInt(2)
                    out.writeShort(8)
                    out.writeShort(4)
                    out.writeInt(0)
                    out.writeInt(14)
                    out.writeBytes("8BIM")
                    out.writeShort(1039)
                    out.writeShort(0)
                    out.writeInt(1)
                    out.writeByte(42)
                    out.writeByte(0)
                    out.writeInt(0)
                    out.writeShort(0)
                    out.write(byteArrayOf(-1, 0, -1, -1, -1, -1, -1, -1, 0, -1))
                }
            }.toByteArray()

    private companion object {
        val BLUE = 0xFF0000FF.toInt()
    }
}
