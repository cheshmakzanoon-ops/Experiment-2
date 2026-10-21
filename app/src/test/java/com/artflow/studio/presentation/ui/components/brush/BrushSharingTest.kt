package com.artflow.studio.presentation.ui.components.brush

import android.content.Context
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.local.BrushFiles
import com.artflow.studio.domain.model.brush.BrushParams
import com.artflow.studio.domain.model.brush.SavedBrush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BrushSharingTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        context = mock(Context::class.java)
        `when`(context.filesDir).thenReturn(temporary.root)
        `when`(context.packageName).thenReturn("com.artflow.studio")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun setFileKeepsBrushNamesAndEmbeddedImagesWhenSharingIsUnavailable() =
        runTest {
            val shape = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it % 229).toByte() })
            val grain = CustomGrains.Tile(CustomGrains.TILE, ByteArray(CustomGrains.TILE * CustomGrains.TILE) { (it % 193).toByte() })
            val shapeId = CustomGrains.idFor(shape)
            val grainId = CustomGrains.idFor(grain)
            CustomGrains.register(shapeId, shape)
            CustomGrains.register(grainId, grain)
            val brushes =
                listOf(
                    SavedBrush("shape", "Shaped ink", BrushParams(shapeId = shapeId)),
                    SavedBrush("grain", "Textured ink", BrushParams(textureId = grainId, blendTexture = true)),
                )
            // Refuse the platform handoff after writing the file; no activity is launched in this test.
            `when`(context.packageManager).thenThrow(IllegalStateException("No share provider"))
            assertFalse(shareBrushes(context, "Ink/set", brushes))
            val file = File(temporary.root, "exports/brushes/Inkset.artbrush")
            assertTrue(file.isFile)
            val (restored, images) = BrushFiles.decode(file.readText())
            assertEquals(brushes, restored)
            assertArrayEquals(shape.values, images.getValue(shapeId).values)
            assertArrayEquals(grain.values, images.getValue(grainId).values)
        }

    @Test
    fun cancellationPropagatesInsteadOfBecomingAShareFailure() =
        runTest {
            `when`(context.filesDir).thenThrow(CancellationException("Share cancelled"))
            try {
                shareBrushes(context, "Ink", listOf(SavedBrush("ink", "Ink", BrushParams())))
                fail("Cancellation must propagate")
            } catch (cancelled: CancellationException) {
                assertEquals("Share cancelled", cancelled.message)
            }
        }

    @Test
    fun fileWriteFailureIsReportedWithoutLaunchingTheChooser() =
        runTest {
            `when`(context.filesDir).thenReturn(temporary.newFile("not-a-directory"))
            assertFalse(shareBrushes(context, "Ink", listOf(SavedBrush("ink", "Ink", BrushParams()))))
        }

    @Test
    fun emptySetDoesNotTouchStorageOrLaunchAnActivity() =
        runTest {
            val untouched = mock(Context::class.java)
            assertFalse(shareBrushes(untouched, "Empty", emptyList()))
            verifyNoInteractions(untouched)
        }
}
