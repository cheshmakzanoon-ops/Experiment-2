package com.artflow.studio.data.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An `.artflow` file: a whole artwork (layers, frames, masks, settings, previews, and custom grain
 * dependencies) in one zip, so it can move to another device and open with everything intact.
 *
 * External font binaries are never bundled automatically so third-party font licenses are respected;
 * instead, `dependencies.json` records referenced font IDs/labels so an importing device can warn if
 * an imported font is missing locally.
 */
object ArtflowPackage {
    const val DOCUMENT = "canvas.artflow"
    const val DEPENDENCIES = "dependencies.json"
    const val GRAINS_DIR = "grains"

    @Serializable
    private data class Marker(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val name: String,
    )

    @Serializable
    data class PackagedFont(
        val id: String,
        val label: String,
    )

    @Serializable
    data class Dependencies(
        val fonts: List<PackagedFont> = emptyList(),
        val grainIds: List<String> = emptyList(),
    )

    class EntrySource(
        val path: String,
        val openStream: () -> InputStream,
    )

    /** A read package held in memory (small packages and unit tests): the artwork's name and relative files. */
    class Contents(
        val name: String,
        val files: Map<String, ByteArray>,
    )

    /** A streamed package backed by a temporary zip file on disk, keeping large archives off the heap. */
    class StreamContents internal constructor(
        val name: String,
        val entries: Set<String>,
        private val zip: ZipFile,
        private val maxTotalBytes: Long,
    ) : Closeable {
        private var totalRead = 0L

        fun readBytes(
            path: String,
            maxBytes: Long = MAX_ENTRY_BYTES,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            streamEntry(path, maxBytes) { buffer, count -> out.write(buffer, 0, count) }
            return out.toByteArray()
        }

        fun copyTo(
            path: String,
            output: OutputStream,
            maxBytes: Long = MAX_ENTRY_BYTES,
        ) {
            streamEntry(path, maxBytes) { buffer, count -> output.write(buffer, 0, count) }
        }

        private fun streamEntry(
            path: String,
            maxEntryBytes: Long,
            sink: (ByteArray, Int) -> Unit,
        ) {
            val entry = requireNotNull(zip.getEntry(path)) { "Missing artwork entry: $path" }
            val limit = (maxTotalBytes - totalRead).coerceAtMost(maxEntryBytes)
            val entryRead =
                try {
                    zip.getInputStream(entry).use { input -> copyZipEntry(input, limit, sink) }
                } catch (damaged: IOException) {
                    throw IllegalArgumentException("This artwork file is damaged", damaged)
                }
            totalRead += entryRead
        }

        private fun copyZipEntry(
            input: java.io.InputStream,
            limit: Long,
            sink: (ByteArray, Int) -> Unit,
        ): Long {
            val buffer = ByteArray(BUFFER)
            var entryRead = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                entryRead += count
                require(entryRead <= limit) { "This artwork is too large to import" }
                sink(buffer, count)
            }
            return entryRead
        }

        override fun close() {
            zip.close()
        }
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun grainEntryPath(grainId: String): String {
        val candidate = "$GRAINS_DIR/$grainId.grain"
        require(GRAIN_ENTRY.matches(candidate)) { "Invalid grain package path: $candidate" }
        return candidate
    }

    fun encodeDependencies(dependencies: Dependencies): ByteArray =
        json.encodeToString(Dependencies.serializer(), dependencies).toByteArray()

    fun decodeDependencies(bytes: ByteArray): Dependencies {
        require(bytes.size <= MAX_METADATA_BYTES) { "Dependency manifest is too large" }
        return json.decodeFromString(Dependencies.serializer(), bytes.decodeToString())
    }

    fun write(
        name: String,
        files: Map<String, ByteArray>,
    ): ByteArray {
        require(DOCUMENT in files) { "An artwork package needs its document" }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MARKER))
            zip.write(json.encodeToString(Marker.serializer(), Marker(name = name)).toByteArray())
            zip.closeEntry()
            files.forEach { (path, bytes) ->
                require(ALLOWED.matches(path)) { "Unexpected file in an artwork: $path" }
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Streams an `.artflow` package directly to [destination] via an atomic temp file. */
    fun writeToFile(
        name: String,
        destination: File,
        sources: List<EntrySource>,
    ) {
        val seen = HashSet<String>(sources.size)
        sources.forEach { source ->
            require(ALLOWED.matches(source.path)) { "Unexpected file in an artwork: ${source.path}" }
            require(seen.add(source.path)) { "Duplicate entry in artwork package: ${source.path}" }
        }
        require(DOCUMENT in seen) { "An artwork package needs its document" }
        require(seen.size < MAX_ENTRIES) { "This artwork has too many files" }

        val parent = requireNotNull(destination.parentFile) { "Export destination needs a parent directory" }
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create export directory" }
        val temp = File.createTempFile(".${destination.name}-", ".tmp", parent)
        try {
            writeTempArchive(temp, name, sources)
            Files.move(
                temp.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun writeTempArchive(
        temp: File,
        name: String,
        sources: List<EntrySource>,
    ) {
        val buffer = ByteArray(BUFFER)
        FileOutputStream(temp).use { fileOut ->
            ZipOutputStream(BufferedOutputStream(fileOut)).use { zip ->
                zip.putNextEntry(ZipEntry(MARKER))
                zip.write(json.encodeToString(Marker.serializer(), Marker(name = name)).toByteArray())
                zip.closeEntry()
                sources.forEach { source -> writeZipEntry(zip, source, buffer) }
                zip.finish()
                zip.flush()
                fileOut.flush()
                fileOut.fd.sync()
            }
        }
    }

    private fun writeZipEntry(
        zip: ZipOutputStream,
        source: EntrySource,
        buffer: ByteArray,
    ) {
        zip.putNextEntry(ZipEntry(source.path))
        source.openStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                zip.write(buffer, 0, count)
            }
        }
        zip.closeEntry()
    }

    /** Opens an `.artflow` zip file on disk, validates every entry path, count, and header without loading payloads into heap. */
    fun openFile(
        file: File,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): StreamContents {
        val zip =
            try {
                ZipFile(file)
            } catch (damaged: IOException) {
                throw IllegalArgumentException("This artwork file is damaged", damaged)
            }
        var opened = false
        try {
            val contents = parseOpenFile(zip, maxTotalBytes)
            opened = true
            return contents
        } finally {
            if (!opened) zip.close()
        }
    }

    private fun parseOpenFile(
        zip: ZipFile,
        maxTotalBytes: Long,
    ): StreamContents {
        val entries = linkedSetOf<String>()
        var hasMarker = false
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            require(entries.size < MAX_ENTRIES) { "This artwork has too many files" }
            val path = entry.name
            require(!entry.isDirectory && (path == MARKER || ALLOWED.matches(path))) {
                "This is not an ArtFlow artwork"
            }
            if (path == MARKER) {
                require(!hasMarker) { "This artwork lists a file twice" }
                hasMarker = true
            } else {
                require(entries.add(path)) { "This artwork lists a file twice" }
            }
        }
        require(hasMarker && DOCUMENT in entries) { "This is not an ArtFlow artwork" }
        val markerBytes =
            try {
                readMarkerBytes(zip)
            } catch (damaged: IOException) {
                throw IllegalArgumentException("This artwork file is damaged", damaged)
            }
        val marker = json.decodeFromString(Marker.serializer(), markerBytes.decodeToString())
        require(marker.format == FORMAT && marker.version in 1..VERSION) { "Unsupported ArtFlow artwork version" }
        val safeName =
            marker.name
                .trim()
                .take(MAX_NAME)
                .ifBlank { "Imported artwork" }
        return StreamContents(safeName, entries, zip, maxTotalBytes)
    }

    private fun readMarkerBytes(zip: ZipFile): ByteArray {
        val entry = requireNotNull(zip.getEntry(MARKER)) { "This is not an ArtFlow artwork" }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var read = 0L
        zip.getInputStream(entry).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                read += count
                require(read <= MAX_METADATA_BYTES) { "This artwork is too large to import" }
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray()
    }

    /** Reads and checks a package: only known file names, bounded sizes, and a document inside. */
    fun read(
        bytes: ByteArray,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): Contents {
        val (name, files) =
            try {
                unzip(bytes, maxTotalBytes)
            } catch (damaged: IOException) {
                // A truncated or corrupt zip is refused the same way as any other damaged artwork.
                throw IllegalArgumentException("This artwork file is damaged", damaged)
            }
        require(name != null && DOCUMENT in files) { "This is not an ArtFlow artwork" }
        return Contents(name.ifBlank { null } ?: "Imported artwork", files)
    }

    /** The marker's name and the package's files, in order, each within its size limits. */
    private fun unzip(
        bytes: ByteArray,
        maxTotalBytes: Long,
    ): Pair<String?, Map<String, ByteArray>> {
        var name: String? = null
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(files.size < MAX_ENTRIES) { "This artwork has too many files" }
                val path = entry.name
                require(!entry.isDirectory && (path == MARKER || ALLOWED.matches(path))) { "This is not an ArtFlow artwork" }
                require(path !in files) { "This artwork lists a file twice" }
                val data = readBounded(zip, (maxTotalBytes - total).coerceAtMost(MAX_ENTRY_BYTES))
                total += data.size
                if (path == MARKER) {
                    val marker = json.decodeFromString(Marker.serializer(), data.decodeToString())
                    require(marker.format == FORMAT && marker.version in 1..VERSION) { "Unsupported ArtFlow artwork version" }
                    name = marker.name.trim().take(MAX_NAME)
                } else {
                    files[path] = data
                }
            }
        }
        return name to files
    }

    private fun readBounded(
        zip: ZipInputStream,
        limit: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var read = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            read += count
            require(read <= limit) { "This artwork is too large to import" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private const val FORMAT = "artflow-package"
    private const val VERSION = 1
    private const val MARKER = "artflow-package.json"
    private const val MAX_ENTRIES = 4_200
    private const val MAX_METADATA_BYTES = 1024L * 1024
    private const val MAX_ENTRY_BYTES = 192L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 768L * 1024 * 1024
    private const val MAX_NAME = 120
    private const val BUFFER = 64 * 1024

    private val GRAIN_ENTRY = Regex("""grains/custom-[0-9a-f]{1,32}\.grain""")

    /** The manifest, previews, dependencies, custom grains, and layer pixel/mask files; nothing else may be written. */
    private val ALLOWED =
        Regex(
            """canvas\.artflow|canvas\.png|thumbnail\.png|model\.obj|dependencies\.json|""" +
                """layers/\d{1,19}/[A-Za-z0-9_][A-Za-z0-9_.-]{0,120}\.png|""" +
                """grains/custom-[0-9a-f]{1,32}\.grain""",
        )
}
