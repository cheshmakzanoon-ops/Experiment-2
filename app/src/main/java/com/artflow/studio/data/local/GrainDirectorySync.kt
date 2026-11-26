package com.artflow.studio.data.local

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.artflow.studio.core.lifecycle.withCleanup
import java.io.File
import java.io.IOException

/** Completes imported texture name publication before a brush or artwork can reference it. */
internal object GrainDirectorySync {
    fun sync(directory: File) {
        try {
            // O_DIRECTORY is not part of Android's public SDK. Check the opened descriptor,
            // retaining no-follow semantics and avoiding a wait if an unexpected FIFO is opened.
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
            withCleanup({ Os.close(descriptor) }) {
                if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                    throw IOException("Brush texture storage is not a directory")
                }
                Os.fsync(descriptor)
            }
        } catch (failure: ErrnoException) {
            throw IOException("Could not save brush texture storage", failure)
        }
    }
}
