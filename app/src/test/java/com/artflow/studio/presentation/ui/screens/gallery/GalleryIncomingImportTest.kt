package com.artflow.studio.presentation.ui.screens.gallery

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.viewModelScope
import com.artflow.studio.R
import com.artflow.studio.core.export.PsdCodec
import com.artflow.studio.core.pixels.ImportPreparation
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.export.PendingImports
import com.artflow.studio.presentation.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    fun aBusyImportRejectsThePsdBeforeOpeningItsFullSourceOrCreatingAnArtwork() =
        runTest {
            val fixture = Fixture(this)
            val uri = fixture.uri("queued.psd", "8BPS".toByteArray())
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val worker = Executors.newSingleThreadExecutor()
            val admitted =
                worker.submit {
                    ImportPreparation.withAdmission {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(fixture.imports().open(uri))
                // Classification reads its small header; PSD admission must precede a second provider open.
                verify(fixture.resolver, times(1)).openInputStream(uri)
                verifyNoInteractions(fixture.viewModel)
                assertEquals(1, fixture.messages.size)
                assertTrue(fixture.messages.single().contains("try again"))
            } finally {
                release.countDown()
                try {
                    admitted.get(5, TimeUnit.SECONDS)
                } finally {
                    worker.shutdownNow()
                }
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

    @Test
    fun blockedDisplayNameQueryLeavesTheGalleryResponsiveAndCancellationKeepsTheNextFileQueued() = metadataCancellation(query = true)

    @Test
    fun blockedMimeLookupLeavesTheGalleryResponsiveAndCancellationKeepsTheNextFileQueued() = metadataCancellation(query = false)

    @Test
    fun providerMetadataCancellationPropagatesWithoutReadingOrPublishingTheFile() =
        runTest {
            for (query in listOf(true, false)) {
                val fixture = Fixture(this)
                val uri = fixture.uri("cancelled.psd", "8BPS".toByteArray())
                val cancellation = CancellationException("Provider metadata cancelled")
                if (query) {
                    `when`(fixture.resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null))
                        .thenThrow(cancellation)
                } else {
                    `when`(fixture.resolver.getType(uri)).thenThrow(cancellation)
                }

                val failure = runCatching { fixture.imports().open(uri) }.exceptionOrNull()

                assertTrue(failure is CancellationException)
                assertEquals(cancellation.message, failure?.message)
                verify(fixture.resolver, never()).openInputStream(uri)
                verifyNoInteractions(fixture.viewModel)
                assertTrue(fixture.messages.isEmpty())
            }
        }

    private fun metadataCancellation(query: Boolean) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val fixture = Fixture(this)
            val first = fixture.uri("first.psd", "8BPS".toByteArray())
            val second = fixture.uri("next.bin", ByteArray(64))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callerThread = Thread.currentThread()
            val providerThread = AtomicReference<Thread>()
            val cursor = mock(Cursor::class.java)
            `when`(cursor.moveToFirst()).thenReturn(true)
            `when`(cursor.getString(0)).thenReturn("first.psd")
            val awaitProvider = {
                providerThread.set(Thread.currentThread())
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "The metadata provider was never released" }
            }
            if (query) {
                `when`(fixture.resolver.query(first, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)).thenAnswer {
                    awaitProvider()
                    cursor
                }
            } else {
                `when`(fixture.resolver.getType(first)).thenAnswer {
                    awaitProvider()
                    "image/vnd.adobe.photoshop"
                }
            }
            val queue = Channel<Uri>(Channel.UNLIMITED)
            queue.trySend(first)
            queue.trySend(second)
            queue.close()
            val opened = mutableListOf<Long>()
            val imports = fixture.imports { opened += it }
            val collection = launch(Dispatchers.Main) { queue.receiveAsFlow().takeWhile { !imports.open(it) }.collect() }
            try {
                runCurrent()
                withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
                assertNotSame("Provider metadata must not run on the gallery dispatcher", callerThread, providerThread.get())
                assertFalse(collection.isCompleted)
                val responsive = AtomicBoolean()
                launch(Dispatchers.Main) { responsive.set(true) }
                runCurrent()
                assertTrue("The gallery must process another UI turn while metadata is blocked", responsive.get())
                collection.cancel()
            } finally {
                release.countDown()
                try {
                    collection.join()
                } finally {
                    Dispatchers.resetMain()
                }
            }
            assertTrue(collection.isCancelled)
            assertSame(second, queue.tryReceive().getOrThrow())
            verify(fixture.resolver, never()).openInputStream(first)
            if (query) {
                verify(cursor).close()
                verify(fixture.resolver, never()).getType(first)
            }
            verifyNoInteractions(fixture.viewModel)
            assertTrue(opened.isEmpty())
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
            GalleryImports(context, scope, viewModel, { messages += it }, { _, _ -> error("No brush expected") }, open)
    }

    private companion object {
        const val PROJECT_ID = 917_001L
    }
}
