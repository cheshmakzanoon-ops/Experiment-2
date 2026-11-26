package com.artflow.studio.data.export

import android.system.Os
import android.system.OsConstants
import com.artflow.studio.core.export.ExportFormat
import com.artflow.studio.core.export.ExportOptions
import com.artflow.studio.core.export.ExportResult
import com.artflow.studio.core.lifecycle.withCleanup
import com.artflow.studio.core.pixels.PixelBuffer
import com.artflow.studio.data.local.ProjectStorage
import com.artflow.studio.data.renderer.BitmapPixelBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
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
        private val controls = TimelapseControlState()

        /** A master switch above the per-artwork one; tests turn it off. */
        @Volatile
        var isEnabled: Boolean = true
        private var lastProjectId = 0L
        private var lastDigest: ByteArray? = null

        private fun dir(projectId: Long): File = TimelapseFiles.directory(storage.projectDir(projectId))

        private fun frames(projectId: Long): List<File> =
            dir(projectId)
                .listFiles { file -> file.name.endsWith(EXTENSION) && Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) }
                ?.sortedWith(compareBy<File> { it.nameWithoutExtension.toLongOrNull() ?: Long.MAX_VALUE }.thenBy { it.name })
                ?: throw IOException("Could not read the time-lapse recording")

        /** Whether this artwork is being recorded: on until Actions > Video > Time-lapse recording is switched off. */
        suspend fun isRecording(projectId: Long): Boolean = withContext(Dispatchers.IO) { recordingAllowed(projectId) }

        private fun recordingAllowed(projectId: Long): Boolean =
            controls.permitsRecording(projectId) && TimelapseFiles.recordingAllowed(storage.projectDir(projectId))

        /** Switches recording on or off for one artwork; the choice stays with the artwork's files. */
        suspend fun setRecording(
            projectId: Long,
            recording: Boolean,
        ) {
            // Block before any dispatcher/mutex wait. A failed or cancelled write remains paused
            // in this process; only a subsequent successful preference request can release it.
            val request = controls.begin(projectId, recording)
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (!controls.owns(request)) return@withLock
                    val coroutine = currentCoroutineContext()
                    val project = storage.projectDir(projectId)
                    val checkRequest = {
                        coroutine.ensureActive()
                        if (!controls.owns(request)) throw RecordingStoppedException()
                    }
                    try {
                        checkRequest()
                        if (request.recording) {
                            TimelapseFiles.removePause(project)
                            syncDirectory(project)
                        } else {
                            val marker = TimelapseFiles.pauseFile(project)
                            storage.writeAtomically(marker, modifiedAfter = null, beforeCommit = checkRequest) {}
                        }
                    } catch (_: RecordingStoppedException) {
                        return@withLock
                    }
                    if (projectId == lastProjectId) lastDigest = null
                    coroutine.ensureActive()
                    controls.persisted(request)
                }
            }
        }

        suspend fun frameCount(projectId: Long): Int = withContext(Dispatchers.IO) { mutex.withLock { frames(projectId).size } }

        /**
         * Immutable disk copies for replay. The IO callback owns them until it returns, including
         * while suspended; cancellation, failed acquisition and callback failures all release them.
         */
        suspend fun <T> withFrameSnapshot(
            projectId: Long,
            block: suspend (List<File>) -> T,
        ): T = withFrameSnapshot(projectId, frameLimit = null, block)

        private suspend fun <T> withFrameSnapshot(
            projectId: Long,
            frameLimit: Int?,
            block: suspend (List<File>) -> T,
        ): T =
            withContext(Dispatchers.IO) {
                // Acquire, consume and release on this dispatcher so cancellation cannot strand a
                // completed snapshot while returning it across a dispatcher boundary.
                val snapshot = TimelapseFrameSnapshot(storage.exportsDir(projectId))
                withCleanup(snapshot::close) {
                    val files =
                        mutex.withLock {
                            val recorded = frames(projectId)
                            val selected = if (frameLimit == null) recorded else sampled(recorded, frameLimit)
                            val coroutine = currentCoroutineContext()
                            snapshot.copyFrames(selected, coroutine::ensureActive)
                        }
                    block(files)
                }
            }

        /**
         * Appends [composite] to the recording unless it is identical to the previous frame.
         * [permitted] is consulted again at the persistence boundary, inside the recorder's lock, so a
         * recording switched off while this capture was scheduled or encoding writes nothing.
         */
        suspend fun capture(
            projectId: Long,
            composite: PixelBuffer,
            maxSide: Int = MAX_SIDE,
            permitted: () -> Boolean = { true },
        ) {
            if (!isEnabled || !permitted() || !isRecording(projectId)) return
            withContext(Dispatchers.Default) {
                val frame = downscale(composite, maxSide)
                mutex.withLock {
                    if (!isEnabled || !permitted() || !isRecording(projectId)) return@withLock
                    val bytes = BitmapPixelBridge.toJpegBytes(frame, JPEG_QUALITY, 0xFFFFFFFF.toInt())
                    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    if (projectId == lastProjectId && lastDigest?.contentEquals(digest) == true) return@withLock
                    val captured =
                        withContext(Dispatchers.IO) {
                            val coroutine = currentCoroutineContext()
                            val checkCapture = {
                                coroutine.ensureActive()
                                val permittedNow = isEnabled && permitted() && recordingAllowed(projectId)
                                coroutine.ensureActive()
                                if (!permittedNow) throw RecordingStoppedException()
                            }
                            try {
                                // Encoding and syncing can outlast a recording toggle or an editor
                                // close. Existing frames cannot change before publication succeeds.
                                checkCapture()
                                val existing = frames(projectId)
                                check(existing.size <= MAX_FRAMES) { "Time-lapse storage is full; clear the recording before continuing" }
                                val previous =
                                    existing.lastOrNull()?.let { file ->
                                        requireNotNull(file.nameWithoutExtension.toLongOrNull()) { "Invalid time-lapse frame sequence" }
                                    } ?: -1L
                                require(previous >= -1L) { "Invalid time-lapse frame sequence" }
                                val next = Math.addExact(previous, 1L)
                                val file = File(dir(projectId), String.format(Locale.ROOT, "%06d%s", next, EXTENSION))
                                storage.writeAtomically(file, modifiedAfter = null, beforeCommit = checkCapture) { it.write(bytes) }
                                // The new frame and its directory entry are durable. Interrupted or
                                // failed pruning now only keeps extra history; survivors never move.
                                if (existing.size >= MAX_FRAMES) {
                                    val removed = decimate(existing)
                                    check(existing.size + 1 - removed <= MAX_FRAMES) { "Could not reduce the time-lapse recording" }
                                }
                                true
                            } catch (_: RecordingStoppedException) {
                                false
                            }
                        }
                    if (!captured) return@withLock
                    lastProjectId = projectId
                    lastDigest = digest
                }
            }
        }

        /** A recording toggle discards only this frame; coroutine cancellation still propagates. */
        private class RecordingStoppedException : RuntimeException()

        suspend fun clear(projectId: Long) {
            mutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        val project = storage.projectDir(projectId)
                        TimelapseFiles.clear(project)
                        syncDirectory(project)
                    }
                } finally {
                    // A checked clear may have removed some frames before reporting a failure.
                    if (projectId == lastProjectId) lastDigest = null
                }
            }
        }

        private fun syncDirectory(directory: File) {
            val descriptor = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            withCleanup({ Os.close(descriptor) }) { Os.fsync(descriptor) }
        }

        /**
         * Encodes the recording as an MP4: every frame at about 30 fps for [fullLength], otherwise a
         * replay of about [TARGET_DURATION_MS], like Procreate's 30-second export.
         * Storage/codec failures become export results; cancellation and fatal errors still propagate.
         */
        @Suppress("TooGenericExceptionCaught")
        suspend fun export(
            projectId: Long,
            projectName: String,
            fullLength: Boolean = false,
        ): Result<ExportResult> =
            withContext(Dispatchers.IO) {
                try {
                    val frameLimit = if (fullLength) null else TARGET_DURATION_MS / MIN_FRAME_MS
                    withFrameSnapshot(projectId, frameLimit) { files ->
                        require(files.isNotEmpty()) { "Nothing has been recorded yet — draw something first" }
                        val first = decode(files.first())
                        // Large recordings fall back to 720p on devices whose encoder cannot take their size.
                        val fitted = if (Mp4Encoder.supports(first.width, first.height)) first else downscale(first, MAX_SIDE)
                        val width = max(16, (fitted.width + 1) and -2)
                        val height = max(16, (fitted.height + 1) and -2)
                        val perFrame =
                            if (fullLength) {
                                MIN_FRAME_MS
                            } else {
                                (TARGET_DURATION_MS / files.size).coerceIn(
                                    MIN_FRAME_MS,
                                    MAX_FRAME_MS,
                                )
                            }
                        val delays = List(files.size) { if (it == files.lastIndex) FINAL_HOLD_MS else perFrame }
                        val safeName = projectName.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Artwork" }
                        val fileName = if (fullLength) "$safeName timelapse.mp4" else "$safeName timelapse 30s.mp4"
                        val temp = File.createTempFile("timelapse", ".mp4", storage.exportsDir(projectId))
                        try {
                            val options = ExportOptions(format = ExportFormat.MP4, backgroundColor = 0xFFFFFFFF.toInt())
                            withContext(Dispatchers.Default) {
                                Mp4Encoder.encode(temp, width, height, files.size, delays, options) { index ->
                                    val decoded = if (index == 0) first else decode(files[index])
                                    if (decoded.width == width && decoded.height == height) decoded else decoded.scaled(width, height)
                                }
                            }
                            val file = storage.saveExportFromFile(projectId, fileName, temp)
                            Result.success(
                                ExportResult(
                                    format = ExportFormat.MP4,
                                    filePath = file.absolutePath,
                                    fileName = fileName,
                                    byteCount = file.length(),
                                    width = width,
                                    height = height,
                                    frameCount = files.size,
                                ),
                            )
                        } finally {
                            temp.delete()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }

        private suspend fun decode(file: File): PixelBuffer =
            withContext(Dispatchers.IO) {
                requireNotNull(BitmapPixelBridge.fromEncodedBytes(file.readBytes())) { "A time-lapse frame is damaged" }
            }

        /** Removes alternating older frames only after an additional frame is durable. */
        private fun decimate(existing: List<File>): Int = existing.filterIndexed { index, _ -> index % 2 == 1 }.count { it.delete() }

        private fun downscale(
            source: PixelBuffer,
            maxSide: Int,
        ): PixelBuffer {
            val longest = max(source.width, source.height)
            val factor = if (longest > maxSide) maxSide.toFloat() / longest else 1f
            val width = max(16, ((source.width * factor).roundToInt() + 1) and -2)
            val height = max(16, ((source.height * factor).roundToInt() + 1) and -2)
            return if (width == source.width && height == source.height) source.copy() else source.scaled(width, height)
        }

        companion object {
            /** At most [count] frames spread evenly from first to last. */
            internal fun sampled(
                files: List<File>,
                count: Int,
            ): List<File> {
                if (files.size <= count || count < 2) return files
                return List(count) { i -> files[(i.toLong() * (files.size - 1) / (count - 1)).toInt()] }
            }

            const val DIR_NAME = TimelapseFiles.DIRECTORY
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
