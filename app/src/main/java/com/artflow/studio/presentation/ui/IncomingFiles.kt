package com.artflow.studio.presentation.ui

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Files other apps hand to ArtFlow (Open with, Edit with, Share to). The activity queues them here and the
 * gallery imports them one by one, so a file that arrives while an artwork is open waits until it is closed.
 */
object IncomingFiles {
    // The gallery deliberately stops receiving while an artwork is open. External callers can
    // keep sending intents during that time, so pending work must have a fixed upper bound.
    internal const val MAX_PENDING_FILES = 32
    private val queue = IncomingFileQueue()
    val files: Flow<Uri> = queue.files

    /** Accepted files remain in FIFO order; callers must tell the artist about rejected files. */
    data class Receipt(
        val accepted: Int,
        val rejected: Int,
    )

    fun offer(intent: Intent?): Receipt = queue.offer(intent)

    internal fun uris(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        return when (intent.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
    }
}

/** Bounded, non-dropping intake shared by the activity and the gallery's suspending collector. */
internal class IncomingFileQueue(
    capacity: Int = IncomingFiles.MAX_PENDING_FILES,
) {
    init {
        require(capacity in 1..IncomingFiles.MAX_PENDING_FILES)
    }

    // The default overflow policy is SUSPEND: trySend refuses a full queue rather than
    // evicting an earlier accepted file. A stopped collector does not close this channel.
    private val queue = Channel<Uri>(capacity)
    val files: Flow<Uri> = queue.receiveAsFlow()

    fun offer(intent: Intent?): IncomingFiles.Receipt = offer(IncomingFiles.uris(intent))

    fun offer(uris: List<Uri>): IncomingFiles.Receipt {
        var accepted = 0
        uris.forEach { uri ->
            if (queue.trySend(uri).isSuccess) accepted++
        }
        return IncomingFiles.Receipt(accepted = accepted, rejected = uris.size - accepted)
    }
}
