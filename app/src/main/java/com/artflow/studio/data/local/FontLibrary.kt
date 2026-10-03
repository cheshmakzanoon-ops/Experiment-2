package com.artflow.studio.data.local

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Fonts imported for text layers (Procreate's Import Font). Each font is copied into the app's
 * private files, and a text style names it as `file:` plus the copy's path.
 */
object FontLibrary {
    const val PREFIX = "file:"
    private const val MAX_BYTES = 20 * 1024 * 1024

    private fun dir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    /** Font families for every imported font, by name. */
    fun families(context: Context): List<String> =
        dir(context)
            .listFiles { file -> file.isFile && file.extension.lowercase() in setOf("ttf", "otf") }
            ?.sortedBy { it.name.lowercase() }
            ?.map { PREFIX + it.absolutePath }
            .orEmpty()

    /** A readable name for [family]: the font's file name without its extension. */
    fun label(family: String): String = if (family.startsWith(PREFIX)) File(family.removePrefix(PREFIX)).nameWithoutExtension else family

    /** The typeface of an imported font, or null when [family] is not one or cannot be read. */
    fun typeface(family: String): Typeface? {
        if (!family.startsWith(PREFIX)) return null
        val file = File(family.removePrefix(PREFIX))
        return if (file.isFile) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
    }

    /** Copies a TrueType or OpenType font into the library; returns its family, or null when it is not a usable font. */
    fun import(
        context: Context,
        uri: Uri,
    ): String? {
        val resolver = context.contentResolver
        val name =
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "Font.ttf"
        val extension = name.substringAfterLast('.', "ttf").lowercase().takeIf { it in setOf("ttf", "otf") } ?: return null
        val base =
            name
                .substringBeforeLast('.')
                .filter { it.isLetterOrDigit() || it in " -_" }
                .trim()
                .ifEmpty { "Font" }
                .take(MAX_NAME)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val target = File(dir(context), "$base.$extension")
        target.writeBytes(bytes)
        // Keep only files Android can actually render.
        if (runCatching { Typeface.createFromFile(target) }.isFailure) {
            target.delete()
            return null
        }
        return PREFIX + target.absolutePath
    }

    private const val MAX_NAME = 60
}
