package com.artflow.studio.data.export

import com.artflow.studio.data.local.StorageFileTree
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** Owns disk copies while capture or Clear recording can remove the original JPEGs. */
internal class TimelapseFrameSnapshot(
    private val parent: File,
) : Closeable {
    private val directory = Files.createTempDirectory(parent.toPath(), "timelapse_snap_").toFile()

    /** The caller holds the recorder lock while copying and owns this scope until reading ends. */
    fun copyFrames(
        files: List<File>,
        checkActive: () -> Unit,
    ): List<File> {
        checkActive()
        return files.mapIndexed { index, file ->
            val copy = File(directory, String.format(Locale.ROOT, "%06d.jpg", index))
            file.copyTo(copy)
            checkActive()
            copy
        }
    }

    override fun close() {
        // The parent was validated by ProjectStorage at acquisition. Refuse a replaced parent,
        // and never follow links inside this owned directory into another recording or export.
        StorageFileTree.requireUnlinked(parent, parent)
        StorageFileTree.delete(directory, parent)
    }
}
