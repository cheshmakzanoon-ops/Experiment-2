package com.artflow.studio.data.export

import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Records a time-lapse of the artwork: one downscaled JPEG snapshot after each committed edit,
 * stored beside the project, and replays it as an MP4 video.
 *
 * Frames are numbered files so recording survives process death; when a recording grows past
 * [MAX_FRAMES] every other frame is dropped, keeping the whole session at a coarser rate.
 */
@Singleton
class TimelapseRecorder
    @Inject
    constructor(
        private val storage: ProjectStorage,
    ) {
        private val mutex = Mutex()

        /** Whether edits are recorded; tests and future per-canvas settings can turn it off. */
        @Volatile
        var isEnabled: Boolean = true
        private var lastProjectId = 0L
        private var lastHash = 0

        private fun dir(projectId: Long): File = File(storage.projectDir(projectId), DIR_NAME).apply { mkdirs() }

        private fun frames(projectId: Long): List<File> =
            dir(projectId)
                .listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
                ?.sortedBy { it.name }
                .orEmpty()

        suspend fun frameCount(projectId: Long): Int = withContext(Dispatchers.IO) { frames(projectId).size }

        /** Recorded frames in order, for the in-app replay. */
        suspend fun frameFiles(projectId: Long): List<File> = mutex.withLock { withContext(Dispatchers.IO) { frames(projectId) } }

        /** Appends [composite] to the recording unless it is identical to the previous frame. */
        suspend fun capture(
            projectId: Long,
            composite: PixelBuffer,
        ) {
            withContext(Dispatchers.Default) {
                val frame = downscale(composite)
                val hash = frame.pixels.contentHashCode()
                mutex.withLock {
                    if (projectId == lastProjectId && hash == lastHash) return@withLock
                    val bytes = BitmapPixelBridge.toJpegBytes(frame, JPEG_QUALITY, 0xFFFFFFFF.toInt())
                    withContext(Dispatchers.IO) {
                        var existing = frames(projectId)
                        if (existing.size >= MAX_FRAMES) existing = decimate(existing)
                        val next = (existing.lastOrNull()?.nameWithoutExtension?.toIntOrNull() ?: -1) + 1
                        val file = File(dir(projectId), "%06d%s".format(next, EXTENSION))
                        storage.writeAtomically(file) { it.write(bytes) }
                    }
                    lastProjectId = projectId
                    lastHash = hash
                }
            }
        }

        suspend fun clear(projectId: Long) {
            mutex.withLock {
                withContext(Dispatchers.IO) { dir(projectId).deleteRecursively() }
                if (projectId == lastProjectId) lastHash = 0
            }
        }

        /** Encodes the recording as an MP4 replay that lasts at most about [TARGET_DURATION_MS]. */
        suspend fun export(
            projectId: Long,
            projectName: String,
        ): Result<ExportResult> =
            withContext(Dispatchers.Default) {
                runCatching {
                    val files = mutex.withLock { withContext(Dispatchers.IO) { frames(projectId) } }
                    require(files.isNotEmpty()) { "Nothing has been recorded yet — draw something first" }
                    val first = decode(files.first())
                    val width = max(16, (first.width + 1) and -2)
                    val height = max(16, (first.height + 1) and -2)
                    val perFrame = (TARGET_DURATION_MS / files.size).coerceIn(MIN_FRAME_MS, MAX_FRAME_MS)
                    val delays = List(files.size) { if (it == files.lastIndex) FINAL_HOLD_MS else perFrame }
                    val safeName = projectName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Artwork" }
                    val fileName = "$safeName timelapse.mp4"
                    val temp = File.createTempFile("timelapse", ".mp4", storage.exportsDir(projectId))
                    try {
                        val options = ExportOptions(format = ExportFormat.MP4, backgroundColor = 0xFFFFFFFF.toInt())
                        Mp4Encoder.encode(temp, width, height, files.size, delays, options) { index ->
                            val decoded = if (index == 0) first else decode(files[index])
                            if (decoded.width == width && decoded.height == height) decoded else decoded.scaled(width, height)
                        }
                        val bytes = withContext(Dispatchers.IO) { temp.readBytes() }
                        val file = storage.saveExport(projectId, fileName, bytes)
                        ExportResult(
                            format = ExportFormat.MP4,
                            filePath = file.absolutePath,
                            fileName = fileName,
                            byteCount = bytes.size.toLong(),
                            width = width,
                            height = height,
                            frameCount = files.size,
                        )
                    } finally {
                        temp.delete()
                    }
                }
            }

        private suspend fun decode(file: File): PixelBuffer =
            withContext(Dispatchers.IO) {
                requireNotNull(BitmapPixelBridge.fromEncodedBytes(file.readBytes())) { "A time-lapse frame is damaged" }
            }

        /** Keeps every other frame and renumbers the survivors contiguously. */
        private fun decimate(existing: List<File>): List<File> {
            existing.filterIndexed { index, _ -> index % 2 == 1 }.forEach { it.delete() }
            return existing.filterIndexed { index, _ -> index % 2 == 0 }.mapIndexed { index, file ->
                val target = File(file.parentFile, "%06d%s".format(index, EXTENSION))
                if (file != target) file.renameTo(target)
                target
            }
        }

        private fun downscale(source: PixelBuffer): PixelBuffer {
            val longest = max(source.width, source.height)
            val factor = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
            val width = max(16, ((source.width * factor).roundToInt() + 1) and -2)
            val height = max(16, ((source.height * factor).roundToInt() + 1) and -2)
            return if (width == source.width && height == source.height) source.copy() else source.scaled(width, height)
        }

        companion object {
            const val DIR_NAME = "timelapse"
            private const val EXTENSION = ".jpg"
            const val MAX_FRAMES = 3_600
            private const val MAX_SIDE = 1_280
            private const val JPEG_QUALITY = 85
            private const val TARGET_DURATION_MS = 30_000
            private const val MIN_FRAME_MS = 33
            private const val MAX_FRAME_MS = 250
            private const val FINAL_HOLD_MS = 2_500
        }
    }
