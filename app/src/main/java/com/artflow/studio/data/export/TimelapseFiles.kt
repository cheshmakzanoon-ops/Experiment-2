package com.artflow.studio.data.export

import com.artflow.studio.data.local.StorageFileTree
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Operations stay inside the already validated artwork directory and never follow recording links. */
internal object TimelapseFiles {
    const val DIRECTORY = "timelapse"
    private const val PAUSED_FILE = "timelapse.paused"

    fun directory(project: File): File {
        requireProject(project)
        return File(project, DIRECTORY).apply {
            StorageFileTree.requireDirectory(this, project, "Could not open the time-lapse recording directory")
        }
    }

    fun recordingAllowed(project: File): Boolean {
        requireProject(project)
        // A dangling link or unknown marker type still means paused, never permission to record.
        return Files.notExists(File(project, PAUSED_FILE).toPath(), LinkOption.NOFOLLOW_LINKS)
    }

    fun pauseFile(project: File): File {
        requireProject(project)
        return File(project, PAUSED_FILE).apply {
            StorageFileTree.requireUnlinked(this, project)
            if (Files.exists(toPath(), LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("The time-lapse recording preference is not a regular file")
            }
        }
    }

    fun removePause(project: File) {
        Files.deleteIfExists(pauseFile(project).toPath())
    }

    fun clear(project: File) {
        requireProject(project)
        StorageFileTree.delete(File(project, DIRECTORY), project)
    }

    private fun requireProject(project: File) {
        StorageFileTree.requireUnlinked(project, project)
        if (!Files.isDirectory(project.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("The artwork directory is unavailable")
        }
    }
}
