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
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY or OsConstants.O_DIRECTORY or OsConstants.O_NOFOLLOW, 0)
            withCleanup({ Os.close(descriptor) }) { Os.fsync(descriptor) }
        } catch (failure: ErrnoException) {
            throw IOException("Could not save brush texture storage", failure)
        }
    }
}
