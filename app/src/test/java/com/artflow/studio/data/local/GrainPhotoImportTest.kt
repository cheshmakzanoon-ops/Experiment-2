package com.artflow.studio.data.local

import com.artflow.studio.core.render.CustomGrains
import com.artflow.studio.core.render.GrainRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Real texture files and registry claims survive only after the photo reaches its active control. */
@OptIn(ExperimentalCoroutinesApi::class)
class GrainPhotoImportTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anAcceptedPhotoIsAvailableDuringTheCallbackAndRetainedAfterItsOwnerCloses(): Unit =
        runBlocking {
            val fixture = Fixture()
            val image = tile(23)
            val id = CustomGrains.idFor(image)
            val accepted = mutableListOf<String>()
            val imported =
                GrainPhotoImport.importInto(fixture.directory, fixture.transaction(), { image }) { selected ->
                    assertArrayEquals(image.values, fixture.file(selected).readBytes())
                    assertArrayEquals(image.values, requireNotNull(fixture.registry.get(selected)).values)
                    accepted += selected
                }
            assertTrue(imported)
            assertEquals(listOf(id), accepted)
            assertArrayEquals(image.values, fixture.file(id).readBytes())
            assertArrayEquals(image.values, requireNotNull(fixture.registry.get(id)).values)
            fixture.assertNoLeakedReservation()
        }

    @Test
    fun leavingTheAttributeWhileDecodingDoesNotInstallTheUnacceptedPhoto(): Unit =
        runBlocking {
            val fixture = Fixture()
            val image = tile(31)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var accepted = false
            val importing =
                async(start = CoroutineStart.UNDISPATCHED) {
                    GrainPhotoImport.importInto(
                        fixture.directory,
                        fixture.transaction(),
                        decode = {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            image
                        },
                        accept = { accepted = true },
                    )
                }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                importing.cancel(CancellationException("The Grain attribute left composition"))
            } finally {
                release.countDown()
                importing.join()
            }
            assertTrue(importing.isCancelled)
            assertFalse(accepted)
            fixture.assertEmpty()
        }

    @Test
    fun cancelledUiDeliveryRollsBackTheAlreadyInstalledTexture(): Unit =
        runBlocking {
            val fixture = Fixture()
            val image = tile(43)
            val id = CustomGrains.idFor(image)
            val ui = QueuedUi()
            var accepted = false
            val importing =
                async(ui, start = CoroutineStart.UNDISPATCHED) {
                    GrainPhotoImport.importInto(fixture.directory, fixture.transaction(), { image }) { accepted = true }
                }
            val completedInstallation = ui.next()
            assertArrayEquals(image.values, fixture.file(id).readBytes())
            assertArrayEquals(image.values, requireNotNull(fixture.registry.get(id)).values)
            importing.cancel(CancellationException("Brush Studio closed before UI delivery"))
            completedInstallation.run()
            while (!importing.isCompleted) ui.next().run()

            assertTrue(importing.isCancelled)
            assertFalse(accepted)
            fixture.assertEmpty()
        }

    @Test
    fun replacingASlowPhotoKeepsOnlyTheNewlyAcceptedChoice(): Unit =
        runBlocking {
            val fixture = Fixture()
            val oldPhoto = tile(45)
            val newPhoto = tile(47)
            val oldEntered = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val accepted = mutableListOf<String>()
            val oldRequest =
                async(start = CoroutineStart.UNDISPATCHED) {
                    GrainPhotoImport.importInto(
                        fixture.directory,
                        fixture.transaction(),
                        decode = {
                            oldEntered.countDown()
                            check(releaseOld.await(10, TimeUnit.SECONDS))
                            oldPhoto
                        },
                        accept = accepted::add,
                    )
                }
            try {
                assertTrue(oldEntered.await(10, TimeUnit.SECONDS))
                // The real picker cancels its preceding Job when another non-null URI arrives.
                oldRequest.cancel()
                assertTrue(GrainPhotoImport.importInto(fixture.directory, fixture.transaction(), { newPhoto }, accepted::add))
                assertEquals(listOf(CustomGrains.idFor(newPhoto)), accepted)
            } finally {
                releaseOld.countDown()
                oldRequest.join()
            }
            assertTrue(oldRequest.isCancelled)
            assertEquals(listOf(CustomGrains.idFor(newPhoto)), accepted)
            assertEquals(listOf(CustomGrains.idFor(newPhoto)), fixture.registry.ids())
            assertFalse(fixture.file(CustomGrains.idFor(oldPhoto)).exists())
            assertArrayEquals(newPhoto.values, fixture.file(CustomGrains.idFor(newPhoto)).readBytes())
            fixture.assertNoLeakedReservation()
        }

    @Test
    fun failedAcceptanceRemovesOnlyItsNewTextureAndPreservesTheExistingAsset(): Unit =
        runBlocking {
            val fixture = Fixture()
            val existing = tile(53)
            val incoming = tile(61)
            val existingId = CustomGrains.idFor(existing)
            GrainStorage.save(fixture.directory, existingId, existing, fixture.registry)
            val expected = IOException("The control refused the result")
            val failure =
                runCatching {
                    GrainPhotoImport.importInto(fixture.directory, fixture.transaction(), { incoming }) { throw expected }
                }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(expected.message, failure?.message)
            assertEquals(listOf(existingId), fixture.registry.ids())
            assertSame(existing, fixture.registry.get(existingId))
            assertArrayEquals(existing.values, fixture.file(existingId).readBytes())
            assertFalse(fixture.file(CustomGrains.idFor(incoming)).exists())
            fixture.assertNoLeakedReservation()
        }

    @Test
    fun cancellationFromTheAcceptedCallbackCannotRemoveItsPublishedTexture(): Unit =
        runBlocking {
            val fixture = Fixture()
            val image = tile(71)
            val id = CustomGrains.idFor(image)
            var accepted: String? = null
            val importing =
                async {
                    val owner = currentCoroutineContext()
                    GrainPhotoImport.importInto(fixture.directory, fixture.transaction(), { image }) { selected ->
                        accepted = selected
                        owner.cancel(CancellationException("Navigation immediately after application"))
                    }
                }
            importing.join()
            assertTrue(importing.isCancelled)
            assertEquals(id, accepted)
            assertArrayEquals(image.values, fixture.file(id).readBytes())
            assertArrayEquals(image.values, requireNotNull(fixture.registry.get(id)).values)
            fixture.assertNoLeakedReservation()
        }

    @Test
    fun cancelledPhotoPreservesAnotherProvisionalClaimWithoutRetainingItPermanently(): Unit =
        runBlocking {
            val fixture = Fixture()
            val image = tile(83)
            val id = CustomGrains.idFor(image)
            fixture.transaction().use { other ->
                other.install(fixture.directory, mapOf(id to image))
                val ui = QueuedUi()
                val importing =
                    async(ui, start = CoroutineStart.UNDISPATCHED) {
                        GrainPhotoImport.importInto(fixture.directory, fixture.transaction(), { image }) {
                            throw AssertionError("A cancelled photo must not be applied")
                        }
                    }
                val completedInstallation = ui.next()
                importing.cancel()
                completedInstallation.run()
                while (!importing.isCompleted) ui.next().run()
                assertTrue(importing.isCancelled)
                assertSame(image, fixture.registry.get(id))
                assertArrayEquals(image.values, fixture.file(id).readBytes())
            }
            // The other import never committed. A cancelled photo must not have stolen its claim.
            fixture.assertEmpty()
        }

    @Test
    fun unavailableAndFailedDecodesDoNotPublishOrApplyAndFatalErrorsPropagate(): Unit =
        runBlocking {
            val fixture = Fixture()
            var accepted = false
            assertFalse(GrainPhotoImport.importInto(fixture.directory, fixture.transaction(), { null }) { accepted = true })
            for (expected in listOf(SecurityException("Provider revoked access"), AssertionError("Decoder failed"))) {
                val failure =
                    runCatching {
                        GrainPhotoImport.importInto(fixture.directory, fixture.transaction(), { throw expected }) { accepted = true }
                    }.exceptionOrNull()
                assertEquals(expected.javaClass, failure?.javaClass)
                assertEquals(expected.message, failure?.message)
            }
            assertFalse(accepted)
            fixture.assertEmpty()
        }

    private inner class Fixture {
        val directory = File(temporary.newFolder(), "grains")
        val registry = GrainRegistry(TILE_BYTES * 4L, TILE_BYTES * 8L)

        fun transaction() = GrainStorage.ImportTransaction(registry)

        fun file(id: String): File = requireNotNull(GrainStorage.fileFor(directory, id))

        fun assertEmpty() {
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            assertTrue(registry.ids().isEmpty())
            assertNoLeakedReservation()
        }

        fun assertNoLeakedReservation() {
            val sizes = (1..4).associate { "custom-888$it" to TILE_BYTES }
            // Reuse existing IDs to fill exactly the retained allowance while separately charging
            // the complete buffered allowance. A lost reservation makes this admission fail.
            val requested =
                registry.ids().associateWith { TILE_BYTES } + sizes.entries.take(4 - registry.ids().size).associate { it.toPair() }
            registry.reserve(requested, bufferedCopies = 2).close()
        }
    }

    private class QueuedUi : CoroutineDispatcher() {
        private val resumes = LinkedBlockingQueue<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            resumes.add(block)
        }

        fun next(): Runnable = requireNotNull(resumes.poll(10, TimeUnit.SECONDS)) { "The photo operation did not resume" }
    }

    private fun tile(seed: Int) = CustomGrains.Tile(CustomGrains.TILE, ByteArray(TILE_BYTES) { (seed + it * 31 + it / 257).toByte() })

    private companion object {
        const val TILE_BYTES = CustomGrains.TILE * CustomGrains.TILE
    }
}
