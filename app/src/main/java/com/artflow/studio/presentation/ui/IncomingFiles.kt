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
    private val queue = Channel<Uri>(Channel.UNLIMITED)
    val files: Flow<Uri> = queue.receiveAsFlow()

    /** Queues the file or files [intent] carries; returns how many there were. */
    fun offer(intent: Intent?): Int {
        val uris = uris(intent)
        uris.forEach { queue.trySend(it) }
        return uris.size
    }

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
