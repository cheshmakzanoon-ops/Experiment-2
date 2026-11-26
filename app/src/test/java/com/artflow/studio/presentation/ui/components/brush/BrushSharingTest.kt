package com.artflow.studio.presentation.ui.components.brush

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.data.local.BrushFiles
import com.artflow.studio.data.local.ShareExportFiles
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.stubbing.Answer
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
            var prepared: File? = null
            var encoded: ByteArray? = null
            // Inspect the complete payload at the handoff boundary, then refuse it. A failed
            // chooser owns no URI and must not leave its unpublished copy behind.
            `when`(context.packageName).thenAnswer {
                val file = sharedFiles().single { it.name == "Inkset.artbrush" }
                prepared = file
                encoded = file.readBytes()
                error("No share provider")
            }
            assertFalse(shareBrushes(context, "Ink/set", brushes))
            val file = requireNotNull(prepared)
            assertEquals("Inkset.artbrush", file.name)
            val (restored, images) = BrushFiles.decode(requireNotNull(encoded).toString(Charsets.UTF_8))
            assertEquals(brushes, restored)
            assertArrayEquals(shape.values, images.getValue(shapeId).values)
            assertArrayEquals(grain.values, images.getValue(grainId).values)
            assertFalse(file.exists())
            assertFalse(requireNotNull(file.parentFile).exists())
            assertTrue(File(temporary.root, "exports/brushes").listFiles()!!.isEmpty())
        }

    @Test
    fun everyShareOwnsAnImmutableFileSoAnEarlierDeliveryIsNeverRewritten() =
        runTest {
            val delivered = mutableListOf<File>()
            val chooser = mock(Intent::class.java)
            val provider =
                Answer { invocation ->
                    check(invocation.method.name == "getUriForFile")
                    assertSame(context, invocation.getArgument<Context>(0))
                    assertEquals("com.artflow.studio.fileprovider", invocation.getArgument<String>(1))
                    delivered += invocation.getArgument<File>(2).also { assertTrue(it.isFile) }
                    mock(Uri::class.java)
                }
            val platformChooser =
                Answer { invocation ->
                    check(invocation.method.name == "createChooser")
                    assertNotNull(invocation.getArgument<Intent>(0))
                    assertEquals("Share brush", invocation.getArgument<CharSequence>(1))
                    chooser
                }
            // These mocks allow the successful platform-return boundary on this JVM. Real URI
            // grants and receiver reads remain covered by ShareContentIdentityDeviceTest.
            mockStatic(FileProvider::class.java, provider).use {
                mockStatic(Intent::class.java, platformChooser).use {
                    val first = listOf(SavedBrush("a", "Ink", BrushParams(size = 10f)))
                    val second = listOf(SavedBrush("b", "Ink", BrushParams(size = 40f)))
                    assertTrue(shareBrushes(context, "Ink", first))
                    val earlier = delivered.single()
                    val earlierBytes = earlier.readBytes()
                    assertEquals(first, BrushFiles.decode(earlier.readText()).first)
                    assertTrue(shareBrushes(context, "Ink", second))
                    val files = sharedFiles()
                    assertEquals(2, files.size)
                    assertEquals("Each share needs its own path", 2, files.map { it.absolutePath }.distinct().size)
                    assertEquals(files.toSet(), delivered.toSet())
                    assertEquals(second, BrushFiles.decode(delivered.last().readText()).first)
                    assertArrayEquals("An earlier share must not change", earlierBytes, earlier.readBytes())

                    `when`(context.packageName).thenThrow(IllegalStateException("Share provider became unavailable"))
                    assertFalse(shareBrushes(context, "Ink", first))
                    assertEquals("A later refusal removes only its own copy", files.toSet(), sharedFiles().toSet())
                    assertArrayEquals(earlierBytes, earlier.readBytes())
                    verify(context, times(2)).startActivity(chooser)
                }
            }
        }

    private fun sharedFiles(): List<File> =
        File(temporary.root, "exports/brushes")
            .walkTopDown()
            .filter { it.isFile && it.name != ShareExportFiles.OWNER_MARKER && !it.name.endsWith(".partial") }
            .toList()

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
