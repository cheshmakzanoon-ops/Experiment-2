package com.artflow.studio

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.artflow.studio.presentation.ui.IncomingFileQueue
import com.artflow.studio.presentation.ui.IncomingFiles
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingFilesTest {
    private val one = Uri.parse("content://example.test/one.procreate")
    private val two = Uri.parse("content://example.test/two.png")

    @Test
    fun openWithAndEditWithCarryTheDataUri() {
        assertEquals(listOf(one), IncomingFiles.uris(Intent(Intent.ACTION_VIEW, one)))
        assertEquals(listOf(two), IncomingFiles.uris(Intent(Intent.ACTION_EDIT, two)))
    }

    @Test
    fun shareCarriesTheStreamExtra() {
        val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, one)
        assertEquals(listOf(one), IncomingFiles.uris(send))
        val many = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(one, two))
        assertEquals(listOf(one, two), IncomingFiles.uris(many))
    }

    @Test
    fun otherIntentsCarryNothing() {
        assertTrue(IncomingFiles.uris(null).isEmpty())
        assertTrue(IncomingFiles.uris(Intent(Intent.ACTION_MAIN)).isEmpty())
        assertTrue(IncomingFiles.uris(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "just words")).isEmpty())
        assertTrue(IncomingFiles.uris(Intent(Intent.ACTION_VIEW)).isEmpty())
    }

    @Test
    fun oversizedSharedBatchKeepsOnlyTheAcceptedUrisAndReportsTheRest() =
        runBlocking {
            val queue = IncomingFileQueue()
            val uris = ArrayList((0 until IncomingFiles.MAX_PENDING_FILES + 3).map { Uri.parse("content://example.test/$it.png") })
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)

            assertEquals(IncomingFiles.Receipt(IncomingFiles.MAX_PENDING_FILES, 3), queue.offer(intent))
            val accepted = withTimeout(5_000) { queue.files.take(IncomingFiles.MAX_PENDING_FILES).toList() }
            assertEquals(uris.take(IncomingFiles.MAX_PENDING_FILES), accepted)
        }

    @Test
    fun newIntentsCannotReplaceFilesWaitingForTheGallery() =
        runBlocking {
            val queue = IncomingFileQueue(capacity = 2)
            assertEquals(IncomingFiles.Receipt(1, 0), queue.offer(Intent(Intent.ACTION_VIEW, one)))
            assertEquals(IncomingFiles.Receipt(1, 0), queue.offer(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, two)))

            repeat(50) {
                val refused = Intent(Intent.ACTION_EDIT, Uri.parse("content://example.test/refused-$it.png"))
                assertEquals(IncomingFiles.Receipt(0, 1), queue.offer(refused))
            }
            assertEquals(listOf(one, two), withTimeout(5_000) { queue.files.take(2).toList() })
            assertEquals(IncomingFiles.Receipt(1, 0), queue.offer(Intent(Intent.ACTION_VIEW, one)))
            assertEquals(listOf(one), withTimeout(5_000) { queue.files.take(1).toList() })
        }
}
