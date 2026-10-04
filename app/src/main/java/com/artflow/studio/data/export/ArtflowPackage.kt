package com.artflow.studio.data.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An `.artflow` file: a whole artwork (layers, frames, masks, settings and previews) in one zip,
 * so it can move to another device and open with everything intact, like a `.procreate` file.
 */
object ArtflowPackage {
    const val DOCUMENT = "canvas.artflow"

    @Serializable
    private data class Marker(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val name: String,
    )

    /** A read package: the artwork's name and its project-relative files. */
    class Contents(
        val name: String,
        val files: Map<String, ByteArray>,
    )

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
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

    /** Reads and checks a package: only known file names, bounded sizes, and a document inside. */
    fun read(
        bytes: ByteArray,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
    ): Contents {
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
        require(name != null && DOCUMENT in files) { "This is not an ArtFlow artwork" }
        return Contents(name?.ifBlank { null } ?: "Imported artwork", files)
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
    private const val MAX_ENTRY_BYTES = 192L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 768L * 1024 * 1024
    private const val MAX_NAME = 120
    private const val BUFFER = 64 * 1024

    /** The manifest, previews, and layer pixel and mask files; nothing else may be written. */
    private val ALLOWED = Regex("""canvas\.artflow|canvas\.png|thumbnail\.png|layers/\d{1,19}/[A-Za-z0-9_][A-Za-z0-9_.-]{0,120}\.png""")
}
