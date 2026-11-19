package com.artflow.studio.data.local

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.artflow.studio.core.export.readAtMost
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/**
 * Fonts imported for text layers (Procreate's Import Font). Each font is stored under
 * `filesDir/fonts/<name>` and identified portably inside documents as `font:<filename>`.
 * Legacy `file:<absolute-path>` identifiers remain readable on load and fall back to a
 * matching filename in `filesDir/fonts` after backup restore or package import.
 */
object FontLibrary {
    const val PREFIX = "font:"
    const val LEGACY_PREFIX = "file:"
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val MAX_NAME = 60
    private const val MAX_COLLISION_ATTEMPTS = 10_000
    private val ALLOWED_EXTENSIONS = setOf("ttf", "otf")
    private val importLock = Any()

    @Volatile
    private var configuredFilesDir: File? = null

    /** Configures the app files root once at startup for callers without a [Context]. */
    fun configure(filesDir: File?) {
        configuredFilesDir = filesDir
    }

    enum class FailureReason {
        UNREADABLE,
        UNSUPPORTED_EXTENSION,
        EMPTY,
        TOO_LARGE,
        INVALID_FONT,
        WRITE_FAILED,
    }

    sealed class ImportResult {
        data class Success(
            val family: String,
            val displayName: String,
            val renamedFromCollision: Boolean = false,
        ) : ImportResult()

        data class Failure(
            val reason: FailureReason,
            val message: String,
        ) : ImportResult()
    }

    private fun dir(filesDir: File) = File(filesDir, "fonts").apply { mkdirs() }

    /** Font families for every imported font, using portable `font:<filename>` IDs. */
    fun families(context: Context): List<String> = families(context.filesDir)

    fun families(filesDir: File): List<String> {
        val files =
            dir(filesDir)
                .listFiles { file ->
                    Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                        file.extension.lowercase() in ALLOWED_EXTENSIONS
                }.orEmpty()
        return files.sortedBy { it.name.lowercase() }.map { PREFIX + it.name }
    }

    /** True when [family] refers to a user-imported font rather than a built-in system family. */
    fun isImported(family: String): Boolean = family.startsWith(PREFIX) || family.startsWith(LEGACY_PREFIX)

    /** Normalizes any imported font identifier into a portable `font:<filename>` reference. */
    fun portableId(family: String): String? =
        when {
            family.startsWith(PREFIX) -> {
                val name = family.removePrefix(PREFIX)
                if (isSafeFileName(name)) PREFIX + name else null
            }
            family.startsWith(LEGACY_PREFIX) -> {
                val raw = family.removePrefix(LEGACY_PREFIX)
                val name = File(raw).name
                if (raw.isNotBlank() && isSafeFileName(name)) PREFIX + name else null
            }
            else -> null
        }

    /** A readable name for [family]: the font's file name without its extension. */
    fun label(family: String): String =
        when {
            family.startsWith(PREFIX) -> File(family.removePrefix(PREFIX)).nameWithoutExtension
            family.startsWith(LEGACY_PREFIX) -> File(family.removePrefix(LEGACY_PREFIX)).nameWithoutExtension
            else -> family
        }

    /** Resolves an imported font's on-disk file, or null when [family] is a system font or invalid. */
    fun resolveFile(
        family: String,
        filesDir: File? = configuredFilesDir,
    ): File? =
        when {
            family.startsWith(PREFIX) -> {
                val name = family.removePrefix(PREFIX)
                if (!isSafeFileName(name)) null else filesDir?.let { File(File(it, "fonts"), name) }
            }
            family.startsWith(LEGACY_PREFIX) -> {
                val raw = family.removePrefix(LEGACY_PREFIX)
                if (raw.isBlank()) {
                    null
                } else {
                    val legacy = File(raw)
                    when {
                        Files.isRegularFile(legacy.toPath(), LinkOption.NOFOLLOW_LINKS) -> legacy
                        filesDir != null && isSafeFileName(legacy.name) -> File(File(filesDir, "fonts"), legacy.name)
                        else -> null
                    }
                }
            }
            else -> null
        }

    /** True when [family] resolves to an existing regular font file on disk. */
    fun exists(
        family: String,
        filesDir: File? = configuredFilesDir,
    ): Boolean =
        resolveFile(family, filesDir)?.let { file ->
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        } == true

    /** The typeface of an imported font, or null when [family] is not one or cannot be read. */
    fun typeface(
        family: String,
        filesDir: File? = configuredFilesDir,
    ): Typeface? {
        val file = resolveFile(family, filesDir) ?: return null
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return runCatching { Typeface.createFromFile(file) }.getOrNull()
    }

    /** Copies a TrueType or OpenType font into the library; returns its family, or null when unusable. */
    fun import(
        context: Context,
        uri: Uri,
    ): String? = (importDetailed(context, uri) as? ImportResult.Success)?.family

    /** Imports a TrueType or OpenType font using isolated temp validation and non-destructive naming. */
    fun importDetailed(
        context: Context,
        uri: Uri,
    ): ImportResult {
        val resolver = context.contentResolver
        val displayName =
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: "Font.ttf"
        return importStream(
            filesDir = context.filesDir,
            displayName = displayName,
            openStream = { resolver.openInputStream(uri) },
            validator = { staged ->
                runCatching { Typeface.createFromFile(staged) != null }.getOrDefault(false)
            },
        )
    }

    /** Validates an SFNT header and table directory size before passing to platform font loading. */
    fun hasValidSfntHeader(bytes: ByteArray): Boolean {
        if (bytes.size < SFNT_HEADER_BYTES) return false
        val version =
            ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        if (version !in SFNT_VERSIONS) return false
        val numTables = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        if (numTables <= 0) return false
        val directoryEnd = SFNT_HEADER_BYTES.toLong() + numTables.toLong() * SFNT_RECORD_BYTES.toLong()
        return directoryEnd <= bytes.size.toLong()
    }

    /** Chooses a non-colliding filename in [directory], using `Name (2).ttf`, `Name (3).ttf`, etc. */
    fun uniqueTargetFile(
        directory: File,
        baseName: String,
        extension: String,
    ): File {
        val primary = File(directory, "$baseName.$extension")
        if (Files.notExists(primary.toPath(), LinkOption.NOFOLLOW_LINKS)) return primary
        for (suffix in 2..MAX_COLLISION_ATTEMPTS) {
            val candidate = File(directory, "$baseName ($suffix).$extension")
            if (Files.notExists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return candidate
        }
        throw IOException("Too many fonts share the name $baseName")
    }

    /** Pure-JVM import pipeline: stages cleanly in a temp file, validates before move, never overwrites. */
    internal fun importStream(
        filesDir: File,
        displayName: String?,
        openStream: () -> InputStream?,
        validator: (File) -> Boolean,
    ): ImportResult {
        val rawName = displayName?.takeIf { it.isNotBlank() } ?: "Font.ttf"
        val extension =
            rawName.substringAfterLast('.', "").lowercase().takeIf { it in ALLOWED_EXTENSIONS }
                ?: return ImportResult.Failure(
                    FailureReason.UNSUPPORTED_EXTENSION,
                    "Choose a TrueType (.ttf) or OpenType (.otf) font file",
                )
        val base = sanitizeBaseName(rawName)
        val bytes =
            try {
                val stream =
                    openStream()
                        ?: return ImportResult.Failure(FailureReason.UNREADABLE, "That font file could not be opened")
                stream.use { it.readAtMost(MAX_BYTES.toLong()) }
            } catch (_: IOException) {
                return ImportResult.Failure(FailureReason.UNREADABLE, "That font file could not be read")
            } ?: return ImportResult.Failure(
                FailureReason.TOO_LARGE,
                "That font file is larger than 20 MB",
            )
        if (bytes.isEmpty()) {
            return ImportResult.Failure(FailureReason.EMPTY, "That font file is empty")
        }
        if (!hasValidSfntHeader(bytes)) {
            return ImportResult.Failure(
                FailureReason.INVALID_FONT,
                "That file is not a TrueType or OpenType font",
            )
        }

        val fontsDir = dir(filesDir)
        val staged =
            try {
                File.createTempFile(".import-", ".$extension.tmp", fontsDir)
            } catch (_: IOException) {
                return ImportResult.Failure(FailureReason.WRITE_FAILED, "Could not prepare storage for the font")
            }
        try {
            try {
                FileOutputStream(staged).use { out ->
                    out.write(bytes)
                    out.flush()
                    out.fd.sync()
                }
            } catch (_: IOException) {
                return ImportResult.Failure(FailureReason.WRITE_FAILED, "Could not write the font to app storage")
            }

            if (!runCatching { validator(staged) }.getOrDefault(false)) {
                return ImportResult.Failure(
                    FailureReason.INVALID_FONT,
                    "That font could not be loaded on this device",
                )
            }

            return synchronized(importLock) {
                try {
                    val destination = moveToUniqueDestination(fontsDir, staged, base, extension)
                    syncDirectoryBestEffort(fontsDir)
                    val renamed = destination.name != "$base.$extension"
                    ImportResult.Success(
                        family = PREFIX + destination.name,
                        displayName = destination.nameWithoutExtension,
                        renamedFromCollision = renamed,
                    )
                } catch (_: IOException) {
                    ImportResult.Failure(FailureReason.WRITE_FAILED, "Could not save the font to app storage")
                }
            }
        } finally {
            if (Files.exists(staged.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                staged.delete()
            }
        }
    }

    private fun moveToUniqueDestination(
        fontsDir: File,
        staged: File,
        base: String,
        extension: String,
    ): File {
        repeat(MAX_COLLISION_ATTEMPTS) {
            val target = uniqueTargetFile(fontsDir, base, extension)
            try {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                return target
            } catch (_: FileAlreadyExistsException) {
                // Retry next collision suffix without replacing the existing valid font.
            }
        }
        throw IOException("Could not allocate a unique filename for $base.$extension")
    }

    private fun syncDirectoryBestEffort(directory: File) {
        try {
            val descriptor =
                android.system.Os.open(
                    directory.path,
                    android.system.OsConstants.O_RDONLY,
                    0,
                )
            try {
                android.system.Os.fsync(descriptor)
            } finally {
                android.system.Os.close(descriptor)
            }
        } catch (_: Throwable) {
            // Host JVM unit tests stub android.system.Os; atomic move already completed.
        }
    }

    private fun sanitizeBaseName(name: String): String =
        name
            .substringBeforeLast('.')
            .filter { it.isLetterOrDigit() || it in " -_" }
            .trim()
            .ifEmpty { "Font" }
            .take(MAX_NAME)

    private fun isSafeFileName(name: String): Boolean =
        name.isNotBlank() &&
            name !in setOf(".", "..") &&
            '/' !in name &&
            '\\' !in name &&
            '\u0000' !in name &&
            name.substringAfterLast('.', "").lowercase() in ALLOWED_EXTENSIONS

    private const val SFNT_HEADER_BYTES = 12
    private const val SFNT_RECORD_BYTES = 16
    private val SFNT_VERSIONS = setOf(0x00010000, 0x4F54544F, 0x74727565, 0x74797031)
}
