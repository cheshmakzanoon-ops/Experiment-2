package com.artflow.studio.presentation.ui.screens.gallery

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.artflow.studio.R
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryIncomingImportTest {
    @Test
    fun incomingCollectionAwaitsTheCurrentProviderInsteadOfLaunchingDetachedImports() =
        runTest {
            val fixture = Fixture(this)
            val release = CountDownLatch(1)
            val uris =
                listOf("first.bin", "second.bin").map { name ->
                    fixture.uri(name, ByteArray(64)).also { uri ->
                        `when`(fixture.resolver.openInputStream(uri)).thenAnswer {
                            object : ByteArrayInputStream(ByteArray(64)) {
                                override fun read(
                                    bytes: ByteArray,
                                    offset: Int,
                                    length: Int,
                                ): Int {
                                    check(release.await(5, TimeUnit.SECONDS)) { "The provider was never released" }
                                    return super.read(bytes, offset, length)
                                }
                            }
                        }
                    }
                }
            val imports = fixture.imports()
            val collection = launch { uris.asFlow().collect { imports.open(it) } }
            try {
                runCurrent()
                assertFalse("The collector must remain suspended until the current import finishes", collection.isCompleted)
            } finally {
                release.countDown()
                collection.join()
            }
        }

    @Test
    fun rejectedIncomingFileReportsItsErrorAndContinuesToTheNextFile() =
        runTest {
            val fixture = Fixture(this)
            val invalid = fixture.uri("broken.psd", "not a PSD".toByteArray())
            val unsupported = fixture.uri("next.bin", ByteArray(64))
            val imports = fixture.imports()
            listOf(invalid, unsupported).asFlow().takeWhile { !imports.open(it) }.collect()
            assertEquals(2, fixture.messages.size)
            assertTrue(fixture.messages.first().contains("Photoshop"))
            assertEquals("Unsupported file", fixture.messages.last())
        }

    @Test
    fun successfulArtworkHandoffLeavesLaterIncomingFilesQueuedForTheNextGalleryVisit() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture(this)
            val pixels = PixelBuffer.filled(2, 2, 0xFF123456.toInt())
            val source = PsdCodec.write(2, 2, emptyList(), pixels)
            val first = fixture.uri("first.psd", source)
            val second = fixture.uri("next.bin", ByteArray(64))
            val queue = Channel<Uri>(Channel.UNLIMITED)
            queue.trySend(first)
            queue.trySend(second)
            queue.close()
            val opened = mutableListOf<Long>()
            `when`(fixture.viewModel.createImportedProject("Imported PSD", 2, 2, 72)).thenReturn(PROJECT_ID)
            val imports = fixture.imports { opened += it }
            try {
                queue.receiveAsFlow().takeWhile { !imports.open(it) }.collect()
                assertEquals(listOf(PROJECT_ID), opened)
                assertSame(second, queue.tryReceive().getOrThrow())
                assertArrayEquals(source, requireNotNull(PendingImports.takePsd(PROJECT_ID)))
                assertTrue(fixture.messages.isEmpty())
            } finally {
                PendingImports.discardProject(PROJECT_ID)
                fixture.viewModel.viewModelScope.cancel()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun cancellationClosesTheActiveProviderAndLeavesLaterFilesQueued() =
        runTest {
            val fixture = Fixture(this)
            val first = fixture.uri("first.bin", ByteArray(64))
            val second = fixture.uri("next.bin", ByteArray(64))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = AtomicBoolean()
            `when`(fixture.resolver.openInputStream(first)).thenAnswer {
                object : ByteArrayInputStream(ByteArray(64)) {
                    override fun read(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS)) { "The provider was never released" }
                        return super.read(bytes, offset, length)
                    }

                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }
            }
            val queue = Channel<Uri>(Channel.UNLIMITED)
            queue.trySend(first)
            queue.trySend(second)
            queue.close()
            val imports = fixture.imports()
            val collection = launch { queue.receiveAsFlow().takeWhile { !imports.open(it) }.collect() }
            try {
                runCurrent()
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                collection.cancel()
            } finally {
                release.countDown()
                collection.join()
            }
            assertTrue(collection.isCancelled)
            assertTrue(closed.get())
            assertSame(second, queue.tryReceive().getOrThrow())
            assertTrue(fixture.messages.isEmpty())
        }

    private class Fixture(
        val scope: CoroutineScope,
    ) {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val viewModel = mock(MainViewModel::class.java)
        val messages = mutableListOf<String>()

        init {
            `when`(context.contentResolver).thenReturn(resolver)
            `when`(context.getString(R.string.incoming_file_unknown)).thenReturn("Unsupported file")
            `when`(context.getString(R.string.galleryscreen_file_could_not_be_read)).thenReturn("File could not be read")
        }

        fun uri(
            name: String,
            bytes: ByteArray,
        ): Uri =
            mock(Uri::class.java).also { uri ->
                `when`(uri.lastPathSegment).thenReturn(name)
                `when`(resolver.openInputStream(uri)).thenAnswer { ByteArrayInputStream(bytes) }
            }

        fun imports(open: (Long) -> Unit = { error("Unsupported files must not navigate") }) =
            GalleryImports(context, scope, viewModel, { messages += it }, { error("No brush expected") }, open)
    }

    private companion object {
        const val PROJECT_ID = 917_001L
    }
}
